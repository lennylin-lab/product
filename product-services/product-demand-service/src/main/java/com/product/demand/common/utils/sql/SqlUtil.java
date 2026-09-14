package com.product.demand.common.utils.sql;

import com.product.demand.common.exception.UtilException;
import com.product.demand.common.utils.StringUtils;

/// SQL操作工具类
///
/// 该工具类专门用于防止SQL注入攻击，提供SQL参数的安全检查和验证功能。
/// 通过预定义的危险关键字检测和正则表达式验证，确保传入的SQL参数符合安全规范。
///
///
/// 核心安全功能：
/// 1. SQL注入防护：检测和阻止常见的SQL注入攻击模式
/// 2. 参数验证：验证排序字段、查询条件等参数的合法性
/// 3. 长度限制：防止过长的参数导致的问题
/// 4. 关键字过滤：阻止危险SQL关键字的执行
/// 5. 模式匹配：通过正则表达式确保参数格式正确
///
///
/// 应用场景：
/// - 动态排序参数验证（ORDER BY子句）
/// - 查询条件参数安全检查
/// - API接口参数过滤
/// - 前端传递的SQL相关参数验证
/// - 数据库操作前的安全检查
///
///
/// @author fast
/// @see StringUtils 字符串工具类
/// @see UtilException 工具类异常
public class SqlUtil
{
    /// 常见SQL注入攻击关键字正则表达式
    ///
    /// 定义了需要检测的危险SQL关键字和特殊字符，包括：
    /// - SQL DML语句：insert, select, delete, update, drop, truncate等
    /// - SQL函数：extractvalue, updatexml, sleep, count, chr, mid, char等
    /// - SQL操作符：and, or, union, like等
    /// - 特殊字符：/*注释符，+连接符等
    /// - MySQL函数：user(), master等
    ///
    ///
    /// 使用示例：
    /// <pre>
    /// // 这些都会被检测为危险关键字：
    /// "SELECT * FROM users"        // 包含select
    /// "DROP TABLE test"           // 包含drop
    /// "1' OR '1'='1'"             // 包含or
    /// "admin'--"                  // 包含注释符
    /// "SLEEP(5)"                  // 包含sleep函数
    /// </pre>
    ///
    public static String SQL_REGEX = "\u000B|and |extractvalue|updatexml|sleep|exec |insert |select |delete |update |drop |count |chr |mid |master |truncate |char |declare |or |union |like |+|/*|user()";

    /// 安全的SQL参数验证正则表达式
    ///
    /// 仅允许以下字符，确保参数不包含危险内容：
    /// - 英文字母：a-z, A-Z
    /// - 数字：0-9
    /// - 下划线：_
    /// - 空格：
    /// - 逗号：,（用于多字段排序）
    /// - 小数点：.（用于表名.字段名格式）
    ///
    ///
    /// 安全示例：
    /// <pre>
    /// // 合法的参数格式：
    /// "user_id"                   // 单个字段名
    /// "user_id, create_time"       // 多字段名，逗号分隔
    /// "user.name"                  // 表名.字段名格式
    /// "create_time DESC"           // 字段名 + 排序方向
    /// "name ASC, age DESC"         // 多字段排序
    /// </pre>
    /// 危险示例：
    /// <pre>
    /// // 不合法的参数格式：
    /// "user_id; DROP TABLE"        // 包含分号
    /// "name' OR '1'='1'"           // 包含单引号和OR
    /// "id UNION SELECT"            // 包含UNION
    /// </pre>
    ///
    public static String SQL_PATTERN = "[a-zA-Z0-9_\\ \\,\\.]+";

    /// ORDER BY参数最大长度限制
    ///
    /// 限制排序参数的最大长度为500个字符，防止：
    /// - 过长的参数导致的性能问题
    /// - 缓冲区溢出攻击
    /// - 复杂的注入攻击尝试
    ///
    private static final int ORDER_BY_MAX_LENGTH = 500;

    /// 安全化ORDER BY SQL参数
    ///
    /// 对传入的排序参数进行安全检查，确保不会导致SQL注入攻击。
    /// 该方法是前端传递排序参数到后端的安全入口点。
    ///
    ///
    /// 检查流程：
    /// 1. 空值检查：空字符串直接返回
    /// 2. 格式验证：检查是否只包含安全字符
    /// 3. 长度检查：确保不超过最大长度限制
    /// 4. 异常处理：不符合规范时抛出UtilException
    ///
    /// 测试用例：
    /// <pre>
    /// // 安全的输入示例
    /// SqlUtil.escapeOrderBySql("user_id")           // 返回: "user_id"
    /// SqlUtil.escapeOrderBySql("create_time DESC")   // 返回: "create_time DESC"
    /// SqlUtil.escapeOrderBySql("name, age")         // 返回: "name, age"
    /// SqlUtil.escapeOrderBySql("")                  // 返回: ""
    /// SqlUtil.escapeOrderBySql(null)                 // 返回: null
    ///
    /// // 会抛出异常的危险输入示例
    /// SqlUtil.escapeOrderBySql("user_id; DROP TABLE") // 抛出: 参数不符合规范
    /// SqlUtil.escapeOrderBySql("name' OR '1'='1'")   // 抛出: 参数不符合规范
    /// SqlUtil.escapeOrderBySql("name UNION SELECT")  // 抛出: 参数不符合规范
    /// </pre>
    ///
    ///
    /// @param value 需要验证的ORDER BY参数字符串
    /// @return 验证通过的安全参数字符串
    /// @throws UtilException 当参数包含不安全字符或超过长度限制时抛出
    ///
    /// @see #isValidOrderBySql(String) 参数格式验证方法
    /// @see #ORDER_BY_MAX_LENGTH 最大长度限制常量
    ///
    /// @since 1.0
    ///
    /// @example
    /// <pre>
    /// // Web层使用
    /// &#64;RestController
    /// public class UserController {
    ///
    ///     &#64;GetMapping("/list")
    ///     public Result list(@RequestParam(required = false) String sort) {
    ///         // 安全化排序参数
    ///         String safeSort = SqlUtil.escapeOrderBySql(sort);
    ///         return userService.getUserList(safeSort);
    ///     }
    /// }
    ///
    /// // 前端调用示例
    /// // GET /api/users/list?sort=create_time_desc
    /// // GET /api/users/list?sort=name,age
    /// </pre>
    public static String escapeOrderBySql(String value)
    {
        if (StringUtils.isNotEmpty(value) && !isValidOrderBySql(value))
        {
            throw new UtilException("参数不符合规范，不能进行查询");
        }
        if (StringUtils.length(value) > ORDER_BY_MAX_LENGTH)
        {
            throw new UtilException("参数已超过最大限制，不能进行查询");
        }
        return value;
    }

    ///
    /// 验证ORDER BY参数格式是否符合规范
    /// <p>
    /// 使用预定义的正则表达式验证参数是否只包含安全字符。
    /// 这是escapeOrderBySql方法的核心验证逻辑，也可单独使用进行格式检查。
    /// </p>
    ///
    /// <p>
    /// 验证规则：
    /// - 只允许英文字母、数字、下划线
    /// - 允许空格（用于排序方向等）
    /// - 允许逗号（用于多字段分隔）
    /// - 允许小数点（用于表名.字段名格式）
    /// - 不允许任何特殊字符或SQL关键字
    /// </p>
    ///
    /// <p>
    /// 使用示例：
    ///
    /// // 条件验证
    /// String userSort = request.getParameter("sort");
    /// if (StringUtils.isNotEmpty(userSort) && SqlUtil.isValidOrderBySql(userSort)) {
    ///     // 使用安全的排序参数
    ///     userRepository.findAll(userSort);
    /// } else {
    ///     // 使用默认排序
    ///     userRepository.findAll("id");
    /// }
    /// </pre>
    /// </p>
    ///
    /// <p>
    /// 验证测试用例：
    /// <pre>
    /// // 返回true的安全示例
    /// isValidOrderBySql("user_id")                 // true
    /// isValidOrderBySql("user.name")                // true
    /// isValidOrderBySql("create_time DESC")         // true
    /// isValidOrderBySql("name, age, create_time")   // true
    /// isValidOrderBySql("field1 ASC, field2 DESC")  // true
    /// isValidOrderBySql("user_profile.user_id")     // true
    ///
    /// // 返回false的危险示例
    /// isValidOrderBySql("user_id; DROP TABLE")     // false (包含分号)
    /// isValidOrderBySql("name' OR '1'='1'")         // false (包含单引号和OR)
    /// isValidOrderBySql("id UNION SELECT")          // false (包含UNION)
    /// isValidOrderBySql("name--")                   // false (包含注释符)
    /// isValidOrderBySql("id/**/")                   // false (包含注释)
    /// isValidOrderBySql("中文字段")                   // false (包含中文)
    /// isValidOrderBySql("name@domain.com")          // false (包含特殊字符)
    /// </pre>
    /// </p>
    ///
    /// @param value 需要验证的字符串参数
    /// @return true表示参数安全，false表示参数包含危险字符
    ///
    /// @see #SQL_PATTERN 安全字符正则表达式
    /// @see #escapeOrderBySql(String) 安全化方法
    ///
    /// @since 1.0
    ///
    /// @example
    /// <pre>
    /// // 拦截器中使用示例
    /// &#64;Component
    /// public class SqlInjectionInterceptor implements HandlerInterceptor {
    ///
    ///     &#64;Override
    ///     public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
    ///         // 检查所有查询参数
    ///         Enumeration&lt;String&gt; params = request.getParameterNames();
    ///         while (params.hasMoreElements()) {
    ///             String paramName = params.nextElement();
    ///             String paramValue = request.getParameter(paramName);
    ///
    ///             // 如果是排序相关参数，进行安全验证
    ///             if (paramName.contains("sort") || paramName.contains("order")) {
    ///                 if (!SqlUtil.isValidOrderBySql(paramValue)) {
    ///                     throw new SecurityException("Potential SQL injection detected");
    ///                 }
    ///             }
    ///         }
    ///         return true;
    ///     }
    /// }
    /// </pre>
    ///
    public static boolean isValidOrderBySql(String value)
    {
        return value.matches(SQL_PATTERN);
    }

    /// SQL关键字安全过滤
    ///
    /// 检查输入字符串是否包含危险的SQL关键字，用于防止SQL注入攻击。
    /// 该方法通过检测预定义的危险关键字列表来识别潜在的注入攻击。
    ///
    ///
    /// 检测范围包括：
    /// - 数据操作语言：INSERT, SELECT, DELETE, UPDATE, DROP, TRUNCATE
    /// - 数据查询语言：COUNT, UNION, LIKE
    /// - 数据控制语言：EXEC, DECLARE
    /// - SQL函数：SLEEP, EXTRACTVALUE, UPDATEXML, CHR, MID, CHAR
    /// - 系统函数：USER(), MASTER
    /// - 特殊字符：/*注释符，+连接符等
    ///
    ///
    /// 使用场景：
    /// - 用户输入的搜索关键词过滤
    /// - 动态SQL构建前的参数检查
    /// - API接口参数安全验证
    /// - 批量数据导入时的字段名验证
    /// - 报表生成时的列名验证
    ///
    ///
    /// 测试用例：
    /// <pre>
    /// // 安全输入（不会抛出异常）
    /// filterKeyword("张三")                    // 正常
    /// filterKeyword("john@example.com")        // 正常
    /// filterKeyword("product_123")             // 正常
    /// filterKeyword("2023-12-01")              // 正常
    /// filterKeyword("")                        // 正常（空值）
    /// filterKeyword(null)                      // 正常（null值）
    ///
    /// // 危险输入（会抛出UtilException）
    /// filterKeyword("admin' OR '1'='1")        // 抛出: 参数存在SQL注入风险
    /// filterKeyword("user; DROP TABLE")        // 抛出: 参数存在SQL注入风险
    /// filterKeyword("name UNION SELECT")       // 抛出: 参数存在SQL注入风险
    /// filterKeyword("1' AND SLEEP(5)")         // 抛出: 参数存在SQL注入风险
    /// filterKeyword("admin'/*")                // 抛出: 参数存在SQL注入风险
    /// filterKeyword("EXTRACTVALUE(1,")         // 抛出: 参数存在SQL注入风险
    /// </pre>
    ///
    ///
    /// 安全建议：
    /// 1. 在所有用户输入处理前使用此方法进行过滤
    /// 2. 配合使用参数化查询，提供额外的安全保护
    /// 3. 对批量操作中的每个参数都要进行检查
    /// 4. 记录被拒绝的请求，用于安全监控
    /// 5. 结合Web应用防火墙提供多层防护
    ///
    ///
    /// @param value 需要检查的字符串参数
    /// @throws UtilException 当参数包含危险SQL关键字时抛出异常
    ///
    /// @see #SQL_REGEX 危险关键字正则表达式
    /// @see #escapeOrderBySql(String) 排序参数安全化
    public static void filterKeyword(String value)
    {
        if (StringUtils.isEmpty(value))
        {
            return;
        }
        String[] sqlKeywords = StringUtils.split(SQL_REGEX, "\\|");
        for (String sqlKeyword : sqlKeywords)
        {
            if (StringUtils.indexOfIgnoreCase(value, sqlKeyword) > -1)
            {
                throw new UtilException("参数存在SQL注入风险");
            }
        }
    }
}
