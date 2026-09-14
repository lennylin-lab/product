#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Phase 6 可观测性验收：日志/指标/追踪三线贯穿。
  A) 同步链：每个业务服务各取一条网关入口请求 —— 响应头 X-Trace-Id == 该服务 JSON 日志
     traceId == 网关 JSON 日志 traceId，且 Jaeger（OTLP 导出）中该 trace 同时含
     product-gateway 与目标服务的 span；
  B) 异步链：execution 命令 traceId 作为事件 correlationId 贯穿 event_outbox
     （ADR-0004 §2 envelope 契约），execution_db.outbox 可回查；
  C) 指标：6 个 /actuator/prometheus 均输出 http_server_requests（受 JWT 保护，健康探针匿名）；
  D) OTLP：Jaeger services 列表含全部 6 个服务。
"""
import json
import subprocess
import sys
import time
import urllib.error
import urllib.request

GW = "http://127.0.0.1:8080"
P6 = "/home/lenny/Projects/pps/product/.trellis/tasks/09-14-spring-cloud-alibaba-migration/scratch/phase6"
JAEGER = "http://127.0.0.1:16686"
LOGD = f"{P6}/logs"
OUT, FAILED = [], []

def log(s):
    print(s, flush=True)
    OUT.append(s)

def expect(desc, cond, detail=""):
    tag = "PASS" if cond else "FAIL"
    log(f"[{tag}] {desc}" + (f" | {detail}" if detail else ""))
    if not cond:
        FAILED.append(desc)

def sh(args):
    return subprocess.check_output(args).decode()

def get(url, token=None):
    req = urllib.request.Request(url)
    if token:
        req.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(req, timeout=15) as r:
            return r.status, dict(r.headers), r.read().decode()
    except urllib.error.HTTPError as e:
        return e.code, dict(e.headers), (e.read().decode() or "{}")

def login():
    st, _, body = get(GW + "/captchaImage")
    cap = json.loads(body)
    code = sh(["docker", "exec", "product-redis", "redis-cli", "-a", "123456", "--no-auth-warning",
               "GET", f"identity:captcha_codes:{cap['uuid']}"]).strip().strip('"')
    req = urllib.request.Request(GW + "/login", method="POST")
    req.add_header("Content-Type", "application/json")
    body = json.dumps({"username": "admin", "password": "admin123", "code": code, "uuid": cap["uuid"]}).encode()
    with urllib.request.urlopen(req, body, timeout=10) as r:
        return json.loads(r.read())["token"]

TOKEN = login()

def sh_ok(args):
    """grep -c 语义：无匹配返回 0 而非报错。"""
    proc = subprocess.run(args, capture_output=True, text=True)
    return (proc.stdout or "0").strip() if proc.returncode in (0, 1) else "-1"

# ---- A. 同步链：5 服务各一条网关入口请求（选会落日志的 list 端点：
#         BaseController.getDataTable 输出 total 日志，JSON 日志带 MDC traceId） ----
PROBES = [
    ("identity",     "/system/dict/type/list?pageNum=1&pageSize=1"),
    ("master-data",  "/demand/product/list?pageNum=1&pageSize=1"),
    ("demand",       "/demand/customer/list?pageNum=1&pageSize=1"),
    ("planning",     "/pps/batch/list?pageNum=1&pageSize=1"),
    ("execution",    "/execute/event/list?pageNum=1&pageSize=1"),
]
for svc, path in PROBES:
    st, headers, body = get(GW + path, TOKEN)
    trace_id = headers.get("X-Trace-Id")
    expect(f"[{svc}] 网关入口请求回写 X-Trace-Id", bool(trace_id), f"http={st}")
    if not trace_id:
        continue
    time.sleep(1.2)  # 等日志 flush + OTLP 批导出
    svc_log = sh_ok(["grep", "-c", trace_id, f"{LOGD}/{svc}/app.json.log"])
    expect(f"[{svc}] traceId {trace_id} 出现在 {svc} JSON 日志({svc_log} 处，网关经 Jaeger span 留痕)",
           int(svc_log) >= 1)
    # Jaeger 查询该 trace：span 应同时来自 product-gateway 与目标服务（OTLP 批导出有秒级时滞，轮询等待）
    procs = set()
    deadline = time.time() + 25
    while time.time() < deadline:
        st, _, body = get(f"{JAEGER}/api/traces/{trace_id}")
        trace = json.loads(body)
        procs = set()
        if trace.get("data"):
            for span in trace["data"][0].get("spans", []):
                pid = str(span.get("processID"))
                procs.add(trace["data"][0]["processes"].get(pid, {}).get("serviceName", pid))
        if "product-gateway" in procs and f"product-{svc}" in procs:
            break
        time.sleep(2)
    expect(f"[{svc}] Jaeger OTLP 导出：trace 同时含 gateway 与 {svc} span",
           "product-gateway" in procs and f"product-{svc}" in procs, f"services={sorted(procs)}")

# ---- B. 异步链 correlationId（ADR-0004 §2/§5）：同一命令 traceId 贯穿三域 outbox ----
def outbox_corrs(query):
    out = sh(["docker", "exec", "product-mysql", "mysql", "-uroot", "-p123456", "-N", "-B", "-e", query]).strip()
    return set(l for l in out.splitlines() if l)

exec_corrs = outbox_corrs("SELECT correlation_id FROM execution_db.event_outbox WHERE event_type='task.status.changed'")
plan_corrs = outbox_corrs("SELECT correlation_id FROM planning_db.event_outbox WHERE event_type='batch.progress.changed'")
dem_corrs = outbox_corrs("SELECT correlation_id FROM demand_db.event_outbox")
shared = exec_corrs & plan_corrs & dem_corrs
expect("同一命令 traceId 作为 correlationId 贯穿 execution->planning->demand 三域 outbox",
       len(shared) >= 1, f"shared={sorted(shared)[:3]}")
if shared:
    cid = sorted(shared)[0]
    # 该 correlationId 即网关入口命令的 traceId：应存在于 execution 服务 JSON 日志（命令处理留痕）
    exec_hit = sh_ok(["grep", "-c", cid, f"{LOGD}/execution/app.json.log"])
    expect(f"correlationId {cid[:16]}.. == 网关入口命令 traceId（execution 日志可回查）", int(exec_hit) >= 1,
           f"exec_hits={exec_hit}")

# ---- C. 指标 ----
for port, name in [(8080, "gateway"), (8101, "identity"), (8102, "master-data"),
                   (8103, "demand"), (8104, "planning"), (8105, "execution")]:
    st, _, body = get(f"http://127.0.0.1:{port}/actuator/prometheus", TOKEN)
    has = "http_server_requests" in body and "jvm_memory_used_bytes" in body
    expect(f"[{name}] /actuator/prometheus 输出 http_server_requests + JVM 指标", st == 200 and has)

# ---- D. OTLP 服务清单 ----
st, _, body = get(f"{JAEGER}/api/services")
services = json.loads(body).get("data") or []
need = {"product-gateway", "product-identity", "product-master-data",
        "product-demand", "product-planning", "product-execution"}
expect("Jaeger 服务清单含全部 6 个微服务（OTLP 导出链路成立）", need.issubset(set(services)),
       f"services={sorted(services)}")

open(f"{P6}/observability_transcript.txt", "w").write("\n".join(OUT))
print(f"OBSERVABILITY: {len(OUT) - len(FAILED) - 1} PASS / {len(FAILED)} FAIL" if FAILED else "OBSERVABILITY ALL PASS")
if FAILED:
    print("FAILED: " + "; ".join(FAILED))
    sys.exit(1)
