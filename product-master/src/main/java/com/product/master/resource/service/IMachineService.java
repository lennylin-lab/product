package com.product.master.resource.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.product.domain.dto.MachineResource;
import com.product.domain.vo.MachineResourceVO;
import com.product.domain.entity.Machine;

import java.util.List;

/**
 * 注塑机扩展信息Service接口（MyBatis-Plus）
 *
 * @author product
 * @date 2026-01-01
 */
public interface IMachineService extends IService<Machine> {

    /**
     * 查询注塑机扩展信息
     *
     * @param machineId 注塑机扩展信息主键
     * @return 注塑机扩展信息
     */
    MachineResourceVO selectMachineByMachineId(Long machineId);

    /**
     * 查询注塑机扩展信息列表
     *
     * @param machine 查询条件
     * @return 注塑机扩展信息集合
     */
    List<Machine> selectMachineList(Machine machine);

    /**
     * 分页查询注塑机扩展信息列表
     *
     * @param page            分页参数
     * @param machineResource 查询条件
     * @return 分页结果
     */
    Page<MachineResourceVO> selectMachinePage(Page<MachineResourceVO> page, MachineResource machineResource);

    /**
     * 新增注塑机扩展信息
     *
     * @param machineResource 注塑机扩展信息
     * @return 是否成功
     */
    boolean insertMachine(MachineResource machineResource);

    /**
     * 批量新增注塑机扩展信息
     *
     * @param machines 注塑机扩展信息列表
     * @return 成功条数
     */
    int batchInsertMachine(List<Machine> machines);

    /**
     * 修改注塑机扩展信息
     *
     * @param machineResource 注塑机扩展信息
     * @return 是否成功
     */
    boolean updateMachine(MachineResource machineResource);

    /**
     * 批量删除注塑机扩展信息
     *
     * @param machineIds 主键集合
     * @return 是否成功
     */
    boolean deleteMachineByMachineIds(String[] machineIds);

    /**
     * 删除注塑机扩展信息信息
     *
     * @param machineId 主键
     * @return 是否成功
     */
    boolean deleteMachineByMachineId(Long machineId);

    boolean down(Long machineId);

    boolean maintenance(Long machineId);

    boolean restore(Long machineId);
}
