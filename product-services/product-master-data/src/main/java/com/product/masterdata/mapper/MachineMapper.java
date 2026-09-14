package com.product.masterdata.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.product.masterdata.domain.dto.MachineResource;
import com.product.masterdata.domain.entity.Machine;
import com.product.masterdata.domain.vo.MachineResourceVO;
import org.apache.ibatis.annotations.Mapper;

/**
 * 注塑机扩展信息Mapper接口（MyBatis-Plus，单体 product-master 逐字移植，包路径服务内化）。
 */
@Mapper
public interface MachineMapper extends BaseMapper<Machine> {
    Page<MachineResourceVO> selectMachinePage(Page<MachineResourceVO> page, MachineResource machineResource);

    MachineResourceVO selectMachineByMachineId(Long machineId);
}
