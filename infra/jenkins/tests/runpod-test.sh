#!/usr/bin/env bash
# runpod.sh 의 분기와 종료 코드를 검증한다. 프레임워크 없이 PATH 앞에 가짜 curl 을
# 놓고 응답을 흉내낸다 — 실제 RunPod 을 부르지 않으므로 과금도 네트워크도 없다.
#
#   bash infra/jenkins/tests/runpod-test.sh
#
# 이 스크립트가 존재하는 이유는 둘이다.
#   ① 멱등성 — cron 이 매일 stop 을 치는데 "이미 꺼짐"이 1 로 끝나면 빌드가 매일
#      빨개지고, 그 빨강은 곧 무시된다.
#   ② resume 의 전이 확인 — start 가 2xx 를 줘도 GPU 재고가 없으면 파드가 안 올라온다.
#      그때 잡이 초록이면 시연 직전에야 안다.
set -euo pipefail

HERE=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
SCRIPT="$HERE/../runpod.sh"
TMP=$(mktemp -d); trap 'rm -rf "$TMP"' EXIT
mkdir -p "$TMP/bin" "$TMP/state"
STATE="$TMP/state"; export STATE
fails=0

# 가짜 curl. GET 은 상태 큐에서 하나씩 꺼내고(마지막 값은 계속 유지) POST 는
# $STATE/post_code 를 돌려주며 호출을 기록한다. runpod.sh 가 `-w '\n%{http_code}'`
# 로 상태를 본문 뒤에 붙이는 것까지 흉내낸다.
cat > "$TMP/bin/curl" <<'FAKE'
#!/usr/bin/env bash
method=GET
for a in "$@"; do case "$*" in *"/start"*|*"/stop"*) method=POST;; esac; done
if [ "$method" = POST ]; then
  echo "POST" >> "$STATE/calls"
  printf '{}\n%s' "$(cat "$STATE/post_code")"
  exit 0
fi
read -r head rest < <(cat "$STATE/queue")
[ -n "$rest" ] && printf '%s' "$rest" > "$STATE/queue"
printf '{"id":"p1","desiredStatus":"%s"}\n%s' "$head" "$(cat "$STATE/get_code")"
FAKE
chmod +x "$TMP/bin/curl"
PATH="$TMP/bin:$PATH"; export PATH

run() { # $1=기대코드 $2=설명 $3=상태큐 나머지=runpod.sh 인자
  local expect=$1 desc=$2 queue=$3; shift 3
  : > "$STATE/calls"
  printf '%s' "$queue"                > "$STATE/queue"
  printf '%s' "${POST_CODE:-200}"     > "$STATE/post_code"
  printf '%s' "${GET_CODE:-200}"      > "$STATE/get_code"
  local out got
  set +e
  out=$("$BASH" "$SCRIPT" "$@" 2>&1); got=$?
  set -e
  if [ "$got" = "$expect" ]; then
    printf 'ok   %s\n' "$desc"
  else
    printf 'FAIL %s — 기대 %s, 실제 %s\n%s\n' "$desc" "$expect" "$got" "$out" >&2
    fails=$((fails + 1))
  fi
}
posted() { [ -s "$STATE/calls" ]; }
BASH=$(command -v bash)

export RUNPOD_API_KEY=k RUNPOD_POD_ID=p1 RUNPOD_API_BASE=http://fake
# 폴링 대기를 짧게 둔다. 실패 경로가 3분씩 걸리면 아무도 이 검사를 안 돌린다.
export RUNPOD_RESUME_TIMEOUT=1

# ── 1. 인자·환경 누락은 2다. 여기서 1을 주면 운영자가 API 장애로 오해한다 ──────
run 2 "인자 없음 → 2"        "RUNNING"
run 2 "모르는 인자 → 2"      "RUNNING" bogus
( unset RUNPOD_API_KEY; run 2 "API 키 없음 → 2"  "RUNNING" stop )
( unset RUNPOD_POD_ID;  run 2 "파드 ID 없음 → 2" "RUNNING" stop )

# ── 2. 이미 그 상태면 0이고 POST 를 치지 않는다 (cron 멱등성) ─────────────────
run 0 "stop / 이미 꺼짐 → 0"   "EXITED" stop
posted && { echo "FAIL 이미 꺼졌는데 POST 를 쳤다" >&2; fails=$((fails + 1)); }
run 0 "stop 2회 연속 → 0"      "EXITED" stop
run 0 "resume / 이미 켜짐 → 0" "RUNNING" resume
posted && { echo "FAIL 이미 켜졌는데 POST 를 쳤다" >&2; fails=$((fails + 1)); }

# ── 3. stop 은 전이를 확인하지 않는다. 요금이 멎는 것이 목적이다 ──────────────
run 0 "stop / 켜져 있음 → 전이" "RUNNING" stop
posted || { echo "FAIL 전이인데 POST 를 안 쳤다" >&2; fails=$((fails + 1)); }

# ── 4. resume 은 RUNNING 이 될 때까지 본다 ───────────────────────────────────
run 0 "resume / RUNNING 확인" "EXITED RUNNING" resume
posted || { echo "FAIL 전이인데 POST 를 안 쳤다" >&2; fails=$((fails + 1)); }
run 1 "resume / 안 올라오면 1" "EXITED" resume

# ── 5. API 오류는 1이다 ──────────────────────────────────────────────────────
POST_CODE=500 run 1 "POST 500 → 1" "EXITED" resume
POST_CODE=200
GET_CODE=401 run 1 "GET 401 → 1"   "EXITED" stop
GET_CODE=200

[ "$fails" -eq 0 ] || { echo "$fails 건 실패" >&2; exit 1; }
echo "전부 통과"
