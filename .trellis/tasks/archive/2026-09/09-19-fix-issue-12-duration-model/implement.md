# 执行计划：修复 issue #12

> 严禁 Bash 中 cd（hook 相对路径），一律绝对路径。

## Step 1：代码

- [ ] `SetupBaseTimeModel` / `PostUnitTimeModel` / `RouteRuleRegistry` 兜底：去 ×batchQty（design §1 原文，含 javadoc）。
- [ ] `RouteRuleRegistryTest`：改 1 条 + 新增 3 条（design §2）。

## Step 2：README 决议表

- [ ] 决议表追加 #12 行：
  `| #12 | 任务标准时长不再随批量线性放大（SETUP/POST 固定基准，INJECT 产能公式不变） | 60×qty/120×qty 占位模型使 batchQty≥7 任务超 720min 班次窗口、整作业 FAILED；时长语义回归「每次换型基准/按批活动」，实际换型差值仍由排程期 ChangeoverCalculator 叠加 |`

## Step 3：验证与质检

- [ ] `mvn -f /home/lenny/Projects/pps/product/product-services/pom.xml -B -ntp -pl product-planning -am test`
- [ ] grep 复核：`grep -rn "batchQty()" product-services/product-planning/src/main --include="*.java"` 仅剩 InjectDurationCalculator 路径。
- [ ] trellis-check。

## Step 4：收尾

- [ ] 提交（单 commit，引用 issue #12）→ 推送 → 评论关闭 #12（注明两个后续项：窗口预校验、链路 IT）。
- [ ] task.py archive --skip-branch-validation + 手动提交归档 + 记日志。
