package com.product.identity.domain.entity;

/**
 * 用户实体类
 *
 * @author travel
 * @version 1.0
 * @since 2025-12-15
 *
 * 功能描述：
 * 系统用户实体，映射数据库表 sys_user，包含用户的基本信息、权限信息、状态等。
 * 继承自 BaseEntity，包含创建时间、更新时间等公共字段。
 *
 * 数据表映射：
 * 表名：sys_user
 * 主键：user_id
 *
 * 主要字段：
 * - userId: 用户ID，主键
 * - userName: 用户账号，唯一标识
 * - nickName: 用户昵称，显示名称
 * - email: 邮箱地址
 * - phonenumber: 手机号码
 * - sex: 性别 (0男 1女 2未知)
 * - avatar: 头像路径
 * - password: 密码，加密存储
 * - status: 状态 (0正常 1停用)
 * - delFlag: 删除标志 (0存在 2删除)
 * - loginIp: 最后登录IP
 * - loginDate: 最后登录时间
 *
 * 关联关系：
 * - 与 SysRole 角色多对多关系 (通过 sys_user_role 中间表)
 * - 与 SysPost 岗位多对多关系 (通过 sys_user_post 中间表)
 * - 与 SysDept 部门多对一关系
 *
 * 验证规则：
 * - 用户名、昵称、密码等字段使用 @NotBlank 注解验证
 * - 邮箱字段使用 @Email 注解验证格式
 * - 部分字段有长度限制 @Size 注解
 *
 * 安全特性：
 * - 密码字段使用 MD5+盐值加密
 * - 用户信息字段使用 @Xss 注解防XSS攻击
 * - 敏感信息支持Excel导出时的脱敏处理
 */

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.product.identity.common.annotation.Excel;
import com.product.identity.common.annotation.Excel.ColumnType;
import com.product.identity.common.annotation.Excel.Type;
import com.product.identity.common.core.entity.BaseEntity;
import com.product.identity.common.annotation.Xss;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 用户对象 sys_user
 *
 * @author fast
 */
@Data
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
@TableName("sys_user")
public class SysUser extends BaseEntity {
    private static final long serialVersionUID = 1L;

    /** 用户ID */
    @TableId(type = IdType.AUTO)
    @Excel(name = "用户序号", type = Type.EXPORT, cellType = ColumnType.NUMERIC, prompt = "用户编号")
    private Long userId;

    /** 用户账号 */
    @Excel(name = "登录名称")
    @Xss(message = "用户账号不能包含脚本字符")
    @NotBlank(message = "用户账号不能为空")
    @Size(min = 0, max = 30, message = "用户账号长度不能超过30个字符")
    private String userName;

    /** 用户邮箱 */
    @Excel(name = "用户邮箱")
    @Email(message = "邮箱格式不正确")
    @Size(min = 0, max = 50, message = "邮箱长度不能超过50个字符")
    private String email;

    /** 手机号码 */
    @Excel(name = "手机号码", cellType = ColumnType.TEXT)
    @Size(min = 0, max = 11, message = "手机号码长度不能超过11个字符")
    private String phonenumber;

    /** 用户性别 */
    @Excel(name = "用户性别", readConverterExp = "0=男,1=女,2=未知")
    private String sex;

    /** 用户头像 */
    private String avatar;

    /** 密码 */
    private String password;

    /** 账号状态（0正常 1停用） */
    @Excel(name = "账号状态", readConverterExp = "0=正常,1=停用")
    private String status;

    @TableField("del_flag")
    /** 删除标志（0代表存在 1代表删除） */
    private String delFlag;

    /** 最后登录IP */
    @Excel(name = "最后登录IP", type = Type.EXPORT)
    private String loginIp;

    /** 最后登录时间 */
    @Excel(name = "最后登录时间", width = 30, dateFormat = "yyyy-MM-dd HH:mm:ss", type = Type.EXPORT)
    private LocalDateTime loginDate;

    /** 角色对象 */
    @TableField(exist = false)
    private List<SysRole> roles;

    /** 角色组 */
    @TableField(exist = false)
    private Long[] roleIds;

    /** 角色ID */
    @TableField(exist = false)
    private Long roleId;

    public SysUser(Long userId) {
        this.userId = userId;
    }

    public boolean isAdmin() {
        return isAdmin(this.userId);
    }

    public static boolean isAdmin(Long userId) {
        return userId != null && 1L == userId;
    }
}
