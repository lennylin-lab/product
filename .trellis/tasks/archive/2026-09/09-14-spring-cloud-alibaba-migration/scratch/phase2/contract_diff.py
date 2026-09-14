#!/usr/bin/env python3
"""Phase 2 contract diff: monolith (8081) vs identity via gateway (8080).
Same account, same permission baseline. Volatile fields (token/uuid/img and
timestamps/IP recorded at login) are normalized before comparison."""
import json, subprocess, urllib.request

MONO = "http://127.0.0.1:8082"  # 当前源码构建的单体（8081 上为 fc52328 提交前的旧构建）
CLOUD = "http://127.0.0.1:8080"

def req(base, path, method="GET", body=None, headers=None):
    r = urllib.request.Request(base + path, method=method,
                               data=json.dumps(body).encode() if body else None,
                               headers={"Content-Type": "application/json", **(headers or {})})
    try:
        with urllib.request.urlopen(r) as resp:
            return json.loads(resp.read().decode())
    except urllib.error.HTTPError as e:
        return json.loads(e.read().decode())

def captcha(base, key_prefix):
    data = req(base, "/captchaImage")
    uuid = data["uuid"]
    out = subprocess.run(["docker", "exec", "product-redis", "redis-cli", "--raw", "-a", "123456",
                          "GET", f"{key_prefix}{uuid}"], capture_output=True, text=True)
    code = out.stdout.strip().strip('"')
    assert code, f"captcha not found in redis for {uuid}"
    return data, uuid, code

def login(base, key_prefix, username, password):
    _, uuid, code = captcha(base, key_prefix)
    return req(base, "/login", "POST", {"username": username, "password": password,
                                        "code": code, "uuid": uuid})

def clean(node):
    """normalize volatile fields"""
    if isinstance(node, dict):
        return {k: clean(v) for k, v in sorted(node.items())
                if k not in ("token", "uuid", "img", "loginDate", "loginIp", "createTime", "updateTime")}
    if isinstance(node, list):
        return [clean(x) for x in node]
    return node

def diff(path, a, b):
    da, db = clean(a), clean(b)
    if da == db:
        print(f"PASS {path}: diff = 0")
        return True
    print(f"FAIL {path}")
    def flat(d, prefix=""):
        out = {}
        if isinstance(d, dict):
            for k, v in d.items():
                out.update(flat(v, f"{prefix}.{k}"))
        elif isinstance(d, list):
            for i, v in enumerate(d):
                out.update(flat(v, f"{prefix}[{i}]"))
        else:
            out[prefix] = d
        return out
    fa, fb = flat(da), flat(db)
    for k in sorted(set(fa) | set(fb)):
        if fa.get(k, "<absent>") != fb.get(k, "<absent>"):
            print(f"  {k}: mono={fa.get(k, '<absent>')!r} cloud={fb.get(k, '<absent>')!r}")
    return False

results = []

for user, pwd in [("admin", "admin123"), ("ptester", "admin123")]:
    m = login(MONO, "captcha_codes:", user, pwd)
    c = login(CLOUD, "identity:captcha_codes:", user, pwd)
    results.append(diff(f"POST /login ({user})",
                        {k: v for k, v in m.items() if k != "token"},
                        {k: v for k, v in c.items() if k != "token"}))
    mt, ct = m.get("token"), c.get("token")
    assert mt and ct, f"login token missing: mono={bool(mt)} cloud={bool(ct)} (msg: {m.get('msg')} / {c.get('msg')})"
    results.append(True)

    for path in ["/getInfo", "/getRouters", "/system/menu/list", "/system/dict/type/list?pageNum=1&pageSize=10",
                 "/system/dict/data/list?pageNum=1&pageSize=10", "/system/dict/data/type/sys_user_sex",
                 "/system/menu/treeselect", "/system/menu/roleMenuTreeselect/2", "/system/dict/type/optionselect",
                 "/system/user/profile", "/system/user/1"]:
        ma = req(MONO, path, headers={"Authorization": f"Bearer {mt}"})
        ca = req(CLOUD, path, headers={"Authorization": f"Bearer {ct}"})
        results.append(diff(f"GET {path} ({user})", ma, ca))

    # anonymous 401 contract (both HTTP 200 + code 401)
    ma = req(MONO, "/system/menu/list")
    ca = req(CLOUD, "/system/menu/list")
    results.append(diff("GET /system/menu/list (anonymous)", ma, ca))

print("\n=== SUMMARY:", f"{'ALL PASS' if all(results) else 'HAS FAILURES'}", f"({sum(1 for r in results if r)}/{len(results)} checks passed)")
