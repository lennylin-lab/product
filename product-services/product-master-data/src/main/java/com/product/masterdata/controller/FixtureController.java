package com.product.masterdata.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.product.masterdata.common.core.page.TableDataInfo;
import com.product.masterdata.common.core.result.AjaxResult;
import com.product.masterdata.common.utils.PageUtils;
import com.product.masterdata.core.controller.BaseController;
import com.product.masterdata.domain.dto.FixtureResource;
import com.product.masterdata.domain.entity.Fixture;
import com.product.masterdata.service.IFixtureService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 夹具扩展信息Controller（2026-09-15 夹具建模任务新增；端点风格与
 * MachineController 同款：resource 主行 + fixture 扩展行聚合 CRUD）。
 *
 * @author product
 * @date 2026-09-15
 */
@RestController
@RequestMapping("/master/resource/fixture")
public class FixtureController extends BaseController {

    private final IFixtureService fixtureService;

    public FixtureController(IFixtureService fixtureService) {
        this.fixtureService = fixtureService;
    }

    /**
     * 查询夹具扩展信息列表
     */
    @GetMapping("/list")
    public TableDataInfo list(Fixture fixture) {
        Page<Fixture> page = PageUtils.buildPage();
        return getDataTable(fixtureService.selectFixturePage(page, fixture));
    }

    /**
     * 获取夹具资源聚合详细信息
     */
    @GetMapping(value = "/{fixtureId}")
    public AjaxResult getInfo(@PathVariable("fixtureId") Long fixtureId) {
        return success(fixtureService.selectFixtureByFixtureId(fixtureId));
    }

    /**
     * 新增夹具资源
     */
    @PostMapping
    public AjaxResult add(@RequestBody FixtureResource fixtureResource) {
        return toAjax(fixtureService.insertFixture(fixtureResource));
    }

    /**
     * 修改夹具资源
     */
    @PutMapping
    public AjaxResult edit(@RequestBody FixtureResource fixtureResource) {
        return toAjax(fixtureService.updateFixture(fixtureResource));
    }

    /**
     * 删除夹具资源
     */
    @DeleteMapping("/{fixtureIds}")
    public AjaxResult remove(@PathVariable String[] fixtureIds) {
        return toAjax(fixtureService.deleteFixtureByFixtureIds(fixtureIds));
    }
}
