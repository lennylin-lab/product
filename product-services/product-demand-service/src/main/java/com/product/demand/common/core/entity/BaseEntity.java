package com.product.demand.common.core.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import org.springframework.format.annotation.DateTimeFormat;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * Entity基类
 *
 * @author fast
 */
public class BaseEntity implements Serializable
{
    private static final long serialVersionUID = 1L;

    /** 搜索值 */
    @JsonIgnore
    @TableField(exist = false)
    private String searchValue;

    /** 创建时间 */
    @DateTimeFormat(pattern = "yyyy-MM-dd[' 'HH:mm:ss']")
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    /** 更新时间 */
    @DateTimeFormat(pattern = "yyyy-MM-dd[' 'HH:mm:ss']")
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    /** 请求参数 */
    @TableField(exist = false)
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private Map<String, Object> params;

    public String getSearchValue()
    {
        return searchValue;
    }

    public void setSearchValue(String searchValue)
    {
        this.searchValue = searchValue;
    }

    public LocalDateTime getCreateTime()
    {
        return createTime;
    }

    public void setCreateTime(LocalDateTime createTime)
    {
        this.createTime = createTime;
    }

    public LocalDateTime getUpdateTime()
    {
        return updateTime;
    }

    public void setUpdateTime(LocalDateTime updateTime)
    {
        this.updateTime = updateTime;
    }

    public Map<String, Object> getParams()
    {
        if (params == null)
        {
            params = new HashMap<>();
        }
        return params;
    }

    public void setParams(Map<String, Object> params)
    {
        this.params = params;
    }
}
