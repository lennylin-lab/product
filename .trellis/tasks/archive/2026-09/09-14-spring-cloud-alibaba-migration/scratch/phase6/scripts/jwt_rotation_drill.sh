#!/usr/bin/env bash
# Phase 6 遗留收口：identity 生产 JWT 密钥注入方式文档化 + 轮换演练（env 双密钥，ADR-0003 决策 3）。
# 步骤：KEY1 签发 -> 注入 KEY2 并发布 KEY1 公钥为轮换窗口 -> 旧 token 仍可验签、新登录用 kid2
#       -> 关闭轮换窗口（只留 KEY2）-> 旧 token 失效。全程经网关 8080 验证。
set -u
export JAVA_HOME=~/.local/share/mise/installs/java/temurin-17.0.20+8
BASE=/home/lenny/Projects/pps/product
P6=$BASE/.trellis/tasks/09-14-spring-cloud-alibaba-migration/scratch/phase6
LOGD=$P6/logs
KEYDIR=$P6/jwt-keys
JARS=$BASE/product-services
mkdir -p "$KEYDIR"
cd "$BASE"

pid_of() { awk -v n="$1" '$1==n{print $2}' "$P6/pids.txt" | tail -1; }
stop_identity() {
  local pid; pid=$(pid_of identity)
  if kill -0 "$pid" 2>/dev/null; then kill "$pid"; sleep 3; fi
  echo "  identity stopped (pid=$pid)"
}
start_identity() { # env assignments as args
  env "$@" OTLP_TRACES_ENABLED=true OTLP_TRACES_ENDPOINT=http://127.0.0.1:4318/v1/traces \
    nohup "$JAVA_HOME/bin/java" -jar "$JARS/product-identity/target/product-identity-0.0.1-SNAPSHOT.jar" \
    --logging.file.path="$LOGD/identity" >> "$LOGD/identity.console.log" 2>&1 &
  local pid=$!
  echo "identity $pid 8101" >> "$P6/pids.txt"
  for i in $(seq 1 45); do
    code=$(curl -s -o /dev/null -w '%{http_code}' --max-time 2 http://127.0.0.1:8101/actuator/health/liveness || true)
    [ "$code" = "200" ] && break; sleep 2
  done
  echo "  identity started pid=$pid liveness=$code"
}
login_token() { # -> TOKEN 或 FAIL
  local cap_json code uuid
  cap_json=$(curl -s http://127.0.0.1:8080/captchaImage)
  uuid=$(echo "$cap_json" | python3 -c "import json,sys;print(json.load(sys.stdin)['uuid'])")
  code=$(docker exec product-redis redis-cli -a 123456 --no-auth-warning GET "identity:captcha_codes:$uuid" | tr -d '"')
  curl -s -X POST http://127.0.0.1:8080/login -H 'Content-Type: application/json' \
    -d "{\"username\":\"admin\",\"password\":\"admin123\",\"code\":\"$code\",\"uuid\":\"$uuid\"}" \
    | python3 -c "import json,sys;print(json.load(sys.stdin).get('token',''))"
}
kid_of() { python3 -c "
import base64, json, sys
t = sys.argv[1].split('.')[0]
t += '=' * (-len(t) % 4)
print(json.loads(base64.urlsafe_b64decode(t))['kid'])" "$1"; }
jwks_kids() { curl -s http://127.0.0.1:8080/jwks | python3 -c "
import json,sys
ks=json.load(sys.stdin)['keys']
print(' '.join(k['kid'][:12] for k in ks), '(n=%d)' % len(ks))"; }
# 注意契约：认证失败也是 HTTP 200 + body code 401 —— 必须检查响应体 code 而非 HTTP 状态
body_code() { curl -s -H "Authorization: Bearer $1" http://127.0.0.1:8080/getInfo | python3 -c "import json,sys;print(json.load(sys.stdin).get('code'))"; }

echo "== [1] 生成两把生产样式 RSA 私钥（PKCS#8，openssl） =="
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out "$KEYDIR/jwt-key-1.pem" 2>/dev/null
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out "$KEYDIR/jwt-key-2.pem" 2>/dev/null
openssl pkey -in "$KEYDIR/jwt-key-1.pem" -pubout -out "$KEYDIR/jwt-key-1.pub.pem" 2>/dev/null
echo "  keys generated: jwt-key-1.pem / jwt-key-2.pem (+pub1)"

echo "== [2] 注入 KEY1 启动 identity -> 登录得 tokenA（kid=kid1） =="
stop_identity
start_identity IDENTITY_JWT_PRIVATE_KEY="$(cat "$KEYDIR/jwt-key-1.pem")"
TOKEN_A=$(login_token)
KID1=$(kid_of "$TOKEN_A")
echo "  tokenA kid=$KID1  jwks: $(jwks_kids)"
echo "  tokenA -> /getInfo body code=$(body_code "$TOKEN_A")"

echo "== [3] 轮换：注入 KEY2（当前）+ KEY1 公钥（轮换窗口）重启 -> 旧 tokenA 仍有效 =="
stop_identity
start_identity IDENTITY_JWT_PRIVATE_KEY="$(cat "$KEYDIR/jwt-key-2.pem")" \
               IDENTITY_JWT_PREVIOUS_PUBLIC_KEY="$(cat "$KEYDIR/jwt-key-1.pub.pem")"
echo "  jwks: $(jwks_kids)"
  echo "  tokenA(旧 kid) -> /getInfo body code=$(body_code "$TOKEN_A")  (200=轮换窗口内旧 token 可验签)"
TOKEN_B=$(login_token)
KID2=$(kid_of "$TOKEN_B")
echo "  tokenB kid=$KID2 -> /getInfo body code=$(body_code "$TOKEN_B")  (新登录由当前密钥签发)"
if [ "$KID1" != "$KID2" ]; then echo "  PASS 轮换后新 token 使用新 kid ($KID1 -> $KID2)"; else echo "  FAIL kid 未轮换"; fi

echo "== [4] 关闭轮换窗口（只留 KEY2）重启 identity+gateway -> tokenA 失效(401)、tokenB 有效 =="
# 说明：网关/服务侧 JWKS 按 kid 缓存、未知 kid 才触发刷新（JwksKeyHolder 设计）——
# 撤销已缓存的 kid 需等缓存刷新或重启网关；演练中重启网关以立即观察撤销语义。
stop_identity
GWPID=$(pid_of gateway)
kill "$GWPID" 2>/dev/null
# 等旧网关完全退出、端口释放（优雅停机可能超过 3s；否则请求仍被旧进程服务，缓存未刷新）
for i in $(seq 1 60); do
  kill -0 "$GWPID" 2>/dev/null || break
  sleep 2
done
for i in $(seq 1 30); do
  ss -tln | grep -q ":8080 " || break
  sleep 2
done
echo "  old gateway exited (pid=$GWPID), port 8080 released"
start_identity IDENTITY_JWT_PRIVATE_KEY="$(cat "$KEYDIR/jwt-key-2.pem")"
OTLP_TRACES_ENABLED=true OTLP_TRACES_ENDPOINT=http://127.0.0.1:4318/v1/traces \
  nohup "$JAVA_HOME/bin/java" -jar "$JARS/product-gateway/target/product-gateway-0.0.1-SNAPSHOT.jar" \
  --logging.file.path="$LOGD/gateway" >> "$LOGD/gateway.console.log" 2>&1 &
echo "gateway $! 8080" >> "$P6/pids.txt"
for i in $(seq 1 45); do
  gcode=$(curl -s -o /dev/null -w '%{http_code}' --max-time 2 http://127.0.0.1:8080/captchaImage || true)
  [ "$gcode" = "200" ] && break; sleep 2
done
echo "  gateway restarted liveness=$gcode"
echo "  jwks: $(jwks_kids)"
echo "  tokenA -> /getInfo body code=$(body_code "$TOKEN_A")  (401=窗口关闭后旧 token 失效)"
echo "  tokenB -> /getInfo body code=$(body_code "$TOKEN_B")  (200=当前密钥签发的 token 正常)"

echo "== [5] 恢复开发默认（临时密钥）重启 identity =="
stop_identity
start_identity
echo "rotation drill done"
