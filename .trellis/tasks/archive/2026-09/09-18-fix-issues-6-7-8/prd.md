# 修复 issue #6/#7/#8：网关夹具路由、机台查询类型过滤、删除机台事务性

## Goal

修复 `lennylin-lab/product` 远程仓库三个 P1 OPEN issue，消除 master-data 域的可达性缺口与静默数据丢失风险：

- **#6 [gateway]** 网关缺 `/master/resource/fixture` 正式路由，夹具接口经网关 404（直连 8102 正常）。
- **#7 [master-data]** 机台列表/详情查询不过滤 `resource_type='MACHINE'`，PERSON/WORKSTATION 等 resource 行被当作机台返回。
- **#8 [master-data]** 删除机台存在半执行：machine 表删 0 行后 resource 行仍被物理删除，且返回"操作失败"掩盖已发生的数据变更。

## Requirements

### R1（对应 #6）网关补夹具路由

- `product-services/product-gateway/src/main/resources/application.yml` 在 master-data 路由组（calendar/machine 旁）新增 `master-data-fixture` 路由：`Path=/master/resource/fixture,/master/resource/fixture/**`，目标 `lb://product-master-data`。
- `product-services/README.md` 「网关正式路由（Phase 6 收敛版）」表同步补夹具路径。
- 保持既有路由声明顺序与注释风格不变（fixture 不与 `/master/resource/machine` 冲突，SCG 字面前缀互斥）。

### R2（对应 #7）机台查询按类型过滤

- `product-services/product-master-data/src/main/resources/mapper/MachineMapper.xml` 两处查询补 `resource_type='MACHINE'` 过滤：
  - `selectMachinePage`：只返回 MACHINE 资源行；
  - `selectMachineByMachineId`：类型 + id 双条件。
- 行为变化接受项：对非 MACHINE 资源 id 调 `GET /master/resource/machine/{id}`，从"错误地返回人员/工位数据"变为"HTTP 200 + code 200 + data null"（不要求升级为 404，保持最小修改）。

### R3（对应 #8）删除机台原子且先校验存在性

- `MachineServiceImpl` 两个删除方法（单数 `deleteMachineByMachineId(Long)`、批量 `deleteMachineByMachineIds(String[])`）：
  - 删除前校验 machine 表存在对应行，缺失即抛 `ServiceException("机台不存在: ...")`，**不触碰 resource 表**；
  - 单数方法补 `@Transactional(rollbackFor = Exception.class)`（与批量版对齐）；
  - 单数方法删除成功后补 `versionService.bump()`（与 insert/update/down/maintenance/restore/批量删除对齐）。
- 关键事实：控制器只暴露 `DELETE /{machineIds}`（`String[]`），单 id 请求实际走批量方法；批量方法虽有事务但"第一步 false 不抛异常 → 事务照常提交"，故**两个方法都要修**，仅加事务不够。
- 错误契约：`ServiceException` 由 `GlobalServiceExceptionHandler` 映射为 HTTP 200 + code 500 + msg（统一契约基线），调用方得到明确"机台不存在"失败且零副作用。
- 非 SKU 细节：id 无法解析为数字时维持"失败 + 无数据变更"语义（NumberFormatException → 通用 500），不新增专门映射。

## Constraints

- **范围 = `product-services` 微服务体系**（现役统一入口形态）。单体 `product-master/src/main/resources/mapper/MachineMapper.xml` 存在同款过滤缺陷，但业务已整体迁移（Phase 6 收敛），本次不改；收尾时提 follow-up 说明。
- 不改 API 路径/请求响应形状（外部契约冻结，baselines §1.2 精神）。
- 不动 Sentinel 规则（application.yml 兜底规则本就只枚举 4 条，fixture 与 calendar/machine 一致地依赖 Nacos 侧规则/默认值）。
- 不顺手修 #10（`/{id}` 数字约束）与 #11（缺省分页分支），保持任务边界。
- 遵循 `.trellis/spec/backend/`：错误处理用模块内 `com.product.masterdata.common.exception.ServiceException`；单测用离线 Mockito + `mockStatic(Db.class)`（对齐 `ResourceStatusUpdateServiceTest`）。

## Acceptance Criteria

- [ ] AC1（#6）：网关 `application.yml` 含 `master-data-fixture` 路由；`GatewayApplicationTest` 上下文加载通过（YAML 合法）；README 路由表含夹具行。
- [ ] AC2（#7）：`selectMachinePage` 生成 SQL 含 `r.resource_type = 'MACHINE'` 过滤；`selectMachineByMachineId` 同样过滤且保留 id 条件。库中存在 PERSON/WORKSTATION resource 行时，`GET /master/resource/machine/list` 不再返回它们。
- [ ] AC3（#8）：machine 表无对应行的 id 调删除（单/批量两条路径）→ 抛"机台不存在" ServiceException，resource 表对应行**不被删除**；存在行 → machine + resource 两表均删、版本计数 bump。
- [ ] AC4：新增/更新单测覆盖 AC2/AC3 语义（离线 Mockito，对齐既有约定），`mvn -pl` 相关模块 `test` 全绿；网关与 master-data 模块无编译回归。
- [ ] AC5：全模块质量检查（trellis-check：lint/编译/测试）通过。

## Notes

- issue 全文与复现记录见远程仓库 lennylin-lab/product #6、#7、#8（2026-09-17 实测复现，已在会话中核对本仓代码属实）。
- 联动关系：#7 修复后列表不再泄露非机台资源 id，从源头减少 #8 的误删入口；#8 的存在性校验是纵深防御。
