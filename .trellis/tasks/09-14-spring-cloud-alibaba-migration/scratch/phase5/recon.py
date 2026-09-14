#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Phase 5 三域状态对账工具（ADR-0004 §6）：差异报告 + 补偿动作 + 人工重放。

跨域不变式核查（服务内定时对账只覆盖本域不变式；跨域事实由此工具核对）：
  P1 planning: batch.status = aggregate(operation_task.status)（本地）
  D1 demand:   line.status = aggregate(planning_batch_state.status)（本地投影）
  D2 demand:   order.status = aggregate(order_line.status)（本地）
  X1 跨域:     planning.production_batch.status == demand.planning_batch_state.status
  X2 跨域:     execution.task_event 最新目标状态 == planning.operation_task.status
               （仅当 execution outbox 无 PENDING/HALTED——事件链已收敛时才判漂移）
  X3 跨域:     order_line.allocated_qty == SUM(production_batch.batch_qty)（Phase 4 遗留
               拆批补偿窗口的巡检面）
  H  健康:     三域 outbox PENDING/HALTED 计数 + dead_letter_audit 未重放计数

补偿动作（全部走服务 ops 端点，服务端 ops_audit 留痕）：
  heal batch <batchId>           -> POST /internal/planning/ops/recompute-batch-status
  heal line <orderLineId>        -> POST /internal/demand/ops/recompute-line-status
  heal order <orderId>           -> POST /internal/demand/ops/recompute-order-status
  heal allocation <lineId> <qty> -> POST /internal/demand/ops/adjust-allocation
  replay outbox <domain> <id>    -> POST /internal/<domain>/ops/replay-outbox
  replay dlq <domain> <id>       -> POST /internal/<domain>/ops/replay-dlq
  report                         -> 全量差异报告（不修改任何状态）

用法: python3 recon.py report|heal ...|replay ...
"""
import json
import subprocess
import sys
import urllib.error
import urllib.request

IDENTITY = "http://127.0.0.1:8101"
PORTS = {"planning": 8104, "demand": 8103, "execution": 8105}
SCRATCH = "/home/lenny/Projects/pps/product/.trellis/tasks/09-14-spring-cloud-alibaba-migration/scratch/phase5"

# 冻结的目标状态映射（与单体 TaskEventServiceImpl 的命令->状态映射一致）
EVENT_TARGET = {"START": "RUNNING", "RESUME": "RUNNING", "PAUSE": "PAUSED", "FINISH": "DONE"}


def sql(db, statement):
    proc = subprocess.run(
        ["docker", "exec", "product-mysql", "mysql", "-uroot", "-p123456", "--batch", "--skip-column-names", db, "-e", statement],
        capture_output=True, text=True)
    if proc.returncode != 0:
        raise RuntimeError(f"SQL failed on {db}: {proc.stderr}")
    return [line.split("\t") for line in proc.stdout.strip().splitlines() if line]


def http(method, url, body=None, token=None):
    req = urllib.request.Request(url, method=method)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    data = json.dumps(body).encode() if body is not None else None
    try:
        with urllib.request.urlopen(req, data=data, timeout=10) as resp:
            return resp.status, json.loads(resp.read().decode())
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read().decode() or "{}")


def login():
    uuid = "phase5-recon-uuid"
    subprocess.run(["docker", "exec", "product-redis", "redis-cli", "-a", "123456", "--no-auth-warning",
                    "SET", f"identity:captcha_codes:{uuid}", '"3529"', "EX", "300"],
                   capture_output=True, text=True, check=True)
    status, body = http("POST", IDENTITY + "/login",
                        {"username": "admin", "password": "admin123", "code": "3529", "uuid": uuid})
    assert status == 200 and body.get("code") == 200, f"identity login failed: {body}"
    return body["token"]


# ---------------- 聚合规则（与 BatchStatusResolver/DemandStatusResolvers 冻结规则一致） ----------------

def resolve_batch(statuses, current):
    if statuses and all(s == "DONE" for s in statuses):
        return "DONE"
    if any(s in ("RUNNING", "PAUSED", "DONE") for s in statuses):
        return "IN_PROCESS"
    if any(s in ("SCHEDULED", "READY") for s in statuses):
        return "RELEASED"
    return current


def resolve_line(statuses, current):
    if statuses and all(s == "DONE" for s in statuses):
        return "DONE"
    if any(s in ("IN_PROCESS", "DONE") for s in statuses):
        return "IN_PRODUCTION"
    if any(s in ("RELEASED", "PLANNED") for s in statuses):
        return "RELEASED"
    return current


def resolve_order(statuses, current):
    if statuses and all(s == "DONE" for s in statuses):
        return "DONE"
    if any(s in ("IN_PRODUCTION", "DONE") for s in statuses):
        return "IN_PRODUCTION"
    if any(s == "RELEASED" for s in statuses):
        return "CONFIRMED"
    return current


def check(report, code, desc, expected, actual, drifts):
    ok = expected == actual
    report.append({"check": code, "desc": desc, "expected": expected, "actual": actual, "drift": not ok})
    if not ok:
        drifts.append((code, desc, expected, actual))


def recon_report():
    report, drifts = [], []

    # ---- planning 本域（P1）----
    batches = {r[0]: r[1] for r in sql("planning_db",
              "SELECT batch_id, IFNULL(status,'') FROM production_batch")}
    for batch_id, status in batches.items():
        ts = [r[0] for r in sql("planning_db",
              f"SELECT IFNULL(status,'') FROM operation_task WHERE batch_id={batch_id} AND status IS NOT NULL")]
        if ts:
            check(report, "P1", f"batch {batch_id} = aggregate(task)", resolve_batch(ts, status), status, drifts)

    # ---- demand 本域（D1/D2）----
    lines = {r[0]: r[1] for r in sql("demand_db", "SELECT order_line_id, IFNULL(status,'') FROM order_line")}
    proj = {}
    for batch_id, status in batches.items():
        proj[int(batch_id)] = status
    for r in sql("demand_db", "SELECT batch_id, IFNULL(status,''), order_line_id FROM planning_batch_state"):
        proj[int(r[0])] = r[1]
        if r[2]:
            bs = [x[0] for x in sql("demand_db",
                  f"SELECT IFNULL(status,'') FROM planning_batch_state WHERE order_line_id={r[2]} AND status IS NOT NULL")]
            if bs and r[2] in lines:
                check(report, "D1", f"line {r[2]} = aggregate(projection)",
                      resolve_line(bs, lines[r[2]]), lines[r[2]], drifts)
    orders = {r[0]: r[1] for r in sql("demand_db", "SELECT order_id, IFNULL(status,'') FROM customer_order")}
    for order_id, status in orders.items():
        ls = [x[0] for x in sql("demand_db",
              f"SELECT IFNULL(status,'') FROM order_line WHERE order_id={order_id} AND status IS NOT NULL")]
        if ls:
            check(report, "D2", f"order {order_id} = aggregate(line)", resolve_order(ls, status), status, drifts)

    # ---- X1 跨域: planning 批次 vs demand 投影 ----
    for batch_id, status in batches.items():
        p = proj.get(int(batch_id))
        if p is not None:
            check(report, "X1", f"batch {batch_id} planning={status} vs demand projection", status, p, drifts)

    # ---- X2 跨域: 任务事件流水 vs planning 终态（仅 outbox 收敛时判漂移）----
    pending = sql("execution_db", "SELECT COUNT(*) FROM event_outbox WHERE status IN ('PENDING','HALTED')")[0][0][0]
    converged = pending == "0"
    events = sql("execution_db", f"""
        SELECT t.task_id,
               SUBSTRING_INDEX(GROUP_CONCAT(t.event_type ORDER BY t.event_id DESC), ',', 1)
        FROM task_event t GROUP BY t.task_id""")
    for task_id, latest_type in events:
        target = EVENT_TARGET.get(latest_type)
        cur = sql("planning_db", f"SELECT IFNULL(status,'') FROM operation_task WHERE task_id={task_id}")
        if target and cur:
            actual = cur[0][0]
            if converged:
                check(report, "X2", f"task {task_id} (latest {latest_type})", target, actual, drifts)
            else:
                # 事件在途：仅记录参考信息，不判漂移（异步链允许滞后窗口）
                report.append({"check": "X2", "desc": f"task {task_id} (latest {latest_type}, in-flight)",
                               "expected": target, "actual": actual, "drift": False})

    # ---- X3 分配漂移（Phase 4 遗留补偿窗口巡检）----
    for line_id in lines:
        alloc = sql("demand_db", f"SELECT IFNULL(allocated_qty,0) FROM order_line WHERE order_line_id={line_id}")[0][0]
        planned = sql("planning_db", f"SELECT IFNULL(SUM(batch_qty),0) FROM production_batch WHERE order_line_id={line_id}")[0][0]
        if planned != "0":
            check(report, "X3", f"line {line_id} allocated_qty vs SUM(batch_qty)", planned, alloc, drifts)

    # ---- H 健康 ----
    health = {}
    for db in ("execution_db", "planning_db", "demand_db"):
        health[db] = {
            "outbox_pending": sql(db, "SELECT COUNT(*) FROM event_outbox WHERE status='PENDING'")[0][0][0],
            "outbox_halted": sql(db, "SELECT COUNT(*) FROM event_outbox WHERE status='HALTED'")[0][0][0],
            "dlq_unreplayed": sql(db, "SELECT COUNT(*) FROM dead_letter_audit WHERE replayed=0")[0][0][0],
        }

    return {"health": health, "checks": report,
            "drifts": [{"check": c, "desc": d, "expected": e, "actual": a} for c, d, e, a in drifts]}


def main():
    args = sys.argv[1:]
    if not args or args[0] == "report":
        result = recon_report()
        print(json.dumps(result, ensure_ascii=False, indent=1))
        json.dump(result, open(f"{SCRATCH}/recon_report.json", "w"), ensure_ascii=False, indent=1)
        return
    token = login()
    cmd = args[0]
    if cmd == "heal":
        target = args[1]
        if target == "batch":
            status, body = http("POST", f"http://127.0.0.1:{PORTS['planning']}/internal/planning/ops/recompute-batch-status",
                                {"batchId": int(args[2])}, token)
        elif target == "line":
            status, body = http("POST", f"http://127.0.0.1:{PORTS['demand']}/internal/demand/ops/recompute-line-status",
                                {"orderLineId": int(args[2])}, token)
        elif target == "order":
            status, body = http("POST", f"http://127.0.0.1:{PORTS['demand']}/internal/demand/ops/recompute-order-status",
                                {"orderId": int(args[2])}, token)
        elif target == "allocation":
            status, body = http("POST", f"http://127.0.0.1:{PORTS['demand']}/internal/demand/ops/adjust-allocation",
                                {"orderLineId": int(args[2]), "newAllocatedQty": int(args[3])}, token)
        else:
            print(f"unknown heal target: {target}")
            return
        print(json.dumps(body, ensure_ascii=False))
    elif cmd == "replay":
        kind, domain, row_id = args[1], args[2], int(args[3])
        if kind == "outbox":
            status, body = http("POST", f"http://127.0.0.1:{PORTS[domain]}/internal/{domain}/ops/replay-outbox",
                                {"outboxId": row_id}, token)
        elif kind == "dlq":
            status, body = http("POST", f"http://127.0.0.1:{PORTS[domain]}/internal/{domain}/ops/replay-dlq",
                                {"auditId": row_id}, token)
        else:
            print(f"unknown replay kind: {kind}")
            return
        print(json.dumps(body, ensure_ascii=False))
    else:
        print(f"unknown command: {cmd}")


if __name__ == "__main__":
    main()
