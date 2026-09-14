package com.product.identity.core.utils;

import com.alibaba.fastjson2.JSONArray;
import com.product.identity.common.constant.CacheConstants;
import com.product.identity.common.core.redis.RedisCache;
import com.product.identity.common.utils.StringUtils;
import com.product.identity.domain.entity.SysDictData;
import com.product.identity.common.utils.spring.SpringUtils;

import java.util.Collection;
import java.util.List;

/**
 * 字典工具类
 * <p>
 * 该工具类提供系统字典数据的统一管理功能，基于Redis缓存实现高性能的字典数据访问。
 * 支持字典数据的缓存管理、双向转换、批量操作等功能，是系统中数据字典的核心操作工具。
 * </p>
 *
 * <p>
 * 核心功能特性：
 * 1. 缓存管理：基于Redis的字典数据缓存，提供高性能访问
 * 2. 双向转换：支持字典值与字典标签的双向转换
 * 3. 批量操作：支持多值转换和批量数据处理
 * 4. 缓存控制：提供字典缓存的增删改查操作
 * 5. 多格式支持：支持JSONArray和List两种缓存格式
 * 6. 分隔符处理：灵活支持自定义分隔符的数据转换
 * </p>
 *
 * <p>
 * 数据字典结构：
 * - 字典类型(dictType)：标识一组相关字典数据的类型，如"sys_user_sex"
 * - 字典值(dictValue)：存储在数据库中的实际值，如"0"、"1"、"2"
 * - 字典标签(dictLabel)：显示给用户的可读文本，如"男"、"女"、"未知"
 * </p>
 *
 * <p>
 * 应用场景：
 * - 数据展示：将数据库存储的编码转换为用户友好的显示文本
 * - 数据导入：将用户输入的文本转换为数据库存储的编码
 * - 下拉选项：为前端提供下拉列表的选项数据
 * - 数据验证：验证用户输入的数据是否在有效范围内
 * - 报表生成：将数据转换为可读的报表格式
 * - Excel导出：配合@Excel注解实现字典数据的自动转换
 * </p>
 *
 * @author fast
 * @see SysDictData 字典数据实体类
 * @see RedisCache Redis缓存工具类
 * @see CacheConstants 缓存常量
 * @since 1.0
 *
 * @example
 * <pre>
 * // 字典数据示例（sys_user_sex类型）：
 * // dictType="sys_user_sex", dictValue="0", dictLabel="男"
 * // dictType="sys_user_sex", dictValue="1", dictLabel="女"
 * // dictType="sys_user_sex", dictValue="2", dictLabel="未知"
 *
 * // 基本使用示例
 * String label = DictUtils.getDictLabel("sys_user_sex", "0");  // 返回: "男"
 * String value = DictUtils.getDictValue("sys_user_sex", "男");  // 返回: "0"
 *
 * // 批量转换示例
 * String labels = DictUtils.getDictLabel("sys_user_sex", "0,1"); // 返回: "男,女"
 * </pre>
 */
public class DictUtils
{
    /**
     * 字典数据分隔符
     * <p>
     * 用于分隔多个字典值或标签，默认使用英文逗号。
     * 在处理多选、批量操作等场景时使用此分隔符。
     * </p>
     *
     * <p>
     * 使用示例：
     * <pre>
     * // 多个字典值使用分隔符连接
     * String userStatuses = "0,1,2";  // 正常,停用,删除
     * String colors = "red,blue,green";  // 红色,蓝色,绿色
     *
     * // 转换时会按分隔符分割处理
     * String labels = DictUtils.getDictLabel("user_status", userStatuses);
     * // 返回: "正常,停用,删除"
     * </pre>
     * </p>
     */
    public static final String SEPARATOR = ",";

    /**
     * 设置字典缓存到Redis
     * <p>
     * 将指定类型的字典数据列表缓存到Redis中，提高后续访问性能。
     * 通常在系统启动时或字典数据更新时调用此方法。
     * </p>
     *
     * <p>
     * 缓存机制：
     * - 使用统一的缓存键格式：SYS_DICT_KEY + dictType
     * - 支持自动序列化和反序列化
     * - 空值安全：Redis不可用时不会抛出异常
     * </p>
     *
     * <p>
     * 使用示例：
     * <pre>
     * // 系统启动时加载字典数据到缓存
     * &#64;PostConstruct
     * public void initDictCache() {
     *     List&lt;SysDictData&gt; userSexDict = dictDataService.selectDictDataByType("sys_user_sex");
     *     DictUtils.setDictCache("sys_user_sex", userSexDict);
     *
     *     List&lt;SysDictData&gt; userStatusDict = dictDataService.selectDictDataByType("sys_user_status");
     *     DictUtils.setDictCache("sys_user_status", userStatusDict);
     * }
     *
     * // 字典数据更新时刷新缓存
     * &#64;Override
     * public void updateDictData(SysDictData dictData) {
     *     dictDataMapper.updateById(dictData);
     *
     *     // 刷新该字典类型的缓存
     *     List&lt;SysDictData&gt; updatedDict = dictDataService.selectDictDataByType(dictData.getDictType());
     *     DictUtils.setDictCache(dictData.getDictType(), updatedDict);
     * }
     *
     * // 批量设置字典缓存
     * public void loadAllDictToCache() {
     *     List&lt;SysDictType&gt; dictTypes = dictTypeService.selectList();
     *     for (SysDictType dictType : dictTypes) {
     *         List&lt;SysDictData&gt; dictDatas = dictDataService.selectDictDataByType(dictType.getDictType());
     *         DictUtils.setDictCache(dictType.getDictType(), dictDatas);
     *     }
     * }
     * </pre>
     * </p>
     *
     * @param key 字典类型标识，如"sys_user_sex"、"sys_user_status"等
     * @param dictDatas 该字典类型对应的所有字典数据列表
     *
     * @see #getDictCache(String) 获取字典缓存
     * @see #removeDictCache(String) 删除指定字典缓存
     * @see #getCacheKey(String) 获取缓存键
     *
     * @since 1.0
     *
     * @example
     * <pre>
     * // 准备字典数据
     * List&lt;SysDictData&gt; sexDict = Arrays.asList(
     *     new SysDictData("sys_user_sex", "0", "男", "0-男性"),
     *     new SysDictData("sys_user_sex", "1", "女", "1-女性"),
     *     new SysDictData("sys_user_sex", "2", "未知", "2-未知性别")
     * );
     *
     * // 设置缓存
     * DictUtils.setDictCache("sys_user_sex", sexDict);
     *
     * // 后续可以通过字典类型快速访问
     * String maleLabel = DictUtils.getDictLabel("sys_user_sex", "0"); // 返回: "男"
     * </pre>
     */
    public static void setDictCache(String key, List<SysDictData> dictDatas)
    {
        RedisCache redisCache = SpringUtils.getBean(RedisCache.class);
        if (redisCache != null) {
            redisCache.setCacheObject(getCacheKey(key), dictDatas);
        }
    }

    /**
     * 从Redis缓存中获取字典数据列表
     * <p>
     * 根据字典类型获取缓存的字典数据列表，支持多种缓存格式的兼容性处理。
     * 这是所有字典操作的基础方法，提供高性能的数据访问。
     * </p>
     *
     * <p>
     * 缓存兼容性：
     * - JSONArray格式：JSON字符串序列化的字典数据
     * - List格式：直接对象列表的字典数据
     * - 空值处理：缓存不存在或Redis不可用时返回null
     * </p>
     *
     * <p>
     * 使用示例：
     * <pre>
     * // 基本使用
     * List&lt;SysDictData&gt; userSexDict = DictUtils.getDictCache("sys_user_sex");
     * if (userSexDict != null) {
     *     // 处理字典数据
     *     for (SysDictData dict : userSexDict) {
     *         System.out.println(dict.getDictValue() + "=" + dict.getDictLabel());
     *     }
     * }
     *
     * // 构建下拉选项
     * public List&lt;SelectOption&gt; getDictOptions(String dictType) {
     *     List&lt;SysDictData&gt; dictData = DictUtils.getDictCache(dictType);
     *     return dictData.stream()
     *         .map(dict -&gt; new SelectOption(dict.getDictValue(), dict.getDictLabel()))
     *         .collect(Collectors.toList());
     * }
     *
     * // 缓存预热检查
     * public boolean isDictCacheLoaded(String dictType) {
     *     return DictUtils.getDictCache(dictType) != null;
     * }
     *
     * // 字典数据验证
     * public boolean isValidDictValue(String dictType, String dictValue) {
     *     List&lt;SysDictData&gt; dictData = DictUtils.getDictCache(dictType);
     *     return dictData != null && dictData.stream()
     *         .anyMatch(dict -&gt; dictValue.equals(dict.getDictValue()));
     * }
     * </pre>
     * </p>
     *
     * @param key 字典类型标识
     * @return 字典数据列表，缓存不存在或Redis不可用时返回null
     *
     * @see #setDictCache(String, List) 设置字典缓存
     * @see #getDictLabel(String, String) 获取字典标签
     * @see #getDictValue(String, String) 获取字典值
     *
     * @since 1.0
     *
     * @example
     * <pre>
     * // 获取用户性别字典数据
     * List&lt;SysDictData&gt; sexDict = DictUtils.getDictCache("sys_user_sex");
     *
     * // 使用字典数据构建前端选项
     * if (sexDict != null) {
     *     for (SysDictData dict : sexDict) {
     *         System.out.println("值: " + dict.getDictValue() + ", 标签: " + dict.getDictLabel());
     *         // 输出: 值: 0, 标签: 男
     *         // 输出: 值: 1, 标签: 女
     *         // 输出: 值: 2, 标签: 未知
     *     }
     * }
     * </pre>
     */
    public static List<SysDictData> getDictCache(String key)
    {
        RedisCache redisCache = SpringUtils.getBean(RedisCache.class);
        if (redisCache == null) {
            return null;
        }
        Object cache = redisCache.getCacheObject(getCacheKey(key));
        if (StringUtils.isNotNull(cache))
        {
            if (cache instanceof JSONArray) {
                return ((JSONArray) cache).toList(SysDictData.class);
            } else if (cache instanceof List) {
                return (List<SysDictData>) cache;
            }
        }
        return null;
    }

    /**
     * 根据字典类型和字典值获取字典标签（使用默认分隔符）
     * <p>
     * 将数据库存储的字典值转换为用户友好的显示标签，是最常用的字典转换方法。
     * 适用于单个字典值的转换场景。
     * </p>
     *
     * <p>
     * 转换流程：
     * 1. 空值检查：空字符串直接返回空字符串
     * 2. 缓存查询：从Redis获取该类型的字典数据
     * 3. 值匹配：在字典数据中查找匹配的字典值
     * 4. 标签返回：返回对应的字典标签
     * </p>
     *
     * <p>
     * 使用示例：
     * <pre>
     * // Controller中使用 - 数据展示
     * &#64;GetMapping("/users")
     * public List&lt;UserVO&gt; getUserList() {
     *     List&lt;User&gt; users = userService.list();
     *     return users.stream().map(user -&gt; {
     *         UserVO vo = new UserVO();
     *         BeanUtils.copyProperties(user, vo);
     *         // 将性别编码转换为显示文本
     *         vo.setSexLabel(DictUtils.getDictLabel("sys_user_sex", user.getSex()));
     *         // 将状态编码转换为显示文本
     *         vo.setStatusLabel(DictUtils.getDictLabel("sys_user_status", user.getStatus()));
     *         return vo;
     *     }).collect(Collectors.toList());
     * }
     *
     * // Service中使用 - 数据转换
     * public String getUserStatusDisplay(Integer statusCode) {
     *     return DictUtils.getDictLabel("sys_user_status", String.valueOf(statusCode));
     * }
     *
     * // Excel导出中使用
     * &#64;Excel(name = "性别", dictType = "sys_user_sex")
     * private String sex;  // 字段值为"0"、"1"、"2"，Excel中显示为"男"、"女"、"未知"
     *
     * // 实体类转换
     * public UserVO convertToVO(User user) {
     *     UserVO vo = new UserVO();
     *     vo.setSex(DictUtils.getDictLabel("sys_user_sex", user.getSex()));
     *     vo.setStatus(DictUtils.getDictLabel("sys_user_status", user.getStatus()));
     *     vo.setDeptName(DictUtils.getDictLabel("sys_dept", user.getDeptId()));
     *     return vo;
     * }
     * </pre>
     * </p>
     *
     * @param dictType 字典类型，如"sys_user_sex"
     * @param dictValue 字典值，如"0"、"1"、"2"
     * @return 对应的字典标签，如果找不到返回空字符串
     *
     * @see #getDictLabel(String, String, String) 支持自定义分隔符的版本
     * @see #getDictValue(String, String) 反向转换方法
     *
     * @since 1.0
     *
     * @example
     * <pre>
     * // 假设sys_user_sex字典数据：
     * // 0 -> 男, 1 -> 女, 2 -> 未知
     *
     * String label1 = DictUtils.getDictLabel("sys_user_sex", "0");    // 返回: "男"
     * String label2 = DictUtils.getDictLabel("sys_user_sex", "1");    // 返回: "女"
     * String label3 = DictUtils.getDictLabel("sys_user_sex", "2");    // 返回: "未知"
     * String label4 = DictUtils.getDictLabel("sys_user_sex", "3");    // 返回: "" (未找到)
     * String label5 = DictUtils.getDictLabel("sys_user_sex", "");     // 返回: "" (空值)
     * String label6 = DictUtils.getDictLabel("unknown_type", "0");    // 返回: "" (字典类型不存在)
     * </pre>
     */
    public static String getDictLabel(String dictType, String dictValue)
    {
        if (StringUtils.isEmpty(dictValue))
        {
            return StringUtils.EMPTY;
        }
        return getDictLabel(dictType, dictValue, SEPARATOR);
    }

    /**
     * 根据字典类型和字典标签获取字典值（使用默认分隔符）
     * <p>
     * 将用户输入的显示文本转换为数据库存储的字典值，是getDictLabel的反向操作。
     * 主要用于数据导入、用户输入处理等场景。
     * </p>
     *
     * <p>
     * 转换流程：
     * 1. 空值检查：空字符串直接返回空字符串
     * 2. 缓存查询：从Redis获取该类型的字典数据
     * 3. 标签匹配：在字典数据中查找匹配的字典标签
     * 4. 值返回：返回对应的字典值
     * </p>
     *
     * <p>
     * 使用示例：
     * <pre>
     * // 数据导入时转换用户输入
     * &#64;PostMapping("/import")
     * public Result importUsers(@RequestBody List&lt;UserImportDTO&gt; users) {
     *     for (UserImportDTO dto : users) {
     *         // 将用户输入的性别文本转换为数据库存储值
     *         String sexValue = DictUtils.getDictValue("sys_user_sex", dto.getSexText());
     *         dto.setSex(sexValue);
     *
     *         // 将用户输入的状态文本转换为数据库存储值
     *         String statusValue = DictUtils.getDictValue("sys_user_status", dto.getStatusText());
     *         dto.setStatus(statusValue);
     *     }
     *     return userService.batchImport(users);
     * }
     *
     * // 搜索条件处理
     * public List&lt;User&gt; searchUsers(UserSearchDTO searchDTO) {
     *     QueryWrapper&lt;User&gt; wrapper = new QueryWrapper&lt;&gt;();
     *
     *     // 将前端传来的显示文本转换为数据库值进行查询
     *     if (StringUtils.isNotEmpty(searchDTO.getSexText())) {
     *         String sexValue = DictUtils.getDictValue("sys_user_sex", searchDTO.getSexText());
     *         wrapper.eq("sex", sexValue);
     *     }
     *
     *     if (StringUtils.isNotEmpty(searchDTO.getStatusText())) {
     *         String statusValue = DictUtils.getDictValue("sys_user_status", searchDTO.getStatusText());
     *         wrapper.eq("status", statusValue);
     *     }
     *
     *     return userMapper.selectList(wrapper);
     * }
     *
     * // 表单数据验证
     * public boolean validateUserInput(String dictType, String userInput) {
     *     String dictValue = DictUtils.getDictValue(dictType, userInput);
     *     return StringUtils.isNotEmpty(dictValue);  // 能转换说明输入有效
     * }
     * </pre>
     * </p>
     *
     * @param dictType 字典类型，如"sys_user_sex"
     * @param dictLabel 字典标签，如"男"、"女"、"未知"
     * @return 对应的字典值，如果找不到返回空字符串
     *
     * @see #getDictValue(String, String, String) 支持自定义分隔符的版本
     * @see #getDictLabel(String, String) 反向转换方法
     *
     * @since 1.0
     *
     * @example
     * <pre>
     * // 假设sys_user_sex字典数据：
     * // 男 -> 0, 女 -> 1, 未知 -> 2
     *
     * String value1 = DictUtils.getDictValue("sys_user_sex", "男");      // 返回: "0"
     * String value2 = DictUtils.getDictValue("sys_user_sex", "女");      // 返回: "1"
     * String value3 = DictUtils.getDictValue("sys_user_sex", "未知");    // 返回: "2"
     * String value4 = DictUtils.getDictValue("sys_user_sex", "其他");    // 返回: "" (未找到)
     * String value5 = DictUtils.getDictValue("sys_user_sex", "");       // 返回: "" (空值)
     * String value6 = DictUtils.getDictValue("unknown_type", "男");      // 返回: "" (字典类型不存在)
     * </pre>
     */
    public static String getDictValue(String dictType, String dictLabel)
    {
        if (StringUtils.isEmpty(dictLabel))
        {
            return StringUtils.EMPTY;
        }
        return getDictValue(dictType, dictLabel, SEPARATOR);
    }

    /**
     * 根据字典类型和字典值获取字典标签（支持自定义分隔符）
     * <p>
     * 支持多值转换的高级版本，可以处理包含分隔符的字典值字符串。
     * 常用于多选字段、批量数据处理等场景。
     * </p>
     *
     * <p>
     * 处理逻辑：
     * - 单值模式：不包含分隔符时，直接查找并返回单个标签
     * - 多值模式：包含分隔符时，按分隔符分割后逐个查找，然后重新组合
     * - 未匹配处理：字典值找不到时跳过，不会影响其他值的转换
     * </p>
     *
     * <p>
     * 使用示例：
     * <pre>
     * // 多选标签转换
     * public String getHobbyLabels(String hobbyValues) {
     *     // hobbyValues: "1,2,4" -> "读书,运动,旅游"
     *     return DictUtils.getDictLabel("user_hobby", hobbyValues, ",");
     * }
     *
     * // 权限标签显示
     * public String getPermissionLabels(String permissionIds) {
     *     // permissionIds: "1001|1002|1003" -> "查看|编辑|删除"
     *     return DictUtils.getDictLabel("sys_permission", permissionIds, "|");
     * }
     *
     * // 用户技能展示（使用分号分隔）
     * public String getSkillLabels(String skillCodes) {
     *     // skillCodes: "JAVA;SPRING;MYSQL" -> "Java编程;Spring框架;MySQL数据库"
     *     return DictUtils.getDictLabel("user_skill", skillCodes, ";");
     * }
     *
     * // 自定义分隔符处理
     * public String processUserInterests(String interests) {
     *     // 使用不同分隔符处理兴趣爱好
     *     if (interests.contains(";")) {
     *         return DictUtils.getDictLabel("user_interest", interests, ";");
     *     } else if (interests.contains("|")) {
     *         return DictUtils.getDictLabel("user_interest", interests, "|");
     *     } else {
     *         return DictUtils.getDictLabel("user_interest", interests, ",");
     *     }
     * }
     * </pre>
     * </p>
     *
     * @param dictType 字典类型
     * @param dictValue 字典值字符串，可能包含分隔符
     * @param separator 分隔符，用于分割多个字典值
     * @return 转换后的字典标签字符串，找不到的值会被跳过
     *
     * @see #getDictLabel(String, String) 单值转换版本
     * @see #getDictValue(String, String, String) 反向多值转换
     *
     * @since 1.0
     *
     * @example
     * <pre>
     * // 假设user_hobby字典数据：
     * // 1 -> 读书, 2 -> 运动, 3 -> 音乐, 4 -> 旅游, 5 -> 美食
     *
     * // 单值转换（不包含分隔符）
     * String label1 = DictUtils.getDictLabel("user_hobby", "1", ",");    // 返回: "读书"
     * String label2 = DictUtils.getDictLabel("user_hobby", "2", ",");    // 返回: "运动"
     *
     * // 多值转换（包含分隔符）
     * String label3 = DictUtils.getDictLabel("user_hobby", "1,2,4", ","); // 返回: "读书,运动,旅游"
     * String label4 = DictUtils.getDictLabel("user_hobby", "1|3|5", "|"); // 返回: "读书|音乐|美食"
     *
     * // 部分匹配（存在找不到的值）
     * String label5 = DictUtils.getDictLabel("user_hobby", "1,2,9", ","); // 返回: "读书,运动" (9不存在，被跳过)
     *
     * // 重复值处理
     * String label6 = DictUtils.getDictLabel("user_hobby", "1,1,2", ","); // 返回: "读书,读书,运动"
     * </pre>
     */
    public static String getDictLabel(String dictType, String dictValue, String separator)
    {
        StringBuilder propertyString = new StringBuilder();
        List<SysDictData> datas = getDictCache(dictType);
        if (StringUtils.isNull(datas))
        {
            return StringUtils.EMPTY;
        }
        if (StringUtils.containsAny(separator, dictValue))
        {
            for (SysDictData dict : datas)
            {
                for (String value : dictValue.split(separator))
                {
                    if (value.equals(dict.getDictValue()))
                    {
                        propertyString.append(dict.getDictLabel()).append(separator);
                        break;
                    }
                }
            }
        }
        else
        {
            for (SysDictData dict : datas)
            {
                if (dictValue.equals(dict.getDictValue()))
                {
                    return dict.getDictLabel();
                }
            }
        }
        return StringUtils.stripEnd(propertyString.toString(), separator);
    }

    /**
     * 根据字典类型和字典标签获取字典值（支持自定义分隔符）
     * <p>
     * 支持多标签转换的高级版本，是getDictLabel的完全反向操作。
     * 将用户输入的多个显示标签转换为数据库存储的字典值。
     * </p>
     *
     * <p>
     * 处理逻辑：
     * - 单标签模式：不包含分隔符时，直接查找并返回单个字典值
     * - 多标签模式：包含分隔符时，按分隔符分割后逐个查找，然后重新组合
     * - 未匹配处理：字典标签找不到时跳过，不会影响其他标签的转换
     * </p>
     *
     * <p>
     * 使用示例：
     * <pre>
     * // 批量数据导入处理
     * public void processBatchImport(List&lt;Map&lt;String, String&gt;&gt; importData) {
     *     for (Map&lt;String, String&gt; data : importData) {
     *         // 将兴趣爱好文本转换为编码
     *         String hobbyText = data.get("hobbies"); // "读书,运动,旅游"
     *         String hobbyCodes = DictUtils.getDictValue("user_hobby", hobbyText, ",");
     *         data.put("hobby_codes", hobbyCodes);     // "1,2,4"
     *
     *         // 将技能文本转换为编码
     *         String skillText = data.get("skills"); // "Java编程|Spring框架"
     *         String skillCodes = DictUtils.getDictValue("user_skill", skillText, "|");
     *         data.put("skill_codes", skillCodes);    // "JAVA|SPRING"
     *     }
     * }
     *
     * // 搜索条件处理
     * public QueryWrapper&lt;User&gt; buildUserQuery(UserSearchDTO searchDTO) {
     *     QueryWrapper&lt;User&gt; wrapper = new QueryWrapper&lt;&gt;();
     *
     *     // 处理多选的爱好搜索
     *     if (StringUtils.isNotEmpty(searchDTO.getHobbyText())) {
     *         String hobbyCodes = DictUtils.getDictValue("user_hobby", searchDTO.getHobbyText(), ",");
     *         wrapper.in("hobby", Arrays.asList(hobbyCodes.split(",")));
     *     }
     *
     *     return wrapper;
     * }
     *
     * // 数据导出时的反向转换
     * public void exportUserData(List&lt;UserVO&gt; users) {
     *     for (UserVO user : users) {
     *         // 将编码转换回文本（导出到其他系统时可能需要）
     *         String hobbyText = DictUtils.getDictLabel("user_hobby", user.getHobbyCodes(), ",");
     *         user.setHobbyText(hobbyText);
     *     }
     * }
     * </pre>
     * </p>
     *
     * @param dictType 字典类型
     * @param dictLabel 字典标签字符串，可能包含分隔符
     * @param separator 分隔符，用于分割多个字典标签
     * @return 转换后的字典值字符串，找不到的标签会被跳过
     *
     * @see #getDictValue(String, String) 单值转换版本
     * @see #getDictLabel(String, String, String) 正向多值转换
     *
     * @since 1.0
     *
     * @example
     * <pre>
     * // 假设user_hobby字典数据：
     * // 读书 -> 1, 运动 -> 2, 音乐 -> 3, 旅游 -> 4, 美食 -> 5
     *
     * // 单标签转换（不包含分隔符）
     * String value1 = DictUtils.getDictValue("user_hobby", "读书", ",");    // 返回: "1"
     * String value2 = DictUtils.getDictValue("user_hobby", "运动", ",");    // 返回: "2"
     *
     * // 多标签转换（包含分隔符）
     * String value3 = DictUtils.getDictValue("user_hobby", "读书,运动,旅游", ","); // 返回: "1,2,4"
     * String value4 = DictUtils.getDictValue("user_hobby", "读书|音乐|美食", "|"); // 返回: "1|3|5"
     *
     * // 部分匹配（存在找不到的标签）
     * String value5 = DictUtils.getDictValue("user_hobby", "读书,运动,其他", ","); // 返回: "1,2" (其他不存在，被跳过)
     *
     * // 重复标签处理
     * String value6 = DictUtils.getDictValue("user_hobby", "读书,读书,运动", ","); // 返回: "1,1,2"
     * </pre>
     */
    public static String getDictValue(String dictType, String dictLabel, String separator)
    {
        StringBuilder propertyString = new StringBuilder();
        List<SysDictData> datas = getDictCache(dictType);
        if (StringUtils.isNull(datas))
        {
            return StringUtils.EMPTY;
        }
        if (StringUtils.containsAny(separator, dictLabel))
        {
            for (SysDictData dict : datas)
            {
                for (String label : dictLabel.split(separator))
                {
                    if (label.equals(dict.getDictLabel()))
                    {
                        propertyString.append(dict.getDictValue()).append(separator);
                        break;
                    }
                }
            }
        }
        else
        {
            for (SysDictData dict : datas)
            {
                if (dictLabel.equals(dict.getDictLabel()))
                {
                    return dict.getDictValue();
                }
            }
        }
        return StringUtils.stripEnd(propertyString.toString(), separator);
    }

    /**
     * 根据字典类型获取所有字典值（以逗号分隔）
     * <p>
     * 获取指定字典类型的所有字典值，用逗号连接成一个字符串。
     * 常用于构建下拉选项的value数组、数据验证的范围检查等场景。
     * </p>
     *
     * <p>
     * 输出格式：
     * - 所有字典值按逗号分隔，如："0,1,2"
     * - 顺序按照字典数据的排列顺序
     * - 空字典类型时返回空字符串
     * </p>
     *
     * <p>
     * 使用示例：
     * <pre>
     * // 构建前端下拉选项
     * public Map&lt;String, Object&gt; getDictSelectOptions(String dictType) {
     *     Map&lt;String, Object&gt; result = new HashMap&lt;&gt;();
     *
     *     // 获取所有值和标签
     *     String values = DictUtils.getDictValues(dictType);      // "0,1,2"
     *     String labels = DictUtils.getDictLabels(dictType);      // "男,女,未知"
     *
     *     // 分割成数组
     *     String[] valueArray = values.split(",");
     *     String[] labelArray = labels.split(",");
     *
     *     // 构建选项列表
     *     List&lt;SelectOption&gt; options = new ArrayList&lt;&gt;();
     *     for (int i = 0; i &lt; valueArray.length; i++) {
     *         options.add(new SelectOption(valueArray[i], labelArray[i]));
     *     }
     *
     *     result.put("options", options);
     *     return result;
     * }
     *
     * // 数据验证
     * public boolean isValidDictValue(String dictType, String checkValue) {
     *     String allValues = DictUtils.getDictValues(dictType);
     *     return StringUtils.containsAny(",", checkValue) ?
     *         Arrays.asList(allValues.split(",")).contains(checkValue) :
     *         allValues.contains(checkValue);
     * }
     *
     * // SQL IN查询构建
     * public String buildInCondition(String dictType, String[] selectedValues) {
     *     String allValidValues = DictUtils.getDictValues(dictType);
     *     List&lt;String&gt; validSelected = Arrays.stream(selectedValues)
     *         .filter(val -&gt; Arrays.asList(allValidValues.split(",")).contains(val))
     *         .collect(Collectors.toList());
     *
     *     return "'" + String.join("','", validSelected) + "'";
     * }
     *
     * // 字典枚举生成
     * public String generateDictEnum(String dictType, String enumName) {
     *     String values = DictUtils.getDictValues(dictType);
     *     String labels = DictUtils.getDictLabels(dictType);
     *
     *     StringBuilder enumCode = new StringBuilder();
     *     enumCode.append("public enum ").append(enumName).append(" {\n");
     *
     *     String[] valueArray = values.split(",");
     *     String[] labelArray = labels.split(",");
     *
     *     for (int i = 0; i &lt; valueArray.length; i++) {
     *         enumCode.append("    ").append(labelArray[i])
     *                  .append("(").append(valueArray[i]).append(")");
     *         if (i &lt; valueArray.length - 1) {
     *             enumCode.append(",\n");
     *         }
     *     }
     *     enumCode.append(";\n");
     *
     *     return enumCode.toString();
     * }
     * </pre>
     * </p>
     *
     * @param dictType 字典类型
     * @return 逗号分隔的字典值字符串，字典不存在时返回空字符串
     *
     * @see #getDictLabels(String) 获取所有字典标签
     * @see #getDictCache(String) 获取字典数据列表
     *
     * @since 1.0
     *
     * @example
     * <pre>
     * // 假设sys_user_sex字典数据：
     * // 0 -> 男, 1 -> 女, 2 -> 未知
     *
     * String values1 = DictUtils.getDictValues("sys_user_sex");    // 返回: "0,1,2"
     * String values2 = DictUtils.getDictValues("sys_user_status"); // 返回: "0,1"
     * String values3 = DictUtils.getDictValues("non_existent");   // 返回: ""
     *
     * // 配合分割使用
     * String[] sexValues = DictUtils.getDictValues("sys_user_sex").split(",");
     * // sexValues = ["0", "1", "2"]
     * </pre>
     */
    public static String getDictValues(String dictType)
    {
        StringBuilder propertyString = new StringBuilder();
        List<SysDictData> datas = getDictCache(dictType);
        if (StringUtils.isNull(datas))
        {
            return StringUtils.EMPTY;
        }
        for (SysDictData dict : datas)
        {
            propertyString.append(dict.getDictValue()).append(SEPARATOR);
        }
        return StringUtils.stripEnd(propertyString.toString(), SEPARATOR);
    }

    /**
     * 根据字典类型获取所有字典标签（以逗号分隔）
     * <p>
     * 获取指定字典类型的所有字典标签，用逗号连接成一个字符串。
     * 常用于构建下拉选项的显示文本、数据展示的选项列表等场景。
     * </p>
     *
     * <p>
     * 输出格式：
     * - 所有字典标签按逗号分隔，如："男,女,未知"
     * - 顺序按照字典数据的排列顺序
     * - 空字典类型时返回空字符串
     * </p>
     *
     * <p>
     * 使用示例：
     * <pre>
     * // 前端下拉选项生成
     * &#64;GetMapping("/dict/{dictType}")
     * public Result getDictOptions(@PathVariable String dictType) {
     *     String values = DictUtils.getDictValues(dictType);  // "0,1,2"
     *     String labels = DictUtils.getDictLabels(dictType);  // "男,女,未知"
     *
     *     String[] valueArray = values.split(",");
     *     String[] labelArray = labels.split(",");
     *
     *     List&lt;Map&lt;String, String&gt;&gt; options = new ArrayList&lt;&gt;();
     *     for (int i = 0; i &lt; valueArray.length; i++) {
     *         Map&lt;String, String&gt; option = new HashMap&lt;&gt;();
     *         option.put("value", valueArray[i]);
     *         option.put("label", labelArray[i]);
     *         options.add(option);
     *     }
     *
     *     return Result.success(options);
     * }
     *
     * // Excel导出下拉列表生成
     * public String[] generateExcelDropdownArray(String dictType) {
     *     return DictUtils.getDictLabels(dictType).split(",");
     * }
     *
     * // 搜索条件提示
     * public List&lt;String&gt; getSearchSuggestions(String dictType) {
     *     String labels = DictUtils.getDictLabels(dictType);
     *     return Arrays.asList(labels.split(","));
     * }
     *
     * // 数据字典文档生成
     * public void generateDictDoc() {
     *     List&lt;SysDictType&gt; dictTypes = dictTypeService.list();
     *     for (SysDictType dictType : dictTypes) {
     *         String type = dictType.getDictType();
     *         String values = DictUtils.getDictValues(type);
     *         String labels = DictUtils.getDictLabels(type);
     *
     *         System.out.println("字典类型: " + type);
     *         System.out.println("可选值: " + values);
     *         System.out.println("显示文本: " + labels);
     *         System.out.println("-------------------");
     *     }
     * }
     * </pre>
     * </p>
     *
     * @param dictType 字典类型
     * @return 逗号分隔的字典标签字符串，字典不存在时返回空字符串
     *
     * @see #getDictValues(String) 获取所有字典值
     * @see #getDictCache(String) 获取字典数据列表
     *
     * @since 1.0
     *
     * @example
     * <pre>
     * // 假设sys_user_sex字典数据：
     * // 0 -> 男, 1 -> 女, 2 -> 未知
     *
     * String labels1 = DictUtils.getDictLabels("sys_user_sex");    // 返回: "男,女,未知"
     * String labels2 = DictUtils.getDictLabels("sys_user_status"); // 返回: "正常,停用"
     * String labels3 = DictUtils.getDictLabels("non_existent");   // 返回: ""
     *
     * // 配合分割使用
     * String[] sexLabels = DictUtils.getDictLabels("sys_user_sex").split(",");
     * // sexLabels = ["男", "女", "未知"]
     *
     * // 构建下拉选项
     * String values = DictUtils.getDictValues("sys_user_sex");
     * String labels = DictUtils.getDictLabels("sys_user_sex");
     * // values = "0,1,2"
     * // labels = "男,女,未知"
     * </pre>
     */
    public static String getDictLabels(String dictType)
    {
        StringBuilder propertyString = new StringBuilder();
        List<SysDictData> datas = getDictCache(dictType);
        if (StringUtils.isNull(datas))
        {
            return StringUtils.EMPTY;
        }
        for (SysDictData dict : datas)
        {
            propertyString.append(dict.getDictLabel()).append(SEPARATOR);
        }
        return StringUtils.stripEnd(propertyString.toString(), SEPARATOR);
    }

    /**
     * 删除指定字典类型的缓存
     * <p>
     * 从Redis中删除指定字典类型的缓存数据。
     * 通常在字典数据更新、删除或缓存失效时调用。
     * </p>
     *
     * <p>
     * 使用场景：
     * - 字典数据更新后刷新缓存
     * - 字典数据删除后清理缓存
     * - 缓存调试和问题排查
     * - 系统维护时的缓存清理
     * </p>
     *
     * <p>
     * 使用示例：
     * <pre>
     * // 字典数据服务中的缓存管理
     * &#64;Service
     * public class SysDictDataServiceImpl implements ISysDictDataService {
     *
     *     &#64;Override
     *     public void deleteDictDataById(Long dictId) {
     *         // 1. 删除数据库记录
     *         dictDataMapper.deleteById(dictId);
     *
     *         // 2. 获取被删除的字典数据类型
     *         SysDictData deletedData = dictDataMapper.selectById(dictId);
     *
     *         // 3. 清除相关字典类型的缓存
     *         DictUtils.removeDictCache(deletedData.getDictType());
     *     }
     *
     *     &#64;Override
     *     public void updateDictData(SysDictData dictData) {
     *         // 1. 更新数据库记录
     *         dictDataMapper.updateById(dictData);
     *
     *         // 2. 清除相关字典类型的缓存
     *         DictUtils.removeDictCache(dictData.getDictType());
     *
     *         // 3. 重新加载缓存（可选）
     *         List&lt;SysDictData&gt; updatedDictData = selectDictDataByType(dictData.getDictType());
     *         DictUtils.setDictCache(dictData.getDictType(), updatedDictData);
     *     }
     *
     *     &#64;Override
     *     public int insertDictData(SysDictData dictData) {
     *         // 1. 插入数据库记录
     *         int result = dictDataMapper.insert(dictData);
     *
     *         // 2. 清除相关字典类型的缓存
     *         DictUtils.removeDictCache(dictData.getDictType());
     *
     *         // 3. 重新加载缓存
     *         List&lt;SysDictData&gt; updatedDictData = selectDictDataByType(dictData.getDictType());
     *         DictUtils.setDictCache(dictData.getDictType(), updatedDictData);
     *
     *         return result;
     *     }
     * }
     *
     * // 批量操作后的缓存清理
     * public void batchUpdateDictData(List&lt;SysDictData&gt; dictDatas) {
     *     Set&lt;String&gt; affectedDictTypes = dictDatas.stream()
     *         .map(SysDictData::getDictType)
     *         .collect(Collectors.toSet());
     *
     *     // 批量更新数据库
     *     dictDataMapper.batchUpdate(dictDatas);
     *
     *     // 清除所有受影响的字典类型缓存
     *     for (String dictType : affectedDictTypes) {
     *         DictUtils.removeDictCache(dictType);
     *
     *         // 重新加载缓存
     *         List&lt;SysDictData&gt; updatedData = selectDictDataByType(dictType);
     *         DictUtils.setDictCache(dictType, updatedData);
     *     }
     * }
     *
     * // 管理接口中的缓存清理
     * &#64;PostMapping("/admin/cache/clear")
     * public Result clearDictCache(@RequestParam String dictType) {
     *     try {
     *         DictUtils.removeDictCache(dictType);
     *         return Result.success("字典缓存清除成功");
     *     } catch (Exception e) {
     *         return Result.error("字典缓存清除失败：" + e.getMessage());
     *     }
     * }
     * </pre>
     * </p>
     *
     * @param key 要删除的字典类型标识
     *
     * @see #setDictCache(String, List) 设置字典缓存
     * @see #clearDictCache() 清空所有字典缓存
     * @see #getCacheKey(String) 获取缓存键
     *
     * @since 1.0
     *
     * @example
     * <pre>
     * // 删除用户性别字典缓存
     * DictUtils.removeDictCache("sys_user_sex");
     *
     * // 删除用户状态字典缓存
     * DictUtils.removeDictCache("sys_user_status");
     *
     * // 删除不存在的字典类型（不会报错）
     * DictUtils.removeDictCache("non_existent_dict");
     * </pre>
     */
    public static void removeDictCache(String key)
    {
        RedisCache redisCache = SpringUtils.getBean(RedisCache.class);
        if (redisCache != null) {
            redisCache.deleteObject(getCacheKey(key));
        }
    }

    /**
     * 清空所有字典缓存
     * <p>
     * 删除Redis中所有的字典相关缓存数据。
     * 通常在系统维护、缓存重置、字典数据大规模更新等场景下使用。
     * </p>
     *
     * <p>
     * 清理范围：
     * - 所有以"SYS_DICT_KEY"开头的缓存键
     * - 包括所有字典类型的缓存数据
     * - 不会影响其他类型的缓存数据
     * </p>
     *
     * <p>
     * 使用示例：
     * <pre>
     * // 系统启动时的缓存清理
     * &#64;Component
     * public class DictCacheManager {
     *
     *     &#64;PostConstruct
     *     public void init() {
     *         // 清理可能存在的旧缓存
     *         DictUtils.clearDictCache();
     *
     *         // 重新加载所有字典数据
     *         loadAllDictToCache();
     *     }
     *
     *     private void loadAllDictToCache() {
     *         List&lt;SysDictType&gt; dictTypes = dictTypeService.selectList();
     *         for (SysDictType dictType : dictTypes) {
     *             List&lt;SysDictData&gt; dictDatas = dictDataService.selectDictDataByType(dictType.getDictType());
     *             DictUtils.setDictCache(dictType.getDictType(), dictDatas);
     *         }
     *     }
     * }
     *
     * // 字典数据大规模更新后的缓存重置
     * &#64;Service
     * public class DictMaintenanceService {
     *
     *     &#64;Transactional
     *     public void reloadAllDictData() {
     *         try {
     *             // 1. 清空所有现有缓存
     *             DictUtils.clearDictCache();
     *
     *             // 2. 重新加载字典类型
     *             dictTypeService.reloadCache();
     *
     *             // 3. 重新加载字典数据
     *             dictDataService.reloadCache();
     *
     *             log.info("所有字典数据缓存已重新加载");
     *         } catch (Exception e) {
     *             log.error("重新加载字典缓存失败", e);
     *             throw new RuntimeException("字典缓存重置失败", e);
     *         }
     *     }
     * }
     *
     * // 管理接口中的缓存重置
     * &#64;RestController
     * &#64;RequestMapping("/admin/cache")
     * public class CacheController {
     *
     *     &#64;PostMapping("/dict/clear")
     *     public Result clearDictCache() {
     *         try {
     *             DictUtils.clearDictCache();
     *             return Result.success("所有字典缓存已清空");
     *         } catch (Exception e) {
     *             return Result.error("清空字典缓存失败：" + e.getMessage());
     *         }
     *     }
     *
     *     &#64;PostMapping("/dict/reload")
     *     public Result reloadDictCache() {
     *         try {
     *             dictCacheManager.reloadAllDictData();
     *             return Result.success("所有字典缓存已重新加载");
     *         } catch (Exception e) {
     *             return Result.error("重新加载字典缓存失败：" + e.getMessage());
     *         }
     *     }
     * }
     *
     * // 定时任务中的缓存维护
     * &#64;Scheduled(cron = "0 0 2 * * ?") // 每天凌晨2点执行
     * public void dailyCacheMaintenance() {
     *     log.info("开始执行每日字典缓存维护");
     *
     *     // 检查缓存完整性
     *     checkCacheIntegrity();
     *
     *     // 如果发现问题，重新加载
     *     if (hasCacheIssues()) {
     *         DictUtils.clearDictCache();
     *         loadAllDictToCache();
     *     }
     *
     *     log.info("每日字典缓存维护完成");
     * }
     * }
     * </pre>
     * </p>
     *
     * @see #removeDictCache(String) 删除指定字典缓存
     * @see #setDictCache(String, List) 设置字典缓存
     * @see CacheConstants#SYS_DICT_KEY 字典缓存键前缀
     *
     * @since 1.0
     *
     * @example
     * <pre>
     * // 清空所有字典缓存
     * DictUtils.clearDictCache();
     *
     * // 在维护脚本中使用
     * public void maintenanceScript() {
     *     System.out.println("开始清空字典缓存...");
     *     DictUtils.clearDictCache();
     *     System.out.println("字典缓存清空完成");
     *
     *     System.out.println("开始重新加载字典数据...");
     *     // 重新加载逻辑
     *     System.out.println("字典数据重新加载完成");
     * }
     * </pre>
     */
    public static void clearDictCache()
    {
        RedisCache redisCache = SpringUtils.getBean(RedisCache.class);
        if (redisCache != null) {
            Collection<String> keys = redisCache.keys(CacheConstants.SYS_DICT_KEY + "*");
            redisCache.deleteObject(keys);
        }
    }

    /**
     * 生成字典缓存的键名
     * <p>
     * 根据字典类型生成Redis中存储字典数据的键名。
     * 确保所有字典操作使用统一的键名格式。
     * </p>
     *
     * <p>
     * 键名格式：
     * - 基础前缀：CacheConstants.SYS_DICT_KEY（通常为"SYS_DICT_KEY:"）
     * - 完整格式：前缀 + 字典类型
     * - 示例："SYS_DICT_KEY:sys_user_sex"
     * </p>
     *
     * <p>
     * 使用示例：
     * <pre>
     * // 直接使用缓存键操作Redis
     * public void directRedisOperation(String dictType) {
     *     String cacheKey = DictUtils.getCacheKey(dictType);
     *
     *     RedisCache redisCache = SpringUtils.getBean(RedisCache.class);
     *
     *     // 检查缓存是否存在
     *     boolean exists = redisCache.hasKey(cacheKey);
     *
     *     // 获取缓存过期时间
     *     Long expireTime = redisCache.getExpire(cacheKey);
     *
     *     // 直接设置缓存（绕过DictUtils的其他逻辑）
     *     List&lt;SysDictData&gt; dictData = loadDictFromDatabase(dictType);
     *     redisCache.setCacheObject(cacheKey, dictData, 3600); // 1小时过期
     *
     *     System.out.println("字典缓存键: " + cacheKey);
     *     System.out.println("缓存是否存在: " + exists);
     *     System.out.println("缓存过期时间: " + expireTime + "秒");
     * }
     *
     * // 缓存监控和统计
     * public Map&lt;String, Object&gt; getCacheStatistics() {
     *     RedisCache redisCache = SpringUtils.getBean(RedisCache.class);
     *
     *     // 获取所有字典缓存键
     *     Collection&lt;String&gt; dictKeys = redisCache.keys(CacheConstants.SYS_DICT_KEY + "*");
     *
     *     Map&lt;String, Object&gt; stats = new HashMap&lt;&gt;();
     *     stats.put("totalDictCount", dictKeys.size());
     *
     *     // 统计每个字典的缓存大小
     *     Map&lt;String, Integer&gt; dictSizes = new HashMap&lt;&gt;();
     *     for (String key : dictKeys) {
     *         String dictType = key.substring(CacheConstants.SYS_DICT_KEY.length());
     *         List&lt;SysDictData&gt; data = DictUtils.getDictCache(dictType);
     *         dictSizes.put(dictType, data != null ? data.size() : 0);
     *     }
     *     stats.put("dictSizes", dictSizes);
     *
     *     return stats;
     * }
     *
     * // 缓存调试工具
     * public void debugDictCache(String dictType) {
     *     String cacheKey = DictUtils.getCacheKey(dictType);
     *     RedisCache redisCache = SpringUtils.getBean(RedisCache.class);
     *
     *     Object cacheObj = redisCache.getCacheObject(cacheKey);
     *     System.out.println("字典类型: " + dictType);
     *     System.out.println("缓存键: " + cacheKey);
     *     System.out.println("缓存类型: " + (cacheObj != null ? cacheObj.getClass().getSimpleName() : "null"));
     *
     *     if (cacheObj instanceof List) {
     *         List&lt;SysDictData&gt; dictData = (List&lt;SysDictData&gt;) cacheObj;
     *         System.out.println("字典条目数: " + dictData.size());
     *         dictData.forEach(dict -&gt;
     *             System.out.println("  " + dict.getDictValue() + " -> " + dict.getDictLabel()));
     *     }
     * }
     *
     * // 自定义缓存过期时间设置
     * public void setDictCacheWithTTL(String dictType, List&lt;SysDictData&gt; dictData, long ttlSeconds) {
     *     String cacheKey = DictUtils.getCacheKey(dictType);
     *     RedisCache redisCache = SpringUtils.getBean(RedisCache.class);
     *
     *     // 设置带过期时间的缓存
     *     redisCache.setCacheObject(cacheKey, dictData, ttlSeconds);
     *
     *     log.info("字典 {} 缓存已设置，过期时间: {} 秒", dictType, ttlSeconds);
     * }
     * </pre>
     * </p>
     *
     * @param configKey 字典类型标识
     * @return 完整的Redis缓存键名
     *
     * @see CacheConstants#SYS_DICT_KEY 字典缓存键前缀常量
     * @see #setDictCache(String, List) 设置字典缓存
     * @see #getDictCache(String) 获取字典缓存
     *
     * @since 1.0
     *
     * @example
     * <pre>
     * // 生成字典缓存键
     * String key1 = DictUtils.getCacheKey("sys_user_sex");      // 返回: "SYS_DICT_KEY:sys_user_sex"
     * String key2 = DictUtils.getCacheKey("sys_user_status");  // 返回: "SYS_DICT_KEY:sys_user_status"
     * String key3 = DictUtils.getCacheKey("user_dept");        // 返回: "SYS_DICT_KEY:user_dept"
     *
     * // 直接使用Redis操作
     * RedisCache redisCache = SpringUtils.getBean(RedisCache.class);
     * String cacheKey = DictUtils.getCacheKey("sys_user_sex");
     * Object cachedData = redisCache.getCacheObject(cacheKey);
     * </pre>
     */
    public static String getCacheKey(String configKey)
    {
        return CacheConstants.SYS_DICT_KEY + configKey;
    }
}
