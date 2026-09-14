package com.product.identity.system.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.product.identity.core.domain.TreeSelect;
import com.product.identity.domain.entity.SysMenu;
import com.product.identity.domain.vo.RouterVo;

import java.util.List;

/**
 * @Auther: chuan
 * @Date: 2025/12/19 - 12 - 19 - 17:17
 * @Description: com.product.service
 * @version: 1.0
 */
public interface ISysMenuService extends IService<SysMenu> {
    List<SysMenu> selectMenuTreeByUserId(Long userId);

    List<RouterVo> buildMenus(List<SysMenu> menus);

    List<SysMenu> selectMenuList(SysMenu menu, Long userId);

    List<SysMenu> selectMenuList(Long userId);

    Object selectMenuListByRoleId(Long roleId);

    boolean checkMenuNameUnique(SysMenu menu);

    boolean hasChildByMenuId(Long menuId);

    boolean checkMenuExistRole(Long menuId);

    List<TreeSelect> buildMenuTreeSelect(List<SysMenu> menus);
}
