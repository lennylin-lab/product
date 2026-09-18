# 技术设计：修复 issue #9/#10/#11

## 1. #9 password 脱敏（identity）

`SysUser.java`（product-identity/domain/entity）：

```java
import com.fasterxml.jackson.annotation.JsonProperty;

    /** 密码 */
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private String password;
```

- Lombok `@Data` 下字段级注解作用于整个属性；WRITE_ONLY = 序列化跳过、反序列化读入。
- 安全面核实结论（设计依据）：① 控制器 add/edit/resetPwd/changeStatus 均为注释死代码，写路径无 Jackson 反序列化依赖；② Redis 仅缓存验证码/重试计数/字典（grep 全量确认），无 SysUser 往返；③ 登录密码校验 `SysPasswordService.validate` 用 DB 现查用户；④ Excel 导出走 `@Excel` 注解反射，password 无该注解本就不导出。
- 测试（`JacksonContractTest` 追加两用例，用其既有 `mapperWithCustomizer()`，即响应真实 mapper 配置）：
  - `passwordShouldNotAppearInSerializedResponse`：`user.setPassword("$2a$10$...")` → json 不含 `"password"`；
  - `passwordShouldStillDeserializeFromRequestBody`：`{"password":"$2a$10$..."}` readValue → getPassword 等值（回归写路径）。

## 2. #10 单 id 路径变量 `:\d+` 约束（41 处 / 17 文件）

统一变换：`/{xxxId}` → `/{xxxId:\d+}`（保留既有 `value =` 形式）。清单（模块 → 控制器:行）：

**identity**
- `SysMenuController` L66 `/{menuId}`、L85 `/roleMenuTreeselect/{roleId}`、L139 DELETE `/{menuId}`
- `SysUserController` L230 `/authRole/{userId}`
- `SysDictTypeController` L69 `/{dictId}`
- `SysDictDataController` L71 `/{dictCode}`

**master-data**
- `FixtureController` L49 `/{fixtureId}`
- `CalendarController` L82 `/{calendarId}`
- `ProductRouteController` L39 `/product/{productId}`、L45 `/product/{productId}/active`、L50 `/{routeId}`、L69 PUT `/{routeId}/activate`、L75 DELETE `/{routeId}`
- `ProductController` L88 `/{productId}`
- `MachineController` L77 `/{machineId}`、L109 `/down/{machineId}`、L117 `/maintenance/{machineId}`、L125 `/restore/{machineId}`

**demand**
- `CustomerController` L83 `/{customerId}`
- `CustomerOrderController` L77 `/{orderId}`、L109 `/check/{orderId}`、L117 `/cancelCheck/{orderId}`
- `OrderLineController` L82 `/{orderLineId}`、L114 `/release/{orderLineId}`、L122 `/cancelRelease/{orderLineId}`

**planning**
- `ProductionBatchController` L80 `/{batchId}`、L112 `/release/{batchId}`、L120 `/cancelRelease/{batchId}`、L136 `/retryGenerateTask/{batchId}`
- `OperationTaskController` L88 `/{taskId}`、L120 `/cancel/{taskId}`、L128 `/restore/{taskId}`、L136 `/revokeSchedule/{taskId}`
- `TaskAssignmentController` L88 `/{assignmentId}`、L145 `/scheduleJob/{jobId}`

**execution**
- `TaskEventController` L86 `/{eventId}`、POST `/start/{taskId}`、`/pause/{taskId}`、`/resume/{taskId}`、`/complete/{taskId}`、`/exception/{taskId}`（L118-151）

不改：复数 `{xxxIds}`/`{dictCodes}`（14 处 = 7 个 `String[]` + 7 个 `Long[]`，绑定机制不同：Long[] 字面路径在绑定期即类型不匹配 500、String[] 进服务层 `Long.valueOf` NFE → 通用 500，均无数据变更）；`SysUserController` 既有 `/{userId:\d+}`。
实现：Python 脚本按显式 (file, old, new) 对做精确替换（无正则歧义），改后 grep 验证零残留。质检补充修正：初版清单按"id 位于路径末尾"过滤漏掉 TaskEventController 5 个 POST 子路径映射，已补齐。

**README 决议表**（「与单体的显式行为差异」表，#1 行后追加，注意表内按 issue 号顺序混排、保持 #10 紧跟 #1 主题相关行，实际按号序插入）：

```
| #10 | 全部单 id 路径变量加 \d 纯数字约束（36 处，复数 {xxxIds} 除外） | 字面路径不再被 `/{xxxId}` 吞掉抛 500 类型不匹配，改落入统一 404；与 #1 的 user 控制器方案一致 |
| #9 | 用户信息响应不再输出 password 字段 | `SysUser.password` 加 @JsonProperty(WRITE_ONLY)，序列化脱敏、反序列化不受影响；Redis 无 SysUser 缓存链路，登录校验用 DB 现查用户 |
| #11 | 列表接口缺省分页参数改为默认第 1 页 10 条且条件生效 | 删除 Customer/Product list 的无参全量分支（TableSupport 缺省 1/10）；全量导出走既有 /export |
| #7 | 机台列表/详情仅返回 MACHINE 类型资源 | MachineMapper 两查询补 resource_type='MACHINE' 过滤；非机台资源 id 详情返回 data:null |
| #8 | 删除机台先校验存在性，缺失拒绝且不动 resource 表 | 消除「失败响应掩盖 resource 行已删」的半执行；单数删除补 @Transactional 与版本 bump |
```

（#9/#11 行与 #7/#8 补录行一并在本任务提交。）

## 3. #11 删除无参全量分支（demand + master-data）

**CustomerController.list**（demand）：

```java
@GetMapping("/list")
public TableDataInfo list(Customer customer) {
    Page<Customer> page = PageUtils.buildPage();
    return getDataTable(customerService.selectCustomerPage(page, customer));
}
```

**ProductController.list**（master-data）同样式。

服务层删除（无其他调用方，grep 已证）：
- `IProductService.selectCustomerPage()` + `ProductServiceImpl` 实现（`page(new Page<>())`）；连带清理接口/实现的 `IPage` import（若他处未用）。
- `ICustomerService.selectCustomerPage()` + `CustomerServiceImpl` 实现同样处理。

控制器 import 清理：`ServletUtils`、`PAGE_NUM`/`PAGE_SIZE` 静态导入、`StringUtils`（若文件内无他处使用）按编译结果清理。

依据：`TableSupport.getPageDomain()` 缺省 `Convert.toInt(param, 1)` / `Convert.toInt(param, 10)`（master-data 与 demand 两份实现逐行一致），`buildPage()` 恒可用——MachineController 无分支写法即证明。

## 4. 测试与验证矩阵

| 模块 | 既有测试回归 | 新增 |
|---|---|---|
| identity | JacksonContractTest（含新 2 用例）、其余全量 | — |
| master-data | 全量（含 #8 的 MachineServiceImplDeleteTest） | — |
| demand-service | 全量 | — |
| planning / execution | 全量（#10 仅注解改动，编译+上下文测试兜底） | — |

命令：`mvn -f product-services/pom.xml -B -ntp -pl product-identity,product-master-data,product-demand-service,product-planning,product-execution -am test`

## 5. 风险

| 风险 | 缓解 |
|---|---|
| #10 漏改/错改 37 处 | 显式清单脚本化替换 + grep 验证 + 全模块编译测试 |
| #10 影响前端拼非数字路径的既有调用 | Long id 体系下无合法非数字路径；openapi 路径模式变化属文档层 |
| #11 前端依赖"不传分页拿全量" | issue 明确目标态；/export 为全量出口；决议表留痕 |
| #9 某处依赖响应中的 password | 全仓 grep password 响应消费方（前端改密流程走专用端点，本层写端点均死代码） |
