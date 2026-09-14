package com.product.identity.system.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.baomidou.mybatisplus.extension.toolkit.Db;
import com.product.identity.common.constant.UserConstants;
import com.product.identity.domain.entity.SysDictData;
import com.product.identity.domain.entity.SysDictType;
import com.product.cloud.common.exception.ServiceException;
import com.product.identity.system.mapper.SysDictTypeMapper;
import com.product.identity.system.service.ISysDictTypeService;
import com.product.identity.core.utils.DictUtils;
import com.product.identity.common.utils.PageUtils;
import com.product.identity.common.utils.StringUtils;
import com.product.identity.common.utils.spring.SpringUtils;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static com.product.identity.core.utils.DictUtils.clearDictCache;

/**
 * @Auther: chuan
 * @Date: 2025/12/21 - 12 - 21 - 17:12
 * @Description: com.product.service.impl
 * @version: 1.0
 */
@Service
public class SysDictTypeServiceImpl extends ServiceImpl<SysDictTypeMapper, SysDictType> implements ISysDictTypeService {
    /**
     * 项目启动时，初始化字典到缓存
     */
    @PostConstruct
    public void init()
    {
        loadingDictCache();
    }

    @Override
    public Page<SysDictType> selectDictTypeList(SysDictType sysDickType) {
        LambdaQueryWrapper<SysDictType> wrapper = getLambdaQueryWrapper(sysDickType);
        applySelectColumns(wrapper);
        Page<SysDictType> page = PageUtils.buildPage();
        return page(page, wrapper);
    }

    private static void applySelectColumns(LambdaQueryWrapper<SysDictType> wrapper) {
        wrapper.select(
                SysDictType::getDictId, SysDictType::getDictName,
                SysDictType::getDictType, SysDictType::getStatus,
                SysDictType::getCreateTime, SysDictType::getRemark
        );
    }

    @Override
    public boolean checkDictTypeUnique(SysDictType sysDictType) {
        Long dictId = StringUtils.isNull(sysDictType.getDictId()) ? -1L : sysDictType.getDictId();
        SysDictType dictType = lambdaQuery().select(SysDictType::getDictId)
                                .eq(SysDictType::getDictType, sysDictType.getDictType())
                                .last("limit 1")
                                .one();
        if (StringUtils.isNotNull(dictType) && dictType.getDictId().longValue() != dictId.longValue())
        {
            return UserConstants.NOT_UNIQUE;
        }
        return UserConstants.UNIQUE;
    }

    @Override
    @Transactional
    public boolean updateDictType(SysDictType sysDictType) {
        SysDictType oldDict = getById(sysDictType.getDictId());
        Db.lambdaUpdate(SysDictData.class)
                .set(SysDictData::getDictType,sysDictType.getDictType())
                .eq(SysDictData::getDictType,oldDict.getDictType())
                .update();
        boolean isSuccess = updateById(sysDictType);
        if (isSuccess)
        {
            List<SysDictData> dictDatas = SpringUtils.getBean(SysDictDataServiceImpl.class)
                                                    .getDictDatas(sysDictType.getDictType());
            DictUtils.setDictCache(sysDictType.getDictType(), dictDatas);
        }
        return isSuccess;
    }

    @Override
    public void deleteDictTypeByIds(Long[] dictIds) {
        for (Long dictId : dictIds)
        {
            SysDictType dictType = lambdaQuery().select(
                        SysDictType::getDictId, SysDictType::getDictName,
                        SysDictType::getDictType, SysDictType::getStatus,
                        SysDictType::getCreateTime, SysDictType::getRemark
                    )
                    .eq(SysDictType::getDictId, dictId)
                    .last("limit 1")
                    .one();
            if (
                    Db.lambdaQuery(SysDictData.class)
                            .eq(SysDictData::getDictType, dictType.getDictType())
                            .count() > 0
                )
            {
                throw new ServiceException(String.format("%1$s已分配,不能删除", dictType.getDictName()));
            }
            removeById(dictId);
            DictUtils.removeDictCache(dictType.getDictType());
        }
    }

    @Override
    public void resetDictCache() {
        clearDictCache();
        loadingDictCache();
    }

    private static LambdaQueryWrapper<SysDictType> getLambdaQueryWrapper(SysDictType sysDickType) {
        // 假设实体类为 SysDictType
        LambdaQueryWrapper<SysDictType> wrapper = new LambdaQueryWrapper<>();
        String dictName = sysDickType.getDictName();
        String status = sysDickType.getStatus();
        String dictType = sysDickType.getDictType();
        Map<String, Object> params = sysDickType.getParams();
        // 1. 普通字段查询 (利用第一个 boolean 参数代替 if)
        // StringUtils 可以使用 org.springframework.util.StringUtils 或 mp 自带的
        wrapper.like(StringUtils.hasText(dictName), SysDictType::getDictName, dictName)
                .eq(StringUtils.hasText(status), SysDictType::getStatus, status)
                .like(StringUtils.hasText(dictType), SysDictType::getDictType, dictType);

        // 2. 时间范围查询 (优化版)
        // 假设 params.beginTime 和 endTime 是 String 类型 (如 "2023-12-01")
        // 如果已经是 LocalDateTime 类型，逻辑类似，只需修改时间部分
        String beginTimeStr = mapGetString(params, "beginTime");
        String endTimeStr = mapGetString(params, "endTime");

        if (StringUtils.hasText(beginTimeStr)) {
            // 对应 date_format(create_time) >= beginTime
            // 解析为当天的 00:00:00
            LocalDateTime start = LocalDate.parse(beginTimeStr, DateTimeFormatter.ofPattern("yyyy-MM-dd")).atStartOfDay();
            wrapper.ge(SysDictType::getCreateTime, start);
        }

        if (StringUtils.hasText(endTimeStr)) {
            // 对应 date_format(create_time) <= endTime
            // 解析为当天的 23:59:59.999999999
            LocalDateTime end = LocalDate.parse(endTimeStr, DateTimeFormatter.ofPattern("yyyy-MM-dd")).atTime(LocalTime.MAX);
            wrapper.le(SysDictType::getCreateTime, end);
        }
        return wrapper;
    }

    /** 等价于单体使用的 org.apache.commons.collections4.MapUtils.getString（空安全）。 */
    private static String mapGetString(Map<String, Object> map, String key) {
        if (map == null) {
            return null;
        }
        Object value = map.get(key);
        return value != null ? value.toString() : null;
    }

    /**
     * 加载字典缓存数据
     */
    @Override
    public void loadingDictCache()
    {
        SysDictData dictData = new SysDictData();
        dictData.setStatus("0");
        Map<String, List<SysDictData>> dictDataMap = SpringUtils.getBean(SysDictDataServiceImpl.class)
                                                                .selectDictDataList(dictData)
                                                                .stream()
                                                                .collect(Collectors.groupingBy(SysDictData::getDictType));
        for (Map.Entry<String, List<SysDictData>> entry : dictDataMap.entrySet())
        {
            DictUtils.setDictCache(entry.getKey(), entry.getValue().stream().sorted(Comparator.comparing(SysDictData::getDictSort)).collect(Collectors.toList()));
        }
    }

    @Override
    public List<SysDictType> selectDictTypeAll() {
        LambdaQueryWrapper<SysDictType> wrapper = new LambdaQueryWrapper<>();
        applySelectColumns(wrapper);
        return list(wrapper);
    }

    @Override
    public SysDictType selectDictTypeById(Long dictId) {
        LambdaQueryWrapper<SysDictType> wrapper = new LambdaQueryWrapper<>();
        applySelectColumns(wrapper);
        wrapper.eq(SysDictType::getDictId, dictId);
        return this.getOne(wrapper);
    }
}
