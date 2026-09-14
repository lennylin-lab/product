package com.product.identity.core.utils;

import com.product.identity.common.annotation.Excel;
import com.product.identity.common.utils.StringUtils;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 极简 Excel 导出器（identity 专属，Phase 2）。
 *
 * <p>背景：单体 ExcelUtil（product-core，1850 行）面向全业务域导出/导入；identity 的
 * 基线导出面只有字典类型/字典数据两个端点，字段仅使用 {@code @Excel} 的 name 与
 * readConverterExp 属性。本导出器读取同一 {@link Excel} 注解，产出语义相同的
 * xlsx（表头行=字段声明顺序的 name 列，数据行=readConverterExp 转换后的值），
 * 响应头与单体一致（Content-Type 同、无 Content-Disposition）。</p>
 *
 * <p>与单体 ExcelUtil 的已知差异：xlsx 内部二进制（样式/时间戳元数据）不逐字节一致——
 * xlsx 文件本身含生成时间戳，单体两次导出亦不相同，不构成 API 契约差异；
 * date/scale/subList 等高级注解属性在 identity 导出面未使用，暂不支持（如后续
 * 阶段把导出迁移面扩大，应整并单体 ExcelUtil）。</p>
 */
public final class ExcelExporter {

    private static final Logger log = LoggerFactory.getLogger(ExcelExporter.class);

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private ExcelExporter() {
    }

    public static <T> void exportExcel(HttpServletResponse response, Class<T> clazz, List<T> list, String sheetName) {
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setCharacterEncoding("utf-8");
        List<Field> excelFields = excelFields(clazz);
        try (SXSSFWorkbook wb = new SXSSFWorkbook(500)) {
            Sheet sheet = wb.createSheet(StringUtils.isNotEmpty(sheetName) ? sheetName : "Sheet1");
            CellStyle headerStyle = headerStyle(wb);
            Row header = sheet.createRow(0);
            for (int i = 0; i < excelFields.size(); i++) {
                Cell cell = header.createCell(i);
                cell.setCellValue(headerName(excelFields.get(i)));
                cell.setCellStyle(headerStyle);
                sheet.setColumnWidth(i, 16 * 256);
            }
            for (int r = 0; r < list.size(); r++) {
                Row row = sheet.createRow(r + 1);
                for (int c = 0; c < excelFields.size(); c++) {
                    row.createCell(c).setCellValue(cellValue(list.get(r), excelFields.get(c)));
                }
            }
            wb.write(response.getOutputStream());
        } catch (IOException e) {
            log.error("导出 Excel 异常", e);
        }
    }

    /** @Excel 字段（含父类），按注解 sort 稳定排序——与单体 ExcelUtil 的字段序一致。 */
    private static List<Field> excelFields(Class<?> clazz) {
        List<Field> fields = new ArrayList<>();
        for (Class<?> type = clazz; type != null && type != Object.class; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (field.isAnnotationPresent(Excel.class)) {
                    field.setAccessible(true);
                    fields.add(field);
                }
            }
        }
        fields.sort((a, b) -> Integer.compare(a.getAnnotation(Excel.class).sort(), b.getAnnotation(Excel.class).sort()));
        return fields;
    }

    private static String headerName(Field field) {
        return field.getAnnotation(Excel.class).name();
    }

    @SuppressWarnings("unchecked")
    private static String cellValue(Object target, Field field) {
        try {
            Object value = field.get(target);
            if (value == null) {
                return "";
            }
            String text;
            if (value instanceof LocalDateTime localDateTime) {
                text = DATE_FORMAT.format(localDateTime);
            } else if (value instanceof BigDecimal bigDecimal) {
                text = bigDecimal.toPlainString();
            } else if (value instanceof List<?>) {
                return "";
            } else {
                text = value.toString();
            }
            Excel excel = field.getAnnotation(Excel.class);
            return convertByExp(text, excel.readConverterExp());
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("读取导出字段失败: " + field.getName(), e);
        }
    }

    /** readConverterExp（"0=男,1=女" 形式）转换，与单体 ExcelUtil.convertByExpBySeparator 语义一致。 */
    private static String convertByExp(String propertyValue, String converterExp) {
        if (StringUtils.isEmpty(converterExp)) {
            return propertyValue;
        }
        String[] items = converterExp.split(",");
        for (String item : items) {
            String[] itemArray = item.split("=");
            if (itemArray.length == 2 && itemArray[0].equals(propertyValue)) {
                return itemArray[1];
            }
        }
        return propertyValue;
    }

    private static CellStyle headerStyle(Workbook wb) {
        CellStyle style = wb.createCellStyle();
        style.setAlignment(HorizontalAlignment.CENTER);
        style.setVerticalAlignment(VerticalAlignment.CENTER);
        Font font = wb.createFont();
        font.setBold(true);
        style.setFont(font);
        return style;
    }
}
