#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""child-2 (09-16-reschedule-trigger) 实机端到端探测。

场景（transcript: e2e_reschedule_transcript.txt）：
  0. 登录网关取 admin token；播种最小排程负载（2 机台 200/201、批次、6 READY 任务）；
  1. 人工基线排程：/pps/assignment/scheduleAllAsync → job1 SUCCESS（机台 200/201 均被选用）；
  2. 任务重置 READY + 清派工 → DOWN 事件（resourceId=200 toStatus=DOWN）经 execution
     /internal 端点登记 → 观察回写（master_data_db resource.status=DOWN + 版本 bump）
     与自动全量重排（无人调排程接口，schedule_job 出现新行且 SUCCESS）→ 新派工不选 200；
  3. 任务再重置 READY → AVAILABLE（恢复）事件 → 再次自动重排 SUCCESS（200 回归可选）；
  4. child-1 check 移交的回写失败通道实测：toStatus=BROKEN（非法）→ master-data 错误契约
     拒绝 → planning fail-closed 不 ack → 3 次重试后 DLX（dead_letter_audit 有行、
     consumed_event 无行、版本与资源状态不变）。
"""
import json
import subprocess
import time
import urllib.request
import urllib.error

GW = "http://127.0.0.1:8080"
EXEC = "http://127.0.0.1:8105"
OUT_PATH = "/home/lenny/Projects/pps/product/scratch/reschedule-trigger/e2e_reschedule_transcript.txt"
OUT = open(OUT_PATH, "w")
results = []


def emit(line):
    print(line)
    OUT.write(line + "\n")


def sh(args):
    return subprocess.check_output(args).decode().strip()


def sql(statement, db=None, check=True):
    args = ["docker", "exec", "product-mysql", "mysql", "-uroot", "-p123456",
            "--default-character-set=utf8mb4", "-N", "-B", "-e", statement]
    if db:
        args.append(db)
    proc = subprocess.run(args, capture_output=True, text=True)
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
            return r.status, json.loads(r.read().decode())
    except urllib.error.HTTPError as e:
        try:
            return e.code, json.loads(e.read().decode())
        except Exception:
            return e.code, {"code": e.code, "msg": "http %s" % e.code}


def expect(name, ok, detail=""):
    results.append(bool(ok))
    emit(("PASS " if ok else "FAIL ") + name + ("" if ok else "  -> " + str(detail)))


def login():
    st, cap = http(GW, "GET", "/captchaImage")
    uuid = cap["uuid"]
    code = sh(["docker", "exec", "product-redis", "redis-cli", "-a", "123456",
               "--no-auth-warning", "GET", "identity:captcha_codes:" + uuid]).strip().strip('"')
    st, res = http(GW, "POST", "/login",
                   {"username": "admin", "password": "admin123", "code": code, "uuid": uuid})
    expect("登录网关获取 admin token", st == 200 and res.get("token"), res)
    return res["token"]


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
VALUES (50,'客户50','重排触发E2E固定数据',NOW())
ON DUPLICATE KEY UPDATE customer_name='客户50';
INSERT INTO customer_order (order_id, customer_id, due_date, priority, status, create_time) VALUES
 (9001,50,'2026-10-30 00:00:00',5,'CONFIRMED',NOW()),
 (9002,50,'2026-10-20 00:00:00',9,'CONFIRMED',NOW())
ON DUPLICATE KEY UPDATE due_date=VALUES(due_date), priority=VALUES(priority), status='CONFIRMED';
INSERT INTO order_line (order_line_id, order_id, product_id, qty, allocated_qty, status) VALUES
 (1001,9001,100,100,0,'RELEASED'),
 (1003,9002,101,60,0,'RELEASED')
ON DUPLICATE KEY UPDATE qty=VALUES(qty), status='RELEASED';
"""

TASK_SEED = """
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

RESET_READY = """
DELETE FROM task_assignment_resource WHERE task_id BETWEEN 79000000 AND 79999999;
DELETE FROM task_assignment WHERE task_id BETWEEN 79000000 AND 79999999;
UPDATE operation_task SET status='READY' WHERE task_id BETWEEN 79000000 AND 79999999;
"""


def master_version():
    return int(sql("SELECT data_version FROM master_data_db.master_data_data_version WHERE scope='MASTER_DATA'"))


def resource_row(rid):
    return sql("SELECT status FROM master_data_db.resource WHERE resource_id=%s" % rid)


def fire_resource_event(token, rid, from_status, to_status, reason):
    body = {"resourceId": rid, "fromStatus": from_status, "toStatus": to_status, "reasonCode": reason}
    st, res = http(EXEC, "POST", "/internal/execution/resource-status-events", body, token=token)
    return st, res


def wait_for_new_job(prev_job_id, deadline_s=30, want_status="SUCCESS"):
    """轮询等待 job_id > prev_job_id 的新排程任务到达 want_status（终态）。"""
    deadline = time.time() + deadline_s
    while time.time() < deadline:
        row = sql("SELECT job_id, status, total_task_count, error_message FROM planning_db.schedule_job "
                  "WHERE job_id > %s ORDER BY job_id ASC LIMIT 1" % prev_job_id)
        if row:
            parts = row.split("\t")
            if want_status is None or parts[1] == want_status:
                return parts
        time.sleep(0.5)
    return None


def count_consumed(aggregate_id):
    return int(sql("SELECT COUNT(*) FROM planning_db.consumed_event "
                   "WHERE consumer_group='product-planning' AND event_type='resource.status.changed' "
                   "AND aggregate_id='%s'" % aggregate_id) or 0)


def count_dead_letter(aggregate_id):
    return int(sql("SELECT COUNT(*) FROM planning_db.dead_letter_audit "
                   "WHERE consumer_group='product-planning' AND event_type='resource.status.changed' "
                   "AND aggregate_id='%s'" % aggregate_id) or 0)


def main():
    emit("== 09-16-reschedule-trigger 实机端到端 %s ==" % time.strftime("%Y-%m-%d %H:%M:%S"))
    emit("stack pids: " + open("/home/lenny/Projects/pps/product/scratch/reschedule-trigger/pids.txt").read().replace("\n", " | "))
    token = login()

    # ---------- 0. 播种 ----------
    sql(MASTER_SEED, "master_data_db")
    sql(DEMAND_SEED, "demand_db")
    sql(TASK_SEED, "planning_db")
    emit("seeded master_data_db / demand_db / planning_db（2 机台 200/201、6 READY 任务）")

    # ---------- 1. 人工基线排程 ----------
    start_max_job = int(sql("SELECT COALESCE(MAX(job_id),0) FROM planning_db.schedule_job") or 0)
    v0 = master_version()
    consumed0 = count_consumed("200")
    emit("起始状态: max(job_id)=%d master_data_data_version=%d consumed_event(agg=200)=%d" % (start_max_job, v0, consumed0))
    st, r = http(GW, "POST", "/pps/assignment/scheduleAllAsync",
                 {"assignmentStart": "2026-09-16 09:00:00", "scheduleStrategy": "EARLIEST_START"},
                 token=token)
    expect("人工基线 scheduleAllAsync 提交成功", st == 200 and r.get("code") == 200, r)
    job1 = str(r["data"])
    parts = wait_for_new_job(start_max_job, 60)
    expect("基线排程 job=%s SUCCESS（6 任务）" % job1,
           parts and parts[0] == job1 and parts[1] == "SUCCESS" and parts[2] == "6", parts)
    baseline_machines = sql("SELECT DISTINCT machine_id FROM planning_db.task_assignment "
                            "WHERE task_id BETWEEN 79000000 AND 79999999")
    emit("基线派工机台 = %s" % baseline_machines.replace("\n", ","))
    expect("基线排程同时选用机台 200 与 201", "200" in baseline_machines and "201" in baseline_machines,
           baseline_machines)

    # ---------- 2. DOWN 事件 → 回写 + 自动重排 → DOWN 资源不被选中 ----------
    sql(RESET_READY, "planning_db")
    st, res = fire_resource_event(token, 200, "AVAILABLE", "DOWN", "EQUIP_FAULT")
    expect("DOWN 事件登记（execution /internal 直连） accepted", st == 200 and res.get("code") == 200, res)

    job2 = wait_for_new_job(int(job1), 30)
    expect("自动全量重排被触发（无人调排程接口，新 schedule_job=%s SUCCESS）" % (job2[0] if job2 else "?"), job2, "no new job")
    if job2:
        emit("自动重排 job_id=%s status=%s totalTaskCount=%s errorMessage=%s" % tuple(job2))
    expect("master_data_db resource 200 状态回写为 DOWN", resource_row(200) == "DOWN", resource_row(200))
    v1 = master_version()
    expect("master_data_data_version 幂等 bump（%d -> %d）" % (v0, v1), v1 == v0 + 1, v1)
    machines_after_down = sql("SELECT DISTINCT COALESCE(machine_id,-1) FROM planning_db.task_assignment "
                              "WHERE task_id BETWEEN 79000000 AND 79999999")
    emit("DOWN 后重排派工机台（-1=无机台的工位任务） = %s" % (machines_after_down.replace("\n", ",") or "(空)"))
    down_200_rows = sql("SELECT COUNT(*) FROM planning_db.task_assignment "
                        "WHERE task_id BETWEEN 79000000 AND 79999999 AND machine_id=200")
    expect("重排后 DOWN 资源 200 未被选中（machine_id=200 派工行数 = 0）", down_200_rows == "0", down_200_rows)
    expect("重排后任务全部由机台 201 承接", "201" in machines_after_down, machines_after_down)
    sched = sql("SELECT status, COUNT(*) FROM planning_db.operation_task WHERE task_id BETWEEN 79000000 AND 79999999 GROUP BY status")
    emit("DOWN 后任务状态分布: %s" % sched.replace("\n", ", "))

    # consumed_event 有回写成功事件的流水（APPLIED；aggregate_id=资源ID 200）
    ce1 = count_consumed("200")
    expect("consumed_event 记录 DOWN 事件 APPLIED（回写成功才 ack，%d -> %d）" % (consumed0, ce1), ce1 == consumed0 + 1, ce1)

    # ---------- 3. AVAILABLE（恢复）事件 → 再次自动重排 ----------
    sql(RESET_READY, "planning_db")
    st, res = fire_resource_event(token, 200, "DOWN", "AVAILABLE", "REPAIR_DONE")
    expect("AVAILABLE 恢复事件登记 accepted", st == 200 and res.get("code") == 200, res)
    job3 = wait_for_new_job(int(job2[0]) if job2 else int(job1), 30)
    expect("恢复事件再次自动重排（新 schedule_job=%s SUCCESS）" % (job3[0] if job3 else "?"), job3, "no new job")
    if job3:
        emit("恢复重排 job_id=%s status=%s totalTaskCount=%s errorMessage=%s" % tuple(job3))
    expect("master_data_db resource 200 恢复为 AVAILABLE", resource_row(200) == "AVAILABLE", resource_row(200))
    v2 = master_version()
    expect("恢复回写再次 bump（%d -> %d）" % (v1, v2), v2 == v1 + 1, v2)
    machines_after_recover = sql("SELECT DISTINCT machine_id FROM planning_db.task_assignment "
                                 "WHERE task_id BETWEEN 79000000 AND 79999999")
    emit("恢复后重排派工机台 = %s" % (machines_after_recover.replace("\n", ",") or "(空)"))
    expect("恢复后 200 重新参与派工（200/201 均可选）", "200" in machines_after_recover, machines_after_recover)

    # ---------- 4. 回写失败通道（child-1 check 移交）：非法 toStatus → 不 ack → DLX ----------
    v3 = master_version()
    dead0 = count_dead_letter("200")
    ce_before_broken = count_consumed("200")
    st, res = fire_resource_event(token, 200, "AVAILABLE", "BROKEN", "DELIBERATE_INVALID")
    expect("非法状态事件登记 accepted（登记端点只要求 resourceId）", st == 200 and res.get("code") == 200, res)
    time.sleep(12)  # 3 次重试（0.5s/1s 退避）→ DLX → 死信审计
    ce2 = count_consumed("200")
    expect("consumed_event 无新增流水（fail-closed 不 ack，仍 %d）" % ce_before_broken, ce2 == ce_before_broken, ce2)
    dead1 = count_dead_letter("200")
    expect("dead_letter_audit 死信审计入库（%d -> %d，重试耗尽进 DLX）" % (dead0, dead1), dead1 == dead0 + 1, dead1)
    expect("非法回写后 resource 200 状态不变（仍 AVAILABLE）", resource_row(200) == "AVAILABLE", resource_row(200))
    v4 = master_version()
    expect("非法回写未 bump 版本（%d 不变）" % v3, v4 == v3, v4)

    emit("")
    emit("RESULT: %d/%d PASS" % (sum(results), len(results)))
    OUT.close()


if __name__ == "__main__":
    main()
