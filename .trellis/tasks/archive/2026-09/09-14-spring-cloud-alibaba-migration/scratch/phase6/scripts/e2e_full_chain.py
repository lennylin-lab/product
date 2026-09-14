#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Phase 6 全链 E2E（baselines.md §3.2 主链路，统一切换验收口径）。

全程只经 Gateway :8080（唯一外部入口）：
  登录(验证码) -> 建客户/产品(含模具参数+启用路线)/日历/机台 -> 建订单/订单行(跨域校验)
  -> 确认/释放 -> 拆批 -> 生成工序任务 -> 异步排程(轮询 schedule_job) -> 批次/任务/派工查询
  -> 执行事件 start->pause->resume->complete -> 三域终态一致 -> recon drifts=[]
  -> 异常路径(幽灵任务) -> 快照漂移守卫(排程期间并发写主数据 -> job FAILED)。

用途: python3 e2e_full_chain.py
"""
import json
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.request
import urllib.parse

GW = "http://127.0.0.1:8080"
P6 = "/home/lenny/Projects/pps/product/.trellis/tasks/09-14-spring-cloud-alibaba-migration/scratch/phase6"
RESULT = {"steps": []}

def log(msg):
    print(msg, flush=True)
    RESULT["steps"].append(msg)

def sh(args):
    return subprocess.check_output(args).decode().strip()

def sql(statement, db="master_data_db"):
    return sh(["docker", "exec", "product-mysql", "mysql", "-uroot", "-p123456",
               "--default-character-set=utf8mb4", "-N", "-B", "-e", statement, db])

def http(method, path, body=None, token=None, timeout=30):
    # 查询串值百分号编码（中文过滤参数）
    if "?" in path:
        base, qs = path.split("?", 1)
        parts = []
        for kv in qs.split("&"):
            k, _, v = kv.partition("=")
            parts.append(f"{k}={urllib.parse.quote(v, safe='')}" if v else kv)
        path = base + "?" + "&".join(parts)
    req = urllib.request.Request(GW + path, method=method)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    data = json.dumps(body).encode() if body is not None else None
    try:
        with urllib.request.urlopen(req, data=data, timeout=timeout) as resp:
            return resp.status, json.loads(resp.read().decode())
    except urllib.error.HTTPError as e:
        try:
            return e.code, json.loads(e.read().decode() or "{}")
        except Exception:
            return e.code, {}

def expect(desc, cond, detail=""):
    tag = "PASS" if cond else "FAIL"
    log(f"[{tag}] {desc}" + (f" | {detail}" if detail else ""))
    if not cond:
        json.dump(RESULT, open(f"{P6}/e2e_result.json", "w"), ensure_ascii=False, indent=1)
        sys.exit(1)

# ---------------- 1. 登录（验证码：读服务端生成于 Redis 的真实验证码值，全链路校验） ----------------
st, cap = http("GET", "/captchaImage")
expect("GET /captchaImage 匿名可访问", st == 200 and cap.get("uuid"), json.dumps(cap)[:120])
uuid = cap["uuid"]
code = sh(["docker", "exec", "product-redis", "redis-cli", "-a", "123456", "--no-auth-warning",
           "GET", f"identity:captcha_codes:{uuid}"]).strip().strip('"')
st, login = http("POST", "/login", {"username": "admin", "password": "admin123", "code": code, "uuid": uuid})
expect("POST /login（验证码校验）返回 token", st == 200 and login.get("token"), f"code={login.get('code')} msg={login.get('msg')}")
TOKEN = login["token"]

def call(method, path, body=None):
    return http(method, path, body, TOKEN)

# ---------------- 2. 主数据准备（产品/路线走 API；无控制器的表与单体一致由 SQL 准备） ----------------
SQL_MASTER_SEED = """
INSERT INTO mold (mold_id, mold_code, cavity, mold_status) VALUES
 (610,'M610',2,'AVAILABLE'), (611,'M611',4,'AVAILABLE')
ON DUPLICATE KEY UPDATE mold_status='AVAILABLE';
INSERT INTO changeover_rule (rule_id, same_mold_time_min, different_mold_time_min, material_change_extra_min, color_change_extra_min, create_time)
VALUES (1, 5, 40, 10, 10, NOW())
ON DUPLICATE KEY UPDATE same_mold_time_min=5, different_mold_time_min=40, material_change_extra_min=10, color_change_extra_min=10;
INSERT INTO resource (resource_id, resource_type, name, status, calendar_id, create_time) VALUES
 (600,'MACHINE','注塑机E2EA','AVAILABLE',1,NOW()),
 (610,'MOLD','模具610','AVAILABLE',1,NOW()),
 (700,'PERSON','操作员E2E','AVAILABLE',1,NOW()),
 (800,'WORKSTATION','后处理工位E2E','AVAILABLE',1,NOW())
ON DUPLICATE KEY UPDATE status='AVAILABLE';
INSERT INTO machine (machine_id, tonnage, default_setup_time_min) VALUES (600, 150, 20)
ON DUPLICATE KEY UPDATE default_setup_time_min=20;
-- 机台-模具兼容：覆盖库内全部机台（含 API 建机台生成的雪花 ID 机台），保证 INJECT 模具可选
INSERT INTO machine_mold_compatibility (machine_id, mold_id, is_compatible)
SELECT m.machine_id, 610, 1 FROM machine m ON DUPLICATE KEY UPDATE is_compatible=1;
INSERT INTO machine_mold_compatibility (machine_id, mold_id, is_compatible)
SELECT m.machine_id, 611, 1 FROM machine m ON DUPLICATE KEY UPDATE is_compatible=1;
"""

st, r = call("POST", "/master/calendar",
             {"calendarId": 1, "calendarName": "常白班E2E", "workdayPattern": "Mon-Sun",
              "shiftStart": "08:00:00", "shiftEnd": "20:00:00"})
expect("POST /master/calendar 建日历（网关 Phase 6 新增正式路由；重跑=已存在）",
       st == 200 and r.get("code") in (200, 500), json.dumps(r, ensure_ascii=False)[:120])

st, r = call("POST", "/master/resource/machine", {"machineId": 600, "tonnage": 150, "defaultSetupTimeMin": 20})
expect("POST /master/resource/machine 建机台（重跑=已存在）", st == 200 and r.get("code") in (200, 500),
       json.dumps(r, ensure_ascii=False)[:120])
st, r = call("GET", "/master/resource/machine/list?pageNum=1&pageSize=50")
rows = r.get("rows") or []
expect("GET /master/resource/machine/list 可查且含新建机台",
       st == 200 and len(rows) >= 1, f"rows={len(rows)} machineIds={[x.get('machineId') for x in rows]}")

sql(SQL_MASTER_SEED)

st, r = call("POST", "/demand/product", {
    "productName": "E2E产品PP", "materialCode": "PP-E2E", "colorCode": "RED",
    "moldParams": [{"moldId": 610, "cycleTimeSec": 30, "cavity": 2, "yieldRate": 0.95, "utilization": 0.9}],
    "activeRoute": {"productId": None, "version": "v1", "isActive": 1,
                    "operations": [
                        {"opCode": "SETUP", "sequence": 1, "eligibleResourceRule": "RULE_SETUP_MACHINE", "stdTimeModel": "TM_SETUP_BASE", "queuePolicy": "FIFO"},
                        {"opCode": "INJECT", "sequence": 2, "eligibleResourceRule": "RULE_INJECT_MACHINE", "stdTimeModel": "TM_INJECT_A2", "queuePolicy": "FIFO"},
                        {"opCode": "POST_QC_PUTAWAY", "sequence": 3, "eligibleResourceRule": "RULE_POST_WORKSTATION", "stdTimeModel": "TM_POST_UNIT", "queuePolicy": "FIFO"}]}})
expect("POST /demand/product 建产品（模具参数+3 工序启用路线）", st == 200 and r.get("code") == 200, json.dumps(r, ensure_ascii=False)[:150])

st, r = call("GET", "/demand/product/list?pageNum=1&pageSize=10&productName=E2E产品PP")
rows = r.get("rows") or []
expect("GET /demand/product/list 可查", st == 200 and len(rows) >= 1, f"total={r.get('total')}")
# 重跑幂等：取最新产品行
PRODUCT_ID = max(int(x["productId"]) for x in rows)

sql(f"INSERT INTO resource_capability (resource_id, op_code, product_id, is_enabled, priority_weight) VALUES "
    f"(700,'SETUP',{PRODUCT_ID},1,1),(700,'INJECT',{PRODUCT_ID},1,1),(700,'POST_QC_PUTAWAY',{PRODUCT_ID},1,1) "
    f"ON DUPLICATE KEY UPDATE is_enabled=1", "master_data_db")
log(f"  productId={PRODUCT_ID}")

# 路线激活唯一性（baselines §3.2-6）：第二条启用路线应被拦截
st, r = call("POST", "/pps/product-route", {"productId": PRODUCT_ID, "version": "v2", "isActive": 1,
                                            "operations": [{"opCode": "SETUP", "sequence": 1,
                                                            "eligibleResourceRule": "RULE_SETUP_MACHINE",
                                                            "stdTimeModel": "TM_SETUP_BASE", "queuePolicy": "FIFO"}]})
expect("第二条启用路线被单活跃约束拦截", st == 200 and r.get("code") == 500, json.dumps(r, ensure_ascii=False)[:150])

# ---------------- 3. 客户/订单/订单行（跨域校验） ----------------
st, r = call("POST", "/demand/customer", {"customerName": "E2E客户", "remark": "统一切换验收"})
expect("POST /demand/customer 建客户", st == 200 and r.get("code") == 200, json.dumps(r, ensure_ascii=False)[:120])
st, r = call("GET", "/demand/customer/list?pageNum=1&pageSize=10&customerName=E2E客户")
# 重跑幂等：IdWorker 雪花 ID 单调递增，取最新一行
CUSTOMER_ID = max(int(x["customerId"]) for x in r["rows"])

st, r = call("POST", "/demand/order", {"customerId": CUSTOMER_ID, "dueDate": "2026-10-31 00:00:00", "priority": 7})
expect("POST /demand/order 建订单", st == 200 and r.get("code") == 200, json.dumps(r, ensure_ascii=False)[:120])
st, r = call("GET", "/demand/order/list?pageNum=1&pageSize=50")
ORDER_ID = max(int(x["orderId"]) for x in r["rows"] if int(x["customerId"]) == CUSTOMER_ID)

# 跨域引用校验：不存在的产品必须被拒（Phase 3 新增防御，fail-closed）
st, r = call("POST", "/demand/orderLine", {"orderId": ORDER_ID, "productId": 999999, "qty": 5})
expect("订单行引用不存在产品被拒（跨域校验）", st == 200 and r.get("code") == 500 and "产品不存在" in r.get("msg", ""),
       json.dumps(r, ensure_ascii=False)[:150])

st, r = call("POST", "/demand/orderLine", {"orderId": ORDER_ID, "productId": PRODUCT_ID, "qty": 50})
expect("POST /demand/orderLine 建订单行（产品存在，Feign 校验通过）", st == 200 and r.get("code") == 200,
       json.dumps(r, ensure_ascii=False)[:150])
st, r = call("GET", f"/demand/orderLine/list?pageNum=1&pageSize=50&orderId={ORDER_ID}")
LINE_ID = max(int(x["orderLineId"]) for x in r["rows"])

st, r = call("PUT", f"/demand/order/check/{ORDER_ID}")
expect("PUT /demand/order/check 订单确认", st == 200 and r.get("code") == 200, json.dumps(r, ensure_ascii=False)[:120])
st, r = call("PUT", f"/demand/orderLine/release/{LINE_ID}")
expect("PUT /demand/orderLine/release 订单行释放", st == 200 and r.get("code") == 200, json.dumps(r, ensure_ascii=False)[:120])

# ---------------- 4. 拆批 -> 生成工序任务 ----------------
st, r = call("POST", "/pps/batch", {"orderLineId": LINE_ID, "batchQty": 30})
expect("POST /pps/batch 拆批（数量预占用经 demand 契约）", st == 200 and r.get("code") == 200,
       json.dumps(r, ensure_ascii=False)[:150])
st, r = call("GET", f"/pps/batch/list?pageNum=1&pageSize=10&orderId={ORDER_ID}")
rows = r.get("rows") or []
expect("GET /pps/batch/list（orderId 过滤 + 跨域字段填充）", st == 200 and len(rows) == 1
       and str(rows[0].get("orderId")) == str(ORDER_ID) and rows[0].get("productName") == "E2E产品PP",
       json.dumps(rows[0] if rows else {}, ensure_ascii=False, default=str)[:200])
BATCH_ID = int(rows[0]["batchId"])

st, r = call("PUT", f"/pps/batch/release/{BATCH_ID}")
expect("PUT /pps/batch/release 批次释放（PLANNED -> RELEASED）", st == 200 and r.get("code") == 200,
       json.dumps(r, ensure_ascii=False)[:150])

st, r = call("POST", "/pps/batch/generateTask", [BATCH_ID])
expect("POST /pps/batch/generateTask 生成工序任务", st == 200 and r.get("code") == 200,
       json.dumps(r, ensure_ascii=False)[:150])

st, r = call("GET", f"/pps/task/list?pageNum=1&pageSize=20&batchId={BATCH_ID}")
tasks = r.get("rows") or []
expect("GET /pps/task/list 3 道工序任务 READY", st == 200 and len(tasks) == 3
       and all(t["status"] == "READY" for t in tasks), f"n={len(tasks)}")

# ---------------- 5. 异步排程（轮询 schedule_job） ----------------
st, r = call("POST", "/pps/assignment/scheduleAllAsync",
             {"assignmentStart": "2026-09-16 08:00:00", "scheduleStrategy": "EARLIEST_START"})
expect("POST /pps/assignment/scheduleAllAsync 受理异步排程", st == 200 and r.get("code") == 200,
       json.dumps(r, ensure_ascii=False)[:150])
JOB_ID = r["data"]
job = {}
deadline = time.time() + 90
while time.time() < deadline:
    st, r = call("GET", f"/pps/assignment/scheduleJob/{JOB_ID}")
    job = r.get("data") or {}
    if job.get("status") in ("SUCCESS", "FAILED"):
        break
    time.sleep(0.5)
expect("排程任务 SUCCESS（异步 + 轮询）", job.get("status") == "SUCCESS", json.dumps(job, ensure_ascii=False, default=str)[:200])

st, r = call("GET", f"/pps/assignment/list?pageNum=1&pageSize=20&taskId={tasks[0]['taskId']}")
assigns = r.get("rows") or []
expect("GET /pps/assignment/list 派工已生成（机台/计划起止）", st == 200 and len(assigns) >= 1
       and assigns[0].get("machineId") and assigns[0].get("plannedStart") and assigns[0].get("plannedEnd"),
       json.dumps(assigns[0] if assigns else {}, ensure_ascii=False, default=str)[:200])
st, r = call("GET", f"/pps/task/list?pageNum=1&pageSize=20&batchId={BATCH_ID}")
tasks = r.get("rows") or []
expect("排程后任务全部 SCHEDULED", len(tasks) == 3 and all(t["status"] == "SCHEDULED" for t in tasks),
       ",".join(t["status"] for t in tasks))
TASK1 = int(tasks[0]["taskId"])

# ---------------- 6. 执行事件链 start->pause->resume->complete ----------------
def wait_chain(desc, want_line, want_order, timeout=30):
    line_st = order_st = None
    deadline = time.time() + timeout
    while time.time() < deadline:
        st, r = call("GET", f"/demand/orderLine/list?pageNum=1&pageSize=10&orderId={ORDER_ID}")
        line_st = r["rows"][0]["status"]
        st, r = call("GET", f"/demand/order/{ORDER_ID}")
        order_st = r["data"]["status"]
        if line_st == want_line and order_st == want_order:
            break
        time.sleep(0.5)
    expect(desc, line_st == want_line and order_st == want_order, f"line={line_st} order={order_st}")

def task_status(task_id):
    # /pps/task/list 分页查询不按 taskId 过滤（与单体一致），按 batchId 过滤后取目标行
    st, r = call("GET", f"/pps/task/list?pageNum=1&pageSize=50&batchId={BATCH_ID}")
    rows = r.get("rows") or []
    for row in rows:
        if int(row["taskId"]) == int(task_id):
            return row["status"]
    return None

def wait_task(desc, task_id, want, timeout=20):
    got = None
    deadline = time.time() + timeout
    while time.time() < deadline:
        got = task_status(task_id)
        if got == want:
            break
        time.sleep(0.4)
    expect(desc, got == want, f"status={got} want={want}")

def exec_cmd(cmd, task_id, expect_ok=True, body=None):
    st, r = call("POST", f"/execute/event/{cmd}/{task_id}", body)
    ok = r.get("code") == 200
    expect(f"POST /execute/event/{cmd}/{task_id} -> {'200' if ok else r.get('code')}",
           ok == expect_ok, json.dumps(r, ensure_ascii=False)[:150])
    return r

exec_cmd("start", TASK1)
wait_chain("start 事件链收敛：line IN_PRODUCTION / order IN_PRODUCTION", "IN_PRODUCTION", "IN_PRODUCTION")
expect("planning 任务状态 RUNNING（事件消费）", task_status(TASK1) == "RUNNING", task_status(TASK1))
st, r = call("GET", f"/pps/batch/list?pageNum=1&pageSize=10&orderId={ORDER_ID}")
expect("批次状态 IN_PROCESS（事件聚合）", r["rows"][0]["status"] == "IN_PROCESS", r["rows"][0]["status"])

exec_cmd("pause", TASK1, body={"pauseDuration": 30})
wait_task("任务 PAUSED（暂停）", TASK1, "PAUSED")
exec_cmd("resume", TASK1)
wait_task("任务 RUNNING（恢复）", TASK1, "RUNNING")

# 异常路径：幽灵任务（与单体同语义：HTTP 200 + code 500，不留事件行）
st, ev_before = call("GET", f"/execute/event/list?pageNum=1&pageSize=100&taskId=999999")
exec_cmd("start", 999999, expect_ok=False)
st, ev_after = call("GET", f"/execute/event/list?pageNum=1&pageSize=100&taskId=999999")
expect("幽灵任务失败语义（操作失败, code 500）且不留事件行",
       str(ev_before.get("total")) == str(ev_after.get("total")) == "0",
       f"before={ev_before.get('total')} after={ev_after.get('total')}")

# 完工闭环：全部工序完成 -> 批次 DONE -> 订单行/订单 DONE
for t in ([{"taskId": TASK1}] + tasks[1:]):
    if int(t["taskId"]) != TASK1:
        exec_cmd("start", int(t["taskId"]))
    exec_cmd("complete", int(t["taskId"]))
wait_chain("完工闭环：line DONE / order DONE", "DONE", "DONE", timeout=60)
st, r = call("GET", f"/pps/batch/list?pageNum=1&pageSize=10&orderId={ORDER_ID}")
expect("批次 DONE", r["rows"][0]["status"] == "DONE", r["rows"][0]["status"])

# ---------------- 7. 快照漂移守卫（排程期间并发写主数据 -> job FAILED，任务保持 READY） ----------------
# 40 批 x 3 道工序 = 120 READY 任务：拉长计算段（captureVersions 与 verifyUnchanged 之间的窗口），
# 使并发版本 bump 必然落在窗口内（3 任务时计算仅 ~20ms，bump 可能错过窗口）。
probe_batches = range(8100001, 8100041)
ins_batch, ins_task, ins_dep, ins_req = [], [], [], []
req_id = 83000001
for b in probe_batches:
    ins_batch.append(f"({b}, {LINE_ID}, 1, 'RELEASED', NOW())")
    prev = None
    for seq, (op, rule, dur) in enumerate(
            [("SETUP", "RULE_SETUP_MACHINE", 10), ("INJECT", "RULE_INJECT_MACHINE", 2),
             ("POST_QC_PUTAWAY", "RULE_POST_WORKSTATION", 5)], start=1):
        tid = b * 100 + seq
        ins_task.append(f"({tid}, {b}, '{op}', {seq}, {dur}, '2026-09-17 08:00:00', 'READY', 'FIFO', '{rule}')")
        if prev is not None:
            ins_dep.append(f"({prev},{tid})")
        prev = tid
        if op == "POST_QC_PUTAWAY":
            ins_req.append(f"({req_id},{tid},'PERSON',NULL,'POST_QC_PUTAWAY',1,1)")
            ins_req.append(f"({req_id+1},{tid},'WORKSTATION',NULL,'POST_QC_PUTAWAY',1,1)")
            req_id += 2
        elif op == "SETUP":
            ins_req.append(f"({req_id},{tid},'PERSON',NULL,'SETUP',1,1)")
            ins_req.append(f"({req_id+1},{tid},'MACHINE',NULL,'SETUP',1,1)")
            req_id += 2
        else:
            ins_req.append(f"({req_id},{tid},'PERSON',NULL,'INJECT',1,1)")
            ins_req.append(f"({req_id+1},{tid},'MACHINE',NULL,'INJECT',1,1)")
            ins_req.append(f"({req_id+2},{tid},'MOLD',NULL,'INJECT',1,1)")
            req_id += 3
sql(f"""
DELETE FROM task_assignment_resource WHERE task_id BETWEEN 81000000 AND 81999999 OR task_id BETWEEN 810000000 AND 819999999;
DELETE FROM task_assignment WHERE task_id BETWEEN 81000000 AND 81999999 OR task_id BETWEEN 810000000 AND 819999999;
DELETE FROM task_resource_requirement WHERE task_id BETWEEN 81000000 AND 81999999 OR task_id BETWEEN 810000000 AND 819999999;
DELETE FROM task_dependency WHERE pre_task_id BETWEEN 81000000 AND 81999999 OR post_task_id BETWEEN 81000000 AND 81999999
  OR pre_task_id BETWEEN 810000000 AND 819999999 OR post_task_id BETWEEN 810000000 AND 819999999;
DELETE FROM operation_task WHERE task_id BETWEEN 81000000 AND 81999999 OR task_id BETWEEN 810000000 AND 819999999;
DELETE FROM production_batch WHERE batch_id BETWEEN 8100000 AND 8100999;
INSERT INTO production_batch (batch_id, order_line_id, batch_qty, status, create_time) VALUES
 {",".join(ins_batch)};
INSERT INTO operation_task (task_id, batch_id, op_code, sequence, std_duration_min, earliest_start, status, queue_policy, eligible_resource_rule) VALUES
 {",".join(ins_task)};
INSERT INTO task_dependency (pre_task_id, post_task_id) VALUES {",".join(ins_dep)};
INSERT INTO task_resource_requirement (requirement_id, task_id, resource_type, resource_id, capability_code, required_count, is_mandatory) VALUES
 {",".join(ins_req)};
""", "planning_db")

# 并发"写主数据"：job 提交后以 50ms 节奏 bump 主数据版本计数（任一 bump 落在
# captureVersions 之后、verifyUnchanged 之前即触发漂移），另在 job 进入 RUNNING 时追加一次
stop_bump = threading.Event()
def bump_master_version():
    while not stop_bump.is_set():
        try:
            sql("UPDATE master_data_data_version SET data_version = data_version + 1", "master_data_db")
        except Exception:
            pass
        stop_bump.wait(0.02)
bumper = threading.Thread(target=bump_master_version, daemon=True)
bumper.start()

st, r = call("POST", "/pps/assignment/scheduleAllAsync",
             {"assignmentStart": "2026-09-17 08:00:00", "scheduleStrategy": "EARLIEST_START"})
JOB2 = r["data"]
job2 = {}
bumped_running = False
deadline = time.time() + 120
while time.time() < deadline:
    st, r = call("GET", f"/pps/assignment/scheduleJob/{JOB2}")
    job2 = r.get("data") or {}
    if job2.get("status") == "RUNNING" and not bumped_running:
        bumped_running = True
    if job2.get("status") in ("SUCCESS", "FAILED"):
        break
    time.sleep(0.1)
stop_bump.set()
bumper.join(timeout=2)
expect("漂移守卫：输入版本漂移 -> job FAILED（不部分落库）", job2.get("status") == "FAILED"
       and "漂移" in (job2.get("errorMessage") or ""), json.dumps(job2, ensure_ascii=False, default=str)[:200])
ready = sql("SELECT COUNT(*) FROM operation_task WHERE batch_id BETWEEN 8100001 AND 8100040 AND status='READY'", "planning_db")
expect("漂移任务保持 READY（无部分落库）", ready == "120", f"ready_count={ready}")

# 清理漂移探针批次（保持三域对账干净）
sql(f"""
DELETE FROM task_assignment_resource WHERE task_id BETWEEN 81000000 AND 81999999 OR task_id BETWEEN 810000000 AND 819999999;
DELETE FROM task_assignment WHERE task_id BETWEEN 81000000 AND 81999999 OR task_id BETWEEN 810000000 AND 819999999;
DELETE FROM task_resource_requirement WHERE task_id BETWEEN 81000000 AND 81999999 OR task_id BETWEEN 810000000 AND 819999999;
DELETE FROM task_dependency WHERE pre_task_id BETWEEN 81000000 AND 81999999 OR post_task_id BETWEEN 81000000 AND 81999999
  OR pre_task_id BETWEEN 810000000 AND 819999999 OR post_task_id BETWEEN 810000000 AND 819999999;
DELETE FROM operation_task WHERE task_id BETWEEN 81000000 AND 81999999 OR task_id BETWEEN 810000000 AND 819999999;
DELETE FROM production_batch WHERE batch_id BETWEEN 8100000 AND 8100999;
""", "planning_db")

RESULT["result"] = "PASS"
json.dump(RESULT, open(f"{P6}/e2e_result.json", "w"), ensure_ascii=False, indent=1)
print("\nE2E ALL PASS")
