package com.product.identity.system.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.product.identity.domain.entity.SysDictType;

import java.util.List;

/**
 * @Auther: chuan
 * @Date: 2025/12/21 - 12 - 21 - 17:11
 * @Description: com.product.service
 * @version: 1.0
 */
public interface ISysDictTypeService extends IService<SysDictType> {
    Page<SysDictType> selectDictTypeList(SysDictType sysDictType);

    boolean checkDictTypeUnique(SysDictType sysDictType);

    boolean updateDictType(SysDictType sysDictType);

    void deleteDictTypeByIds(Long[] dictIds);

    void resetDictCache();

    /**
     * 加载字典缓存数据
     */
    public void loadingDictCache();

    List<SysDictType> selectDictTypeAll();

    SysDictType selectDictTypeById(Long dictId);
}
