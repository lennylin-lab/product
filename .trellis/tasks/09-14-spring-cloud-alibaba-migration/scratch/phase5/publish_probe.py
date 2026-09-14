#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""直接向 execution.events 发布 envelope（模拟重复/乱序/延迟投递——正常链路不会产生这些形态）。"""
import json
import subprocess
import sys
import urllib.request


def mysql(db, stmt):
    proc = subprocess.run(["docker", "exec", "product-mysql", "mysql", "-uroot", "-p123456",
                           "--batch", "--skip-column-names", db, "-e", stmt],
                          capture_output=True, text=True)
    if proc.returncode != 0:
        raise RuntimeError(proc.stderr)
    return [line.split("\t") for line in proc.stdout.strip().splitlines() if line]


def publish(envelope):
    body = {
        "properties": {
            "content_type": "application/json",
            "delivery_mode": 2,
            "headers": {"eventId": envelope["eventId"], "eventType": envelope["eventType"]},
        },
        "routing_key": envelope["eventType"],
        "payload": json.dumps(envelope),
        "payload_encoding": "string",
    }
    req = urllib.request.Request(
        "http://127.0.0.1:15673/api/exchanges/%2F/execution.events/publish", method="POST")
    req.add_header("Content-Type", "application/json")
    # management API basic auth guest:guest
    import base64
    req.add_header("Authorization", "Basic " + base64.b64encode(b"guest:guest").decode())
    with urllib.request.urlopen(req, data=json.dumps(body).encode(), timeout=10) as resp:
        return json.loads(resp.read().decode())


def outbox_envelope(outbox_id):
    rows = mysql("execution_db", f"SELECT event_id,event_type,aggregate_id,correlation_id,producer,payload,occurred_at FROM event_outbox WHERE id={outbox_id}")
    r = rows[0]
    return {"eventId": r[0], "eventType": r[1], "version": 1, "occurredAt": r[6].replace(" ", "T") + "+08:00",
            "producer": r[4], "aggregateId": r[2], "correlationId": r[3], "payload": json.loads(r[5])}


if __name__ == "__main__":
    cmd = sys.argv[1]
    if cmd == "duplicate":
        # 取最后一条 task.status.changed（complete 9731）原样重发（同 eventId）
        env = outbox_envelope(sys.argv[2])
        env["occurredAt"] = env["occurredAt"]  # 原样
        print(json.dumps(publish(env)))
        print("republished eventId=" + env["eventId"])
    elif cmd == "stale":
        # 合成过期事件（occurredAt 在过去）：aggregate=9731，targetStatus=PAUSED
        env = {"eventId": sys.argv[2], "eventType": "task.status.changed", "version": 1,
               "occurredAt": "2026-09-15T00:00:00+08:00", "producer": "product-execution",
               "aggregateId": "9731", "correlationId": "phase5-stale-test",
               "payload": {"taskId": 9731, "eventType": "PAUSE", "targetStatus": "PAUSED",
                           "resourceId": 9741, "occurredEventId": 0}}
        print(json.dumps(publish(env)))
    elif cmd == "fresh":
        # 全新聚合的 FINISH（正常时间）
        env = {"eventId": sys.argv[2], "eventType": "task.status.changed", "version": 1,
               "occurredAt": sys.argv[3], "producer": "product-execution",
               "aggregateId": "9733", "correlationId": "phase5-order-test",
               "payload": {"taskId": 9733, "eventType": "FINISH", "targetStatus": "DONE",
                           "resourceId": 9741, "occurredEventId": 0}}
        print(json.dumps(publish(env)))
    elif cmd == "late-start":
        # 同聚合乱序/延迟到达的 START（occurredAt 早于已应用 FINISH）
        env = {"eventId": sys.argv[2], "eventType": "task.status.changed", "version": 1,
               "occurredAt": sys.argv[3], "producer": "product-execution",
               "aggregateId": "9733", "correlationId": "phase5-order-test",
               "payload": {"taskId": 9733, "eventType": "START", "targetStatus": "RUNNING",
                           "resourceId": 9741, "occurredEventId": 0}}
        print(json.dumps(publish(env)))
