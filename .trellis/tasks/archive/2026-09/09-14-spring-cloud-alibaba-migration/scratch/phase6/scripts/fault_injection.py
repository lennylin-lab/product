#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Phase 6 故障注入验收：demand / master-data / planning / rabbitmq 各停一次，
验证失败语义（fail-closed / 统一 503 / outbox 积压）与恢复收敛（路由恢复、排程成功、
事件链排空、对账 drifts=[]）。

场景：
  F1 kill demand    -> 网关 503 统一错误体；scheduleAllAsync job FAILED（需求服务不可用）
                       -> 重启 -> 列表 200；再次排程 SUCCESS
  F2 kill master-data -> 订单行创建 fail-closed（主数据服务不可用，无法校验产品引用）
                       -> 重启 -> 创建 200 -> 删除（清理）
  F3 kill planning  -> 网关 503；execution 命令 fail-closed（操作失败，不留事件）
                       -> 重启 -> 同一命令成功
  F4 stop rabbitmq  -> execution 命令成功（本地事务），outbox PENDING 积压、planning 不推进
                       -> 重启 -> relay 排空、消费者收敛 -> 全部探针任务 complete -> DONE
收尾：清理全部探针行（三库）-> recon.py report drifts=[]
"""
import json
import subprocess
import sys
import time
import urllib.error
import urllib.request

GW = "http://127.0.0.1:8080"
P6 = "/home/lenny/Projects/pps/product/.trellis/tasks/09-14-spring-cloud-alibaba-migration/scratch/phase6"
LOGD = f"{P6}/logs"
BASE = "/home/lenny/Projects/pps/product"
JARS = f"{BASE}/product-services"
JAVA = "/home/lenny/.local/share/mise/installs/java/temurin-17.0.20+8/bin/java"
OUT, FAILED = [], []
ENV = {"OTLP_TRACES_ENABLED": "true", "OTLP_TRACES_ENDPOINT": "http://127.0.0.1:4318/v1/traces",
       "PLANNING_RECON_INTERVAL_MS": "5000", "PLANNING_RECON_INITIAL_DELAY_MS": "5000",
       "DEMAND_RECON_INTERVAL_MS": "5000", "DEMAND_RECON_INITIAL_DELAY_MS": "5000",
       "SPRING_DATA_REDIS_PASSWORD": "123456"}

def log(s):
    print(s, flush=True)
    OUT.append(s)

def expect(desc, cond, detail=""):
    tag = "PASS" if cond else "FAIL"
    log(f"[{tag}] {desc}" + (f" | {detail}" if detail else ""))
    if not cond:
        FAILED.append(desc)
        open(f"{P6}/fault_injection_transcript.txt", "w").write("\n".join(OUT))
        json.dump(OUT, open(f"{P6}/fault_injection_transcript.json", "w"), ensure_ascii=False, indent=1)
        sys.exit(1)

def sh(args):
    return subprocess.check_output(args).decode().strip()

def sql(stmt, db=None):
    args = ["docker", "exec", "product-mysql", "mysql", "-uroot", "-p123456",
            "--default-character-set=utf8mb4", "-N", "-B", "-e", stmt]
    if db:
        args.append(db)
    return sh(args)

def raw(method, url, headers=None, body=None, timeout=15):
    req = urllib.request.Request(url, method=method)
    for k, v in (headers or {}).items():
        req.add_header(k, v)
    data = body.encode() if isinstance(body, str) else (json.dumps(body).encode() if body else None)
    try:
        with urllib.request.urlopen(req, data=data, timeout=timeout) as resp:
            return resp.status, dict(resp.headers), resp.read().decode()
    except urllib.error.HTTPError as e:
        return e.code, dict(e.headers), e.read().decode()
    except Exception as e:
        return 0, {}, str(e)

def login():
    st, _, body = raw("GET", GW + "/captchaImage")
    cap = json.loads(body)
    code = sh(["docker", "exec", "product-redis", "redis-cli", "-a", "123456", "--no-auth-warning",
               "GET", f"identity:captcha_codes:{cap['uuid']}"]).strip().strip('"')
    st, _, body = raw("POST", GW + "/login", {"Content-Type": "application/json"},
                      json.dumps({"username": "admin", "password": "admin123", "code": code, "uuid": cap["uuid"]}))
    return json.loads(body)["token"]

TOKEN = login()
AUTH = {"Authorization": "Bearer " + TOKEN, "Content-Type": "application/json"}

def pid_of(name):
    return sh(["awk", f'$1=="{name}"{{print $2}}', f"{P6}/pids.txt"]).splitlines()[-1]

def kill_service(name):
    pid = pid_of(name)
    subprocess.run(["kill", "-9", pid], check=True)
    time.sleep(2)
    log(f"  >> kill -9 {name} pid={pid}")

def start_service(name, jar, port):
    import os
    env = dict(os.environ)
    env.update(ENV)
    proc = subprocess.Popen([JAVA, "-jar", jar, f"--logging.file.path={LOGD}/{name}"],
                            cwd=BASE, env=env, stdout=open(f"{LOGD}/{name}.console.log", "ab"),
                            stderr=subprocess.STDOUT)
    with open(f"{P6}/pids.txt", "a") as f:
        f.write(f"{name} {proc.pid} {port}\n")
    deadline = time.time() + 90
    code = 0
    while time.time() < deadline:
        probe = subprocess.run(["curl", "-s", "-o", "/dev/null", "-w", "%{http_code}",
                                f"http://127.0.0.1:{port}/actuator/health/liveness"],
                               capture_output=True, text=True)
        try:
            code = int(probe.stdout or 0)
        except ValueError:
            code = 0
        if code == 200:
            break
        time.sleep(2)
    log(f"  >> restart {name} pid={proc.pid} port={port} liveness={code}")
    return proc.pid

# ============ 探针数据：3 个探针批次（各 3 任务 READY） ============
probe = {"batches": [8150001, 8150002, 8150003]}
ins_b = ",".join(f"({b}, 999999001, 1, 'RELEASED', NOW())" for b in probe["batches"])
ins_t, ins_d, ins_r = [], [], []
req_id = 85000001
for b in probe["batches"]:
    prev = None
    for seq, (op, rule, dur) in enumerate(
            [("SETUP", "RULE_SETUP_MACHINE", 30), ("INJECT", "RULE_INJECT_MACHINE", 3),
             ("POST_QC_PUTAWAY", "RULE_POST_WORKSTATION", 15)], start=1):
        tid = b * 100 + seq
        ins_t.append(f"({tid}, {b}, '{op}', {seq}, {dur}, '2026-09-19 08:00:00', 'READY', 'FIFO', '{rule}')")
        if prev:
            ins_d.append(f"({prev},{tid})")
        prev = tid
        caps = {"SETUP": [("PERSON", "SETUP"), ("MACHINE", "SETUP")],
                "INJECT": [("PERSON", "INJECT"), ("MACHINE", "INJECT"), ("MOLD", "INJECT")],
                "POST_QC_PUTAWAY": [("PERSON", "POST_QC_PUTAWAY"), ("WORKSTATION", "POST_QC_PUTAWAY")]}[op]
        for rtype, cap in caps:
            ins_r.append(f"({req_id},{tid},'{rtype}',NULL,'{cap}',1,1)")
            req_id += 1
sql(f"""
DELETE FROM task_assignment_resource WHERE task_id BETWEEN 815000000 AND 815099999;
DELETE FROM task_assignment WHERE task_id BETWEEN 815000000 AND 815099999;
DELETE FROM task_resource_requirement WHERE task_id BETWEEN 815000000 AND 815099999;
DELETE FROM task_dependency WHERE pre_task_id BETWEEN 815000000 AND 815099999 OR post_task_id BETWEEN 815000000 AND 815099999;
DELETE FROM operation_task WHERE task_id BETWEEN 815000000 AND 815099999;
DELETE FROM production_batch WHERE batch_id BETWEEN 8150000 AND 8150099;
INSERT INTO production_batch (batch_id, order_line_id, batch_qty, status, create_time) VALUES {ins_b};
INSERT INTO operation_task (task_id, batch_id, op_code, sequence, std_duration_min, earliest_start, status, queue_policy, eligible_resource_rule) VALUES {",".join(ins_t)};
INSERT INTO task_dependency (pre_task_id, post_task_id) VALUES {",".join(ins_d)};
INSERT INTO task_resource_requirement (requirement_id, task_id, resource_type, resource_id, capability_code, required_count, is_mandatory) VALUES {",".join(ins_r)};
""", "planning_db")
log("seeded 3 probe batches (8150001-8150003, tasks READY, order_line_id=999999001)")

def sched_job(strategy="EARLIEST_START", start="2026-09-19 08:00:00", timeout=90):
    st, _, body = raw("POST", GW + "/pps/assignment/scheduleAllAsync", AUTH,
                      json.dumps({"assignmentStart": start, "scheduleStrategy": strategy}))
    r = json.loads(body)
    job_id = r.get("data")
    job = {}
    deadline = time.time() + timeout
    while time.time() < deadline:
        st, _, body = raw("GET", GW + f"/pps/assignment/scheduleJob/{job_id}", AUTH)
        job = json.loads(body).get("data") or {}
        if job.get("status") in ("SUCCESS", "FAILED"):
            break
        time.sleep(0.4)
    return job

def wait_task(task_id, want, timeout=30):
    got = None
    deadline = time.time() + timeout
    while time.time() < deadline:
        got = sql(f"SELECT status FROM planning_db.operation_task WHERE task_id={task_id}")
        if got == want:
            break
        time.sleep(0.5)
    return got

# ================= F1: kill demand =================
log("== F1 demand 故障注入 ==")
job = sched_job()  # demand 在位时先成功排程一轮（探针任务 SCHEDULED，为 F3/F4 提供可执行任务）
expect("F1 前置：demand 在位排程 SUCCESS", job.get("status") == "SUCCESS", str(job.get("status")))

kill_service("demand")
st, h, b = raw("GET", GW + "/demand/customer/list?pageNum=1&pageSize=1", AUTH)
expect("F1 demand 停机：网关返回统一 503 错误体 + X-Trace-Id",
       st == 503 and b == '{"msg":"服务暂时不可用，请稍后重试","code":503}' and "X-Trace-Id" in h,
       f"http={st} body={b[:80]}")

sql("UPDATE operation_task SET status='READY' WHERE batch_id=8150001", "planning_db")
sql("DELETE FROM task_assignment WHERE task_id BETWEEN 815000101 AND 815000199", "planning_db")
sql("DELETE FROM task_assignment_resource WHERE task_id BETWEEN 815000101 AND 815000199", "planning_db")
job = sched_job()
expect("F1 demand 停机：排程 job FAILED（需求服务不可用）",
       job.get("status") == "FAILED" and "需求服务不可用" in (job.get("errorMessage") or ""),
       json.dumps(job, ensure_ascii=False)[:160])
ready = sql("SELECT COUNT(*) FROM planning_db.operation_task WHERE batch_id=8150001 AND status='READY'")
expect("F1 demand 停机：任务保持 READY（无部分落库）", ready == "3", f"ready={ready}")

start_service("demand", f"{JARS}/product-demand-service/target/product-demand-service-0.0.1-SNAPSHOT.jar", 8103)
st, _, b = raw("GET", GW + "/demand/customer/list?pageNum=1&pageSize=1", AUTH)
expect("F1 demand 恢复：网关路由恢复 200", st == 200 and '"code":200' in b, f"http={st}")
job = sched_job()
expect("F1 demand 恢复：重新排程 SUCCESS", job.get("status") == "SUCCESS", str(job.get("status")))

# ================= F2: kill master-data =================
log("== F2 master-data 故障注入 ==")
REAL_PRODUCT_ID = sql("SELECT MAX(product_id) FROM master_data_db.product")
REAL_ORDER_ID = sql("SELECT MAX(order_id) FROM demand_db.customer_order")
kill_service("master-data")
st, _, b = raw("POST", GW + "/demand/orderLine", AUTH,
               json.dumps({"orderId": int(REAL_ORDER_ID), "productId": int(REAL_PRODUCT_ID), "qty": 1}))
expect("F2 master-data 停机：订单行创建 fail-closed（主数据服务不可用）",
       st == 200 and "主数据服务不可用" in json.loads(b).get("msg", ""),
       f"body={b[:120]}")
start_service("master-data", f"{JARS}/product-master-data/target/product-master-data-0.0.1-SNAPSHOT.jar", 8102)
# 等 Nacos 注册 + LB 实例表刷新（liveness UP 不等于可被 Feign 发现）
deadline = time.time() + 60
recovered = False
while time.time() < deadline:
    stp, _, bp = raw("GET", GW + "/demand/product/list?pageNum=1&pageSize=1", AUTH)
    if stp == 200 and '"code":200' in bp:
        recovered = True
        break
    time.sleep(2)
expect("F2 master-data 恢复：网关读路由恢复 200（Nacos 注册完成）", recovered)
st, _, b = raw("POST", GW + "/demand/orderLine", AUTH,
               json.dumps({"orderId": int(REAL_ORDER_ID), "productId": int(REAL_PRODUCT_ID), "qty": 1}))
expect("F2 master-data 恢复：订单行创建成功（产品校验契约恢复）",
       st == 200 and json.loads(b).get("code") == 200, f"body={b[:100]}")
st, _, b = raw("GET", GW + f"/demand/orderLine/list?pageNum=1&pageSize=50&orderId={REAL_ORDER_ID}", AUTH)
rows = json.loads(b).get("rows") or []
if rows:
    line_id = max(int(x["orderLineId"]) for x in rows)
    st, _, b = raw("DELETE", GW + f"/demand/orderLine/{line_id}", AUTH)
    expect("F2 清理：临时订单行已删除", st == 200 and json.loads(b).get("code") == 200, f"line={line_id}")

# ================= F3: kill planning =================
log("== F3 planning 故障注入 ==")
kill_service("planning")
st, h, b = raw("GET", GW + "/pps/task/list?pageNum=1&pageSize=1", AUTH)
# 失败语义两种等价形态（均统一错误体 + X-Trace-Id）：
#   503 = 实例已从 Nacos 注销（Unable to find instance）；500 = LB 缓存未失效时连接拒绝（connect refused）
expect("F3 planning 停机：网关统一错误体（503 无实例 或 500 连接拒绝）",
       st in (503, 500) and '"code"' in b and "X-Trace-Id" in h, f"http={st} body={b[:80]}")
ev_before = sql("SELECT COUNT(*) FROM execution_db.task_event WHERE task_id=815000201")
st, _, b = raw("POST", GW + "/execute/event/start/815000201", AUTH)
expect("F3 planning 停机：execution 命令 fail-closed（操作失败）",
       st == 200 and json.loads(b).get("code") == 500 and json.loads(b).get("msg") == "操作失败",
       f"body={b[:100]}")
ev_after = sql("SELECT COUNT(*) FROM execution_db.task_event WHERE task_id=815000201")
expect("F3 planning 停机：不留事件行", ev_before == ev_after, f"before={ev_before} after={ev_after}")

start_service("planning", f"{JARS}/product-planning/target/product-planning-0.0.1-SNAPSHOT.jar", 8104)
st, _, b = raw("POST", GW + "/execute/event/start/815000201", AUTH)
expect("F3 planning 恢复：同一命令成功", st == 200 and json.loads(b).get("code") == 200, f"body={b[:100]}")
got = wait_task(815000201, "RUNNING")
expect("F3 planning 恢复：事件链推进任务 RUNNING", got == "RUNNING", f"status={got}")

# ================= F4: stop rabbitmq =================
log("== F4 rabbitmq 故障注入 ==")
subprocess.run(["docker", "stop", "product-rabbitmq"], check=True, capture_output=True)
log("  >> docker stop product-rabbitmq")
time.sleep(2)
st, _, b = raw("POST", GW + "/execute/event/start/815000202", AUTH)
expect("F4 rabbitmq 停机：execution 命令仍成功（本地事务+outbox）",
       st == 200 and json.loads(b).get("code") == 200, f"body={b[:100]}")
time.sleep(3)
pending = sql("SELECT COUNT(*) FROM execution_db.event_outbox WHERE status='PENDING'")
halted = sql("SELECT COUNT(*) FROM execution_db.event_outbox WHERE status='HALTED'")
running_at_down = sql("SELECT status FROM planning_db.operation_task WHERE task_id=815000202")
expect("F4 rabbitmq 停机：outbox 积压（PENDING/HALTED>0）且 planning 未推进",
       (int(pending) + int(halted)) >= 1 and running_at_down == "SCHEDULED",
       f"pending={pending} halted={halted} task={running_at_down}")
subprocess.run(["docker", "start", "product-rabbitmq"], check=True, capture_output=True)
log("  >> docker start product-rabbitmq")
deadline = time.time() + 90
while time.time() < deadline:
    st = sh(["docker", "inspect", "-f", "{{.State.Health.Status}}", "product-rabbitmq"])
    if st == "healthy":
        break
    time.sleep(3)
drained = False
deadline = time.time() + 90
got = None
while time.time() < deadline:
    pending = sql("SELECT COUNT(*) FROM execution_db.event_outbox WHERE status='PENDING'")
    halted = sql("SELECT COUNT(*) FROM execution_db.event_outbox WHERE status='HALTED'")
    got = sql("SELECT status FROM planning_db.operation_task WHERE task_id=815000202")
    if pending == "0" and halted == "0" and got == "RUNNING":
        drained = True
        break
    time.sleep(2)
expect("F4 rabbitmq 恢复：outbox 排空 + 消费收敛（任务 RUNNING）", drained,
       f"pending={pending} halted={halted} task={got}")

# 探针任务全部完工（恢复收敛后再走一遍事件链）
for pb in probe["batches"]:
    for psq in (1, 2, 3):
        tid = pb * 100 + psq
        stt = sql(f"SELECT status FROM planning_db.operation_task WHERE task_id={tid}")
        if stt != "DONE":
            if stt in ("READY", "SCHEDULED", "PAUSED"):
                st, _, rb = raw("POST", GW + f"/execute/event/start/{tid}", AUTH)
            st, _, rb = raw("POST", GW + f"/execute/event/complete/{tid}", AUTH)
            expect(f"探针任务 {tid} complete", st == 200 and json.loads(rb).get("code") == 200, f"body={rb[:80]}")
deadline = time.time() + 60
while time.time() < deadline:
    done = sql("SELECT COUNT(*) FROM planning_db.operation_task WHERE batch_id BETWEEN 8150001 AND 8150003 AND status='DONE'")
    if done == "9":
        break
    time.sleep(1)
expect("全部 9 个探针任务收敛 DONE", done == "9", f"done={done}")

# ================= 清理探针行 + 终态对账 =================
log("== 清理探针行（三库） ==")
# 先以子查询删除消费去重行（跨库子查询合法；必须先于 outbox 行删除）
sql("DELETE FROM planning_db.consumed_event WHERE event_id IN "
    "(SELECT event_id FROM execution_db.event_outbox WHERE CAST(aggregate_id AS UNSIGNED) BETWEEN 815000000 AND 815099999)")
sql("DELETE FROM demand_db.consumed_event WHERE event_id IN "
    "(SELECT event_id FROM planning_db.event_outbox WHERE CAST(aggregate_id AS UNSIGNED) BETWEEN 815000000 AND 815099999)")
sql("DELETE FROM execution_db.event_outbox WHERE CAST(aggregate_id AS UNSIGNED) BETWEEN 815000000 AND 815099999")
sql("DELETE FROM planning_db.event_outbox WHERE CAST(aggregate_id AS UNSIGNED) BETWEEN 815000000 AND 815099999")
sql("DELETE FROM execution_db.task_event WHERE task_id BETWEEN 815000000 AND 815099999")
sql("DELETE FROM planning_db.task_assignment_resource WHERE task_id BETWEEN 815000000 AND 815099999")
sql("DELETE FROM planning_db.task_assignment WHERE task_id BETWEEN 815000000 AND 815099999")
sql("DELETE FROM planning_db.task_resource_requirement WHERE task_id BETWEEN 815000000 AND 815099999")
sql("DELETE FROM planning_db.task_dependency WHERE pre_task_id BETWEEN 815000000 AND 815099999 OR post_task_id BETWEEN 815000000 AND 815099999")
sql("DELETE FROM planning_db.operation_task WHERE task_id BETWEEN 815000000 AND 815099999")
sql("DELETE FROM planning_db.production_batch WHERE batch_id BETWEEN 8150000 AND 8150099")
sql("DELETE FROM demand_db.planning_batch_state WHERE batch_id BETWEEN 8150000 AND 8150099")
log("  probe rows cleaned (planning/execution/demand)")

time.sleep(12)  # 等本域对账自愈周期
log("== 终态对账（recon.py report） ==")
recon = subprocess.run(["python3", f"{P6.rsplit('/phase6', 1)[0]}/phase5/recon.py", "report"],
                       capture_output=True, text=True)
report = json.loads(recon.stdout)
expect("故障注入后终态对账 drifts=[]", len(report["drifts"]) == 0,
       json.dumps(report["drifts"], ensure_ascii=False)[:300])
expect("故障注入后 outbox 全部收敛（无 PENDING/HALTED）",
       all(v["outbox_pending"] == "0" and v["outbox_halted"] == "0" for v in report["health"].values()),
       json.dumps(report["health"]))

open(f"{P6}/fault_injection_transcript.txt", "w").write("\n".join(OUT))
print(f"FAULT INJECTION: ALL PASS ({len(OUT)-1} checks)")
