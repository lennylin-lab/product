package com.product.identity.system.controller;

/// 数据字典数据控制器
///
/// @author travel
/// @version 1.0
/// @since 2025-12-15
/// 功能描述：
/// 提供数据字典数据的增删改查功能，包括：
/// - 字典数据的分页查询
/// - 字典数据的导出功能
/// - 新增、修改、删除字典数据
/// - 字典数据状态管理
/// API路径：
/// - GET /system/dict/data/list - 分页查询字典数据
/// - POST /system/dict/data - 新增字典数据
/// - PUT /system/dict/data - 修改字典数据
/// - DELETE /system/dict/data/{dictCodes} - 删除字典数据
/// - GET /system/dict/data/export - 导出字典数据

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.product.identity.common.core.page.TableDataInfo;
import com.product.identity.common.core.result.AjaxResult;
import com.product.identity.core.controller.BaseController;
import com.product.identity.domain.entity.SysDictData;
import com.product.identity.system.service.ISysDictDataService;
import com.product.identity.core.utils.ExcelExporter;
import com.product.identity.common.utils.PageUtils;
import com.product.identity.common.utils.StringUtils;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;

/**
 * 数据字典信息
 *
 * @author fast
 */
@RestController
@RequestMapping("/system/dict/data")
public class SysDictDataController extends BaseController
{
    @Autowired
    private ISysDictDataService dictDataService;

    @PreAuthorize("@ss.hasPermi('system:dict:list')")
    @GetMapping("/list")
    public TableDataInfo list(SysDictData dictData)
    {
        Page<SysDictData> page = PageUtils.buildPage();
        return getDataTable(dictDataService.selectDictDataList(page, dictData));
    }

    @PreAuthorize("@ss.hasPermi('system:dict:export')")
    @PostMapping("/export")
    public void export(HttpServletResponse response, SysDictData dictData)
    {
        List<SysDictData> list = dictDataService.selectDictDataList(dictData);
        ExcelExporter.exportExcel(response, SysDictData.class, list, "字典数据");
    }

    /**
     * 查询字典数据详细
     */
    @PreAuthorize("@ss.hasPermi('system:dict:query')")
    @GetMapping(value = "/{dictCode}")
    public AjaxResult getInfo(@PathVariable Long dictCode)
    {
        return success(dictDataService.selectDictDataById(dictCode));
    }

    /**
     * 根据字典类型查询字典数据信息
     */
    @GetMapping(value = "/type/{dictType}")
    public AjaxResult dictType(@PathVariable String dictType)
    {
        List<SysDictData> data = dictDataService.selectDictDataByType(dictType);
        if (StringUtils.isNull(data))
        {
            data = new ArrayList<SysDictData>();
        }
        return success(data);
    }

    /**
     * 新增字典类型
     */
    @PreAuthorize("@ss.hasPermi('system:dict:add')")
    @PostMapping
    public AjaxResult add(@Validated @RequestBody SysDictData dict)
    {
        return toAjax(dictDataService.insertDictData(dict));
    }

    /**
     * 修改保存字典类型
     */
    @PreAuthorize("@ss.hasPermi('system:dict:edit')")
    @PutMapping
    public AjaxResult edit(@Validated @RequestBody SysDictData dict)
    {
        return toAjax(dictDataService.updateDictData(dict));
    }

    /**
     * 删除字典类型
     */
    @PreAuthorize("@ss.hasPermi('system:dict:remove')")
    @DeleteMapping("/{dictCodes}")
    public AjaxResult remove(@PathVariable Long[] dictCodes)
    {
        dictDataService.deleteDictDataByIds(dictCodes);
        return success();
    }
}
