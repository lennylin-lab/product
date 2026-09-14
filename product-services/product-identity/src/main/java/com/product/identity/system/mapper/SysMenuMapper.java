package com.product.identity.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.product.identity.domain.entity.SysMenu;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * @Auther: chuan
 * @Date: 2025/12/19 - 12 - 19 - 17:16
 * @Description: com.product.mapper
 * @version: 1.0
 */
@Mapper
public interface SysMenuMapper extends BaseMapper<SysMenu> {
    List<SysMenu> selectMenuTreeByUserId(Long userId);

    List<SysMenu> selectMenuListByUserId(SysMenu menu);

    /**
     * 根据角色ID查询菜单树信息
     *
     * @param roleId 角色ID
     * @param menuCheckStrictly 菜单树选择项是否关联显示
     * @return 选中菜单列表
     */
    public List<Long> selectMenuListByRoleId(@Param("roleId") Long roleId, @Param("menuCheckStrictly") boolean menuCheckStrictly);
}
