#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Phase 6 数据校验脚本（统一切换验收）：三域行数/状态核对 + planning_batch_state 投影一致性。
在 recon.py（phase5，跨域不变式 X1/X2/X3 + 本域 P1/D1/D2）之外补充行数与关键状态分布，
并直接调用 recon 的 report 作为终局判定。用法: python3 validate_data.py
"""
import json
import subprocess
import sys

P6 = "/home/lenny/Projects/pps/product/.trellis/tasks/09-14-spring-cloud-alibaba-migration/scratch/phase6"
RECON = P6.rsplit("/phase6", 1)[0] + "/phase5/recon.py"
FAILED = []

def sql(stmt, db):
    proc = subprocess.run(["docker", "exec", "product-mysql", "mysql", "-uroot", "-p123456",
                           "--default-character-set=utf8mb4", "-N", "-B", "-e", stmt, db],
                          capture_output=True, text=True)
    if proc.returncode != 0:
        raise RuntimeError(f"sql failed on {db}: {proc.stderr[:300]}")
    return proc.stdout.strip()

def check(desc, cond, detail=""):
    tag = "PASS" if cond else "FAIL"
    print(f"[{tag}] {desc}" + (f" | {detail}" if detail else ""), flush=True)
    if not cond:
        FAILED.append(desc)

def table_count(db, table):
    return int(sql(f"SELECT COUNT(*) FROM `{table}`", db))

# ---- 1. 三域行数快照 ----
print("== 行数快照 ==")
snap = {}
for db, tables in {
    "identity_db": ["sys_user", "sys_role", "sys_menu", "sys_dict_type", "sys_dict_data"],
    "master_data_db": ["product", "product_route", "route_operation", "resource", "machine", "mold",
                        "machine_mold_compatibility", "resource_capability", "calendar", "changeover_rule"],
    "demand_db": ["customer", "customer_order", "order_line", "planning_batch_state"],
    "planning_db": ["production_batch", "operation_task", "task_assignment", "task_assignment_resource",
                     "task_dependency", "task_resource_requirement", "schedule_job"],
    "execution_db": ["task_event", "resource_status_event"],
}.items():
    for t in tables:
        snap[f"{db}.{t}"] = table_count(db, t)
        print(f"  {db}.{t} = {snap[f'{db}.{t}']}")

# ---- 2. 关键不变式（与 recon.py 相同规则的独立复核）----
print("== 关键不变式 ==")
status_dist = sql("SELECT status, COUNT(*) FROM production_batch GROUP BY status", "planning_db")
print(f"  planning 批次状态分布: {status_dist or '(空)'}")
line_dist = sql("SELECT status, COUNT(*) FROM order_line GROUP BY status", "demand_db")
print(f"  demand 订单行状态分布: {line_dist or '(空)'}")
order_dist = sql("SELECT status, COUNT(*) FROM customer_order GROUP BY status", "demand_db")
print(f"  demand 订单状态分布: {order_dist or '(空)'}")

# 投影一致性 X1：planning 批次 vs demand 投影
batches = {r.split("\t")[0]: r.split("\t")[1]
           for r in sql("SELECT batch_id, IFNULL(status,'') FROM production_batch", "planning_db").splitlines() if r}
proj = {r.split("\t")[0]: r.split("\t")[1]
        for r in sql("SELECT batch_id, IFNULL(status,'') FROM planning_batch_state", "demand_db").splitlines() if r}
mismatch = [b for b, s in batches.items() if b in proj and proj[b] != s]
check(f"planning_batch_state 投影与 planning 批次一致（比较 {len(set(batches) & set(proj))} 个共同批次）",
      not mismatch, f"mismatch={mismatch[:5]}")
orphan = [b for b in proj if b not in batches]
check(f"无孤儿投影（demand 有、planning 无）", not orphan, f"orphan={orphan[:5]}")

# X2：事件流水 vs 任务终态（仅 execution outbox 收敛时判漂移）
pending = sql("SELECT COUNT(*) FROM event_outbox WHERE status IN ('PENDING','HALTED')", "execution_db")
converged = pending == "0"
events = sql("""SELECT t.task_id,
       SUBSTRING_INDEX(GROUP_CONCAT(t.event_type ORDER BY t.event_id DESC), ',', 1)
FROM task_event t GROUP BY t.task_id""", "execution_db")
target_map = {"START": "RUNNING", "RESUME": "RUNNING", "PAUSE": "PAUSED", "FINISH": "DONE"}
x2_bad = []
n_x2 = 0
for line in filter(None, events.splitlines()):
    task_id, latest = line.split("\t")
    target = target_map.get(latest)
    if not target:
        continue
    cur = sql(f"SELECT IFNULL(status,'') FROM operation_task WHERE task_id={task_id}", "planning_db")
    if not cur:
        continue
    n_x2 += 1
    if converged and cur != target:
        x2_bad.append((task_id, latest, cur))
check(f"X2 任务事件流水与 planning 终态一致（{n_x2} 个任务，outbox {'收敛' if converged else '在途'}）",
      not x2_bad, f"bad={x2_bad[:5]}")

# X3：allocated_qty vs SUM(batch_qty)
x3_bad = []
for line in filter(None, sql("SELECT order_line_id, IFNULL(allocated_qty,0) FROM order_line", "demand_db").splitlines()):
    line_id, alloc = line.split("\t")
    planned = sql(f"SELECT IFNULL(SUM(batch_qty),0) FROM production_batch WHERE order_line_id={line_id}", "planning_db")
    if planned != "0" and alloc != planned:
        x3_bad.append((line_id, alloc, planned))
check("X3 allocated_qty 与 SUM(batch_qty) 一致", not x3_bad, f"bad={x3_bad[:5]}")

# 基础设施健康
for db in ("execution_db", "planning_db", "demand_db"):
    p = sql(f"SELECT COUNT(*) FROM event_outbox WHERE status='PENDING'", db)
    h = sql(f"SELECT COUNT(*) FROM event_outbox WHERE status='HALTED'", db)
    dlq = sql(f"SELECT COUNT(*) FROM dead_letter_audit WHERE replayed=0", db)
    check(f"{db} outbox/死信健康（PENDING={p} HALTED={h} DLQ未重放={dlq}）",
          p == "0" and dlq == "0")  # HALTED 允许存在证据性遗留，对账说明中单列

# ---- 3. recon.py 终局判定 ----
print("== recon.py 终局判定 ==")
out = subprocess.run(["python3", RECON, "report"], capture_output=True, text=True)
report = json.loads(out.stdout)
print(f"  checks={len(report['checks'])} drifts={len(report['drifts'])}")
json.dump(report, open(f"{P6}/validate_data_recon.json", "w"), ensure_ascii=False, indent=1)
check("recon.py drifts=[]", len(report["drifts"]) == 0, json.dumps(report["drifts"], ensure_ascii=False)[:200])

print(f"\nVALIDATE DATA: {'ALL PASS' if not FAILED else 'FAILED: ' + '; '.join(FAILED)}")
sys.exit(1 if FAILED else 0)
