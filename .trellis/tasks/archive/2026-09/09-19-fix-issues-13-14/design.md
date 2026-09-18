# 技术设计：issue #13/#14

## #13 分块助手（SchedulingSnapshotLoader）

```java
/** 契约单次批量 ID 上限（demand 与 master-data 同规则，issue #13 分块依据）。 */
private static final int CONTRACT_MAX_IDS = DemandQueryRequests.MAX_IDS;

private <V> Map<Long, V> loadByIdsInChunks(Collection<Long> ids, String label,
        java.util.function.Function<List<Long>, List<V>> fetch,
        java.util.function.Function<V, Long> key) {
    List<Long> distinct = ids.stream().filter(Objects::nonNull).distinct().collect(Collectors.toList());
    if (distinct.isEmpty()) return Map.of();
    Map<Long, V> merged = new HashMap<>();
    for (int from = 0; from < distinct.size(); from += CONTRACT_MAX_IDS) {
        List<Long> chunk = distinct.subList(from, Math.min(from + CONTRACT_MAX_IDS, distinct.size()));
        List<V> rows = fetch.apply(chunk);
        if (rows != null) rows.forEach(row -> { if (row != null) merged.putIfAbsent(key.apply(row), row); });
    }
    if (merged.size() < distinct.size()) log.warn("{}批量加载部分ID无返回: expected={} found={}", label, distinct.size(), merged.size());
    return merged;
}
```

四方法改造模式（以 loadOrderLines 为例）：

```java
public Map<Long, OrderLineSnapshot> loadOrderLines(Collection<Long> orderLineIds) {
    return loadByIdsInChunks(orderLineIds, "订单行", chunk -> {
        try {
            OrderDTO.OrderLineBatchResponse response = callWithAuthRetry(
                    () -> demandBatchQueryApi.getOrderLines(new DemandQueryRequests.OrderLineBatchQueryRequest(chunk)),
                    r -> r == null || r.getOrderLines() == null);
            if (response == null || response.getOrderLines() == null) {
                throw new ServiceException("需求服务响应异常，无法加载排程输入快照（请检查提供方日志与服务版本）");
            }
            return response.getOrderLines();
        } catch (FeignException e) {
            log.error("订单行批量加载失败，排程输入快照不可用: chunkSize={} status={}", chunk.size(), e.status(), e);
            throw new ServiceException("需求服务不可用，无法加载排程输入快照，请稍后重试");
        }
    }, OrderLineSnapshot::getOrderLineId);
}
```

- 文案策略：FeignException（暂时性）保留「请稍后重试」；形态异常（确定性）改为「（请检查提供方日志与服务版本）」——保留既有测试断言前缀「需求服务响应异常」。
- loadOrders/loadCalendars/loadProducts 同构；loadAvailableResources（类型全量）不动。
- 删除 requireBoundedIds（零调用）；类 javadoc 调用次数口径更新。

## #14 TRUNCATE 落点（各脚本末尾，FOREIGN_KEY_CHECKS=0 已设）

| 脚本 | 新增 TRUNCATE | 保留 |
|---|---|---|
| demand_schema | planning_batch_state, event_outbox, consumed_event, dead_letter_audit, ops_audit | demand_data_version（刻意，注释） |
| planning_schema | event_outbox, consumed_event, dead_letter_audit, ops_audit | （schedule_job 已 DROP+CREATE） |
| execution_schema | event_outbox, consumed_event, dead_letter_audit, ops_audit | — |

运维手册步骤 4 标注「运行时/审计表随重置清空；*_data_version 单调计数刻意保留」。

## 测试（SchedulingSnapshotLoaderTest 追加）

1. loadOrderLinesShouldChunkWhenIdsExceedContractLimit：2501 ids → thenAnswer 按请求返回对应 DTO，断言 3 次调用、每请求 ≤1000、合并 2501。
2. loadOrderLinesShouldDeduplicateIdsBeforeChunking：含重复 id → 捕获请求断言无重复。
3. loadCalendarsShouldChunkByContractLimit：1500 ids → 2 次调用、合并 1500。
既有 9 用例不动（单分块语义不变，含 auth-retry 两条）。
