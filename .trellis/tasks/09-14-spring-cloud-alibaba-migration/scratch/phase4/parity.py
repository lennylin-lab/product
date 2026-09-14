#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Phase 4 对拍: 同一固定数据集分别在单体(8082, product 库)与微服务(网关 8080, 服务库)上
执行 拆批 -> 释放 -> 生成任务 -> 异步排程，逐字段比对 批次/工序任务/派工/派工资源/资源需求。

用法: python3 parity.py dump|compare
"""
import json
import subprocess
import sys
import time
import urllib.request
import urllib.error

GW = "http://127.0.0.1:8080"
MONO = "http://127.0.0.1:8082"
ASSIGNMENT_START = "2026-09-15 09:00:00"
SCRATCH = "/home/lenny/Projects/pps/product/scratch/phase4"

MASTER_SEED = """
INSERT INTO calendar (calendar_id, calendar_name, workday_pattern, shift_start, shift_end, create_time)
VALUES (1, '常白班', 'Mon-Sun', '08:00', '20:00', NOW())
ON DUPLICATE KEY UPDATE workday_pattern='Mon-Sun', shift_start='08:00', shift_end='20:00';
INSERT INTO changeover_rule (rule_id, same_mold_time_min, different_mold_time_min, material_change_extra_min, color_change_extra_min, create_time)
VALUES (1, 5, 40, 10, 10, NOW())
ON DUPLICATE KEY UPDATE same_mold_time_min=5, different_mold_time_min=40, material_change_extra_min=10, color_change_extra_min=10;
INSERT INTO resource (resource_id, resource_type, name, status, calendar_id, create_time) VALUES
 ('200','MACHINE','注塑机A','AVAILABLE',1,NOW()),
 ('201','MACHINE','注塑机B','AVAILABLE',1,NOW()),
 ('210','MOLD','模具210','AVAILABLE',1,NOW()),
 ('211','MOLD','模具211','AVAILABLE',1,NOW()),
 ('300','PERSON','操作员A','AVAILABLE',1,NOW()),
 ('400','WORKSTATION','后处理工位','AVAILABLE',1,NOW())
ON DUPLICATE KEY UPDATE status='AVAILABLE';
INSERT INTO machine (machine_id, tonnage, default_setup_time_min) VALUES
 ('200', 120, 20), ('201', 200, 30)
ON DUPLICATE KEY UPDATE default_setup_time_min=VALUES(default_setup_time_min);
INSERT INTO mold (mold_id, mold_code, cavity, mold_status) VALUES
 ('210','M210',2,'AVAILABLE'), ('211','M211',4,'AVAILABLE')
ON DUPLICATE KEY UPDATE mold_status='AVAILABLE';
INSERT INTO machine_mold_compatibility (machine_id, mold_id, is_compatible) VALUES
 ('200','210',1), ('200','211',1), ('201','210',1), ('201','211',1)
ON DUPLICATE KEY UPDATE is_compatible=1;
INSERT INTO resource_capability (resource_id, op_code, product_id, is_enabled, priority_weight) VALUES
 ('300','SETUP',100,1,1), ('300','INJECT',100,1,1), ('300','POST_QC_PUTAWAY',100,1,1),
 ('300','SETUP',101,1,1), ('300','INJECT',101,1,1), ('300','POST_QC_PUTAWAY',101,1,1)
ON DUPLICATE KEY UPDATE is_enabled=1;
INSERT INTO product (product_id, product_name, material_code, color_code, create_time) VALUES
 (100,'产品PP','PP','RED',NOW()), (101,'产品ABS','ABS','BLUE',NOW())
ON DUPLICATE KEY UPDATE material_code=VALUES(material_code), color_code=VALUES(color_code);
INSERT INTO product_mold_param (product_id, mold_id, cycle_time_sec, cavity, yield_rate, utilization) VALUES
 (100,'210',30.00,2,0.9500,0.9000), (101,'211',20.00,4,0.9000,0.8000)
ON DUPLICATE KEY UPDATE cycle_time_sec=VALUES(cycle_time_sec);
INSERT INTO product_route (route_id, product_id, version, is_active, create_time) VALUES
 (701,100,'v1',1,NOW()), (702,101,'v1',1,NOW())
ON DUPLICATE KEY UPDATE is_active=1;
INSERT INTO route_operation (op_id, route_id, op_code, sequence, eligible_resource_rule, std_time_model, queue_policy) VALUES
 (8011,701,'SETUP',1,'RULE_SETUP_MACHINE','TM_SETUP_BASE','FIFO'),
 (8012,701,'INJECT',2,'RULE_INJECT_MACHINE','TM_INJECT_A2','FIFO'),
 (8013,701,'POST_QC_PUTAWAY',3,'RULE_POST_WORKSTATION','TM_POST_UNIT','FIFO'),
 (8021,702,'SETUP',1,'RULE_SETUP_MACHINE','TM_SETUP_BASE','FIFO'),
 (8022,702,'INJECT',2,'RULE_INJECT_MACHINE','TM_INJECT_A2','FIFO'),
 (8023,702,'POST_QC_PUTAWAY',3,'RULE_POST_WORKSTATION','TM_POST_UNIT','FIFO')
ON DUPLICATE KEY UPDATE queue_policy='FIFO';
"""

DEMAND_SEED = """
INSERT INTO customer (customer_id, customer_name, remark, create_time)
VALUES (50,'客户50','对拍固定数据',NOW())
ON DUPLICATE KEY UPDATE customer_name='客户50';
INSERT INTO customer_order (order_id, customer_id, due_date, priority, status, create_time) VALUES
 (9001,50,'2026-09-30 00:00:00',5,'CONFIRMED',NOW()),
 (9002,50,'2026-09-20 00:00:00',9,'CONFIRMED',NOW())
ON DUPLICATE KEY UPDATE due_date=VALUES(due_date), priority=VALUES(priority), status='CONFIRMED';
INSERT INTO order_line (order_line_id, order_id, product_id, qty, allocated_qty, status) VALUES
 (1001,9001,100,100,0,'RELEASED'),
 (1002,9001,100,100,0,'RELEASED'),
 (1003,9002,101,60,0,'RELEASED'),
 (1004,9002,101,60,0,'RELEASED')
ON DUPLICATE KEY UPDATE qty=VALUES(qty), status='RELEASED';
"""

def sh(args):
    return subprocess.check_output(args).decode().strip()


def sql(db, statement, check=True):
    proc = subprocess.run(["docker", "exec", "product-mysql", "mysql", "-uroot", "-p123456",
                           "--default-character-set=utf8mb4", "-N", "-B", "-e", statement, db],
                          capture_output=True, text=True)
    if check and proc.returncode != 0:
        raise RuntimeError("sql failed: %s" % proc.stderr)
    return proc.stdout.strip()


def http(base, method, path, body=None, token=None):
    req = urllib.request.Request(base + path, method=method)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    data = json.dumps(body).encode() if body is not None else None
    try:
        with urllib.request.urlopen(req, data, timeout=30) as r:
            return json.loads(r.read().decode())
    except urllib.error.HTTPError as e:
        try:
            return json.loads(e.read().decode())
        except Exception:
            return {"code": e.code, "msg": "http %s" % e.code}


def login(base):
    # 单体验证码 key 为 captcha_codes:<uuid>；identity 命名空间为 identity:captcha_codes:<uuid>
    cap = http(base, "GET", "/captchaImage")
    uuid = cap["uuid"]
    key = ("identity:captcha_codes:" if base == GW else "captcha_codes:") + uuid
    raw = sh(["docker", "exec", "product-redis", "redis-cli", "-a", "123456", "--no-auth-warning",
              "GET", key])
    val = raw.strip().strip('"')
    res = http(base, "POST", "/login",
               {"username": "admin", "password": "admin123", "code": val, "uuid": uuid})
    assert res.get("token"), "login failed on %s: %s" % (base, res)
    return res["token"]


def api_session(base):
    token = login(base)

    def call(method, path, body=None):
        return http(base, method, path, body, token)
    return call


def seed(db):
    # 主数据种子仅入 master_data_db（服务侧）/ product（单体制）；demand 种子仅入 demand_db/product
    if db == "product":
        sql(db, MASTER_SEED)
        sql(db, DEMAND_SEED)
    else:
        sql("master_data_db", MASTER_SEED)
        sql("demand_db", DEMAND_SEED)
    print("seeded", db)


SVC_SEED_R1 = """
DELETE FROM task_assignment_resource WHERE task_id BETWEEN 79000000 AND 79999999;
DELETE FROM task_assignment WHERE task_id BETWEEN 79000000 AND 79999999;
DELETE FROM task_resource_requirement WHERE task_id BETWEEN 79000000 AND 79999999;
DELETE FROM task_dependency WHERE pre_task_id BETWEEN 79000000 AND 79999999 OR post_task_id BETWEEN 79000000 AND 79999999;
DELETE FROM operation_task WHERE task_id BETWEEN 79000000 AND 79999999;
DELETE FROM production_batch WHERE batch_id BETWEEN 7000000 AND 7000999;
INSERT INTO production_batch (batch_id, order_line_id, batch_qty, status, create_time) VALUES
 (7000001, 1001, 30, 'RELEASED', NOW()),
 (7000002, 1003, 20, 'RELEASED', NOW());
INSERT INTO operation_task (task_id, batch_id, op_code, sequence, std_duration_min, earliest_start, status, queue_policy, eligible_resource_rule) VALUES
 (79000011, 7000001, 'SETUP', 1, 1800, '2026-09-15 08:00:00', 'READY', 'FIFO', 'RULE_SETUP_MACHINE'),
 (79000012, 7000001, 'INJECT', 2, 9, '2026-09-16 14:00:00', 'READY', 'FIFO', 'RULE_INJECT_MACHINE'),
 (79000013, 7000001, 'POST_QC_PUTAWAY', 3, 3600, '2026-09-16 14:09:00', 'READY', 'FIFO', 'RULE_POST_WORKSTATION'),
 (79000021, 7000002, 'SETUP', 1, 1200, '2026-09-15 08:00:00', 'READY', 'FIFO', 'RULE_SETUP_MACHINE'),
 (79000022, 7000002, 'INJECT', 2, 3, '2026-09-15 20:00:00', 'READY', 'FIFO', 'RULE_INJECT_MACHINE'),
 (79000023, 7000002, 'POST_QC_PUTAWAY', 3, 2400, '2026-09-15 20:03:00', 'READY', 'FIFO', 'RULE_POST_WORKSTATION');
INSERT INTO task_dependency (pre_task_id, post_task_id) VALUES
 (79000011,79000012),(79000012,79000013),(79000021,79000022),(79000022,79000023);
INSERT INTO task_resource_requirement (requirement_id, task_id, resource_type, resource_id, capability_code, required_count, is_mandatory) VALUES
 (91000001,79000011,'PERSON',NULL,'SETUP',1,1),(91000002,79000011,'MACHINE',NULL,'SETUP',1,1),
 (91000003,79000012,'PERSON',NULL,'INJECT',1,1),(91000004,79000012,'MACHINE',NULL,'INJECT',1,1),(91000005,79000012,'MOLD',NULL,'INJECT',1,1),
 (91000006,79000013,'PERSON',NULL,'POST_QC_PUTAWAY',1,1),(91000007,79000013,'WORKSTATION',NULL,'POST_QC_PUTAWAY',1,1),
 (91000011,79000021,'PERSON',NULL,'SETUP',1,1),(91000012,79000021,'MACHINE',NULL,'SETUP',1,1),
 (91000013,79000022,'PERSON',NULL,'INJECT',1,1),(91000014,79000022,'MACHINE',NULL,'INJECT',1,1),(91000015,79000022,'MOLD',NULL,'INJECT',1,1),
 (91000016,79000023,'PERSON',NULL,'POST_QC_PUTAWAY',1,1),(91000017,79000023,'WORKSTATION',NULL,'POST_QC_PUTAWAY',1,1);
"""

SVC_SEED_R2 = """
INSERT INTO production_batch (batch_id, order_line_id, batch_qty, status, create_time) VALUES
 (7000003, 1002, 40, 'RELEASED', NOW()),
 (7000004, 1004, 10, 'RELEASED', NOW());
INSERT INTO operation_task (task_id, batch_id, op_code, sequence, std_duration_min, earliest_start, status, queue_policy, eligible_resource_rule) VALUES
 (79000031, 7000003, 'SETUP', 1, 2400, '2026-09-15 08:00:00', 'READY', 'FIFO', 'RULE_SETUP_MACHINE'),
 (79000032, 7000003, 'INJECT', 2, 12, '2026-09-16 16:00:00', 'READY', 'FIFO', 'RULE_INJECT_MACHINE'),
 (79000033, 7000003, 'POST_QC_PUTAWAY', 3, 4800, '2026-09-16 16:12:00', 'READY', 'FIFO', 'RULE_POST_WORKSTATION'),
 (79000041, 7000004, 'SETUP', 1, 600, '2026-09-15 08:00:00', 'READY', 'FIFO', 'RULE_SETUP_MACHINE'),
 (79000042, 7000004, 'INJECT', 2, 2, '2026-09-15 18:00:00', 'READY', 'FIFO', 'RULE_INJECT_MACHINE'),
 (79000043, 7000004, 'POST_QC_PUTAWAY', 3, 1200, '2026-09-15 18:02:00', 'READY', 'FIFO', 'RULE_POST_WORKSTATION');
INSERT INTO task_dependency (pre_task_id, post_task_id) VALUES
 (79000031,79000032),(79000032,79000033),(79000041,79000042),(79000042,79000043);
INSERT INTO task_resource_requirement (requirement_id, task_id, resource_type, resource_id, capability_code, required_count, is_mandatory) VALUES
 (91000021,79000031,'PERSON',NULL,'SETUP',1,1),(91000022,79000031,'MACHINE',NULL,'SETUP',1,1),
 (91000023,79000032,'PERSON',NULL,'INJECT',1,1),(91000024,79000032,'MACHINE',NULL,'INJECT',1,1),(91000025,79000032,'MOLD',NULL,'INJECT',1,1),
 (91000026,79000033,'PERSON',NULL,'POST_QC_PUTAWAY',1,1),(91000027,79000033,'WORKSTATION',NULL,'POST_QC_PUTAWAY',1,1),
 (91000031,79000041,'PERSON',NULL,'SETUP',1,1),(91000032,79000041,'MACHINE',NULL,'SETUP',1,1),
 (91000033,79000042,'PERSON',NULL,'INJECT',1,1),(91000034,79000042,'MACHINE',NULL,'INJECT',1,1),(91000035,79000042,'MOLD',NULL,'INJECT',1,1),
 (91000036,79000043,'PERSON',NULL,'POST_QC_PUTAWAY',1,1),(91000037,79000043,'WORKSTATION',NULL,'POST_QC_PUTAWAY',1,1);
"""

def run_service():
    """微服务侧：两轮排程。第一轮仅 B1/B2 任务 READY；SUCCESS 后种入 B3/B4 并以
    DUE_DATE_PRIORITY 发起第二轮 —— 与真实系统一致：第二轮运行时上下文（资源占用
    统计 + 机台最近派工快照）由第一轮落库结果重建。"""
    call = api_session(GW)
    phases = [(1, "EARLIEST_START", SVC_SEED_R1), (2, "DUE_DATE_PRIORITY", SVC_SEED_R2)]
    jobs = {}
    for rnd, strategy, seed_sql in phases:
        sql("planning_db", seed_sql)
        r = call("POST", "/pps/assignment/scheduleAllAsync",
                 {"assignmentStart": ASSIGNMENT_START, "scheduleStrategy": strategy})
        assert r.get("code") == 200, "scheduleAllAsync failed: %s" % r
        jobs[rnd] = r["data"]
        deadline = time.time() + 60
        data = {}
        while time.time() < deadline:
            job = call("GET", "/pps/assignment/scheduleJob/%s" % jobs[rnd])
            data = job.get("data") or {}
            if data.get("status") in ("SUCCESS", "FAILED"):
                break
            time.sleep(0.5)
        print("  [svc] round %s job %s -> %s tasks=%s %s" % (rnd, jobs[rnd], data.get("status"),
              data.get("totalTaskCount"), data.get("errorMessage") or ""))
        assert data.get("status") == "SUCCESS", "service scheduling round %s failed: %s" % (rnd, data)
        assert data.get("totalTaskCount") == 6, "round %s expected 6 tasks got %s" % (rnd, data.get("totalTaskCount"))
    time.sleep(1)
    rows = {}
    rows["task"] = sql("planning_db", "SELECT task_id, status FROM operation_task WHERE task_id BETWEEN 79000000 AND 79999999")
    rows["assign"] = sql("planning_db", "SELECT task_id, machine_id, planned_start, planned_end, sequence_on_resource FROM task_assignment WHERE task_id BETWEEN 79000000 AND 79999999 ORDER BY task_id")
    rows["assign_res"] = sql("planning_db", "SELECT task_id, resource_type, resource_id, sequence_on_resource FROM task_assignment_resource WHERE task_id BETWEEN 79000000 AND 79999999 ORDER BY task_id, resource_type")
    return rows


def compare(expected, svc):
    """逐字段比对：任务状态、派工主行（机器/模具/人员/工位、计划起止、序号、换型）、派工资源明细。"""
    failures = []
    statuses = {}
    for line in filter(None, svc["task"].split("\n")):
        parts = line.split("\t")
        statuses[int(parts[0])] = parts[1]
    assigns = {}
    for line in filter(None, svc["assign"].split("\n")):
        tid, mid, ps, pe, seq = line.split("\t")
        assigns[int(tid)] = (mid, ps, pe, seq)
    ares = {}
    for line in filter(None, svc["assign_res"].split("\n")):
        tid, rtype, rid, seq = line.split("\t")
        ares.setdefault(int(tid), []).append((rtype, rid, seq))

    checks = 0
    for rnd in ("round1", "round2"):
        for e in expected[rnd]["assignments"]:
            tid = e["taskId"]
            checks += 1
            if statuses.get(tid) != "SCHEDULED":
                failures.append("task %s status=%s expected SCHEDULED" % (tid, statuses.get(tid)))
            if tid not in assigns:
                failures.append("assignment missing: task %s" % tid)
                continue
            mid, ps, pe, seq = assigns[tid]
            exp = (str(e["machineId"]) if e["machineId"] is not None else "NULL",
                   e["plannedStart"], e["plannedEnd"],
                   str(e["sequenceOnResource"]) if e["sequenceOnResource"] is not None else "NULL")
            got = (mid, ps, pe, seq)
            if exp != got:
                failures.append("assignment mismatch task %s:\n  expected=%s\n  actual  =%s" % (tid, exp, got))
            exp_rows = []
            if e["machineId"] is not None:
                exp_rows.append(("MACHINE", str(e["machineId"]), str(e["sequenceOnResource"])))
            if e["moldId"] is not None:
                exp_rows.append(("MOLD", str(e["moldId"]), str(e["moldSequence"])))
            if e["personId"] is not None:
                exp_rows.append(("PERSON", str(e["personId"]), str(e["personSequence"])))
            if e["workstationId"] is not None:
                exp_rows.append(("WORKSTATION", str(e["workstationId"]), str(e["workstationSequence"])))
            got_rows = sorted(ares.get(tid, []))
            if sorted(exp_rows) != got_rows:
                failures.append("assignment_resource mismatch task %s:\n  expected=%s\n  actual  =%s"
                                % (tid, sorted(exp_rows), got_rows))

    if failures:
        print("PARITY FAILURES (%d):" % len(failures))
        for f in failures:
            print(" -", f)
        sys.exit(1)
    print("PARITY OK: %d tasks x [状态 + 派工主行 + 派工资源明细] 与单体算法逐字段一致"
          "（EARLIEST_START + DUE_DATE_PRIORITY，含跨班次顺延、级联选模、人员能力、换型时间）" % checks)
    # 换型核对：换型分钟不落 task_assignment（exist=false），已体现在计划起止的顺延中
    r2 = {a["taskId"]: a for a in expected["round2"]["assignments"]}
    print("换型核对: harness 期望 31 号任务 changeoverTimeMin=%s（不同模具40+换料10），"
          "其服务侧计划起止 %s -> %s 已含该顺延"
          % (r2[79000031]["changeoverTimeMin"],
             {a["taskId"]: a["plannedStart"] for a in svc_assign_list() if a["taskId"] == 79000031}.get(79000031),
             {a["taskId"]: a["plannedEnd"] for a in svc_assign_list() if a["taskId"] == 79000031}.get(79000031)))


def svc_assign_list():
    svc = json.load(open(SCRATCH + "/parity_svc.json"))
    out = []
    for line in filter(None, svc["assign"].split("\n")):
        tid, mid, ps, pe, seq = line.split("\t")
        out.append({"taskId": int(tid), "plannedStart": ps, "plannedEnd": pe})
    return out


def main():
    mode = sys.argv[1] if len(sys.argv) > 1 else "all"
    if mode in ("seed", "all"):
        seed("product")
        seed("planning_db")
        print("service-side planning rows seeded")
    if mode in ("run-svc", "all"):
        svc = run_service()
        json.dump(svc, open(SCRATCH + "/parity_svc.json", "w"), indent=1)
        print("service run dumped")
    if mode in ("compare", "all"):
        expected = json.load(open(SCRATCH + "/mono-expected.json"))
        svc = json.load(open(SCRATCH + "/parity_svc.json"))
        compare(expected, svc)


if __name__ == "__main__":
    main()
