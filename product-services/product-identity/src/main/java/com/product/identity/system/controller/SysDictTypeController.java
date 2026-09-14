package com.product.identity.system.controller;

import com.product.identity.common.core.page.TableDataInfo;
import com.product.identity.common.core.result.AjaxResult;
import com.product.identity.core.controller.BaseController;
import com.product.identity.domain.entity.SysDictType;
import com.product.identity.system.service.ISysDictTypeService;
import com.product.identity.core.utils.ExcelExporter;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/// 数据字典类型控制器
///
/// @author travel
/// @version 1.0
/// @since 2025-12-15
/// 功能描述：
/// 提供数据字典类型的增删改查功能，包括：
/// - 字典类型的分页查询
/// - 字典类型的导出功能
/// - 新增、修改、删除字典类型
/// - 字典类型缓存刷新
/// - 查询字典详细信息
/// API路径：
/// - GET /system/dict/type/list - 分页查询字典类型
/// - POST /system/dict/type - 新增字典类型
/// - PUT /system/dict/type - 修改字典类型
/// - DELETE /system/dict/type/{dictIds} - 删除字典类型
/// - GET /system/dict/type/{dictId} - 查询字典类型详情
/// - GET /system/dict/type/export - 导出字典类型
/// - DELETE /system/dict/type/refreshCache - 刷新字典缓存

/**
 * 数据字典信息
 *
 * @author fast
 */
@RestController
@RequestMapping("/system/dict/type")
public class SysDictTypeController extends BaseController
{
    @Autowired
    private ISysDictTypeService dictTypeService;

    @PreAuthorize("@ss.hasPermi('system:dict:list')")
    @GetMapping("/list")
    public TableDataInfo list(SysDictType dictType)
    {
        return getDataTable(dictTypeService.selectDictTypeList(dictType));
    }

    @PreAuthorize("@ss.hasPermi('system:dict:export')")
    @PostMapping("/export")
    public void export(HttpServletResponse response, SysDictType dictType)
    {
        List<SysDictType> list = dictTypeService.selectDictTypeList(dictType).getRecords();
        ExcelExporter.exportExcel(response, SysDictType.class, list, "字典类型");
    }

    /**
     * 查询字典类型详细
     */
    @PreAuthorize("@ss.hasPermi('system:dict:query')")
    @GetMapping(value = "/{dictId}")
    public AjaxResult getInfo(@PathVariable Long dictId)
    {
        return success(dictTypeService.selectDictTypeById(dictId));
    }

    /**
     * 新增字典类型
     */
    @PreAuthorize("@ss.hasPermi('system:dict:add')")
    @PostMapping
    public AjaxResult add(@Validated @RequestBody SysDictType dict)
    {
        if (!dictTypeService.checkDictTypeUnique(dict))
        {
            return error("新增字典'" + dict.getDictName() + "'失败，字典类型已存在");
        }
        return toAjax(dictTypeService.save(dict));
    }

    /**
     * 修改字典类型
     */
    @PreAuthorize("@ss.hasPermi('system:dict:edit')")
    @PutMapping
    public AjaxResult edit(@Validated @RequestBody SysDictType dict)
    {
        if (!dictTypeService.checkDictTypeUnique(dict))
        {
            return error("修改字典'" + dict.getDictName() + "'失败，字典类型已存在");
        }
        return toAjax(dictTypeService.updateDictType(dict));
    }

    /**
     * 删除字典类型
     */
    @PreAuthorize("@ss.hasPermi('system:dict:remove')")
    @DeleteMapping("/{dictIds}")
    public AjaxResult remove(@PathVariable Long[] dictIds)
    {
        dictTypeService.deleteDictTypeByIds(dictIds);
        return success();
    }

    /**
     * 刷新字典缓存
     */
    @PreAuthorize("@ss.hasPermi('system:dict:remove')")
    @DeleteMapping("/refreshCache")
    public AjaxResult refreshCache()
    {
        dictTypeService.resetDictCache();
        return success();
    }

    /**
     * 获取字典选择框列表
     */
    @GetMapping("/optionselect")
    public AjaxResult optionselect()
    {
        List<SysDictType> dictTypes = dictTypeService.selectDictTypeAll();
        return success(dictTypes);
    }
}
