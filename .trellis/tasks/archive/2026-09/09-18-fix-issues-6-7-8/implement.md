# 执行计划：修复 issue #6/#7/#8

> 前置：`task.py start` 已执行（status=in_progress）。按序执行，每步有验证命令，失败即停。

## Step 1：#6 网关夹具路由

- [ ] `product-services/product-gateway/src/main/resources/application.yml`：`master-data-machine` 路由后插入 `master-data-fixture` 路由（design §2 原文）。
- [ ] `product-services/README.md`：网关正式路由表「日历/机台」行扩充夹具路径。
- 验证：
  ```bash
  mvn -B -ntp -pl product-services/product-gateway -am test -DskipITs
  ```
  （`GatewayApplicationTest` 上下文加载通过 = YAML/路由声明合法）

## Step 2：#7 MachineMapper 类型过滤

- [ ] `MachineMapper.xml`：`selectMachinePage` 加 `where r.resource_type = 'MACHINE'`；`selectMachineByMachineId` 改为类型 + id 双条件（design §3 原文，共享 sql 片段不动）。
- 验证：
  ```bash
  mvn -B -ntp -pl product-services/product-master-data -am test -DskipITs
  ```

## Step 3：#8 删除存在性校验 + 事务 + bump

- [ ] `MachineServiceImpl`：`deleteMachineByMachineId` 加 `@Transactional` + 存在性校验 + bump；`deleteMachineByMachineIds` 加批量存在性校验（design §4 原文，含 `validateMachineExists` 私有方法）。
- [ ] 新增 `MachineServiceImplDeleteTest`（design §5 四组用例）。
- 验证：
  ```bash
  mvn -B -ntp -pl product-services/product-master-data -am test -DskipITs
  ```

## Step 4：质量检查（最后一轮全量）

- [ ] trellis-check：spec 合规 + 编译 + 相关模块测试全量重跑：
  ```bash
  mvn -B -ntp -pl product-services/product-gateway,product-services/product-master-data -am test
  ```
- [ ] 本地可选（docker 可用时）：`product-services/product-integration-tests` IT 栈冒烟（机台列表/夹具经网关）。

## Step 5：收尾

- [ ] spec 更新评估（若踩坑/新约定出现才写，否则记 N/A）。
- [ ] 提交（feat/fix 前缀，一次提交或按 issue 分三个提交均可，消息引用 issue 号）。
- [ ] 远程 issue 处理：修复落地提交后，在 #6/#7/#8 下评论修复 commit 并关闭（需用户确认或用户自行操作）。
- [ ] follow-up 备注：单体 `product-master` MachineMapper 同款缺陷未修（已迁移收敛，低优先级）。

## 回滚点

- 任一步验证失败：`git checkout -- <file>` 回退该步文件；整体回滚 = revert 本次提交（无 DB/配置中心变更）。

## 审查门

- Step 3 完成后、Step 4 之前：自查 diff 对照 design §2–§4，确认无越界改动（不动单体、不动 Sentinel、不动 #10/#11 模式）。
