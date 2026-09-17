# 技术设计：修复 issue #6/#7/#8

## 1. 边界

| 项 | 决定 |
|---|---|
| 修改模块 | `product-gateway`（配置）、`product-master-data`（mapper XML + service + 单测）、`product-services/README.md` |
| 不改 | 单体 `product-master`（含同款 mapper 缺陷，已收敛迁移，另提 follow-up）；Sentinel 规则；任何 API 路径/形状 |
| 数据 | 无 schema 变更、无数据迁移；`resource`/`machine` 不变式由应用层维护 |

## 2. #6 网关 fixture 路由

`product-services/product-gateway/src/main/resources/application.yml`，在 `master-data-machine` 路由（L85-88）之后插入：

```yaml
- id: master-data-fixture
  uri: lb://product-master-data
  predicates:
    - Path=/master/resource/fixture,/master/resource/fixture/**
```

要点：
- `/master/resource/fixture` 与 `/master/resource/machine` 是互斥字面前缀，SCG 按声明顺序匹配不会互相吞并；放在 machine 之后保持 master-data 组内聚。
- 该路径不落在 `/demand/**`（demand-business）等兜底路由内，因此修复前必然 404，与 issue 复现一致。
- Sentinel：application.yml 兜底规则只枚举 identity-auth/identity-system/demand-business/planning-assignment 四条，calendar/machine/fixture 均依赖 Nacos 侧规则，无需同步。
- `product-services/README.md` 路由表 L33 的日历/机台行扩为「日历/机台/夹具」并列出夹具路径。

## 3. #7 MachineMapper 类型过滤

`MachineMapper.xml` 共享片段 `<sql id="selectMachineResourceVO">` 不动（两处 select 对 where 的拼接方式不同，改共享片段会让 `selectMachineByMachineId` 出现双 where）。分别改：

```xml
<select id="selectMachinePage" resultMap="MachineResouceMap">
    <include refid="selectMachineResourceVO"></include>
    where r.resource_type = 'MACHINE'
</select>
<select id="selectMachineByMachineId" resultMap="MachineResouceMap">
    <include refid="selectMachineResourceVO"></include>
    where r.resource_type = 'MACHINE'
      and r.resource_id = #{machineId}
</select>
```

- 类型值用 `ResourceConstants.RESOURCE_TYPE_MACHINE`（="MACHINE"）同字面量；XML 内无法引用常量，与 `insertMachine` 落库值一致即可。
- `machineResource` 查询条件参数继续不被 XML 消费（现状如此，属 #11 同类独立问题，不扩 scope）。
- 调用方影响：仅 `MachineController.list/getInfo`（已全仓 grep 确认无排程等其他调用方复用这两个查询）。getInfo 对非 MACHINE id 由"错数据"变 `data:null`，PRD 已列为接受项。

## 4. #8 删除机台：存在性校验 + 事务 + 版本 bump

`MachineServiceImpl`：

```java
@Override
@Transactional(rollbackFor = Exception.class)
public boolean deleteMachineByMachineId(Long machineId) {
    validateMachineExists(Collections.singletonList(machineId));
    boolean removeMachine = removeById(machineId);
    boolean removeResource = Db.removeById(machineId, Resource.class);
    boolean removed = removeMachine && removeResource;
    if (removed) {
        versionService.bump();
    }
    return removed;
}

@Override
@Transactional(rollbackFor = Exception.class)
public boolean deleteMachineByMachineIds(String[] machineIds) {
    if (machineIds == null || machineIds.length == 0) {
        return false;
    }
    List<Long> ids = Arrays.stream(machineIds).map(Long::valueOf).toList();
    validateMachineExists(ids);
    boolean removeMachine = removeByIds(ids);
    boolean removeResource = Db.removeByIds(ids, Resource.class);
    boolean removed = removeMachine && removeResource;
    if (removed) {
        versionService.bump();
    }
    return removed;
}

private void validateMachineExists(List<Long> machineIds) {
    List<Long> found = listByIds(machineIds).stream().map(Machine::getMachineId).toList();
    List<Long> missing = machineIds.stream().filter(id -> !found.contains(id)).toList();
    if (!missing.isEmpty()) {
        throw new ServiceException("机台不存在: " + missing);
    }
}
```

决策依据：
- **两方法都加存在性校验**：控制器唯一删除入口是 `DELETE /{machineIds}`（单 id → `String[]{"8301"}` → 批量方法），issue 复现实际命中的是批量方法；批量方法的事务帮不上忙——第一步 `removeByIds` 返回 false 但**不抛异常**，事务照常提交，resource 行已被删。只加事务不修逻辑等于没修。
- **校验放删除前、事务内**：抛 `ServiceException` 触发回滚 + 零副作用，错误体 HTTP 200 + code 500 + `机台不存在: [8301]`，满足统一错误契约（`GlobalServiceExceptionHandler.handleServiceException`）。
- **单数方法补 `@Transactional` 与 `versionService.bump()`**：与批量删除及 insert/update/down/maintenance/restore 全部对齐（现状单数删除是唯一不动版本计数的写操作，属遗漏）。
- `Long::valueOf` 解析失败（非数字 id）→ NumberFormatException → 通用 500，与现状"false → 500"客户端可见语义等价且同样无数据变更，不新增映射。
- `listByIds` 空集合防护：批量方法已先挡 null/empty，单数路径恒单元素，无需额外判空。

## 5. 测试设计（离线 Mockito，对齐 `ResourceStatusUpdateServiceTest` 约定）

新增 `MachineServiceImplDeleteTest`（master-data 模块 `src/test`）：

1. 删除（单/批量）machine 表无行 → 抛 ServiceException"机台不存在"，`Db.removeById(s)` 静态调用 `never()` 发生、`versionService.bump()` never。
2. 存在校验通过 → machine + resource 均删、bump 一次。
3. 批量部分缺失 → 整批拒绝（不删任何行），报文含缺失 id。
4. 空/null 数组 → 维持返回 false。

Mock 手段：`mockStatic(Db.class)` + mock `MachineMapper`/`MasterDataVersionService`；`removeById`/`removeByIds`/`getById`/`listByIds` 为 ServiceImpl 基类方法，用 spy + `doReturn` 打桩（同既有测试的链式 mock 风格）。

网关侧：现有 `GatewayApplicationTest` 上下文加载即校验新 YAML；无需新增网关用例（路由可达性属 IT 栈验证，本地可选跑 `product-integration-tests`）。

## 6. 兼容与回滚

- 前端若曾把非机台资源 id 当机台操作：修复后这些调用从"错误成功/静默丢数据"变为明确报错，属纠偏不属破坏。
- 回滚 = revert 三个文件的提交（无 DB/配置中心变更；README 为文档）。

## 7. 风险

| 风险 | 缓解 |
|---|---|
| 排程等其他链路隐式依赖"machine 列表返回全部资源" | 已 grep 全部调用方仅 MachineController；排程用 planning 自有 SQL |
| `listByIds` 大批量 in 查询 | 删除为低频管理操作，id 集来自人工勾选，量级小 |
| XML 改动导致的 SQL 语法错误 | master-data `mvn test` 上下文启动 + `GatewayApplicationTest`/`MasterDataApplicationTest` 兜底 |
