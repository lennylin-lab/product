package com.product.identity.common.annotation;

import com.product.identity.common.utils.poi.ExcelHandlerAdapter;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.IndexedColors;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.math.BigDecimal;

/// 自定义导出Excel数据注解
///
/// 该注解提供了完整的Excel导入导出功能配置，支持字段映射、数据格式化、样式设置、
/// 数据验证、字典转换、图片处理等丰富的功能。通过注解方式实现声明式的Excel操作配置。
///
///
/// 主要功能特性：
/// 1. 字段映射：实体类字段与Excel列的双向映射
/// 2. 数据转换：支持日期格式化、字典翻译、表达式转换
/// 3. 样式控制：单元格颜色、字体、对齐方式等样式设置
/// 4. 数据验证：下拉列表、输入提示、数据范围限制
/// 5. 特殊处理：图片导出、单元格合并、统计汇总
/// 6. 类型支持：数字、字符串、图片、文本等多种数据类型
/// 7. 场景适配：导入模板、数据导出、双向操作等不同场景
///
///
/// 使用示例：
/// <pre>
/// // 基本用法
/// &#64;Excel(name = "用户名称")
/// private String userName;
///
/// // 日期格式化
/// &#64;Excel(name = "创建时间", dateFormat = "yyyy-MM-dd HH:mm:ss")
/// private Date createTime;
///
/// // 字典翻译
/// &#64;Excel(name = "用户性别", dictType = "sys_user_sex")
/// private Integer sex;
///
/// // 表达式转换
/// &#64;Excel(name = "状态", readConverterExp = "0=正常,1=停用")
/// private Integer status;
///
/// // 下拉列表
/// &#64;Excel(name = "部门", combo = {"技术部","市场部","财务部"})
/// private String deptName;
///
/// // 图片导出
/// &#64;Excel(name = "头像", cellType = ColumnType.IMAGE)
/// private String avatar;
/// </pre>
///
///
/// @author fast
/// @see ExcelUtil Excel操作工具类
/// @see ExcelHandlerAdapter 自定义数据处理器
/// @see Excels 多重Excel注解组合
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface Excel
{
    /// 导出时在Excel中的列排序
    ///
    /// 控制字段在Excel中的显示顺序，数值越小越靠前。
    /// 默认值为Integer.MAX_VALUE，表示排在最后。
    ///
    ///
    /// @return 排序值，数值越小越靠前
    ///
    /// @example
    /// <pre>
    /// &#64;Excel(name = "姓名", sort = 1)
    /// private String name;
    ///
    /// &#64;Excel(name = "年龄", sort = 2)
    /// private Integer age;
    /// </pre>
    public int sort() default Integer.MAX_VALUE;

    /// 导出到Excel中的列标题名称
    ///
    /// 指定该字段在Excel表头中显示的名称。支持中英文和特殊字符。
    /// 如果设置为空字符串，则使用字段名作为列标题。
    ///
    ///
    /// @return Excel列标题名称
    ///
    /// @example
    /// <pre>
    /// &#64;Excel(name = "用户编号")
    /// private Long userId;
    ///
    /// &#64;Excel(name = "创建日期(yyyy-MM-dd)")
    /// private Date createTime;
    /// </pre>
    public String name() default "";

    /// 日期格式化模式
    ///
    /// 当字段类型为Date、LocalDateTime、LocalDate时，使用此格式进行日期格式化。
    /// 支持Java中所有的日期格式模式，如：
    /// - yyyy-MM-dd：年-月-日
    /// - yyyy-MM-dd HH:mm:ss：年-月-日 时:分:秒
    /// - HH:mm：时:分
    ///
    ///
    /// @return 日期格式化字符串，空字符串表示不格式化
    ///
    /// @see java.text.SimpleDateFormat 日期格式化规则
    ///
    /// @example
    /// <pre>
    /// &#64;Excel(name = "创建时间", dateFormat = "yyyy-MM-dd HH:mm:ss")
    /// private Date createTime;
    ///
    /// &#64;Excel(name = "生日", dateFormat = "yyyy年MM月dd日")
    /// private Date birthday;
    /// </pre>
    public String dateFormat() default "";

    /// 数据字典类型标识
    ///
    /// 指定数据字典的类型编码，用于将存储的数值转换为可读的标签。
    /// 字典数据通常存储在系统字典表中，通过dictType和dictValue进行关联。
    /// 导出时自动将字典值转换为字典标签，导入时将字典标签转换回字典值。
    ///
    ///
    /// @return 字典类型编码，空字符串表示不使用字典转换
    ///
    /// @example
    /// <pre>
    /// // 数据库中存储：0=男,1=女
    /// // Excel中显示：男,女
    /// &#64;Excel(name = "性别", dictType = "sys_user_sex")
    /// private Integer sex;
    ///
    /// // 系统字典：sys_user_status -> 0=正常,1=停用
    /// &#64;Excel(name = "用户状态", dictType = "sys_user_status")
    /// private Integer status;
    /// </pre>
    public String dictType() default "";

    /// 读取内容的转换表达式
    ///
    /// 定义简单的键值对转换规则，格式为"键=值"，多个转换用逗号分隔。
    /// 导出时将字段的实际值转换为显示值，导入时将显示值转换回实际值。
    /// 适用于简单的状态码、类型码等场景。
    ///
    ///
    /// @return 转换表达式，格式为"key1=value1,key2=value2"，空字符串表示不转换
    ///
    /// @see #separator() 转换表达式的分隔符
    ///
    /// @example
    /// <pre>
    /// // 数据库存储：0,1；Excel显示：正常,停用
    /// &#64;Excel(name = "状态", readConverterExp = "0=正常,1=停用")
    /// private Integer status;
    ///
    /// // 多状态转换
    /// &#64;Excel(name = "类型", readConverterExp = "1=商品,2=服务,3=虚拟")
    /// private Integer type;
    ///
    /// // 布尔值转换
    /// &#64;Excel(name = "是否启用", readConverterExp = "true=是,false=否")
    /// private Boolean enabled;
    /// </pre>
    public String readConverterExp() default "";

    /// 转换表达式的分隔符
    ///
    /// 指定readConverterExp中多个键值对之间的分隔符。
    /// 默认使用英文逗号","作为分隔符。可以自定义其他字符作为分隔符。
    ///
    ///
    /// @return 分隔符字符串，默认为英文逗号
    ///
    /// @see #readConverterExp() 转换表达式
    ///
    /// @example
    /// <pre>
    /// // 使用默认逗号分隔
    /// &#64;Excel(name = "状态", readConverterExp = "0=正常,1=停用")
    /// private Integer status;
    ///
    /// // 使用分号分隔
    /// &#64;Excel(name = "级别", readConverterExp = "1=初级;2=中级;3=高级", separator = ";")
    /// private Integer level;
    /// </pre>
    public String separator() default ",";

    /// BigDecimal数值的精度设置
    ///
    /// 控制BigDecimal类型数值的小数位数。设置为-1表示不进行精度控制，
    /// 保持原始精度。设置为0表示整数，1表示一位小数，以此类推。
    ///
    ///
    /// @return 小数位数，-1表示不控制精度
    ///
    /// @see #roundingMode() 舍入规则设置
    ///
    /// @example
    /// <pre>
    /// // 保留两位小数
    /// &#64;Excel(name = "金额", scale = 2)
    /// private BigDecimal amount;
    ///
    /// // 整数显示
    /// &#64;Excel(name = "数量", scale = 0)
    /// private BigDecimal quantity;
    ///
    /// // 保持原始精度
    /// &#64;Excel(name = "汇率", scale = -1)
    /// private BigDecimal rate;
    /// </pre>
    public int scale() default -1;

    /// BigDecimal数值的舍入规则
    ///
    /// 当设置scale进行精度控制时，指定小数位的舍入方式。
    /// 使用BigDecimal内置的舍入常量，支持多种舍入策略。
    ///
    ///
    /// @return 舍入规则常量，默认为BigDecimal.ROUND_HALF_EVEN
    ///
    /// @see #scale() 精度设置
    /// @see java.math.BigDecimal 舍入规则常量
    ///
    /// @example
    /// <pre>
    /// // 四舍五入
    /// &#64;Excel(name = "金额", scale = 2, roundingMode = BigDecimal.ROUND_HALF_UP)
    /// private BigDecimal amount;
    ///
    /// // 向下取整
    /// &#64;Excel(name = "数量", scale = 0, roundingMode = BigDecimal.ROUND_DOWN)
    /// private BigDecimal quantity;
    /// </pre>
    @SuppressWarnings("deprecation")
    public int roundingMode() default BigDecimal.ROUND_HALF_EVEN;

    /// Excel中行的默认高度（单位：磅）
    ///
    /// 设置该字段所在行的默认高度。影响Excel显示效果和打印效果。
    /// 数值单位为磅（point），1磅约等于1/72英寸。
    ///
    ///
    /// @return 行高度值，默认为14磅
    ///
    /// @see #width() 列宽度设置
    ///
    /// @example
    /// <pre>
    /// // 设置较高的行高以适应多行文本
    /// &#64;Excel(name = "地址", height = 30)
    /// private String address;
    ///
    /// // 标准行高
    /// &#64;Excel(name = "姓名", height = 14)
    /// private String name;
    /// </pre>
    public double height() default 14;

    /// Excel中列的默认宽度（单位：字符）
    ///
    /// 设置该字段所在列的宽度。宽度基于标准字符的宽度计算。
    /// 系统会根据此值自动计算Excel列的实际宽度（像素值）。
    ///
    ///
    /// @return 列宽度值，默认为16个字符宽度
    ///
    /// @see #height() 行高度设置
    ///
    /// @example
    /// <pre>
    /// // 较宽的列用于长文本
    /// &#64;Excel(name = "详细描述", width = 50)
    /// private String description;
    ///
    /// // 较窄的列用于短文本
    /// &#64;Excel(name = "性别", width = 8)
    /// private String gender;
    ///
    /// // 超宽列用于备注
    /// &#64;Excel(name = "备注", width = 100)
    /// private String remark;
    /// </pre>
    public double width() default 16;

    /// 数值显示的后缀字符串
    ///
    /// 在数值后面添加固定的后缀文本，常用于单位显示。
    /// 只在导出时生效，导入时会自动去除后缀。
    ///
    ///
    /// @return 后缀字符串，空字符串表示无后缀
    ///
    /// @example
    /// <pre>
    /// // 百分比显示
    /// &#64;Excel(name = "完成率", suffix = "%")
    /// private Double completionRate;
    ///
    /// // 金额单位
    /// &#64;Excel(name = "价格", suffix = "元")
    /// private BigDecimal price;
    ///
    /// // 时间单位
    /// &#64;Excel(name = "工时", suffix = "小时")
    /// private Integer workHours;
    /// </pre>
    public String suffix() default "";

    /// 空值时的默认显示文本
    ///
    /// 当字段值为null或空字符串时，在Excel中显示的默认文本。
    /// 常用于提供友好的空值显示，如"未设置"、"暂无"等。
    ///
    ///
    /// @return 默认显示文本，空字符串表示显示为空
    ///
    /// @example
    /// <pre>
    /// // 友好的空值提示
    /// &#64;Excel(name = "备注", defaultValue = "暂无备注")
    /// private String remark;
    ///
    /// // 显示0而不是空
    /// &#64;Excel(name = "库存", defaultValue = "0")
    /// private Integer stock;
    ///
    /// // 显示未知状态
    /// &#64;Excel(name = "分类", defaultValue = "未分类")
    /// private String category;
    /// </pre>
    public String defaultValue() default "";

    /// 鼠标悬停时的提示信息
    ///
    /// 在Excel中，当鼠标悬停在该字段对应列的单元格上时显示的提示文本。
    /// 常用于提供输入说明、格式要求、取值范围等信息。
    ///
    ///
    /// @return 提示信息文本，空字符串表示无提示
    ///
    /// @example
    /// <pre>
    /// // 输入格式提示
    /// &#64;Excel(name = "手机号", prompt = "请输入11位手机号码")
    /// private String phoneNumber;
    ///
    /// // 取值范围说明
    /// &#64;Excel(name = "年龄", prompt = "请输入18-60之间的整数")
    /// private Integer age;
    ///
    /// // 格式要求说明
    /// &#64;Excel(name = "日期", prompt = "格式：yyyy-MM-dd")
    /// private Date birthday;
    /// </pre>
    public String prompt() default "";

    /// 是否允许单元格内容自动换行
    ///
    /// 设置Excel单元格是否支持文本自动换行显示。
    /// 当文本内容超过单元格宽度时，如果设置为true，会自动换行显示；
    /// 如果设置为false，文本会超出单元格边界显示。
    ///
    ///
    /// @return true表示允许自动换行，false表示不允许，默认为false
    ///
    /// @example
    /// <pre>
    /// // 长文本自动换行
    /// &#64;Excel(name = "详细地址", wrapText = true)
    /// private String fullAddress;
    ///
    /// // 短文本不换行
    /// &#64;Excel(name = "姓名", wrapText = false)
    /// private String name;
    /// </pre>
    public boolean wrapText() default false;

    /// 下拉列表的可选值
    ///
    /// 为Excel单元格设置下拉选择列表，用户只能从预定义的选项中选择值。
    /// 适用于有固定取值范围的字段，如性别、状态、类型等。
    /// 当选项数量超过15个或总长度超过255字符时，会自动创建隐藏sheet存储选项。
    ///
    ///
    /// @return 下拉选项数组，空数组表示不设置下拉列表
    ///
    /// @see #comboReadDict() 从字典读取下拉选项
    /// @see #prompt() 鼠标悬停提示
    ///
    /// @example
    /// <pre>
    /// // 固定选项下拉列表
    /// &#64;Excel(name = "性别", combo = {"男", "女", "未知"})
    /// private String gender;
    ///
    /// // 状态下拉列表
    /// &#64;Excel(name = "优先级", combo = {"低", "中", "高", "紧急"})
    /// private String priority;
    ///
    /// // 数值选项
    /// &#64;Excel(name = "评分", combo = {"1", "2", "3", "4", "5"})
    /// private Integer rating;
    /// </pre>
    public String[] combo() default {};

    /// 是否从数据字典读取下拉列表选项
    ///
    /// 设置为true时，会从dictType指定的数据字典中读取所有选项作为下拉列表。
    /// 这样可以动态获取最新的字典选项，避免硬编码下拉选项。
    /// 需要配合dictType属性使用。
    ///
    ///
    /// @return true表示从字典读取，false表示使用combo数组，默认为false
    ///
    /// @see #dictType() 数据字典类型
    /// @see #combo() 固定下拉选项
    ///
    /// @example
    /// <pre>
    /// // 从sys_user_sex字典读取下拉选项
    /// &#64;Excel(name = "性别", dictType = "sys_user_sex", comboReadDict = true)
    /// private String gender;
    ///
    /// // 从sys_dept字典读取部门选项
    /// &#64;Excel(name = "部门", dictType = "sys_dept", comboReadDict = true)
    /// private String deptName;
    /// </pre>
    public boolean comboReadDict() default false;

    /// 是否需要纵向合并单元格
    ///
    /// 当实体类中包含集合字段时，设置此选项可以将相同行的非集合字段进行纵向合并。
    /// 主要用于一对多关系的数据展示，如一个用户有多个订单的场景。
    ///
    ///
    /// @return true表示需要纵向合并，false表示不需要，默认为false
    ///
    /// @example
    /// <pre>
    /// // 用户信息（不合并，但对应多个订单）
    /// &#64;Excel(name = "用户名", needMerge = true)
    /// private String userName;
    ///
    /// // 订单列表（Collection类型，不需要合并）
    /// &#64;Excel(name = "订单号", needMerge = false)
    /// private List&lt;Order&gt; orders;
    /// </pre>
    public boolean needMerge() default false;

    /// 是否导出数据到Excel
    ///
    /// 控制该字段在导出Excel时是否包含实际数据。
    /// 设置为false时，只导出列标题，数据单元格留空，
    /// 适用于生成导入模板的场景，用户需要手工填写数据。
    ///
    ///
    /// @return true表示导出数据，false表示只导出标题，默认为true
    ///
    /// @example
    /// <pre>
    /// // 导出模板时只显示标题，数据留空
    /// &#64;Excel(name = "用户姓名", isExport = false)
    /// private String userName;
    ///
    /// // 正常导出数据
    /// &#64;Excel(name = "创建时间", isExport = true)
    /// private Date createTime;
    /// </pre>
    public boolean isExport() default true;

    /// 关联对象的属性名称
    ///
    /// 支持多级属性访问，用小数点分隔。用于处理关联对象字段的导出。
    /// 例如用户对象有部门对象，部门对象有名称属性，可以通过"dept.name"访问。
    ///
    ///
    /// @return 属性路径，空字符串表示使用当前字段
    ///
    /// @example
    /// <pre>
    /// // 访问关联对象的属性
    /// &#64;Excel(name = "部门名称", targetAttr = "dept.name")
    /// private Department dept;
    ///
    /// // 多级属性访问
    /// &#64;Excel(name = "创建人部门", targetAttr = "createBy.dept.name")
    /// private User createBy;
    ///
    /// // 当前属性（不使用targetAttr）
    /// &#64;Excel(name = "用户名")
    /// private String userName;
    /// </pre>
    public String targetAttr() default "";

    /// 是否自动统计数据
    ///
    /// 设置为true时，会在Excel的最后一行自动添加统计数据行。
    /// 对所有标记为isStatistics=true的数值字段进行求和计算。
    /// 统计结果以"合计"标签显示，数值格式化为两位小数。
    ///
    ///
    /// @return true表示参与统计，false表示不参与，默认为false
    ///
    /// @example
    /// <pre>
    /// // 数量字段参与统计
    /// &#64;Excel(name = "数量", isStatistics = true)
    /// private Integer quantity;
    ///
    /// // 金额字段参与统计
    /// &#64;Excel(name = "总金额", isStatistics = true)
    /// private BigDecimal totalAmount;
    ///
    /// // 名称字段不参与统计
    /// &#64;Excel(name = "产品名称", isStatistics = false)
    /// private String productName;
    /// </pre>
    public boolean isStatistics() default false;

    /// Excel单元格的数据类型
    ///
    /// 指定该字段在Excel中的数据类型，影响数据的存储方式和显示格式。
    /// 支持数字、字符串、图片、文本等类型。
    ///
    ///
    /// @return 单元格数据类型，默认为字符串类型
    ///
    /// @see ColumnType 单元格类型枚举
    ///
    /// @example
    /// <pre>
    /// // 数字类型，支持计算
    /// &#64;Excel(name = "年龄", cellType = ColumnType.NUMERIC)
    /// private Integer age;
    ///
    /// // 字符串类型，不支持计算
    /// &#64;Excel(name = "手机号", cellType = ColumnType.STRING)
    /// private String phone;
    ///
    /// // 图片类型
    /// &#64;Excel(name = "头像", cellType = ColumnType.IMAGE)
    /// private String avatar;
    ///
    /// // 文本类型（强制文本格式）
    /// &#64;Excel(name = "编号", cellType = ColumnType.TEXT)
    /// private String code;
    /// </pre>
    public ColumnType cellType() default ColumnType.STRING;

    /// Excel列表头的背景颜色
    ///
    /// 设置列标题单元格的背景填充颜色。
    /// 使用Apache POI的IndexedColors预定义颜色。
    /// 默认为灰色50%的淡灰色。
    ///
    ///
    /// @return 列头背景颜色，默认为GREY_50_PERCENT
    ///
    /// @see #headerColor() 列头字体颜色
    /// @see #backgroundColor() 数据单元格背景颜色
    /// @see org.apache.poi.ss.usermodel.IndexedColors 颜色常量
    ///
    /// @example
    /// <pre>
    /// // 蓝色表头
    /// &#64;Excel(name = "姓名", headerBackgroundColor = IndexedColors.LIGHT_BLUE)
    /// private String name;
    ///
    /// // 绿色表头
    /// &#64;Excel(name = "状态", headerBackgroundColor = IndexedColors.LIGHT_GREEN)
    /// private String status;
    ///
    /// // 使用默认颜色
    /// &#64;Excel(name = "年龄")  // 默认GREY_50_PERCENT
    /// private Integer age;
    /// </pre>
    public IndexedColors headerBackgroundColor() default IndexedColors.GREY_50_PERCENT;

    /// Excel列表头的字体颜色
    ///
    /// 设置列标题单元格的文本字体颜色。
    /// 使用Apache POI的IndexedColors预定义颜色。
    /// 默认为白色，与默认的灰色背景形成对比。
    ///
    ///
    /// @return 列头字体颜色，默认为WHITE
    ///
    /// @see #headerBackgroundColor() 列头背景颜色
    /// @see #color() 数据单元格字体颜色
    /// @see org.apache.poi.ss.usermodel.IndexedColors 颜色常量
    ///
    /// @example
    /// <pre>
    /// // 黑色表头文字
    /// &#64;Excel(name = "姓名", headerColor = IndexedColors.BLACK)
    /// private String name;
    ///
    /// // 红色表头文字
    /// &#64;Excel(name = "紧急度", headerColor = IndexedColors.RED)
    /// private String urgency;
    ///
    /// // 使用默认白色
    /// &#64;Excel(name = "年龄")  // 默认WHITE
    /// private Integer age;
    /// </pre>
    public IndexedColors headerColor() default IndexedColors.WHITE;

    /// Excel数据单元格的背景颜色
    ///
    /// 设置数据单元格的背景填充颜色。
    /// 用于区分不同类型的数据或提高可读性。
    /// 默认为白色背景。
    ///
    ///
    /// @return 数据单元格背景颜色，默认为WHITE
    ///
    /// @see #headerBackgroundColor() 列头背景颜色
    /// @see #color() 数据单元格字体颜色
    /// @see org.apache.poi.ss.usermodel.IndexedColors 颜色常量
    ///
    /// @example
    /// <pre>
    /// // 浅黄色背景
    /// &#64;Excel(name = "价格", backgroundColor = IndexedColors.LIGHT_YELLOW)
    /// private BigDecimal price;
    ///
    /// // 浅绿色背景
    /// &#64;Excel(name = "库存", backgroundColor = IndexedColors.LIGHT_GREEN)
    /// private Integer stock;
    ///
    /// // 使用默认白色
    /// &#64;Excel(name = "姓名")  // 默认WHITE
    /// private String name;
    /// </pre>
    public IndexedColors backgroundColor() default IndexedColors.WHITE;

    /// Excel数据单元格的字体颜色
    ///
    /// 设置数据单元格中文本的字体颜色。
    /// 用于突出显示重要数据或区分不同类型的数据。
    /// 默认为黑色文字。
    ///
    ///
    /// @return 数据单元格字体颜色，默认为BLACK
    ///
    /// @see #backgroundColor() 数据单元格背景颜色
    /// @see #headerColor() 列头字体颜色
    /// @see org.apache.poi.ss.usermodel.IndexedColors 颜色常量
    ///
    /// @example
    /// <pre>
    /// // 红色文字显示重要数据
    /// &#64;Excel(name = "金额", color = IndexedColors.RED)
    /// private BigDecimal amount;
    ///
    /// // 蓝色文字显示链接类型数据
    /// &#64;Excel(name = "网址", color = IndexedColors.BLUE)
    /// private String website;
    ///
    /// // 使用默认黑色
    /// &#64;Excel(name = "姓名")  // 默认BLACK
    /// private String name;
    /// </pre>
    public IndexedColors color() default IndexedColors.BLACK;

    /// Excel单元格的对齐方式
    ///
    /// 设置单元格内容的水平和垂直对齐方式。
    /// 控制文本在单元格中的显示位置，提升数据可读性。
    /// 默认为居中对齐。
    ///
    ///
    /// @return 对齐方式，默认为居中对齐
    ///
    /// @see org.apache.poi.ss.usermodel.HorizontalAlignment 水平对齐
    /// @see org.apache.poi.ss.usermodel.VerticalAlignment 垂直对齐
    ///
    /// @example
    /// <pre>
    /// // 左对齐（适合文本）
    /// &#64;Excel(name = "姓名", align = HorizontalAlignment.LEFT)
    /// private String name;
    ///
    /// // 右对齐（适合数字）
    /// &#64;Excel(name = "金额", align = HorizontalAlignment.RIGHT)
    /// private BigDecimal amount;
    ///
    /// // 居中对齐（默认）
    /// &#64;Excel(name = "状态")  // 默认CENTER
    /// private String status;
    /// </pre>
    public HorizontalAlignment align() default HorizontalAlignment.CENTER;

    /// 自定义数据处理器类
    ///
    /// 指定一个实现了ExcelHandlerAdapter接口的类，用于自定义数据的导入导出处理逻辑。
    /// 当系统内置的转换功能无法满足需求时，可以通过自定义处理器实现复杂的数据转换。
    ///
    ///
    /// @return 处理器类Class对象，默认为ExcelHandlerAdapter（不处理）
    ///
    /// @see #args() 处理器参数
    /// @see ExcelHandlerAdapter 数据处理器接口
    ///
    /// @example
    /// <pre>
    /// // 使用自定义处理器
    /// &#64;Excel(name = "身份证号", handler = IdCardHandler.class)
    /// private String idCard;
    ///
    /// // 自定义图片处理器
    /// &#64;Excel(name = "照片", handler = ImageHandler.class, args = {"200", "200"})
    /// private String photo;
    ///
    /// // 使用默认处理器（不处理）
    /// &#64;Excel(name = "姓名")  // 默认ExcelHandlerAdapter.class
    /// private String name;
    /// </pre>
    public Class<?> handler() default ExcelHandlerAdapter.class;

    /// 自定义数据处理器的参数
    ///
    /// 传递给自定义数据处理器的参数数组。
    /// 这些参数会在调用处理器时传递给format方法，
    /// 用于控制处理器的具体行为。
    ///
    ///
    /// @return 处理器参数数组，默认为空数组
    ///
    /// @see #handler() 自定义处理器类
    ///
    /// @example
    /// <pre>
    /// // 图片尺寸参数
    /// &#64;Excel(name = "头像", handler = ImageResizeHandler.class, args = {"200", "200"})
    /// private String avatar;
    ///
    /// // 格式化参数
    /// &#64;Excel(name = "电话", handler = PhoneFormatHandler.class, args = {"", "-"})
    /// private String phoneNumber;
    ///
    /// // 多个参数
    /// &#64;Excel(name = "地址", handler = AddressSplitHandler.class, args = {"省", "市", "区"})
    /// private String address;
    /// </pre>
    public String[] args() default {};

    /// 字段的导入导出类型控制
    ///
    /// 控制字段在不同操作场景下的行为：
    /// - ALL(0): 同时支持导入和导出
    /// - EXPORT(1): 仅支持导出，不导入
    /// - IMPORT(2): 仅支持导入，不导出
    ///
    ///
    /// @return 字段类型枚举值，默认为ALL（全支持）
    ///
    /// @see Type 字段类型枚举
    ///
    /// @example
    /// <pre>
    /// // 只导出，不导入（如系统生成的字段）
    /// &#64;Excel(name = "创建时间", type = Type.EXPORT)
    /// private Date createTime;
    ///
    /// // 只导入，不导出（如临时字段）
    /// &#64;Excel(name = "确认密码", type = Type.IMPORT)
    /// private String confirmPassword;
    ///
    /// // 导入导出都支持
    /// &#64;Excel(name = "用户名", type = Type.ALL)  // 默认ALL
    /// private String userName;
    /// </pre>
    Type type() default Type.ALL;

    public enum Type
    {
        ALL(0), EXPORT(1), IMPORT(2);
        private final int value;

        Type(int value)
        {
            this.value = value;
        }

        public int value()
        {
            return this.value;
        }
    }

    public enum ColumnType
    {
        NUMERIC(0), STRING(1), IMAGE(2), TEXT(3);
        private final int value;

        ColumnType(int value)
        {
            this.value = value;
        }

        public int value()
        {
            return this.value;
        }
    }
}
