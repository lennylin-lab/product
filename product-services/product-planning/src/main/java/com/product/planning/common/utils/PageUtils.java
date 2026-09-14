package com.product.planning.common.utils;

import com.baomidou.mybatisplus.core.metadata.OrderItem;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.product.planning.common.core.page.PageDomain;
import com.product.planning.common.core.page.TableSupport;
import com.product.planning.common.utils.sql.SqlUtil;

import java.util.ArrayList;
import java.util.List;

/**
 * 分页工具类
 *
 * @author fast
 */
public class PageUtils
{
    /**
     * 设置请求分页数据
     */
    public static <T> Page<T> buildPage()
    {
        PageDomain pageDomain = TableSupport.buildPageRequest();
        Integer pageNum = pageDomain.getPageNum();
        Integer pageSize = pageDomain.getPageSize();
        Page<T> page = new Page<>(pageNum, pageSize);
        // 3. 处理排序逻辑 (兼容原有的 SQL 排序字符串)
        // 假设前端传过来的是 "create_time desc, id asc" 这种格式
        String orderBy = pageDomain.getOrderBy();
        if (StringUtils.isNotEmpty(orderBy)) {
            String cleanOrderBy = SqlUtil.escapeOrderBySql(orderBy);
            page.addOrder(handleOrderBy(cleanOrderBy));
        }
        return page;
    }

    /**
     * 辅助方法：将 SQL 排序字符串解析为 MP 的 OrderItem 列表
     * 输入示例："create_time desc, id asc"
     */
    public static List<OrderItem> handleOrderBy(String orderBySql) {
        List<OrderItem> orderItems = new ArrayList<>();
        String[] orders = orderBySql.split(",");
        for (String order : orders) {
            String[] split = order.trim().split("\\s+"); // 按空格分割
            if (split.length > 0) {
                String column = split[0];
                boolean isAsc = split.length == 1 || "asc".equalsIgnoreCase(split[1]);
                orderItems.add(isAsc ? OrderItem.asc(column) : OrderItem.desc(column));
            }
        }
        return orderItems;
    }
}
