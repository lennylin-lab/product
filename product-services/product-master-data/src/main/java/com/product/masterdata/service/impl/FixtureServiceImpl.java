package com.product.masterdata.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.baomidou.mybatisplus.extension.toolkit.Db;
import com.product.masterdata.common.constant.ResourceConstants;
import com.product.masterdata.common.constant.StatusConstants;
import com.product.masterdata.domain.dto.FixtureResource;
import com.product.masterdata.domain.entity.Fixture;
import com.product.masterdata.domain.entity.Resource;
import com.product.masterdata.mapper.FixtureMapper;
import com.product.masterdata.service.IFixtureService;
import com.product.masterdata.service.MasterDataVersionService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.List;

/**
 * 夹具扩展信息Service业务层处理（MyBatis-Plus）。
 *
 * <p>写路径沿用 machine 扩展的既有约定：resource 主行 + fixture 扩展行在同一
 * 写事务内落库，成功后在同事务内 {@link MasterDataVersionService#bump()}
 * 递增版本计数（快照漂移检测依赖；事务回滚则计数不递增）。</p>
 *
 * @author product
 * @date 2026-09-15
 */
@Service
@RequiredArgsConstructor
public class FixtureServiceImpl extends ServiceImpl<FixtureMapper, Fixture> implements IFixtureService {

    /** Phase 4 版本计数约定：主数据业务写与版本递增同事务。 */
    private final MasterDataVersionService versionService;

    /**
     * 分页查询夹具扩展信息列表
     *
     * @param page    分页参数
     * @param fixture 查询条件
     * @return 分页结果
     */
    @Override
    public Page<Fixture> selectFixturePage(Page<Fixture> page, Fixture fixture) {
        return page(page, buildQueryWrapper(fixture));
    }

    /**
     * 查询夹具扩展信息列表
     *
     * @param fixture 查询条件
     * @return 夹具扩展信息集合
     */
    @Override
    public List<Fixture> selectFixtureList(Fixture fixture) {
        return list(buildQueryWrapper(fixture));
    }

    /**
     * 查询夹具资源聚合（resource 主行 + fixture 扩展）
     *
     * @param fixtureId 夹具ID
     * @return 资源聚合（不存在时返回 null）
     */
    @Override
    public Resource selectFixtureByFixtureId(Long fixtureId) {
        Resource resource = Db.lambdaQuery(Resource.class)
                .eq(Resource::getResourceId, fixtureId)
                .one();
        if (resource == null) {
            return null;
        }
        resource.setFixture(getById(fixtureId));
        return resource;
    }

    /**
     * 新增夹具资源
     *
     * @param fixtureResource 夹具资源聚合入参
     * @return 是否成功
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean insertFixture(FixtureResource fixtureResource) {
        Fixture fixture = new Fixture();
        BeanUtils.copyProperties(fixtureResource, fixture);
        // 需要插入资源表
        Resource resource = new Resource();
        BeanUtils.copyProperties(fixtureResource, resource);
        resource.setResourceId(IdWorker.getId());
        fixture.setFixtureId(resource.getResourceId());
        resource.setResourceType(ResourceConstants.RESOURCE_TYPE_FIXTURE);
        resource.setStatus(StatusConstants.AVAILABLE_RESOURCE_STATUS);
        boolean saveResource = Db.save(resource);
        boolean saveFixture = save(fixture);
        boolean saved = saveResource && saveFixture;
        if (saved) {
            versionService.bump();
        }
        return saved;
    }

    /**
     * 修改夹具资源
     *
     * @param fixtureResource 夹具资源聚合入参
     * @return 是否成功
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean updateFixture(FixtureResource fixtureResource) {
        boolean updateResource = Db.lambdaUpdate(Resource.class)
                .set(Resource::getCalendarId, fixtureResource.getCalendarId())
                .set(Resource::getName, fixtureResource.getName())
                .eq(Resource::getResourceId, fixtureResource.getFixtureId())
                .update();
        boolean updateFixture = lambdaUpdate().set(Fixture::getFixtureCode, fixtureResource.getFixtureCode())
                .eq(Fixture::getFixtureId, fixtureResource.getFixtureId())
                .update();
        boolean updated = updateResource && updateFixture;
        if (updated) {
            versionService.bump();
        }
        return updated;
    }

    /**
     * 批量删除夹具资源
     *
     * @param fixtureIds 主键集合
     * @return 是否成功
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean deleteFixtureByFixtureIds(String[] fixtureIds) {
        if (fixtureIds == null || fixtureIds.length == 0) {
            return false;
        }
        boolean removeFixture = removeByIds(Arrays.asList(fixtureIds));
        boolean removeResource = Db.removeByIds(Arrays.asList(fixtureIds), Resource.class);
        boolean removed = removeFixture && removeResource;
        if (removed) {
            versionService.bump();
        }
        return removed;
    }

    /**
     * 构建查询条件
     */
    private LambdaQueryWrapper<Fixture> buildQueryWrapper(Fixture fixture) {
        LambdaQueryWrapper<Fixture> wrapper = new LambdaQueryWrapper<>();
        if (fixture == null) {
            return wrapper;
        }
        wrapper.eq(fixture.getFixtureCode() != null, Fixture::getFixtureCode, fixture.getFixtureCode());
        return wrapper;
    }
}
