#!/usr/bin/env bash
# runpod.sh 의 분기와 종료 코드를 검증한다. 프레임워크 없이 PATH 앞에 가짜 curl 을
# 놓고 응답을 흉내낸다 — 실제 RunPod 을 부르지 않으므로 과금도 네트워크도 없다.
#
#   bash infra/jenkins/tests/runpod-test.sh
#
# 이 스크립트가 존재하는 이유는 셋이다.
#   ① 멱등성 — cron 이 매일 stop 을 치는데 "이미 꺼짐"이 1 로 끝나면 빌드가 매일
#      빨개지고, 그 빨강은 곧 무시된다.
#   ② resume 의 실제 기동 확인 — desiredStatus 는 "요청한 상태" 라 이미지 내려받기
#      중에도 RUNNING 이다. 그것만 보면 뜨지도 않은 파드를 두고 잡이 초록이 된다.
#   ③ GPU 재고가 없어 끝내 안 뜨는 경우를 실패로 잡는가.
set -euo pipefail

HERE=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
SCRIPT="$HERE/../runpod.sh"
TMP=$(mktemp -d); trap 'rm -rf "$TMP"' EXIT
mkdir -p "$TMP/bin" "$TMP/state"
STATE="$TMP/state"; export STATE
fails=0

# 가짜 curl.
#   GraphQL(POST, URL 에 graphql) → 상태 큐에서 하나 꺼낸다. 항목은 `<상태>/<기동여부>`
#                                   이고 마지막 값은 계속 유지된다.
#   REST start/stop(POST)         → post_code 를 돌려주고 호출을 기록한다.
# runpod.sh 가 `-w '\n%{http_code}'` 로 상태를 본문 뒤에 붙이는 것까지 흉내낸다.
cat > "$TMP/bin/curl" <<'FAKE'
#!/usr/bin/env bash
case "$*" in
  *graphql*)
    read -r head rest < <(cat "$STATE/queue")
    [ -n "$rest" ] && printf '%s' "$rest" > "$STATE/queue"
    desired=${head%%/*}; up=${head##*/}
    if [ "$up" = yes ]; then
      runtime='{"uptimeInSeconds":42}'
    else
      runtime='null'
    fi
    printf '{"data":{"pod":{"id":"p1","desiredStatus":"%s","runtime":%s}}}\n%s' \
      "$desired" "$runtime" "$(cat "$STATE/gql_code")"
    ;;
  *"/start"*|*"/stop"*)
    echo "POST" >> "$STATE/calls"
    # 코드 큐에서 하나 꺼낸다. 마지막 값은 계속 유지된다.
    read -r head rest < <(cat "$STATE/post_code")
    [ -n "$rest" ] && printf '%s' "$rest" > "$STATE/post_code"
    body='{}'
    [ "$head" = 500 ] && body='{"error":"start pod: There are not enough free GPUs on the host machine to start this pod."}'
    printf '%s\n%s' "$body" "$head"
    ;;
  *)
    printf '{}\n404'
    ;;
esac
FAKE
chmod +x "$TMP/bin/curl"
PATH="$TMP/bin:$PATH"; export PATH

run() { # $1=기대코드 $2=설명 $3=상태큐 나머지=runpod.sh 인자
  local expect=$1 desc=$2 queue=$3; shift 3
  : > "$STATE/calls"
  printf '%s' "$queue"            > "$STATE/queue"
  printf "%s" "${POST_CODE:-200}" > "$STATE/post_code"
  printf '%s' "${GQL_CODE:-200}"  > "$STATE/gql_code"
  local out got
  set +e
  out=$(bash "$SCRIPT" "$@" 2>&1); got=$?
  set -e
  if [ "$got" = "$expect" ]; then
    printf 'ok   %s\n' "$desc"
  else
    printf 'FAIL %s — 기대 %s, 실제 %s\n%s\n' "$desc" "$expect" "$got" "$out" >&2
    fails=$((fails + 1))
  fi
}
posted() { [ -s "$STATE/calls" ]; }

export RUNPOD_API_KEY=k RUNPOD_POD_ID=p1
export RUNPOD_API_BASE=http://fake RUNPOD_GRAPHQL_URL=http://fake/graphql
# 폴링 대기를 짧게 둔다. 실패 경로가 3분씩 걸리면 아무도 이 검사를 안 돌린다.
export RUNPOD_RESUME_TIMEOUT=6 RUNPOD_POLL_INTERVAL=1

# ── 1. 인자·환경 누락은 2다. 여기서 1을 주면 운영자가 API 장애로 오해한다 ──────
run 2 "인자 없음 → 2"        "RUNNING/yes"
run 2 "모르는 인자 → 2"      "RUNNING/yes" bogus
( unset RUNPOD_API_KEY; run 2 "API 키 없음 → 2"  "RUNNING/yes" stop )
( unset RUNPOD_POD_ID;  run 2 "파드 ID 없음 → 2" "RUNNING/yes" stop )

# ── 2. 이미 그 상태면 0이고 POST 를 치지 않는다 (cron 멱등성) ─────────────────
run 0 "stop / 이미 꺼짐 → 0"   "EXITED/no" stop
posted && { echo "FAIL 이미 꺼졌는데 POST 를 쳤다" >&2; fails=$((fails + 1)); }
run 0 "stop 2회 연속 → 0"      "EXITED/no" stop
run 0 "resume / 이미 떠 있음 → 0" "RUNNING/yes" resume
posted && { echo "FAIL 이미 떴는데 POST 를 쳤다" >&2; fails=$((fails + 1)); }

# ── 3. stop 은 전이를 확인하지 않는다. 요금이 멎는 것이 목적이다 ──────────────
run 0 "stop / 켜져 있음 → 전이" "RUNNING/yes" stop
posted || { echo "FAIL 전이인데 POST 를 안 쳤다" >&2; fails=$((fails + 1)); }

# ── 4. resume 은 runtime 이 생길 때까지 본다 ─────────────────────────────────
# desiredStatus 만 보면 두 번째 항목에서 성공으로 끝나 버린다. 그게 이 검사의 요점이다.
run 0 "resume / runtime 생길 때까지" "EXITED/no RUNNING/no RUNNING/yes" resume
posted || { echo "FAIL 전이인데 POST 를 안 쳤다" >&2; fails=$((fails + 1)); }

# **desiredStatus=RUNNING 인데 runtime 이 없는 상태**는 "요청만 들어간" 것이다.
# POST 를 다시 치지 않고 기다리기만 해야 한다.
run 0 "요청만 들어간 상태 → 재요청 없이 대기" "RUNNING/no RUNNING/yes" resume
posted && { echo "FAIL 요청이 이미 있는데 POST 를 또 쳤다" >&2; fails=$((fails + 1)); }

# 끝내 안 뜨면 실패다. GPU 재고가 없을 때가 이 경로다.
run 1 "runtime 이 끝내 없으면 1" "EXITED/no RUNNING/no" resume

# ── 5. GPU 재고 부족은 기다린다 ─────────────────────────────────────────────
# 정지된 파드는 호스트에 묶여 있어 그 호스트가 차면 start 가 500 으로 거절된다.
# 리전이 마른 것이 아니라 그 호스트만 찬 것이라, 즉시 실패로 끝내면 안 된다.
POST_CODE="500 500 200" run 0 "재고 부족 → 기다렸다 성공" "EXITED/no EXITED/no EXITED/no RUNNING/yes" resume

# 끝내 안 나면 실패다. 그때는 파드를 다시 만들거나 SSAFY GPU 로 돌린다.
POST_CODE=500 run 1 "재고가 끝내 없으면 1" "EXITED/no" resume

# ── 6. 인증·인자 오류는 기다리지 않는다 ─────────────────────────────────────
# 4xx 는 기다려도 낫지 않는다. 여기서 지체하면 운영자가 재고 문제로 오해한다.
POST_CODE=401 run 1 "POST 401 → 즉시 1" "EXITED/no" resume
POST_CODE=200
GQL_CODE=401 run 1 "GraphQL 401 → 1" "EXITED/no" stop
GQL_CODE=200

[ "$fails" -eq 0 ] || { echo "$fails 건 실패" >&2; exit 1; }
echo "전부 통과"
