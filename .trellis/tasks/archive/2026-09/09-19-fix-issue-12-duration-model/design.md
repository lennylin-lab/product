# 技术设计：修复 issue #12 时长模型

## 1. 代码变更（3 处乘法 + 注释）

**SetupBaseTimeModel.java**

```java
/**
 * SETUP 工序标准工时：每次换型基准时长（占位常量，不随批量放大）。
 * 机台间实际换型差值由排程期 ChangeoverCalculator 独立叠加；
 * 机台级 default_setup_time_min 在 generateTask 时不可得（机台未分配）。
 */
@Override
public long calculateDurationMin(RouteDurationContext context) {
    return OperationTaskConstants.STD_DURATION_MIM.get(0);
}
```

**PostUnitTimeModel.java**

```java
/**
 * POST_QC_PUTAWAY 工序标准工时：按批次活动计（占位基准，不随批量放大）。
 * 模型码 TM_POST_UNIT 已落库故类名/码不变；真实单位工时接入后再启用
 * 单位语义，届时需配班次上限校验/分批（issue #12 后续项）。
 */
@Override
public long calculateDurationMin(RouteDurationContext context) {
    return OperationTaskConstants.STD_DURATION_MIM.get(2);
}
```

**RouteRuleRegistry.calculateDurationMin 兜底分支**

```java
long baseDuration = opIndex >= 0
        ? OperationTaskConstants.STD_DURATION_MIM.get(opIndex)
        : OperationTaskConstants.STD_DURATION_MIM.get(1);
return baseDuration;
```

（兜底路径仅在 stdTimeModel 指向未注册模型码时触达；与模型语义保持一致，不再隐性 ×qty。）

## 2. 测试变更（RouteRuleRegistryTest）

- 改 `calculateDurationMinShouldUseSetupBaseModel`：qty=1 与 qty=100 均 60（批次无关）。
- 新增 `calculateDurationMinShouldUsePostBatchBaseModel`：qty=100 → 120。
- 新增 `calculateDurationMinFallbackShouldNotScaleWithBatchQty`：`stdTimeModel="TM_UNKNOWN"`、opCode=SETUP、qty=100 → 60。
- 新增 `realisticBatchQtyShouldStayWithinShiftWindow`（issue 级回归）：qty=100 时 SETUP(60)/POST(120) 与 INJECT（30s 周期×2 腔：100/240h≈25→26min）全部 ≤ 720。

## 3. 不做的事（边界）

- INJECT 产能公式、RouteDurationContext、TM_* 码、OperationTaskConstants 常量值均不动。
- generateTask 提前校验「时长>班次窗口」：需跨域（master-data 日历）查询，现三模型有界、排程侧已 fail-safe（单任务报错明确），列为后续项。
- generateTask→scheduleAllAsync 链路 IT：本期以单元语义钉死；链路 IT 建议后续单开（负载测试任务已有该链路的实测基建）。

## 4. 验证

`mvn -f /home/lenny/Projects/pps/product/product-services/pom.xml -B -ntp -pl product-planning -am test`；
trellis-check 复核三处乘法清除 + README 决议行。
