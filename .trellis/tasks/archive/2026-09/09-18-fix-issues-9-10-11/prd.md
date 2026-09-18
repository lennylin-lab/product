# 修复 issue #9/#10/#11：密码哈希脱敏、路径变量数字约束、分页缺省分支

## Goal

修复远程仓库剩余三个 OPEN issue，收敛安全暴露与两类 API 契约缺陷：

- **#9 [P2][security]** `/getInfo`、`/system/user/{id}`、`/system/user/profile` 响应体暴露 BCrypt 密码哈希。
- **#10 [P3][错误契约]** 字面路径落入无约束 `/{xxxId}` 映射返回 500 类型不匹配，而非统一 404（仅 user 控制器已修复）。
- **#11 [P3][API契约]** 列表接口缺省分页参数时走无参分支，查询条件被静默忽略并返回全量数据。

## Requirements

### R1（#9）password 序列化脱敏

- `product-identity` 的 `SysUser.password` 加 `@JsonProperty(access = JsonProperty.Access.WRITE_ONLY)`：响应不输出、反序列化仍可读入。
- 影响面已核实：add/edit/resetPwd 在微服务控制器中为注释死代码；Redis 只缓存验证码/重试计数/字典（无 SysUser 往返），登录密码校验用 DB 现查用户——脱敏对写路径与缓存链路零影响。
- 测试：在 `JacksonContractTest` 增加两个用例（用与响应一致的定制 mapper）：带 password 序列化不含字段；含 password 反序列化字段可读入。

### R2（#10）全部单 id 路径变量加 `:\d+` 约束

- 全仓 41 处无约束单 id 映射（GET/POST/PUT/DELETE，见 design §2 清单）统一加 `{xxxId:\d+}` 正则约束，语义与 #1 修复的 `SysUserController` 既有方案一致：字面路径不再被吞掉抛类型不匹配 500，改落入统一 404。
- 明确不改：复数 `{xxxIds}`/`{dictCodes}`（14 处 = 7 个 String[] + 7 个 Long[] 绑定，机制不同但残留行为一致且无数据变更，在 issue 评论中说明）；已加约束的 SysUserController。
- 决策记录：`product-services/README.md`「与单体的显式行为差异（issue 修复决议）」表补 #10 行；同时补录已关闭的 #7/#8 行（该表即为此用途，#6 属网关配置非行为差异、已在路由表注明，不补）。

### R3（#11）删除无参全量分支

- `CustomerController.list`（demand）与 `ProductController.list`（master-data）删除「缺省分页参数 → 无参全量」分支，统一走 `PageUtils.buildPage()` + 过滤查询；`TableSupport` 缺省值已确认 pageNum=1、pageSize=10（两模块各自实现一致）。
- 行为变化（issue 明确要求的目标态）：不传分页参数时返回第 1 页 10 条（条件生效），不再返回全量；全量导出走既有 `/export`。
- 顺带删除仅被该分支调用的无参服务方法 `selectCustomerPage()`（IProductService/ProductServiceImpl、ICustomerService/CustomerServiceImpl 共四处，已确认无其他调用方），并清理控制器随之失效的 import。
- 决策记录：README 决议表补 #11 行。

## Constraints

- 范围 = `product-services`；单体冻结不动（决议表标题口径：单体保持冻结不改动）。
- 不改任何 API 路径、响应信封形状；#10 的 404 文案沿用统一错误契约（`NoResourceFoundException` 处理器已存在）。
- #9 契约注意：响应 mapper 定制（Long→String 等）不受影响；`JacksonContractTest` 既有用例必须继续通过。
- 遵循 `.trellis/spec/backend/`：测试为离线单测（identity 用 Jackson 契约测试惯例）；错误处理沿用统一契约。

## Acceptance Criteria

- [ ] AC1（#9）：`SysUser.password` 带 WRITE_ONLY 注解；`JacksonContractTest` 新用例证明"序列化无 password、反序列化可读入"，既有用例全绿。
- [ ] AC2（#10）：全仓 grep 无剩余无约束单 id 映射（`{xxxId}` 且无 `\d`，复数除外）；`/system/menu/listxx` 类字面路径落入 404 语义（由既有 NoResourceFoundException 映射保证）；涉及 5 个模块编译测试全绿。
- [ ] AC3（#11）：两控制器无分支、始终过滤；无参服务方法与死 import 清除；模块测试全绿。
- [ ] AC4：README 决议表含 #7/#8（补录）与 #9/#10/#11 行。
- [ ] AC5：trellis-check 全项 PASS；提交按 issue 分三个 commit，推送后评论并关闭 #9/#10/#11。

## Notes

- #9 属继承问题：单体 `product-domain` SysUser 同样无脱敏注解，单体冻结故不修（issue 本身也建议先修微服务层）。
- #10 复数 `{xxxIds}` 残留：`DELETE /listxx` 类请求绑定 String[] 成功后在服务层 `Long.valueOf` 抛 NFE → 通用 500（无数据变更），与 #8 修复后的行为一致；如需 404 化可后续单开 issue。
