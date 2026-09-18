# 执行计划：修复 issue #9/#10/#11

> 前置：task.py start 已执行。严禁在 Bash 中使用 cd（Trellis ZCode hooks 相对路径解析），一律绝对路径。

## Step 1：#9 password 脱敏

- [ ] `SysUser.java`：import JsonProperty + `@JsonProperty(access = JsonProperty.Access.WRITE_ONLY)`（design §1）。
- [ ] `JacksonContractTest` 追加两用例（序列化无 password / 反序列化可读入）。
- 验证：`mvn -f /home/lenny/Projects/pps/product/product-services/pom.xml -B -ntp -pl product-identity -am test`

## Step 2：#10 单 id 约束（37 处 / 17 文件）

- [ ] Python 脚本按 design §2 显式清单替换；README 决议表插入 #10/#9/#11/#7/#8 五行（文本见 design §2）。
- [ ] grep 复核：`grep -rn 'Mapping.*"/{' product-services --include="*Controller.java" | grep 单id | grep -v '\\d'` 应为空（复数 `{xxxIds}` 除外）。
- 验证：`mvn -f /home/lenny/Projects/pps/product/product-services/pom.xml -B -ntp -pl product-identity,product-master-data,product-demand-service,product-planning,product-execution -am test`

## Step 3：#11 删除无参全量分支

- [ ] CustomerController / ProductController list 方法改写（design §3）；两个 Service 接口 + 实现删除无参 `selectCustomerPage()`；清理失效 import。
- 验证：同 Step 2 命令（全五模块一次跑）。

## Step 4：质量检查

- [ ] 自查 diff 对照 design；trellis-check 子代理全项检查（含 spec 合规与五模块测试）。

## Step 5：收尾

- [ ] 三个 commit（#9 / #10 / #11 分开，#7/#8 决议补录行归入 #10 的提交或单独 docs commit——归入 #10 提交并在 body 注明）。
- [ ] 推送 origin master；gh 评论并关闭 #9/#10/#11（评论含根因/修复/验证/残留说明）。
- [ ] task.py archive + 记日志。

## 回滚点

任一步验证失败即停；整体回滚 = revert 对应提交（无 DB/配置变更）。
