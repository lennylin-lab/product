#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Phase 5 状态对拍: 单体进程内刷新链 vs 微服务事件链。

同一数据集（订单 9701 / 订单行 9711 / 批次 9721 / 任务 9731+9732）分别落在
  - 单体: product 库（临时单体实例 8082，/execute/event/* 进程内同步刷新链）
  - 微服务: planning_db/demand_db/execution_db（execution 8105 命令 -> RabbitMQ 事件链）
执行同一事件序列（start/pause/resume/complete + 异常路径），每步后对五类状态
(task1, task2, batch, line, order) 逐字段快照比对，并比对 task_event 行与失败响应体。

用法: python3 parity_states.py mono|svc|compare
"""
import json
import subprocess
import sys
import time
import urllib.error
import urllib.request

MONO = "http://127.0.0.1:8082"
EXEC_DIR = "http://127.0.0.1:8105"
SCRATCH = "/home/lenny/Projects/pps/product/.trellis/tasks/09-14-spring-cloud-alibaba-migration/scratch/phase5"
# 单体侧库名：默认 clean-room 基线库（root schema.sql 原样初始化，BIGINT ID）。
# 用户 live 库 product 的 varchar ID 存量数据（如 'O1'）使单体刷新链触发
# "Truncated incorrect DOUBLE value" 数据依赖缺陷，不能作为对拍基线（差异已记录）。
import os
MONO_DB = os.environ.get("MONO_DB", "product_phase5_mono")

ORDER_ID, LINE_ID, BATCH_ID = 9701, 9711, 9721
TASK1, TASK2 = 9731, 9732
MACHINE_ID = 9741
GHOST_TASK = 999999

# (action, taskId, 期望日志标记)
SEQUENCE = [
    ("start", TASK1),
    ("pause", TASK1),
    ("resume", TASK1),
    ("start", TASK2),
    ("complete", TASK2),
    ("complete", TASK1),
    ("start", GHOST_TASK),      # 异常路径: 任务不存在 -> 命令失败
    ("pause", TASK1),           # 异常路径: DONE -> PAUSED（无条件映射，冻结语义）
    ("resume", TASK1),
    ("complete", TASK1),
]


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


def login_mono():
    # 验证码: monolith validateCaptcha 读 redis key captcha_codes:<uuid>（Jackson String 序列化带引号）
    uuid = "phase5-parity-uuid"
    subprocess.run(["docker", "exec", "product-redis", "redis-cli", "-a", "123456", "--no-auth-warning",
                    "SET", f"captcha_codes:{uuid}", '"3527"', "EX", "300"],
                   capture_output=True, text=True, check=True)
    status, body = http("POST", MONO + "/login",
                        {"username": "admin", "password": "admin123", "code": "3527", "uuid": uuid})
    assert status == 200 and body.get("code") == 200, f"monolith login failed: {body}"
    return body["token"]  # 单体契约: AjaxResult 顶层 token 键


def login_svc():
    # identity 服务直连登录（服务端本地验签；execution 8105 直连必须携带有效签名 token）。
    # identity 与单体同契约：验证码必验（Redis identity:captcha_codes:<uuid>），AjaxResult 顶层 token。
    uuid = "phase5-svc-uuid"
    subprocess.run(["docker", "exec", "product-redis", "redis-cli", "-a", "123456", "--no-auth-warning",
                    "SET", f"identity:captcha_codes:{uuid}", '"3528"', "EX", "300"],
                   capture_output=True, text=True, check=True)
    status, body = http("POST", "http://127.0.0.1:8101/login",
                        {"username": "admin", "password": "admin123", "code": "3528", "uuid": uuid})
    assert status == 200 and body.get("code") == 200, f"identity login failed: {body}"
    return body["token"]


COMMON_ROWS = f"""
INSERT INTO customer_order (order_id, customer_id, due_date, priority, status, create_time)
VALUES ({ORDER_ID}, 2, '2026-10-30 00:00:00', 5, 'CONFIRMED', NOW());
INSERT INTO order_line (order_line_id, order_id, product_id, qty, allocated_qty, status)
VALUES ({LINE_ID}, {ORDER_ID}, 1, 50, 50, 'RELEASED');
INSERT INTO production_batch (batch_id, order_line_id, batch_qty, status, planned_start, planned_end, create_time)
VALUES ({BATCH_ID}, {LINE_ID}, 50, 'RELEASED', '2026-09-16 08:00:00', '2026-09-18 20:00:00', NOW());
INSERT INTO operation_task (task_id, batch_id, op_code, sequence, std_duration_min, status, create_time) VALUES
 ({TASK1}, {BATCH_ID}, 'SETUP', 1, 60, 'SCHEDULED', NOW()),
 ({TASK2}, {BATCH_ID}, 'INJECT', 2, 120, 'SCHEDULED', NOW());
INSERT INTO task_assignment (task_id, machine_id, planned_start, planned_end, sequence_on_resource, create_time) VALUES
 ({TASK1}, {MACHINE_ID}, '2026-09-16 08:00:00', '2026-09-16 09:00:00', 1, NOW()),
 ({TASK2}, {MACHINE_ID}, '2026-09-16 09:00:00', '2026-09-16 11:00:00', 2, NOW());
"""


def seed_mono():
    # 单体 live 库 ID 列为 VARCHAR(64)（含非数字 ID 存量数据），必须字符串引号比较，避免隐式 cast 全表扫描报错
    sql(MONO_DB, f"""
DELETE FROM customer_order WHERE order_id='{ORDER_ID}';
DELETE FROM order_line WHERE order_id='{ORDER_ID}' AND order_line_id={LINE_ID};
DELETE FROM production_batch WHERE batch_id='{BATCH_ID}';
DELETE FROM operation_task WHERE task_id='{TASK1}' OR task_id='{TASK2}';
DELETE FROM task_assignment WHERE task_id='{TASK1}' OR task_id='{TASK2}';
DELETE FROM task_event WHERE task_id='{TASK1}' OR task_id='{TASK2}' OR task_id='{GHOST_TASK}';
INSERT INTO customer_order (order_id, customer_id, due_date, priority, status, create_time)
VALUES ('{ORDER_ID}', 2, '2026-10-30 00:00:00', 5, 'CONFIRMED', NOW());
INSERT INTO order_line (order_line_id, order_id, product_id, qty, allocated_qty, status)
VALUES ({LINE_ID}, '{ORDER_ID}', 1, 50, 50, 'RELEASED');
INSERT INTO production_batch (batch_id, order_line_id, batch_qty, status, planned_start, planned_end, create_time)
VALUES ('{BATCH_ID}', {LINE_ID}, 50, 'RELEASED', '2026-09-16 08:00:00', '2026-09-18 20:00:00', NOW());
INSERT INTO operation_task (task_id, batch_id, op_code, sequence, std_duration_min, status, create_time) VALUES
 ('{TASK1}', '{BATCH_ID}', 'SETUP', 1, 60, 'SCHEDULED', NOW()),
 ('{TASK2}', '{BATCH_ID}', 'INJECT', 2, 120, 'SCHEDULED', NOW());
INSERT INTO task_assignment (task_id, machine_id, planned_start, planned_end, sequence_on_resource, create_time) VALUES
 ('{TASK1}', '{MACHINE_ID}', '2026-09-16 08:00:00', '2026-09-16 09:00:00', 1, NOW()),
 ('{TASK2}', '{MACHINE_ID}', '2026-09-16 09:00:00', '2026-09-16 11:00:00', 2, NOW());
""")


def seed_svc():
    # planning_db: 批次/任务/派工 + 事件基础设施表清空（仅本对拍相关行）
    sql("planning_db", f"""
DELETE FROM production_batch WHERE batch_id={BATCH_ID};
DELETE FROM operation_task WHERE task_id IN ({TASK1},{TASK2});
DELETE FROM task_assignment WHERE task_id IN ({TASK1},{TASK2});
DELETE FROM event_outbox WHERE aggregate_id IN ('{BATCH_ID}','{TASK1}','{TASK2}');
DELETE FROM consumed_event WHERE aggregate_id IN ('{BATCH_ID}','{TASK1}','{TASK2}');
DELETE FROM dead_letter_audit;
DELETE FROM ops_audit;
INSERT INTO production_batch (batch_id, order_line_id, batch_qty, status, planned_start, planned_end, create_time)
VALUES ({BATCH_ID}, {LINE_ID}, 50, 'RELEASED', '2026-09-16 08:00:00', '2026-09-18 20:00:00', NOW());
INSERT INTO operation_task (task_id, batch_id, op_code, sequence, std_duration_min, status, create_time) VALUES
 ({TASK1}, {BATCH_ID}, 'SETUP', 1, 60, 'SCHEDULED', NOW()),
 ({TASK2}, {BATCH_ID}, 'INJECT', 2, 120, 'SCHEDULED', NOW());
INSERT INTO task_assignment (task_id, machine_id, planned_start, planned_end, sequence_on_resource, create_time) VALUES
 ({TASK1}, {MACHINE_ID}, '2026-09-16 08:00:00', '2026-09-16 09:00:00', 1, NOW()),
 ({TASK2}, {MACHINE_ID}, '2026-09-16 09:00:00', '2026-09-16 11:00:00', 2, NOW());
""")
    # demand_db: 订单/订单行（与单体行同 ID 同值）
    sql("demand_db", f"""
DELETE FROM customer_order WHERE order_id={ORDER_ID};
DELETE FROM order_line WHERE order_line_id={LINE_ID};
INSERT INTO customer_order (order_id, customer_id, due_date, priority, status, create_time)
VALUES ({ORDER_ID}, 2, '2026-10-30 00:00:00', 5, 'CONFIRMED', NOW());
INSERT INTO order_line (order_line_id, order_id, product_id, qty, allocated_qty, status)
VALUES ({LINE_ID}, {ORDER_ID}, 1, 50, 50, 'RELEASED');
DELETE FROM planning_batch_state WHERE batch_id={BATCH_ID};
DELETE FROM event_outbox WHERE aggregate_id IN ('{LINE_ID}','{ORDER_ID}','{BATCH_ID}');
DELETE FROM consumed_event WHERE aggregate_id IN ('{LINE_ID}','{ORDER_ID}','{BATCH_ID}');
DELETE FROM dead_letter_audit;
DELETE FROM ops_audit;
""")
    # execution_db: 事件行与出站表清空
    sql("execution_db", f"""
DELETE FROM task_event WHERE task_id IN ({TASK1},{TASK2},{GHOST_TASK});
DELETE FROM event_outbox WHERE aggregate_id IN ('{TASK1}','{TASK2}','{GHOST_TASK}');
DELETE FROM consumed_event WHERE aggregate_id IN ('{TASK1}','{TASK2}');
DELETE FROM dead_letter_audit;
DELETE FROM ops_audit;
""")


def snap_mono():
    q = f"""
SELECT CONCAT('task1=',IFNULL((SELECT status FROM operation_task WHERE task_id='{TASK1}'),'NONE'));
SELECT CONCAT('task2=',IFNULL((SELECT status FROM operation_task WHERE task_id='{TASK2}'),'NONE'));
SELECT CONCAT('batch=',IFNULL((SELECT status FROM production_batch WHERE batch_id='{BATCH_ID}'),'NONE'));
SELECT CONCAT('line=',IFNULL((SELECT status FROM order_line WHERE order_line_id={LINE_ID}),'NONE'));
SELECT CONCAT('order=',IFNULL((SELECT status FROM customer_order WHERE order_id='{ORDER_ID}'),'NONE'));
"""
    out = sql(MONO_DB, q)
    flat = [row[0] for row in out]
    states = dict(kv.split("=", 1) for kv in flat)
    events = sql(MONO_DB, f"SELECT event_type, IFNULL(resource_id,'NULL') FROM task_event WHERE task_id='{TASK1}' OR task_id='{TASK2}' ORDER BY event_id")
    states["events"] = events
    return states


def snap_svc():
    states = {}
    p = sql("planning_db", f"SELECT CONCAT('task1=',IFNULL((SELECT status FROM operation_task WHERE task_id={TASK1}),'NONE'),"
                           f"',task2=',IFNULL((SELECT status FROM operation_task WHERE task_id={TASK2}),'NONE'),"
                           f"',batch=',IFNULL((SELECT status FROM production_batch WHERE batch_id={BATCH_ID}),'NONE'))")
    for k, v in (kv.split("=", 1) for kv in p[0][0].split(",")):
        states[k] = v
    d = sql("demand_db", f"SELECT CONCAT('line=',IFNULL((SELECT status FROM order_line WHERE order_line_id={LINE_ID}),'NONE'),"
                         f"',order=',IFNULL((SELECT status FROM customer_order WHERE order_id={ORDER_ID}),'NONE'))")
    for k, v in (kv.split("=", 1) for kv in d[0][0].split(",")):
        states[k] = v
    events = sql("execution_db", f"SELECT event_type, IFNULL(resource_id,'NULL') FROM task_event WHERE task_id IN ({TASK1},{TASK2}) ORDER BY event_id")
    states["events"] = events
    return states


def svc_settled():
    """事件链收敛判定: 三个服务库 outbox 无 PENDING/HALTED。"""
    for db in ("execution_db", "planning_db", "demand_db"):
        out = sql(db, "SELECT COUNT(*) FROM event_outbox WHERE status IN ('PENDING','HALTED')")
        if out[0][0][0] != "0":
            return False
    return True


def run_mono():
    token = login_mono()
    snapshots = []
    for action, task_id in SEQUENCE:
        if action == "pause":
            status, body = http("POST", f"{MONO}/execute/event/pause/{task_id}", {}, token)
        else:
            status, body = http("POST", f"{MONO}/execute/event/{action}/{task_id}", None, token)
        time.sleep(0.4)  # 进程内同步链，短暂等待落库可见
        snap = snap_mono()
        snap["cmd"] = f"{action} {task_id}"
        snap["http_status"] = status
        snap["resp"] = body
        snapshots.append(snap)
        print(f"[mono] {action} {task_id} -> {json.dumps(body, ensure_ascii=False)} | "
              f"task1={snap['task1']} task2={snap['task2']} batch={snap['batch']} line={snap['line']} order={snap['order']}")
    return snapshots


def run_svc():
    token = login_svc()
    snapshots = []
    for action, task_id in SEQUENCE:
        if action == "pause":
            status, body = http("POST", f"{EXEC_DIR}/execute/event/pause/{task_id}", {}, token)
        else:
            status, body = http("POST", f"{EXEC_DIR}/execute/event/{action}/{task_id}", None, token)
        deadline = time.time() + 20
        prev = None
        stable_since = None
        while time.time() < deadline:
            if svc_settled():
                snap = snap_svc()
                if prev == snap and time.time() - stable_since >= 1.0:
                    break
                if prev != snap:
                    stable_since = time.time()
                prev = snap
            time.sleep(0.3)
        snap = snap_svc()
        snap["cmd"] = f"{action} {task_id}"
        snap["http_status"] = status
        snap["resp"] = body
        snapshots.append(snap)
        print(f"[svc ] {action} {task_id} -> {json.dumps(body, ensure_ascii=False)} | "
              f"task1={snap['task1']} task2={snap['task2']} batch={snap['batch']} line={snap['line']} order={snap['order']}")
    return snapshots


def normalize(snap):
    """归一化: 保留五态 + 事件序列 + 命令失败语义（code/msg），剔除时间戳/事件ID/uuid。"""
    fields = {k: snap.get(k) for k in ("task1", "task2", "batch", "line", "order", "events")}
    resp = snap.get("resp") or {}
    fields["resp_code"] = resp.get("code")
    fields["resp_msg"] = resp.get("msg")
    fields["http_status"] = snap.get("http_status")
    return fields


def compare(mono_snaps, svc_snaps):
    all_ok = True
    report = []
    for m, s in zip(mono_snaps, svc_snaps):
        nm, ns = normalize(m), normalize(s)
        diffs = {k: (nm[k], ns[k]) for k in nm if nm[k] != ns[k]}
        ok = not diffs
        all_ok &= ok
        line = f"{'PASS' if ok else 'DIFF'}  {m['cmd']}"
        for k, (a, b) in diffs.items():
            line += f" | {k}: mono={a} svc={b}"
        report.append(line)
        print(line)
    print("\nPARITY " + ("OK" if all_ok else "FAILED") + f" ({len(mono_snaps)} steps)")
    return all_ok


if __name__ == "__main__":
    mode = sys.argv[1] if len(sys.argv) > 1 else "compare"
    if mode == "seed":
        seed_mono()
        seed_svc()
        print("seeded mono(product db) + svc(planning_db/demand_db/execution_db)")
    elif mode == "mono":
        snaps = run_mono()
        json.dump(snaps, open(f"{SCRATCH}/parity_mono.json", "w"), ensure_ascii=False, indent=1, default=str)
        print("saved parity_mono.json")
    elif mode == "svc":
        snaps = run_svc()
        json.dump(snaps, open(f"{SCRATCH}/parity_svc.json", "w"), ensure_ascii=False, indent=1, default=str)
        print("saved parity_svc.json")
    elif mode == "compare":
        mono_snaps = json.load(open(f"{SCRATCH}/parity_mono.json"))
        svc_snaps = json.load(open(f"{SCRATCH}/parity_svc.json"))
        ok = compare(mono_snaps, svc_snaps)
        sys.exit(0 if ok else 2)
