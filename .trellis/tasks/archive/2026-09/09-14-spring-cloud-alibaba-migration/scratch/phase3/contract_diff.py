#!/usr/bin/env python3
# Phase 3 contract diff v3 (final): monolith (8082 temp instance from current source) vs product-services (gateway 8080).
# Products seeded via SQL on both sides: monolith /demand/product create+detail and ALL /pps/product-route
# endpoints are frozen-broken (ProductMoldParam/ProductRoute have no mapper -> TableInfoCache 500).
# Those are probed and recorded as documented differences instead of being diffed.
import json, re, subprocess, sys, urllib.request, urllib.parse

MONO = "http://127.0.0.1:8082"
SVC  = "http://127.0.0.1:8080"
MONO_PID, SVC_PID = 90001, 90002

def sql(stmt):
    subprocess.run(["docker", "exec", "-i", "product-mysql", "mysql", "-uroot", "-p123456", "-e", stmt],
                   check=True, capture_output=True)

def http(base, method, path, token=None, body=None):
    path = urllib.parse.quote(path, safe="/?&=@%:+,")
    req = urllib.request.Request(base + path, method=method)
    if token: req.add_header("Authorization", "Bearer " + token)
    data = None
    if body is not None:
        req.add_header("Content-Type", "application/json")
        data = json.dumps(body).encode()
    try:
        with urllib.request.urlopen(req, data) as r:
            return r.status, json.loads(r.read())
    except urllib.error.HTTPError as e:
        try: return e.code, json.loads(e.read())
        except Exception: return e.code, None

SNOW = re.compile(r'^\d{19}$'); TS = re.compile(r'^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}$')
def norm(v):
    if isinstance(v, dict): return {k: norm(x) for k, x in v.items()}
    if isinstance(v, list): return [norm(x) for x in v]
    if isinstance(v, str):
        if SNOW.match(v): return "<SNOWFLAKE>"
        if TS.match(v): return "<TS>"
        if v in ("90001", "90002"): return "<SEEDED_PID>"
    return v

def shape(v):
    if isinstance(v, dict): return (sorted(v.keys()), [shape(x) for x in v.values()])
    if isinstance(v, list): return ("list", [shape(x) for x in v[:1]])
    return type(v).__name__

def login(base):
    with urllib.request.urlopen(base + "/captchaImage") as r:
        cap = json.loads(r.read())
    uuid = cap["uuid"]
    def rget(key):
        return subprocess.check_output(["docker", "exec", "product-redis", "redis-cli", "-a", "123456",
            "--no-auth-warning", "GET", key]).decode().strip().strip('"')
    code = rget(f"captcha_codes:{uuid}") or rget(f"identity:captcha_codes:{uuid}")
    st, resp = http(base, "POST", "/login", body={"username": "admin", "password": "admin123", "code": code, "uuid": uuid})
    assert st == 200 and resp.get("code") == 200, (base, resp)
    return resp["token"]

CUSTOMER = {"customerName": "契约对比客户-C4", "remark": "phase3-contract-diff"}
PRODUCT_NAME = "契约对比产品-P4"
ORDER = {}; ORDERLINE = {}; ROUTE = {}
results = []; diff_probes = {}

mt, st_ = login(MONO), login(SVC)

# ---- seed products via SQL (monolith product API frozen-broken) ----
sql(f"""INSERT INTO product.product (product_id, product_name, image, material_code, color_code, create_time, update_time)
VALUES ({MONO_PID}, '{PRODUCT_NAME}', '', 'MAT-DIFF', 'CLR-DIFF', NOW(), NOW())""")
sql(f"""INSERT INTO master_data_db.product (product_id, product_name, image, material_code, color_code, create_time, update_time)
VALUES ({SVC_PID}, '{PRODUCT_NAME}', '', 'MAT-DIFF', 'CLR-DIFF', NOW(), NOW())""")

# ---- create customer/order/orderLine via APIs; route only on service ----
for base, tok, tag, pid in [(MONO, mt, "mono", MONO_PID), (SVC, st_, "svc", SVC_PID)]:
    st, r = http(base, "POST", "/demand/customer", tok, CUSTOMER); assert r.get("code") == 200, (tag, r)
    st, resp = http(base, "GET", f"/demand/customer/list?pageNum=1&pageSize=50&customerName={CUSTOMER['customerName']}", tok)
    CUSTOMER[tag + "Id"] = [x for x in resp["rows"] if x.get("customerName") == CUSTOMER["customerName"]][0]["customerId"]
    st, r = http(base, "POST", "/demand/order", tok, {"customerId": CUSTOMER[tag+"Id"], "dueDate": "2026-10-01 00:00:00", "priority": 5})
    assert r.get("code") == 200, (tag, r)
    st, resp = http(base, "GET", f"/demand/order/list?pageNum=1&pageSize=50&customerId={CUSTOMER[tag+'Id']}", tok)
    ORDER[tag + "Id"] = resp["rows"][0]["orderId"]
    st, r = http(base, "POST", "/demand/orderLine", tok, {"orderId": ORDER[tag+"Id"], "productId": pid, "qty": 100})
    assert r.get("code") == 200, (tag, r)
    st, resp = http(base, "GET", f"/demand/orderLine/list?pageNum=1&pageSize=50&orderId={ORDER[tag+'Id']}", tok)
    ORDERLINE[tag + "Id"] = resp["rows"][0]["orderLineId"]

route_ops = [
    {"opCode": "SETUP", "sequence": 10, "eligibleResourceRule": "RULE_SETUP_MACHINE", "stdTimeModel": "TM_SETUP_BASE", "queuePolicy": "FIFO"},
    {"opCode": "INJECT", "sequence": 20, "eligibleResourceRule": "RULE_INJECT_MACHINE", "stdTimeModel": "TM_INJECT_A2", "queuePolicy": "FIFO"},
    {"opCode": "POST_QC_PUTAWAY", "sequence": 30, "eligibleResourceRule": "RULE_POST_WORKSTATION", "stdTimeModel": "TM_POST_UNIT", "queuePolicy": "FIFO"}]
st, r = http(SVC, "POST", "/pps/product-route", st_, {"productId": SVC_PID, "version": "v1", "isActive": 1, "operations": route_ops})
assert r.get("code") == 200, r
ROUTE["svcId"] = r["routeId"]

# ---- diffs ----
def check(name, mono, svc):
    same = norm(mono) == norm(svc) and shape(mono) == shape(svc)
    results.append(("PASS" if same else "DIFF", name, mono, svc))

check("GET /demand/customer/list (filtered)",
      http(MONO, "GET", f"/demand/customer/list?pageNum=1&pageSize=50&customerName={CUSTOMER['customerName']}", mt)[1],
      http(SVC, "GET", f"/demand/customer/list?pageNum=1&pageSize=50&customerName={CUSTOMER['customerName']}", st_)[1])
check("GET /demand/customer/{id}",
      http(MONO, "GET", f"/demand/customer/{CUSTOMER['monoId']}", mt)[1],
      http(SVC, "GET", f"/demand/customer/{CUSTOMER['svcId']}", st_)[1])
check("GET /demand/product/list (filtered)",
      http(MONO, "GET", f"/demand/product/list?pageNum=1&pageSize=50&productName={PRODUCT_NAME}", mt)[1],
      http(SVC, "GET", f"/demand/product/list?pageNum=1&pageSize=50&productName={PRODUCT_NAME}", st_)[1])
def order_rows(base, tok, oid):
    st, resp = http(base, "GET", f"/demand/order/list?pageNum=1&pageSize=50&customerId=", tok)
    rows = [r for r in resp["rows"] if r.get("orderId") == oid]
    return {"total": len(rows), "rows": rows}
check("GET /demand/order/list (own row, page query has no customerId filter on either side)",
      order_rows(MONO, mt, ORDER["monoId"]), order_rows(SVC, st_, ORDER["svcId"]))
check("GET /demand/order/{id}",
      http(MONO, "GET", f"/demand/order/{ORDER['monoId']}", mt)[1],
      http(SVC, "GET", f"/demand/order/{ORDER['svcId']}", st_)[1])
check("GET /demand/orderLine/list (by order)",
      http(MONO, "GET", f"/demand/orderLine/list?pageNum=1&pageSize=50&orderId={ORDER['monoId']}", mt)[1],
      http(SVC, "GET", f"/demand/orderLine/list?pageNum=1&pageSize=50&orderId={ORDER['svcId']}", st_)[1])
check("GET /demand/orderLine/{id}",
      http(MONO, "GET", f"/demand/orderLine/{ORDERLINE['monoId']}", mt)[1],
      http(SVC, "GET", f"/demand/orderLine/{ORDERLINE['svcId']}", st_)[1])
assert http(MONO, "PUT", f"/demand/order/check/{ORDER['monoId']}", mt)[1]["code"] == 200
assert http(SVC, "PUT", f"/demand/order/check/{ORDER['svcId']}", st_)[1]["code"] == 200
check("GET /demand/order/{id} after check",
      http(MONO, "GET", f"/demand/order/{ORDER['monoId']}", mt)[1],
      http(SVC, "GET", f"/demand/order/{ORDER['svcId']}", st_)[1])
check("PUT /demand/orderLine/release",
      http(MONO, "PUT", f"/demand/orderLine/release/{ORDERLINE['monoId']}", mt)[1],
      http(SVC, "PUT", f"/demand/orderLine/release/{ORDERLINE['svcId']}", st_)[1])
check("PUT /demand/orderLine/cancelRelease",
      http(MONO, "PUT", f"/demand/orderLine/cancelRelease/{ORDERLINE['monoId']}", mt)[1],
      http(SVC, "PUT", f"/demand/orderLine/cancelRelease/{ORDERLINE['svcId']}", st_)[1])
check("GET missing customer (error body)",
      http(MONO, "GET", "/demand/customer/999999999", mt)[1],
      http(SVC, "GET", "/demand/customer/999999999", st_)[1])
check("POST /demand/orderLine missing orderId (error body)",
      http(MONO, "POST", "/demand/orderLine", mt, {"productId": MONO_PID, "qty": 1})[1],
      http(SVC, "POST", "/demand/orderLine", st_, {"productId": SVC_PID, "qty": 1})[1])

# ---- documented difference probes ----
diff_probes["POST /demand/product with moldParams (monolith frozen-broken: TableInfoCache)"] = {
    "monolith": http(MONO, "POST", "/demand/product", mt, {"productName": "P-X", "moldParams": [{"moldId": 1}]}),
    "service": http(SVC, "POST", "/demand/product", st_, {"productName": "P-X2", "moldParams": [{"moldId": 9001, "cycleTimeSec": 1}]})}
diff_probes["GET /demand/product/{id} (monolith frozen-broken)"] = {
    "monolith": http(MONO, "GET", f"/demand/product/{MONO_PID}", mt),
    "service": http(SVC, "GET", f"/demand/product/{SVC_PID}", st_)}
diff_probes["GET /pps/product-route/list (monolith frozen-broken)"] = {
    "monolith": http(MONO, "GET", f"/pps/product-route/list?pageNum=1&pageSize=50&productId={MONO_PID}", mt),
    "service": http(SVC, "GET", f"/pps/product-route/list?pageNum=1&pageSize=50&productId={SVC_PID}", st_)}
diff_probes["POST orderLine with nonexistent product (deliberate: service validates cross-domain refs)"] = {
    "monolith": http(MONO, "POST", "/demand/orderLine", mt, {"orderId": ORDER["monoId"], "productId": 999999, "qty": 1}),
    "service": http(SVC, "POST", "/demand/orderLine", st_, {"orderId": ORDER["svcId"], "productId": 999999, "qty": 1})}
diff_probes["POST second ACTIVE route on service (DB uk constraint keeps single-active)"] = {
    "service": http(SVC, "POST", "/pps/product-route", st_, {"productId": SVC_PID, "version": "v1", "isActive": 1,
        "operations": [{"opCode": "INJECT", "sequence": 10, "eligibleResourceRule": "RULE_INJECT_MACHINE", "stdTimeModel": "TM_INJECT_A2", "queuePolicy": "FIFO"}]})}

# ---- results ----
passed = sum(1 for v, *_ in results if v == "PASS")
print(f"=== contract diff: {passed}/{len(results)} PASS ===")
for v, name, mono, svc in results:
    print(f"[{v}] {name}")
    if v == "DIFF":
        print("   mono:", json.dumps(norm(mono), ensure_ascii=False)[:600])
        print("   svc :", json.dumps(norm(svc), ensure_ascii=False)[:600])
print("\n=== documented differences (monolith frozen bugs / deliberate additions) ===")
print(json.dumps(diff_probes, ensure_ascii=False, indent=1, default=str))

# ---- cleanup (API then SQL; fault tolerant) ----
for base, tok, tag in [(MONO, mt, "mono"), (SVC, st_, "svc")]:
    http(base, "DELETE", f"/demand/orderLine/{ORDERLINE[tag+'Id']}", tok)
    http(base, "DELETE", f"/demand/order/{ORDER[tag+'Id']}", tok)
    http(base, "DELETE", f"/demand/customer/{CUSTOMER[tag+'Id']}", tok)
http(SVC, "DELETE", f"/pps/product-route/{ROUTE['svcId']}", st_)
cleanup = [
    f"DELETE FROM product.order_line WHERE order_id IN (SELECT t.oid FROM (SELECT order_id oid FROM product.customer_order WHERE customer_id={CUSTOMER['monoId']}) t)",
    f"DELETE FROM product.customer_order WHERE customer_id={CUSTOMER['monoId']}",
    f"DELETE FROM product.customer WHERE customer_id={CUSTOMER['monoId']}",
    f"DELETE FROM product.product WHERE product_id={MONO_PID}",
    f"DELETE FROM master_data_db.route_operation WHERE route_id={ROUTE['svcId']}",
    f"DELETE FROM master_data_db.product_route WHERE product_id={SVC_PID}",
    f"DELETE FROM master_data_db.product WHERE product_id IN ({SVC_PID}) OR product_name IN ('P-X2')",
]
for s in cleanup:
    try: sql(s)
    except Exception as e: print("cleanup failed:", s[:70], repr(e))
print("\ncleanup done")
