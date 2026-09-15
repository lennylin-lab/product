package com.product.masterdata.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.product.masterdata.domain.dto.FixtureResource;
import com.product.masterdata.domain.entity.Fixture;
import com.product.masterdata.domain.entity.FixtureMoldCompatibility;
import com.product.masterdata.domain.entity.Resource;

import java.util.List;

/**
 * 夹具扩展信息Service接口（MyBatis-Plus）
 *
 * <p>fixture 新增/修改/删除走 resource + fixture 扩展的聚合写事务，
 * 并在同一事务内递增主数据版本计数（MasterDataVersionService.bump，
 * 排程快照漂移检测依赖）。</p>
 *
 * @author product
 * @date 2026-09-15
 */
public interface IFixtureService extends IService<Fixture> {

    /**
     * 分页查询夹具扩展信息列表
     *
     * @param page    分页参数
     * @param fixture 查询条件
     * @return 分页结果
     */
    Page<Fixture> selectFixturePage(Page<Fixture> page, Fixture fixture);

    /**
     * 查询夹具扩展信息列表
     *
     * @param fixture 查询条件
     * @return 夹具扩展信息集合
     */
    List<Fixture> selectFixtureList(Fixture fixture);

    /**
     * 查询夹具资源聚合（resource 主行 + fixture 扩展）
     *
     * @param fixtureId 夹具ID
     * @return 资源聚合（fixture 为 null 表示缺扩展行）
     */
    Resource selectFixtureByFixtureId(Long fixtureId);

    /**
     * 新增夹具资源（resource 主行 + fixture 扩展行，同事务 + 版本递增）
     *
     * @param fixtureResource 夹具资源聚合入参
     * @return 是否成功
     */
    boolean insertFixture(FixtureResource fixtureResource);

    /**
     * 修改夹具资源（resource 主行 + fixture 扩展行，同事务 + 版本递增）
     *
     * @param fixtureResource 夹具资源聚合入参
     * @return 是否成功
     */
    boolean updateFixture(FixtureResource fixtureResource);

    /**
     * 批量删除夹具资源（resource 主行 + fixture 扩展行，同事务 + 版本递增）
     *
     * @param fixtureIds 夹具ID集合
     * @return 是否成功
     */
    boolean deleteFixtureByFixtureIds(String[] fixtureIds);

    /**
     * 维护夹具-模具兼容行（先删后插全量替换，同事务 + 版本递增）。
     *
     * <p>machine_mold_compatibility 无独立写路径（兼容矩阵历史上直接落库维护），
     * 本入口按 fixture 资源聚合写路径同款约定提供最小维护能力：
     * {@code @Transactional} + 成功后同事务 {@code MasterDataVersionService.bump()}
     * （事务回滚则计数不递增，快照漂移检测依赖）。</p>
     *
     * @param fixtureId       夹具ID
     * @param compatibilities 兼容行集合（null/空 = 清空该夹具全部兼容行）
     * @return 是否成功
     */
    boolean saveMoldCompatibilities(Long fixtureId, List<FixtureMoldCompatibility> compatibilities);
}
