#!/usr/bin/env python3
"""issue #1-#5 修复后的网关入口实测探测（transcript: issue_probes_transcript.txt）"""
import json
import subprocess
import urllib.request
import urllib.error

BASE = "http://127.0.0.1:8080"
OUT = open("/home/lenny/Projects/pps/product/scratch/issue-fixes/issue_probes_transcript.txt", "w")
results = []


def emit(line):
    print(line)
    OUT.write(line + "\n")


def http(method, path, body=None, token=None, base=BASE):
    req = urllib.request.Request(base + path, method=method)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    data = json.dumps(body).encode() if body is not None else None
    try:
        with urllib.request.urlopen(req, data, timeout=10) as resp:
            return resp.status, dict(resp.headers), json.loads(resp.read().decode())
    except urllib.error.HTTPError as e:
        try:
            return e.code, dict(e.headers), json.loads(e.read().decode())
        except Exception:
            return e.code, dict(e.headers), {}


def expect(name, ok, detail=""):
    results.append(ok)
    emit(("PASS " if ok else "FAIL ") + name + ("" if ok else "  -> " + str(detail)))


# ---------- 登录 ----------
st, _, cap = http("GET", "/captchaImage")
uuid = cap["uuid"]
code = subprocess.run(["docker", "exec", "product-redis", "redis-cli", "-a", "123456",
                       "--no-auth-warning", "GET", f"identity:captcha_codes:{uuid}"],
                      capture_output=True, text=True).stdout.strip().strip('"')
st, _, login = http("POST", "/login", {"username": "admin", "password": "admin123",
                                       "code": code, "uuid": uuid})
expect("登录获取 token", st == 200 and login.get("token"), login)
TOKEN = login["token"]

# ---------- issue #1 + #5：服务内 404 语义 ----------
st, hd, body = http("GET", "/system/user/list?pageNum=1&pageSize=5", token=TOKEN)
expect("#1 GET /system/user/list → 统一 404 体（不再 500 类型不匹配）",
       st == 200 and body.get("code") == 404 and body.get("msg") == "请求路径不存在", body)
expect("#1 服务 404 响应带 X-Trace-Id", bool(hd.get("X-Trace-Id")), dict(hd))

st, hd, body = http("POST", "/register", {"username": "x"}, token=TOKEN)
expect("#5 POST /register（冻结不存在）→ 统一 404 体", body.get("code") == 404
       and body.get("msg") == "请求路径不存在", body)

st, hd, body = http("GET", "/system/user/1", token=TOKEN)
expect("#1 GET /system/user/1（数字路径不受正则约束影响）仍 200",
       body.get("code") == 200, body.get("msg"))

# ---------- issue #2：字典缓存往返 ----------
subprocess.run(["docker", "exec", "product-redis", "redis-cli", "-a", "123456",
                "--no-auth-warning", "--scan", "--pattern", "identity:sys_dict:*"],
               capture_output=True, text=True)
subprocess.run(["sh", "-c", "docker exec product-redis redis-cli -a 123456 --no-auth-warning "
                             "--scan --pattern 'identity:sys_dict:*' | xargs -r "
                             "docker exec -i product-redis redis-cli -a 123456 --no-auth-warning DEL"],
               capture_output=True, text=True)
st1, _, b1 = http("GET", "/system/dict/data/type/sys_normal_disable", token=TOKEN)
st2, _, b2 = http("GET", "/system/dict/data/type/sys_normal_disable", token=TOKEN)
expect("#2 第一次读（DB 加载并回写缓存）200", st1 == 200 and b1.get("code") == 200,
       b1.get("msg"))
expect("#2 第二次读（命中缓存）仍 200 —— 缓存不再自我污染",
       st2 == 200 and b2.get("code") == 200, b2.get("msg"))
expect("#2 两次读返回数据一致", json.dumps(b1.get("data"), sort_keys=True, ensure_ascii=False)
       == json.dumps(b2.get("data"), sort_keys=True, ensure_ascii=False))

# ---------- issue #4：/execute/event 直录校验 ----------
st, _, body = http("POST", "/execute/event",
                   {"taskId": 0, "eventType": "START", "resourceId": 1}, token=TOKEN)
expect("#4 taskId=0 → 拒绝：任务事件必须指定有效的任务ID",
       body.get("code") == 500 and "任务事件必须指定有效的任务ID" in str(body.get("msg")), body)

st, _, body = http("POST", "/execute/event",
                   {"taskId": 999999999, "eventType": "START"}, token=TOKEN)
expect("#4 taskId 不存在 → 拒绝：任务不存在（经 planning 契约校验）",
       body.get("code") == 500 and "任务不存在" in str(body.get("msg")), body)

# ---------- issue #3：OpenAPI servers 收敛 ----------
import re
for prefix in ("identity", "master-data", "planning"):
    st, _, doc = http("GET", f"/{prefix}/v3/api-docs")
    servers = (doc or {}).get("servers", [])
    ok = servers == [{"url": "/", "description": "经网关按前缀访问"}]
    expect(f"#3 /{prefix}/v3/api-docs servers 收敛为相对路径", ok, servers)
    raw = json.dumps(doc)
    expect(f"#3 /{prefix}/v3/api-docs 无内网地址/直连端口泄露",
           not re.search(r"10\.\d+\.\d+\.\d+", raw) and ":810" not in raw, raw[:200])

emit("")
emit(f"RESULT: {sum(results)}/{len(results)} PASS")
OUT.close()
