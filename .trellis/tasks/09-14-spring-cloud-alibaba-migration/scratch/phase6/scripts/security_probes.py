#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Phase 6 安装验收探测（统一切换安全基线，复用 Phase 2/3/4/5 各阶段探测项）：
  1) 无 token / 伪造 token / 篡改 token（经网关 + 直连 8101）
  2) 伪造内部头（X-User-Id 等）不作为鉴权依据
  3) /internal/** 网关穿透封堵（字面 + 变形）
  4) 非 admin 用户（planning_svc，common 角色）：业务端点 200、system 端点 403、ops 端点 403（Phase 6 新门禁）
  5) admin：ops 端点 200（门禁放行 + 审计可用）
  6) Sentinel 规则 Nacos 持久化实证：经 Nacos 发布低 QPS 网关流控规则 -> 热加载 -> 429；
     恢复规则后限流解除（sentinel-datasource 生效证据）
"""
import json
import subprocess
import sys
import time
import urllib.error
import urllib.request
import urllib.parse

GW = "http://127.0.0.1:8080"
ID = "http://127.0.0.1:8101"
P6 = "/home/lenny/Projects/pps/product/.trellis/tasks/09-14-spring-cloud-alibaba-migration/scratch/phase6"
OUT = []
FAILED = []

def log(s):
    print(s, flush=True)
    OUT.append(s)

def expect(desc, cond, detail=""):
    tag = "PASS" if cond else "FAIL"
    log(f"[{tag}] {desc}" + (f" | {detail}" if detail else ""))
    if not cond:
        FAILED.append(desc)

def raw(method, url, headers=None, body=None):
    req = urllib.request.Request(url, method=method)
    for k, v in (headers or {}).items():
        req.add_header(k, v)
    data = body.encode() if isinstance(body, str) else (json.dumps(body).encode() if body else None)
    try:
        with urllib.request.urlopen(req, data=data, timeout=10) as resp:
            return resp.status, dict(resp.headers), resp.read().decode()
    except urllib.error.HTTPError as e:
        return e.code, dict(e.headers), e.read().decode()

def sh(args):
    return subprocess.check_output(args).decode().strip()

def sql(stmt, db):
    return sh(["docker", "exec", "product-mysql", "mysql", "-uroot", "-p123456", "-N", "-B", "-e", stmt, db])

def login_captcha(username, password):
    st, _, body = raw("GET", GW + "/captchaImage")
    cap = json.loads(body)
    code = sh(["docker", "exec", "product-redis", "redis-cli", "-a", "123456", "--no-auth-warning",
               "GET", f"identity:captcha_codes:{cap['uuid']}"]).strip().strip('"')
    st, _, body = raw("POST", GW + "/login", {"Content-Type": "application/json"},
                      json.dumps({"username": username, "password": password, "code": code, "uuid": cap["uuid"]}))
    return json.loads(body).get("token")

# ---- 1. 无 token ----
st, h, b = raw("GET", GW + "/system/menu/list")
expect("无 token 经网关 -> 401 单体逐字节错误体 + X-Trace-Id",
       st == 200 and b == '{"msg":"请求访问：/system/menu/list，认证失败，无法访问系统资源","code":401}'
       and "X-Trace-Id" in h, f"http={st} body={b[:80]}")
st, h, b = raw("GET", ID + "/system/menu/list")
expect("无 token 直连 8101 -> 同一 401 错误体（服务本地验签链）",
       st == 200 and b.startswith('{"msg":"请求访问：/system/menu/list') and '"code":401' in b, f"http={st}")

# ---- 2. 伪造/篡改 token ----
st, h, b = raw("GET", GW + "/getInfo", {"Authorization": "Bearer fake.token.sig"})
expect("伪造 token -> 401 错误体", st == 200 and '"code":401' in b, f"http={st}")
# 用 admin 真实 token 篡改 payload 段
admin_token = login_captcha("admin", "admin123")
head, payload, sig = admin_token.split(".")
tampered = f"{head}.{payload[:-4]}AAAA.{sig}"
st, h, b = raw("GET", GW + "/getInfo", {"Authorization": "Bearer " + tampered})
expect("篡改 payload 的 token -> 401（RS256 验签拒绝）", st == 200 and '"code":401' in b, f"http={st}")

# ---- 3. 伪造内部头 ----
st, h, b = raw("GET", GW + "/system/menu/list",
               {"X-User-Id": "1", "X-User-Name": "admin", "X-User-Permissions": "*:*:*"})
expect("伪造内部头（无 token）仍 401（内部头不作为鉴权依据）", st == 200 and '"code":401' in b, f"http={st}")

# ---- 4. /internal/** 穿透封堵 ----
for path in ["/internal/planning/ops/health", "/planning/internal/planning/ops/health",
             "/master-data/internal/master-data/products/exists", "/%69nternal/planning/ops/health",
             "/planning/%69nternal/ops/health", "/execute/internal/execution/ops/outbox"]:
    st, h, b = raw("GET", GW + path, {"Authorization": "Bearer " + admin_token})
    ok = st == 404 and b == '{"msg":"请求路径不存在","code":404}' and "X-Trace-Id" in h
    expect(f"网关拒绝内部端点（含变形）: {path}", ok, f"http={st} body={b[:60]}")

# ---- 5. 非 admin 用户（planning_svc：common 角色、权限串空集） ----
svc_token = login_captcha("planning_svc", "planning-svc-dev-pwd")
st, h, b = raw("GET", GW + "/demand/customer/list?pageNum=1&pageSize=1",
               {"Authorization": "Bearer " + svc_token})
expect("非 admin 业务端点 200（业务域仅要求登录，冻结现状）", st == 200 and '"code":200' in b, f"http={st} body={b[:60]}")
st, h, b = raw("GET", GW + "/system/menu/list", {"Authorization": "Bearer " + svc_token})
expect("非 admin /system/menu/list -> 403 没有权限", st == 200 and b == '{"msg":"没有权限，请联系管理员授权","code":403}',
       f"http={st} body={b[:80]}")
st, h, b = raw("GET", "http://127.0.0.1:8104" + "/internal/planning/ops/health", {"Authorization": "Bearer " + svc_token})
expect("Phase 6 新门禁：非 admin 调 ops 端点 -> 403", st == 200 and '"code":403' in b, f"http={st} body={b[:80]}")
st, h, b = raw("GET", "http://127.0.0.1:8105" + "/internal/execution/ops/outbox", {"Authorization": "Bearer " + svc_token})
expect("Phase 6 新门禁：非 admin 调 execution ops 巡检 -> 403", st == 200 and '"code":403' in b, f"http={st} body={b[:80]}")
# 服务身份令牌（无请求上下文的合法服务凭据）也不得调用 ops
svc2 = sh(["curl", "-s", "-X", "POST", ID + "/internal/identity/service-token",
           "-H", "Content-Type: application/json",
           "-d", '{"username":"planning_svc","password":"planning-svc-dev-pwd"}'])
svc2_token = json.loads(svc2).get("token", "x")
st, h, b = raw("GET", "http://127.0.0.1:8103" + "/internal/demand/ops/health", {"Authorization": "Bearer " + svc2_token})
expect("服务身份令牌调 ops 端点 -> 403（permissions 空集）", st == 200 and '"code":403' in b, f"http={st} body={b[:80]}")

# ---- 6. admin ops 放行 ----
st, h, b = raw("GET", "http://127.0.0.1:8104" + "/internal/planning/ops/health", {"Authorization": "Bearer " + admin_token})
expect("admin 调 ops 端点 -> 200（门禁放行）", st == 200 and '"code":200' in b, f"http={st} body={b[:80]}")

# ---- 7. Sentinel 规则 Nacos 持久化实证（发布低 QPS 规则 -> 热加载 429 -> 恢复） ----
pub = raw("POST", "http://127.0.0.1:8848/nacos/v1/cs/configs", {"Content-Type": "application/x-www-form-urlencoded"},
          "tenant=dev&dataId=product-gateway-flow-rules.json&group=PRODUCT_GATEWAY&type=json&content=" +
          urllib.parse.quote(json.dumps([{"resource": "identity-auth", "grade": 1, "count": 2}]), safe=""))
expect("Sentinel 规则发布到 Nacos（sentinel-datasource dataId）", pub[2] == "true", f"resp={pub[2]}")
time.sleep(3)  # 等 Nacos 推送 + GatewayRuleManager 热加载
codes = []
for i in range(8):
    st, h, b = raw("GET", GW + "/captchaImage")
    codes.append(st)
    time.sleep(0.05)
n429 = sum(1 for c in codes if c == 429)
expect("Nacos 规则热加载：QPS=2 -> 连发 8 次，后续 429 + 统一错误体",
       codes[:2] == [200, 200] and n429 >= 4, f"codes={codes}")
st, h, b = raw("GET", GW + "/captchaImage")
expect("429 响应体为网关统一错误体", st == 429 and '"code":429' in b and "X-Trace-Id" in h, f"body={b[:80]}")
# 恢复正式规则（count=200）
raw("POST", "http://127.0.0.1:8848/nacos/v1/cs/configs", {"Content-Type": "application/x-www-form-urlencoded"},
    "tenant=dev&dataId=product-gateway-flow-rules.json&group=PRODUCT_GATEWAY&type=json&content=" +
    urllib.parse.quote(json.dumps([
        {"resource": "identity-auth", "grade": 1, "count": 200},
        {"resource": "identity-system", "grade": 1, "count": 200},
        {"resource": "demand-business", "grade": 1, "count": 200},
        {"resource": "planning-assignment", "grade": 1, "count": 200}]), safe=""))
time.sleep(3)
codes = [raw("GET", GW + "/captchaImage")[0] for _ in range(8)]
expect("恢复 Nacos 规则（QPS=200）后限流解除", all(c == 200 for c in codes), f"codes={codes}")

# ---- 8. swagger 文档聚合（匿名可读，baselines §1.3 显式差异） ----
st, h, b = raw("GET", GW + "/identity/v3/api-docs")
ok200 = st == 200 and "openapi" in b.lower()
st2, h2, b2 = raw("GET", GW + "/planning/v3/api-docs")
expect("网关 swagger 聚合：/{svc}/v3/api-docs 匿名 200（identity+planning）",
       ok200 and st2 == 200 and "openapi" in b2.lower(), f"identity={st} planning={st2}")
st, h, b = raw("GET", GW + "/swagger-ui.html")
expect("网关 swagger-ui.html 可达", st == 200, f"http={st}")

log("")
log(f"SECURITY PROBES: {len(OUT) - len(FAILED) - 1} PASS / {len(FAILED)} FAIL")
if FAILED:
    log("FAILED items: " + "; ".join(FAILED))
    open(f"{P6}/security_probes_transcript.txt", "w").write("\n".join(OUT))
    sys.exit(1)
open(f"{P6}/security_probes_transcript.txt", "w").write("\n".join(OUT))
print("SECURITY ALL PASS")
