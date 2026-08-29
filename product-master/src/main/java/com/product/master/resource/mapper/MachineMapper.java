package com.product.master.resource.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.product.domain.dto.MachineResource;
import com.product.domain.vo.MachineResourceVO;
import org.apache.ibatis.annotations.Mapper;
import com.product.domain.entity.Machine;

/**
 * 注塑机扩展信息Mapper接口，基于 MyBatis-Plus
 *
 * @author product
 * @date 2026-01-01
 */
@Mapper
public interface MachineMapper extends BaseMapper<Machine> {
    Page<MachineResourceVO> selectMachinePage(Page<MachineResourceVO> page, MachineResource machineResource);

    MachineResourceVO selectMachineByMachineId(String machineId);
}
