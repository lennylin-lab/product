#!/usr/bin/env bash
# Phase 2 live validation battery (identity + gateway), 2026-09-14
set -u
GW=http://127.0.0.1:8080
ID=http://127.0.0.1:8101
MO=http://127.0.0.1:8081
OUT=/tmp/phase2-live
REDIS="docker exec product-redis redis-cli -a 123456"

echo "=== 1. anonymous /captchaImage via gateway"
curl -s -o $OUT/captcha.json -D $OUT/captcha.hdr "$GW/captchaImage"
head -c 120 $OUT/captcha.json; echo; grep -i "x-trace-id" $OUT/captcha.hdr
UUID=$(python3 -c "import json;print(json.load(open('$OUT/captcha.json'))['uuid'])")
CODE=$($REDIS GET "identity:captcha_codes:$UUID")
echo "captcha code=$CODE uuid=$UUID"

echo "=== 2. login admin via gateway (with captcha)"
CODE=$(echo $CODE | tr -d '\r\n"')
curl -s -o $OUT/login_admin.json -D $OUT/login_admin.hdr -X POST "$GW/login" -H "Content-Type: application/json" \
  -d "{\"username\":\"admin\",\"password\":\"admin123\",\"code\":\"$CODE\",\"uuid\":\"$UUID\"}"
head -c 200 $OUT/login_admin.json; echo
TOKEN=$(python3 -c "import json;print(json.load(open('$OUT/login_admin.json')).get('token',''))")
echo "token len=${#TOKEN} prefix=${TOKEN:0:20}"

echo "=== 3. getInfo + getRouters via gateway"
curl -s -o $OUT/info_admin.json "$GW/getInfo" -H "Authorization: Bearer $TOKEN"
curl -s -o $OUT/routers_admin.json "$GW/getRouters" -H "Authorization: Bearer $TOKEN"
head -c 150 $OUT/info_admin.json; echo
head -c 150 $OUT/routers_admin.json; echo

echo "=== 4. menu list admin (permitted) + dict list"
curl -s -o $OUT/menulist_admin.json "$GW/system/menu/list" -H "Authorization: Bearer $TOKEN"
head -c 200 $OUT/menulist_admin.json; echo
curl -s -o $OUT/dictlist_admin.json "$GW/system/dict/type/list?pageNum=1&pageSize=10" -H "Authorization: Bearer $TOKEN"
head -c 200 $OUT/dictlist_admin.json; echo

echo "=== 5. protected endpoint without token via gateway -> unified 401 body"
curl -s -o $OUT/noauth.json -D $OUT/noauth.hdr "$GW/system/menu/list"
cat $OUT/noauth.json; echo; grep -i "x-trace-id" $OUT/noauth.hdr

echo "=== 6. forged token -> 401 body"
curl -s "$GW/getInfo" -H "Authorization: Bearer eyJhbGciOiJSUzI1NiJ9.fake.signature" | head -c 200; echo

echo "=== 7. direct port 8101 without token -> 401 body (anti-forgery)"
curl -s -o $OUT/direct_noauth.json -D $OUT/direct_noauth.hdr "$ID/system/menu/list"
cat $OUT/direct_noauth.json; echo; grep -i "x-trace-id" $OUT/direct_noauth.hdr

echo "=== 8. spoofed internal header stripped at gateway (no token -> still 401)"
curl -s "$GW/getInfo" -H "X-User-Id: 1" -H "X-User-Name: admin" | head -c 200; echo

echo "=== 9. direct port with valid token -> 200 (local verify)"
curl -s -o $OUT/direct_auth.json "$ID/getInfo" -H "Authorization: Bearer $TOKEN"
head -c 120 $OUT/direct_auth.json; echo

echo "=== 10. unroute 404 + no-instance 503 unified bodies"
curl -s -D $OUT/nf.hdr "$GW/no-such-path" | head -c 120; echo; grep -i "x-trace-id" $OUT/nf.hdr
curl -s -D $OUT/pl.hdr "$GW/master-data/skeleton/info" -H "Authorization: Bearer $TOKEN" | head -c 120; echo; grep -i "x-trace-id" $OUT/pl.hdr

echo "=== 11. JWKS anonymous via gateway"
curl -s "$GW/jwks" | head -c 200; echo

echo "=== 12. CORS preflight via gateway"
curl -s -o /dev/null -D - -X OPTIONS "$GW/system/menu/list" -H "Origin: http://localhost:5173" -H "Access-Control-Request-Method: GET" | grep -iE "access-control|HTTP"

echo "=== 13. login ptester via gateway (normal role)"
curl -s -o $OUT/captcha2.json "$GW/captchaImage"
UUID2=$(python3 -c "import json;print(json.load(open('$OUT/captcha2.json'))['uuid'])")
CODE2=$($REDIS GET "identity:captcha_codes:$UUID2" | tr -d '\r\n"')
curl -s -o $OUT/login_pt.json -X POST "$GW/login" -H "Content-Type: application/json" \
  -d "{\"username\":\"ptester\",\"password\":\"admin123\",\"code\":\"$CODE2\",\"uuid\":\"$UUID2\"}"
PTOKEN=$(python3 -c "import json;print(json.load(open('$OUT/login_pt.json')).get('token',''))")
echo "ptester token len=${#PTOKEN}"

echo "=== 14. ptester menu list -> 403 (no system:menu:list in token)"
curl -s -o $OUT/pt_menulist.json -D $OUT/pt_menulist.hdr "$GW/system/menu/list" -H "Authorization: Bearer $PTOKEN"
cat $OUT/pt_menulist.json; echo; grep -i "x-trace-id" $OUT/pt_menulist.hdr
curl -s "$GW/system/dict/type/list?pageNum=1&pageSize=10" -H "Authorization: Bearer $PTOKEN" | head -c 200; echo

echo "=== done battery"
