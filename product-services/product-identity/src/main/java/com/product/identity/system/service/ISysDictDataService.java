package com.product.identity.system.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.product.identity.domain.entity.SysDictData;

import java.util.List;

/**
 * @Auther: chuan
 * @Date: 2025/12/21 - 12 - 21 - 18:45
 * @Description: com.product.service
 * @version: 1.0
 */
public interface ISysDictDataService extends IService<SysDictData> {
    List<SysDictData> selectDictDataList(SysDictData sysDictData);

    SysDictData selectDictDataById(Long dictCode);

    Page<SysDictData> selectDictDataList(Page<SysDictData> page, SysDictData sysDictData);

    List<SysDictData> selectDictDataByType(String dictType);

    boolean insertDictData(SysDictData dict);

    void deleteDictDataByIds(Long[] dictCodes);

    boolean updateDictData(SysDictData dict);
}
