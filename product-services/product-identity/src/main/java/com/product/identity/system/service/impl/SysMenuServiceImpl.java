package com.product.identity.system.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.baomidou.mybatisplus.extension.toolkit.Db;
import com.product.identity.common.constant.Constants;
import com.product.identity.common.constant.UserConstants;
import com.product.identity.core.domain.TreeSelect;
import com.product.identity.domain.entity.SysMenu;
import com.product.identity.domain.entity.SysRole;
import com.product.identity.domain.entity.SysRoleMenu;
import com.product.identity.domain.entity.SysUser;
import com.product.identity.system.mapper.SysMenuMapper;
import com.product.identity.system.mapper.SysRoleMapper;
import com.product.identity.system.service.ISysMenuService;
import com.product.identity.core.utils.SecurityUtils;
import com.product.identity.common.utils.StringUtils;
import com.product.identity.domain.vo.MetaVo;
import com.product.identity.domain.vo.RouterVo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * @Auther: chuan
 * @Date: 2025/12/19 - 12 - 19 - 17:19
 * @Description: com.product.service.impl
 * @version: 1.0
 */
@Slf4j
@Service
public class SysMenuServiceImpl extends ServiceImpl<SysMenuMapper, SysMenu> implements ISysMenuService {
    @Autowired
    private SysMenuMapper menuMapper;

    @Autowired
    private SysRoleMapper roleMapper;

    @Override
    public List<SysMenu> selectMenuTreeByUserId(Long userId) {
        List<SysMenu> menus = new ArrayList<>();
        if (SecurityUtils.isAdmin(userId)) {
            // 使用 QueryWrapper 而不是 LambdaQueryWrapper
            QueryWrapper<SysMenu> wrapper = new QueryWrapper<>();

            // 1. 设定 Select 部分 (手动写 SQL 片段以支持 ifnull 和 distinct)
            wrapper.select("distinct menu_id, parent_id, menu_name, path, component, `query`" +
                    ", route_name, " + "visible, status, ifnull(perms,'') as perms, is_frame, is_cache," +
                    " menu_type, " +
                    "icon, order_num, create_time");

            // 2. 切换为 Lambda 模式来写条件 (保持类型安全)
            wrapper.lambda()
                    .in(SysMenu::getMenuType, "M", "C")
                    .eq(SysMenu::getStatus, 0)
                    .orderByAsc(SysMenu::getParentId, SysMenu::getOrderNum);
            menus = list(wrapper);
        } else {
            menus = menuMapper.selectMenuTreeByUserId(userId);
        }
        return getChildPerms(menus, 0);
    }

    /**
     * 构建前端路由所需要的菜单
     *
     * @param menus 菜单列表
     * @return 路由列表
     */
    @Override
    public List<RouterVo> buildMenus(List<SysMenu> menus)
    {
        List<RouterVo> routers = new LinkedList<RouterVo>();
        for (SysMenu menu : menus)
        {
            RouterVo router = new RouterVo();
            router.setHidden("1".equals(menu.getVisible()));
            router.setName(getRouteName(menu));
            router.setPath(getRouterPath(menu));
            router.setComponent(getComponent(menu));
            router.setQuery(menu.getQuery());
            router.setMeta(new MetaVo(menu.getMenuName(), menu.getIcon(), 1 == menu.getIsCache(), menu.getPath()));
            List<SysMenu> cMenus = menu.getChildren();
            if (StringUtils.isNotEmpty(cMenus) && UserConstants.TYPE_DIR.equals(menu.getMenuType()))
            {
                router.setAlwaysShow(true);
                router.setRedirect("noRedirect");
                router.setChildren(buildMenus(cMenus));
            }
            else if (isMenuFrame(menu))
            {
                router.setMeta(null);
                List<RouterVo> childrenList = new ArrayList<RouterVo>();
                RouterVo children = new RouterVo();
                children.setPath(menu.getPath());
                children.setComponent(menu.getComponent());
                children.setName(getRouteName(menu.getRouteName(), menu.getPath()));
                children.setMeta(new MetaVo(menu.getMenuName(), menu.getIcon(), 1 == menu.getIsCache(), menu.getPath()));
                children.setQuery(menu.getQuery());
                childrenList.add(children);
                router.setChildren(childrenList);
            }
            else if (menu.getParentId().intValue() == 0 && isInnerLink(menu))
            {
                router.setMeta(new MetaVo(menu.getMenuName(), menu.getIcon()));
                router.setPath("/");
                List<RouterVo> childrenList = new ArrayList<RouterVo>();
                RouterVo children = new RouterVo();
                String routerPath = innerLinkReplaceEach(menu.getPath());
                children.setPath(routerPath);
                children.setComponent(UserConstants.INNER_LINK);
                children.setName(getRouteName(menu.getRouteName(), routerPath));
                children.setMeta(new MetaVo(menu.getMenuName(), menu.getIcon(), menu.getPath()));
                childrenList.add(children);
                router.setChildren(childrenList);
            }
            routers.add(router);
        }
        return routers;
    }

    @Override
    public List<SysMenu> selectMenuList(SysMenu menu, Long userId) {
        List<SysMenu> menuList = null;
        // 管理员显示所有菜单信息
        if (SysUser.isAdmin(userId))
        {
            log.info("是管理员");
            LambdaQueryWrapper<SysMenu> wrapper = baseSelectWrapper().like(StringUtils.hasText(menu.getMenuName()), SysMenu::getMenuName, menu.getMenuName())
                    .eq(StringUtils.hasText(menu.getVisible()), SysMenu::getVisible, menu.getVisible())
                    .eq(StringUtils.hasText(menu.getStatus()), SysMenu::getStatus, menu.getStatus());
            menuList = list(wrapper);
        }
        else
        {
            menu.getParams().put("userId", userId);
            menuList = menuMapper.selectMenuListByUserId(menu);
        }
        return menuList;
    }

    @Override
    public List<SysMenu> selectMenuList(Long userId) {
        return selectMenuList(new SysMenu(), userId);
    }

    @Override
    public Object selectMenuListByRoleId(Long roleId) {
        SysRole role = roleMapper.selectRoleById(roleId);
        return menuMapper.selectMenuListByRoleId(roleId, role.isMenuCheckStrictly());
    }

    @Override
    public boolean checkMenuNameUnique(SysMenu menu) {
        Long menuId = StringUtils.isNull(menu.getMenuId()) ? -1L : menu.getMenuId();
        SysMenu info = getOne(
                                baseSelectWrapper().eq(SysMenu::getMenuName, menu.getMenuName())
                                        .eq(SysMenu::getParentId, menu.getParentId())
                                        .last("limit 1")
        );
        if (StringUtils.isNotNull(info) && info.getMenuId().longValue() != menuId.longValue())
        {
            return UserConstants.NOT_UNIQUE;
        }
        return UserConstants.UNIQUE;
    }

    @Override
    public boolean hasChildByMenuId(Long menuId) {
        return lambdaQuery().eq(SysMenu::getParentId, menuId).count() > 0;
    }

    @Override
    public boolean checkMenuExistRole(Long menuId) {
        return Db.lambdaQuery(SysRoleMenu.class).eq(SysRoleMenu::getMenuId, menuId).count() > 0;
    }

    @Override
    public List<TreeSelect> buildMenuTreeSelect(List<SysMenu> menus) {
        List<SysMenu> menuTrees = buildMenuTree(menus);
        return menuTrees.stream().map(TreeSelect::new).collect(Collectors.toList());
    }

    public List<SysMenu> buildMenuTree(List<SysMenu> menus)
    {
        List<SysMenu> returnList = new ArrayList<SysMenu>();
        List<Long> tempList = menus.stream().map(SysMenu::getMenuId).collect(Collectors.toList());
        for (Iterator<SysMenu> iterator = menus.iterator(); iterator.hasNext();)
        {
            SysMenu menu = (SysMenu) iterator.next();
            // 如果是顶级节点, 遍历该父节点的所有子节点
            if (!tempList.contains(menu.getParentId()))
            {
                recursionFn(menus, menu);
                returnList.add(menu);
            }
        }
        if (returnList.isEmpty())
        {
            returnList = menus;
        }
        return returnList;
    }

    /**
     * 根据父节点的ID获取所有子节点
     *
     * @param list 分类表
     * @param parentId 传入的父节点ID
     * @return String
     */
    public List<SysMenu> getChildPerms(List<SysMenu> list, int parentId)
    {
        List<SysMenu> returnList = new ArrayList<SysMenu>();
        for (Iterator<SysMenu> iterator = list.iterator(); iterator.hasNext();)
        {
            SysMenu t = (SysMenu) iterator.next();
            // 一、根据传入的某个父节点ID,遍历该父节点的所有子节点
            if (t.getParentId() == parentId)
            {
                recursionFn(list, t);
                returnList.add(t);
            }
        }
        return returnList;
    }

    /**
     * 递归列表
     *
     * @param list 分类表
     * @param t 子节点
     */
    private void recursionFn(List<SysMenu> list, SysMenu t)
    {
        // 得到子节点列表
        List<SysMenu> childList = getChildList(list, t);
        t.setChildren(childList);
        for (SysMenu tChild : childList)
        {
            if (hasChild(list, tChild))
            {
                recursionFn(list, tChild);
            }
        }
    }

    /**
     * 得到子节点列表
     */
    private List<SysMenu> getChildList(List<SysMenu> list, SysMenu t)
    {
        List<SysMenu> tlist = new ArrayList<SysMenu>();
        Iterator<SysMenu> it = list.iterator();
        while (it.hasNext())
        {
            SysMenu n = (SysMenu) it.next();
            if (n.getParentId().longValue() == t.getMenuId().longValue())
            {
                tlist.add(n);
            }
        }
        return tlist;
    }

    /**
     * 判断是否有子节点
     */
    private boolean hasChild(List<SysMenu> list, SysMenu t)
    {
        return getChildList(list, t).size() > 0;
    }

    /**
     * 获取路由名称
     *
     * @param menu 菜单信息
     * @return 路由名称
     */
    public String getRouteName(SysMenu menu)
    {
        // 非外链并且是一级目录（类型为目录）
        if (isMenuFrame(menu))
        {
            return StringUtils.EMPTY;
        }
        return getRouteName(menu.getRouteName(), menu.getPath());
    }

    /**
     * 是否为菜单内部跳转
     *
     * @param menu 菜单信息
     * @return 结果
     */
    public boolean isMenuFrame(SysMenu menu)
    {
        return menu.getParentId().intValue() == 0
                && UserConstants.TYPE_MENU.equals(menu.getMenuType())
                && UserConstants.NO_FRAME.equals(String.valueOf(menu.getIsFrame()));
    }

    /**
     * 获取路由名称，如没有配置路由名称则取路由地址
     *
     * @param name 路由名称
     * @param path 路由地址
     * @return 路由名称（驼峰格式）
     */
    public String getRouteName(String name, String path)
    {
        String routerName = StringUtils.isNotEmpty(name) ? name : path;
        return StringUtils.capitalize(routerName);
    }

    /**
     * 获取路由地址
     *
     * @param menu 菜单信息
     * @return 路由地址
     */
    public String getRouterPath(SysMenu menu)
    {
        // 示例：
        // parentId=0, menuType="M", isFrame=0, path="system" -> "/system"
        // parentId=0, isMenuFrame=true, path="user" -> "/"
        // parentId=10, isFrame=0, path="https://docs.example.com/page" -> "docs/example/com/page"
        String routerPath = menu.getPath();
        // 内链子菜单：parentId != 0 且外链，路由需要去掉协议等特殊字符
        if (menu.getParentId().intValue() != 0 && isInnerLink(menu))
        {
            routerPath = innerLinkReplaceEach(routerPath);
        }
        // 一级目录且非外链：前缀加 "/" 形成根级路由
        if (0 == menu.getParentId().intValue()
                && UserConstants.TYPE_DIR.equals(menu.getMenuType())
                && UserConstants.NO_FRAME.equals(String.valueOf(menu.getIsFrame())))
        {
            routerPath = "/" + menu.getPath();
        }
        // 一级菜单且需要框架：固定为 "/"，具体子路由交给前端配置
        else if (isMenuFrame(menu))
        {
            routerPath = "/";
        }
        return routerPath;
    }

    /**
     * 是否为内链组件
     *
     * @param menu 菜单信息
     * @return 结果
     */
    public boolean isInnerLink(SysMenu menu)
    {
        return UserConstants.NO_FRAME.equals(String.valueOf(menu.getIsFrame())) && StringUtils.ishttp(menu.getPath());
    }

    /// 内链域名特殊字符替换
    ///
    /// 1. 子级内链菜单（外部网址）
    ///      - 输入：parentId=10、path="https://docs.example.com/page"、menuType="C"、isFrame="0"。
    ///      - 逻辑：parentId 非 0 且 isInnerLink 成立（外链且 isFrame=0），先把https://、:、. 等替换成 /，得到 docs/example/com/page。
    ///      - 输出：routerPath="docs/example/com/page"。
    /// 2. 一级目录（前端目录，不渲染 iframe）
    ///      - 输入：parentId=0、path="system"、menuType="M"、isFrame="0"。
    ///      - 逻辑：满足“非外链且一级目录且 isFrame=0”，前面加 /。
    ///      - 输出：routerPath="/system"。
    /// 3. 一级菜单框架（需要在一级路径下再渲染子路由）
    ///      - 输入：parentId=0、path="user"、menuType="C" 且满足 isMenuFrame（通常
    ///      isFrame="1" 表示用 iframe 打开子页面）。
    ///      - 逻辑：命中 isMenuFrame，强制路由为根 /，再由前端子路由决定实际展示。
    ///      - 输出：routerPath="/"。
    /// @return 替换后的内链域名
    public String innerLinkReplaceEach(String path)
    {
        return StringUtils.replaceEach(path, new String[] { Constants.HTTP, Constants.HTTPS, Constants.WWW, ".", ":" },
                new String[] { "", "", "", "/", "/" });
    }

    /**
     * 获取组件信息
     *
     * @param menu 菜单信息
     * @return 组件信息
     */
    public String getComponent(SysMenu menu)
    {
        // 组件选择规则示例：
        // 1) 默认：返回 "Layout"
        // 2) 有自定义组件且不是菜单框架 -> 返回自定义 component
        // 3) 子级外链且未配置组件 -> 返回 "InnerLink"
        // 4) 目录占位（父级视图）且未配置组件 -> 返回 "ParentView"
        String component = UserConstants.LAYOUT;
        if (StringUtils.isNotEmpty(menu.getComponent()) && !isMenuFrame(menu))
        {
            component = menu.getComponent();
        }
        else if (StringUtils.isEmpty(menu.getComponent()) && menu.getParentId().intValue() != 0 && isInnerLink(menu))
        {
            component = UserConstants.INNER_LINK;
        }
        else if (StringUtils.isEmpty(menu.getComponent()) && isParentView(menu))
        {
            component = UserConstants.PARENT_VIEW;
        }
        return component;
    }

    /**
     * 是否为parent_view组件
     *
     * @param menu 菜单信息
     * @return 结果
     */
    public boolean isParentView(SysMenu menu)
    {
        return menu.getParentId().intValue() != 0 && UserConstants.TYPE_DIR.equals(menu.getMenuType());
    }

    public static LambdaQueryWrapper<SysMenu> baseSelectWrapper() {
        LambdaQueryWrapper<SysMenu> wrapper = new LambdaQueryWrapper<>();
        wrapper.select(
                SysMenu::getMenuId, SysMenu::getMenuName,
                SysMenu::getParentId, SysMenu::getOrderNum,
                SysMenu::getPath, SysMenu::getComponent,
                SysMenu::getQuery, SysMenu::getRouteName,
                SysMenu::getIsFrame, SysMenu::getIsCache,
                SysMenu::getMenuType, SysMenu::getVisible,
                SysMenu::getStatus, SysMenu::getPerms,
                SysMenu::getIcon, SysMenu::getCreateTime
        );
        return wrapper;
    }
}
