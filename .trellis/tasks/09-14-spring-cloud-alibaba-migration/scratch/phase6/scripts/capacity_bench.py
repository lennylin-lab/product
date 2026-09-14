#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Phase 6 容量基准（轻量）：200 产品 + 50 订单（100 订单行 -> 100 批次 -> 300 工序任务），
经网关触发 generateTask + 异步排程，记录排程耗时与 planning 进程内存。
不设硬门槛，只留数据。资源池：20 机台/20 模具/10 人员/5 工位。
"""
import json
import subprocess
import sys
import time
import urllib.error
import urllib.request

GW = "http://127.0.0.1:8080"
P6 = "/home/lenny/Projects/pps/product/.trellis/tasks/09-14-spring-cloud-alibaba-migration/scratch/phase6"
OUT = []

def log(s):
    print(s, flush=True)
    OUT.append(s)

def sh(args):
    return subprocess.check_output(args).decode().strip()

def sql(stmt, db):
    # 大语句经 stdin 传入（命令行参数超长会触发 E2BIG）
    proc = subprocess.run(["docker", "exec", "-i", "product-mysql", "mysql", "-uroot", "-p123456",
                           "--default-character-set=utf8mb4", "-N", "-B", db],
                          input=stmt, capture_output=True, text=True)
    if proc.returncode != 0:
        raise RuntimeError(f"sql failed on {db}: {proc.stderr[:500]}")
    return proc.stdout.strip()

def raw(method, url, headers=None, body=None, timeout=60):
    req = urllib.request.Request(url, method=method)
    for k, v in (headers or {}).items():
        req.add_header(k, v)
    data = body.encode() if isinstance(body, str) else (json.dumps(body).encode() if body else None)
    try:
        with urllib.request.urlopen(req, data=data, timeout=timeout) as resp:
            return resp.status, resp.read().decode()
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode()

st, body = raw("GET", GW + "/captchaImage")
cap = json.loads(body)
code = sh(["docker", "exec", "product-redis", "redis-cli", "-a", "123456", "--no-auth-warning",
           "GET", f"identity:captcha_codes:{cap['uuid']}"]).strip().strip('"')
st, body = raw("POST", GW + "/login", {"Content-Type": "application/json"},
               json.dumps({"username": "admin", "password": "admin123", "code": code, "uuid": cap["uuid"]}))
TOKEN = json.loads(body)["token"]
AUTH = {"Authorization": "Bearer " + TOKEN, "Content-Type": "application/json"}

def planning_pid():
    return sh(["bash", "-c", "ss -tlnp | grep ':8104 ' | grep -oP 'pid=\\d+' | head -1 | cut -d= -f2"])

def rss_mb(pid):
    out = sh(["bash", "-c", f"grep VmRSS /proc/{pid}/status"]).split()[1]
    return int(out) // 1024

# ---- 1. 清理上一轮基准数据（幂等） ----
log("== 准备：清理旧基准数据 ==")
BATCH_RANGE = "BATCH_ID_RANGE"
def clean_bench():
    # 任务/派工/需求/依赖为 generateTask 生成的雪花 ID：一律经 batch_id 关联删除（跨库同实例）
    sql("DELETE FROM planning_db.task_assignment_resource WHERE task_id IN "
        "(SELECT task_id FROM planning_db.operation_task WHERE batch_id BETWEEN 91000000 AND 91099999)", "master_data_db")
    sql("DELETE FROM planning_db.task_assignment WHERE task_id IN "
        "(SELECT task_id FROM planning_db.operation_task WHERE batch_id BETWEEN 91000000 AND 91099999)", "master_data_db")
    sql("DELETE FROM planning_db.task_resource_requirement WHERE task_id IN "
        "(SELECT task_id FROM planning_db.operation_task WHERE batch_id BETWEEN 91000000 AND 91099999)", "master_data_db")
    sql("DELETE FROM planning_db.task_dependency WHERE pre_task_id IN "
        "(SELECT task_id FROM planning_db.operation_task WHERE batch_id BETWEEN 91000000 AND 91099999) "
        "OR post_task_id IN (SELECT task_id FROM planning_db.operation_task WHERE batch_id BETWEEN 91000000 AND 91099999)", "master_data_db")
    sql("DELETE FROM planning_db.operation_task WHERE batch_id BETWEEN 91000000 AND 91099999", "master_data_db")
    sql("DELETE FROM planning_db.production_batch WHERE batch_id BETWEEN 91000000 AND 91099999", "master_data_db")
    sql("DELETE FROM demand_db.order_line WHERE order_line_id BETWEEN 91000000 AND 91099999", "demand_db")
    sql("DELETE FROM demand_db.customer_order WHERE order_id BETWEEN 91000000 AND 91099999", "demand_db")
    sql("DELETE FROM demand_db.planning_batch_state WHERE batch_id BETWEEN 91000000 AND 91099999", "demand_db")
    sql("DELETE FROM master_data_db.product_mold_param WHERE product_id BETWEEN 91000000 AND 91099999", "master_data_db")
    sql("DELETE FROM master_data_db.route_operation WHERE route_id BETWEEN 91000000 AND 91099999", "master_data_db")
    sql("DELETE FROM master_data_db.product_route WHERE route_id BETWEEN 91000000 AND 91099999 OR product_id BETWEEN 91000000 AND 91099999", "master_data_db")
    sql("DELETE FROM master_data_db.product WHERE product_id BETWEEN 91000000 AND 91099999", "master_data_db")
    sql("DELETE FROM master_data_db.resource_capability WHERE product_id BETWEEN 91000000 AND 91099999", "master_data_db")
clean_bench()
log("  cleaned")

# ---- 2. 资源池（20 机台/20 模具/10 人/5 工位 + 兼容矩阵 + 换型规则） ----
log("== 准备：资源池 ==")
res, mac, mold, compat, person = [], [], [], [], []
for i in range(20):
    rid, mid = 92000000 + i, 92100000 + i
    res.append(f"('{rid}','MACHINE','压机{i}','AVAILABLE',93000000,NOW())")
    mac.append(f"('{rid}', 150 + {i}, 20)")
for i in range(20):
    rid, mid = 92200000 + i, 92300000 + i
    res.append(f"('{rid}','MOLD','模具{i}','AVAILABLE',93000000,NOW())")
    mold.append(f"('{mid}','BENCH{i}',4,'AVAILABLE')")
    for j in range(20):
        compat.append(f"('{92000000 + j}','{rid}',1)")
for i in range(10):
    res.append(f"('{92400000 + i}','PERSON','员工{i}','AVAILABLE',93000000,NOW())")
for i in range(5):
    res.append(f"('{92500000 + i}','WORKSTATION','工位{i}','AVAILABLE',93000000,NOW())")

sql(f"""
DELETE FROM master_data_db.resource WHERE resource_id BETWEEN 92000000 AND 92999999;
DELETE FROM master_data_db.machine WHERE machine_id BETWEEN 92000000 AND 92999999;
DELETE FROM master_data_db.mold WHERE mold_id BETWEEN 92300000 AND 92399999;
DELETE FROM master_data_db.machine_mold_compatibility WHERE machine_id BETWEEN 92000000 AND 92999999;
INSERT INTO master_data_db.calendar (calendar_id, calendar_name, workday_pattern, shift_start, shift_end, create_time)
VALUES (93000000, '两班制', 'Mon-Sat', '08:00', '20:00', NOW())
ON DUPLICATE KEY UPDATE shift_start='08:00', shift_end='20:00';
INSERT INTO master_data_db.changeover_rule (rule_id, same_mold_time_min, different_mold_time_min, material_change_extra_min, color_change_extra_min, create_time)
VALUES (93000001, 5, 30, 10, 5, NOW()) ON DUPLICATE KEY UPDATE same_mold_time_min=5;
INSERT INTO master_data_db.resource (resource_id, resource_type, name, status, calendar_id, create_time) VALUES
{",".join(res)};
INSERT INTO master_data_db.machine (machine_id, tonnage, default_setup_time_min) VALUES {",".join(mac)};
INSERT INTO master_data_db.mold (mold_id, mold_code, cavity, mold_status) VALUES {",".join(mold)};
INSERT INTO master_data_db.machine_mold_compatibility (machine_id, mold_id, is_compatible) VALUES {",".join(compat)};
""", "master_data_db")
log("  resource pool ready")

# ---- 3. 200 产品（模具参数 + 3 工序启用路线） + 人员能力 ----
log("== 准备：200 产品 ==")
prod, mparam, route, rop, cap = [], [], [], [], []
for i in range(200):
    pid = 91000000 + i
    prod.append(f"({pid},'基准产品{i}','MAT{i}','RED',NOW())")
    mparam.append(f"({pid}, 92300000 + ({i} MOD 20), 30.00, 2, 0.95, 0.90)")
    rid = 91000000 + i
    route.append(f"({rid}, {pid}, 'v1', 1, NOW())")
    for seq, (opc, rule, model) in enumerate(
            [("SETUP", "RULE_SETUP_MACHINE", "TM_SETUP_BASE"),
             ("INJECT", "RULE_INJECT_MACHINE", "TM_INJECT_A2"),
             ("POST_QC_PUTAWAY", "RULE_POST_WORKSTATION", "TM_POST_UNIT")], 1):
        rop.append(f"((91000000 + {i}) * 10 + {seq}, {rid}, '{opc}', {seq}, '{rule}', '{model}', 'FIFO')")
    for person_id in range(92400000, 92400010):
        for opc in ("SETUP", "INJECT", "POST_QC_PUTAWAY"):
            cap.append(f"('{person_id}','{opc}',{pid},1,1)")
sql(f"""
INSERT INTO master_data_db.product (product_id, product_name, material_code, color_code, create_time) VALUES {",".join(prod)};
INSERT INTO master_data_db.product_mold_param (product_id, mold_id, cycle_time_sec, cavity, yield_rate, utilization) VALUES {",".join(mparam)};
INSERT INTO master_data_db.product_route (route_id, product_id, version, is_active, create_time) VALUES {",".join(route)};
INSERT INTO master_data_db.route_operation (op_id, route_id, op_code, sequence, eligible_resource_rule, std_time_model, queue_policy) VALUES {",".join(rop)};
INSERT INTO master_data_db.resource_capability (resource_id, op_code, product_id, is_enabled, priority_weight) VALUES {",".join(cap)};
""", "master_data_db")
log(f"  products=200 routes=200 ops=600 capabilities={len(cap)}")

# ---- 4. 50 订单 x 2 行 + 100 批次（RELEASED, SQL 种子；数量预占用同步写） ----
log("== 准备：50 订单 / 100 订单行 / 100 批次 ==")
orders, lines, batches = [], [], []
for k in range(50):
    oid = 91000000 + k
    orders.append(f"({oid}, 1, '2026-12-01 00:00:00', {1 + (k % 9)}, 'CONFIRMED', NOW())")
    for m in range(2):
        lid = 91000000 + k * 2 + m
        pid = 91000000 + ((k * 2 + m) % 200)
        lines.append(f"({lid}, {oid}, {pid}, 40, 40, 'RELEASED')")
        bid = 91000000 + k * 2 + m
        batches.append(f"({bid}, {lid}, 40, 'RELEASED', NOW())")
sql(f"""
INSERT INTO demand_db.customer_order (order_id, customer_id, due_date, priority, status, create_time) VALUES {",".join(orders)};
INSERT INTO demand_db.order_line (order_line_id, order_id, product_id, qty, allocated_qty, status) VALUES {",".join(lines)};
INSERT INTO planning_db.production_batch (batch_id, order_line_id, batch_qty, status, create_time) VALUES {",".join(batches)};
""", "master_data_db")
log(f"  orders=50 lines=100 batches=100")

# ---- 5. generateTask（经网关，1 次调用 100 批次） ----
log("== generateTask ==")
t0 = time.time()
batch_ids = [91000000 + i for i in range(100)]
st, body = raw("POST", GW + "/pps/batch/generateTask", AUTH, json.dumps(batch_ids), timeout=120)
r = json.loads(body)
gt_wall = time.time() - t0
log(f"  generateTask http={st} code={r.get('code')} errors={len(r.get('errors') or [])} wall={gt_wall:.1f}s")
tasks = int(sql("SELECT COUNT(*) FROM planning_db.operation_task WHERE batch_id BETWEEN 91000000 AND 91099999", "planning_db"))
log(f"  tasks generated: {tasks}")

# ---- 6. 异步排程（计时 + 内存） ----
log("== scheduleAllAsync ==")
PID = planning_pid()
rss_before = rss_mb(PID)
t0 = time.time()
st, body = raw("POST", GW + "/pps/assignment/scheduleAllAsync", AUTH,
               json.dumps({"assignmentStart": "2026-09-21 08:00:00", "scheduleStrategy": "EARLIEST_START"}))
r = json.loads(body)
job_id = r.get("data")
submit_wall = time.time() - t0
job = {}
while time.time() - t0 < 600:
    st, body = raw("GET", GW + f"/pps/assignment/scheduleJob/{job_id}", AUTH)
    job = json.loads(body).get("data") or {}
    if job.get("status") in ("SUCCESS", "FAILED"):
        break
    time.sleep(1)
total_wall = time.time() - t0
time.sleep(3)
rss_after = rss_mb(PID)
log(f"  submit http={st} jobId={job_id} submit_wall={submit_wall:.2f}s")
log(f"  job status={job.get('status')} tasks={job.get('totalTaskCount')} err={job.get('errorMessage')}")
log(f"  TOTAL wall (submit->terminal) = {total_wall:.1f}s")
log(f"  planning RSS before={rss_before}MB after={rss_after}MB (delta={rss_after - rss_before}MB)")
sched_line = sh(["bash", "-c",
                 f"grep 'scheduleAll completed' {P6}/logs/planning.console.log | tail -1"])
log(f"  planning log: {sched_line.strip()}")
assigns = int(sql("SELECT COUNT(*) FROM planning_db.task_assignment a JOIN planning_db.operation_task t ON a.task_id=t.task_id WHERE t.batch_id BETWEEN 91000000 AND 91099999", "planning_db"))
log(f"  assignments persisted: {assigns}")

# ---- 7. 清理基准数据 ----
log("== 清理基准数据 ==")
clean_bench()
sql("DELETE FROM master_data_db.resource WHERE resource_id BETWEEN 92000000 AND 92999999", "master_data_db")
sql("DELETE FROM master_data_db.machine WHERE machine_id BETWEEN 92000000 AND 92999999", "master_data_db")
sql("DELETE FROM master_data_db.mold WHERE mold_id BETWEEN 92300000 AND 92399999", "master_data_db")
sql("DELETE FROM master_data_db.machine_mold_compatibility WHERE machine_id BETWEEN 92000000 AND 92999999", "master_data_db")
log("  cleaned")
open(f"{P6}/capacity_bench_transcript.txt", "w").write("\n".join(OUT))
print("CAPACITY BENCH DONE")
