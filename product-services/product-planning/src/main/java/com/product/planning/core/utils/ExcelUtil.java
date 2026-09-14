package com.product.planning.core.utils;

import com.product.planning.common.annotation.Excel;
import com.product.planning.common.core.text.Convert;
import com.product.planning.common.exception.UtilException;
import com.product.planning.common.utils.DateUtils;
import com.product.planning.common.utils.StringUtils;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataValidation;
import org.apache.poi.ss.usermodel.DataValidationConstraint;
import org.apache.poi.ss.usermodel.DataValidationHelper;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.ss.util.CellRangeAddressList;
import org.apache.poi.xssf.streaming.SXSSFSheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Excel 导入导出工具（Phase 3，master-data 服务内化副本）。
 *
 * <p>背景：单体 ExcelUtil（product-core，1850 行）服务全业务域；本副本保留 master-data
 * 导入导出面用到的行为子集，语义与单体一致：</p>
 * <ul>
 *   <li>响应头一致（Content-Type application/vnd.openxmlformats-officedocument.spreadsheetml.sheet
 *       + utf-8，无 Content-Disposition）；</li>
 *   <li>导出：表头行 = @Excel(name) 列（按 sort 稳定排序、含父类字段），数据值经
 *       readConverterExp 转换、dateFormat 日期格式化；</li>
 *   <li>模板：仅表头行（Type.IMPORT 语义）；</li>
 *   <li>导入：按表头名匹配 @Excel 字段，getCellValue 数值/日期单元格处理与单体一致
 *       （整数 DecimalFormat("0")、小数 BigDecimal(toString)、日期 DateUtil.getJavaDate），
 *       字段类型分支与单体相同（String/Integer/Long/Double/Float/BigDecimal/Date/Boolean），
 *       readConverterExp 反向转换（label→code）。</li>
 * </ul>
 *
 * <p>已知差异：xlsx 内部二进制（样式/元数据/时间戳）不逐字节一致——文件本身含生成时间戳，
 * 单体两次导出亦不相同，不构成 API 契约差异（Phase 2 已冻结该口径）；单体未使用的注解属性
 * （dictType/sub/handler/cellType=IMAGE 等）不支持。</p>
 */
public class ExcelUtil<T>
{
    private static final Logger log = LoggerFactory.getLogger(ExcelUtil.class);

    public static final String SEPARATOR = ",";

    private final Class<T> clazz;

    public ExcelUtil(Class<T> clazz)
    {
        this.clazz = clazz;
    }

    /**
     * 对 list 数据源将其里面的数据导出excel表单（与单体 exportExcel(response, list, sheetName) 一致）。
     */
    public void exportExcel(HttpServletResponse response, List<T> list, String sheetName)
    {
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setCharacterEncoding("utf-8");
        List<Field> fields = excelFields();
        try (SXSSFWorkbook wb = new SXSSFWorkbook(500))
        {
            Sheet sheet = wb.createSheet(StringUtils.isNotEmpty(sheetName) ? sheetName : "Sheet1");
            Row header = sheet.createRow(0);
            for (int i = 0; i < fields.size(); i++)
            {
                Excel attr = fields.get(i).getAnnotation(Excel.class);
                header.createCell(i).setCellValue(attr.name());
                sheet.setColumnWidth(i, attr.width() != 16 ? (int) ((attr.width() + 0.72) * 256) : 6000);
            }
            for (int r = 0; r < list.size(); r++)
            {
                Row row = sheet.createRow(r + 1);
                for (int c = 0; c < fields.size(); c++)
                {
                    writeCell(row.createCell(c), getValue(list.get(r), fields.get(c)), fields.get(c).getAnnotation(Excel.class));
                }
            }
            wb.write(response.getOutputStream());
        }
        catch (IOException e)
        {
            log.error("导出Excel异常{}", e.getMessage());
        }
    }

    /**
     * 下载导入模板（与单体 importTemplateExcel(response, sheetName) 一致：仅表头行）。
     */
    public void importTemplateExcel(HttpServletResponse response, String sheetName)
    {
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setCharacterEncoding("utf-8");
        List<Field> fields = excelFields();
        try (SXSSFWorkbook wb = new SXSSFWorkbook(500))
        {
            Sheet sheet = wb.createSheet(StringUtils.isNotEmpty(sheetName) ? sheetName : "Sheet1");
            Row header = sheet.createRow(0);
            for (int i = 0; i < fields.size(); i++)
            {
                Excel attr = fields.get(i).getAnnotation(Excel.class);
                header.createCell(i).setCellValue(attr.name());
                sheet.setColumnWidth(i, attr.width() != 16 ? (int) ((attr.width() + 0.72) * 256) : 6000);
                if (StringUtils.isNotEmpty(attr.readConverterExp()) && StringUtils.isNotEmpty(attr.separator()))
                {
                    addDictDataValidation(wb, sheet, attr.readConverterExp(), attr.separator(), 1, fields.size() + 100, i, i);
                }
            }
            wb.write(response.getOutputStream());
        }
        catch (IOException e)
        {
            log.error("导入模板Excel异常{}", e.getMessage());
        }
    }

    /**
     * 导入数据（与单体 importExcel(is) 一致：默认第 1 个 sheet、第 1 行表头）。
     */
    public List<T> importExcel(InputStream is) throws Exception
    {
        return importExcel(StringUtils.EMPTY, is, 0);
    }

    public List<T> importExcel(String sheetName, InputStream is, int titleNum) throws Exception
    {
        try (Workbook wb = WorkbookFactory.create(is))
        {
            List<T> list = new ArrayList<T>();
            Sheet sheet = StringUtils.isNotEmpty(sheetName) ? wb.getSheet(sheetName) : wb.getSheetAt(0);
            if (sheet == null)
            {
                throw new IOException("文件sheet不存在");
            }
            int rows = sheet.getLastRowNum();
            if (rows > 0)
            {
                Map<String, Integer> cellMap = new HashMap<String, Integer>();
                Row heard = sheet.getRow(titleNum);
                for (int i = 0; i < heard.getPhysicalNumberOfCells(); i++)
                {
                    Cell cell = heard.getCell(i);
                    if (StringUtils.isNotNull(cell))
                    {
                        String value = this.getCellValue(heard, i).toString();
                        cellMap.put(value, i);
                    }
                    else
                    {
                        cellMap.put(null, i);
                    }
                }
                List<Field> fields = excelFields();
                Map<Integer, Field> fieldsMap = new HashMap<Integer, Field>();
                for (Field field : fields)
                {
                    Excel attr = field.getAnnotation(Excel.class);
                    Integer column = cellMap.get(attr.name());
                    if (column != null)
                    {
                        fieldsMap.put(column, field);
                    }
                }
                for (int i = titleNum + 1; i <= rows; i++)
                {
                    Row row = sheet.getRow(i);
                    if (isRowEmpty(row))
                    {
                        continue;
                    }
                    T entity = null;
                    for (Map.Entry<Integer, Field> entry : fieldsMap.entrySet())
                    {
                        Object val = this.getCellValue(row, entry.getKey());
                        entity = (entity == null ? clazz.getDeclaredConstructor().newInstance() : entity);
                        Field field = entry.getValue();
                        Excel attr = field.getAnnotation(Excel.class);
                        Class<?> fieldType = field.getType();
                        if (String.class == fieldType)
                        {
                            String s = Convert.toStr(val);
                            if (s.matches("^\\d+\\.0$"))
                            {
                                val = StringUtils.substringBefore(s, ".0");
                            }
                            else
                            {
                                String dateFormat = attr.dateFormat();
                                if (StringUtils.isNotEmpty(dateFormat))
                                {
                                    val = parseDateToStr(dateFormat, val);
                                }
                                else
                                {
                                    val = Convert.toStr(val);
                                }
                            }
                        }
                        else if ((Integer.TYPE == fieldType || Integer.class == fieldType) && StringUtils.isNumeric(Convert.toStr(val)))
                        {
                            val = Convert.toInt(val);
                        }
                        else if ((Long.TYPE == fieldType || Long.class == fieldType) && StringUtils.isNumeric(Convert.toStr(val)))
                        {
                            val = Convert.toLong(val);
                        }
                        else if (Double.TYPE == fieldType || Double.class == fieldType)
                        {
                            val = Convert.toDouble(val);
                        }
                        else if (Float.TYPE == fieldType || Float.class == fieldType)
                        {
                            val = Convert.toFloat(val);
                        }
                        else if (BigDecimal.class == fieldType)
                        {
                            val = Convert.toBigDecimal(val);
                        }
                        else if (Date.class == fieldType)
                        {
                            if (val instanceof String)
                            {
                                val = DateUtils.parseDate(val);
                            }
                            else if (val instanceof Double)
                            {
                                val = DateUtil.getJavaDate((Double) val);
                            }
                        }
                        if (StringUtils.isNotNull(fieldType))
                        {
                            if (StringUtils.isNotEmpty(attr.readConverterExp()))
                            {
                                val = reverseByExp(Convert.toStr(val), attr.readConverterExp(), attr.separator());
                            }
                            setFieldValue(entity, field, val);
                        }
                    }
                    list.add(entity);
                }
            }
            return list;
        }
    }

    // ---------------------------- 内部实现 ----------------------------

    private void writeCell(Cell cell, Object value, Excel attr)
    {
        String text;
        if (value == null)
        {
            text = StringUtils.EMPTY;
        }
        else if (value instanceof Date date)
        {
            text = StringUtils.isNotEmpty(attr.dateFormat()) ? parseDateToStr(attr.dateFormat(), date) : DateUtils.dateTime(date);
        }
        else if (value instanceof LocalDateTime localDateTime)
        {
            text = localDateTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        }
        else if (value instanceof LocalDate localDate)
        {
            text = localDate.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        }
        else if (value instanceof BigDecimal bigDecimal)
        {
            text = bigDecimal.toPlainString();
        }
        else
        {
            text = Convert.toStr(value);
        }
        text = convertByExp(text, attr.readConverterExp(), attr.separator());
        if (StringUtils.isNotEmpty(text))
        {
            try
            {
                cell.setCellValue(new BigDecimal(text).doubleValue());
                return;
            }
            catch (NumberFormatException ignored)
            {
            }
        }
        cell.setCellValue(text);
    }

    private Object getValue(Object target, Field field)
    {
        try
        {
            return field.get(target);
        }
        catch (IllegalAccessException e)
        {
            throw new UtilException("读取导出字段失败: " + field.getName(), e);
        }
    }

    /** @Excel 字段（含父类），按注解 sort 稳定排序——与单体 ExcelUtil.getFields 的字段序一致。 */
    private List<Field> excelFields()
    {
        List<Field> fields = new ArrayList<>();
        for (Class<?> type = clazz; type != null && type != Object.class; type = type.getSuperclass())
        {
            for (Field field : type.getDeclaredFields())
            {
                if (field.isAnnotationPresent(Excel.class))
                {
                    field.setAccessible(true);
                    fields.add(field);
                }
            }
        }
        fields.sort((a, b) -> Integer.compare(a.getAnnotation(Excel.class).sort(), b.getAnnotation(Excel.class).sort()));
        return fields;
    }

    /**
     * 单元格取值（单体 ExcelUtil.getCellValue 逐字移植）。
     */
    public Object getCellValue(Row row, int column)
    {
        if (row == null)
        {
            return row;
        }
        Object val = "";
        try
        {
            Cell cell = row.getCell(column);
            if (StringUtils.isNotNull(cell))
            {
                if (cell.getCellType() == CellType.NUMERIC || cell.getCellType() == CellType.FORMULA)
                {
                    val = cell.getNumericCellValue();
                    if (DateUtil.isCellDateFormatted(cell))
                    {
                        val = DateUtil.getJavaDate((Double) val); // POI Excel 日期格式转换
                    }
                    else
                    {
                        if ((Double) val % 1 != 0)
                        {
                            val = new BigDecimal(val.toString());
                        }
                        else
                        {
                            val = new DecimalFormat("0").format(val);
                        }
                    }
                }
                else if (cell.getCellType() == CellType.STRING)
                {
                    val = cell.getStringCellValue();
                }
                else if (cell.getCellType() == CellType.BOOLEAN)
                {
                    val = cell.getBooleanCellValue();
                }
                else if (cell.getCellType() == CellType.ERROR)
                {
                    val = cell.getErrorCellValue();
                }
            }
        }
        catch (Exception e)
        {
            return val;
        }
        return val;
    }

    private boolean isRowEmpty(Row row)
    {
        if (row == null)
        {
            return true;
        }
        for (int i = row.getFirstCellNum(); i < row.getLastCellNum(); i++)
        {
            Cell cell = row.getCell(i);
            if (cell != null && cell.getCellType() != CellType.BLANK)
            {
                return false;
            }
        }
        return true;
    }

    private String parseDateToStr(String format, Object val)
    {
        if (val instanceof Date date)
        {
            return DateUtils.parseDateToStr(format, date);
        }
        return Convert.toStr(val);
    }

    /**
     * 导出转换（value→label），与单体 ExcelUtil.convertByExpBySeparator 语义一致。
     */
    private String convertByExp(String propertyValue, String converterExp, String separator)
    {
        if (StringUtils.isEmpty(converterExp))
        {
            return propertyValue;
        }
        String[] items = converterExp.split(",");
        for (String item : items)
        {
            String[] itemArray = item.split("=");
            if (StringUtils.isNotEmpty(propertyValue) && itemArray.length == 2 && itemArray[0].equals(propertyValue))
            {
                return itemArray[1];
            }
        }
        return propertyValue;
    }

    /**
     * 导入反向转换（label→value），与单体 ExcelUtil.reverseByExp 语义一致。
     */
    private String reverseByExp(String propertyValue, String converterExp, String separator)
    {
        try
        {
            String[] convertSource = converterExp.split(SEPARATOR);
            for (String item : convertSource)
            {
                String[] itemArray = item.split("=");
                if (StringUtils.isNotEmpty(propertyValue))
                {
                    if (itemArray.length == 2 && StringUtils.trim(itemArray[1]).equals(propertyValue))
                    {
                        return itemArray[0];
                    }
                }
                else
                {
                    return propertyValue;
                }
            }
        }
        catch (Exception e)
        {
            throw e;
        }
        return propertyValue;
    }

    private void setFieldValue(T entity, Field field, Object val)
    {
        try
        {
            field.set(entity, val);
        }
        catch (IllegalAccessException | IllegalArgumentException e)
        {
            throw new UtilException("设置导入字段失败: " + field.getName(), e);
        }
    }

    /** 模板下拉约束（readConverterExp 提示），与单体 createDictDataValidationEvent 等价（非契约点）。 */
    private void addDictDataValidation(Workbook wb, Sheet sheet, String converterExp, String separator, int firstRow, int lastRow, int firstCol, int lastCol)
    {
        StringBuilder explicitList = new StringBuilder();
        for (String item : converterExp.split(","))
        {
            String[] itemArray = item.split("=");
            if (itemArray.length == 2)
            {
                if (explicitList.length() > 0)
                {
                    explicitList.append(",");
                }
                explicitList.append(itemArray[1]);
            }
        }
        if (explicitList.length() == 0)
        {
            return;
        }
        DataValidationHelper helper = sheet.getDataValidationHelper();
        DataValidationConstraint constraint = helper.createExplicitListConstraint(explicitList.toString().split(","));
        DataValidation validation = helper.createValidation(constraint,
                new CellRangeAddressList(firstRow, lastRow, firstCol, lastCol));
        if (wb instanceof SXSSFWorkbook)
        {
            ((SXSSFSheet) sheet).addValidationData(validation);
        }
    }
}
