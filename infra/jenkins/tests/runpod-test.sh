#!/usr/bin/env bash
# runpod.sh 의 분기와 종료 코드를 검증한다. 프레임워크 없이 PATH 앞에 가짜 curl 을
# 놓고 응답을 흉내낸다 — 실제 RunPod 을 부르지 않으므로 과금도 네트워크도 없다.
#
#   bash infra/jenkins/tests/runpod-test.sh
#
# 이 스크립트가 존재하는 이유:
#   ① 멱등성 — cron 이 매일 stop 을 치는데 "이미 꺼짐"이 1 로 끝나면 빌드가 매일
#      빨개지고, 그 빨강은 곧 무시된다.
#   ② resume 의 실제 기동 확인 — desiredStatus 는 "요청한 상태" 라 이미지 내려받기
#      중에도 RUNNING 이다. 그것만 보면 뜨지도 않은 파드를 두고 잡이 초록이 된다.
#   ③ 재고 부족은 기다린다 — 정지된 파드는 호스트에 묶여 있어 그 호스트가 차면
#      start 가 거절된다. 리전이 마른 것이 아니므로 즉시 실패로 끝내면 안 된다.
#   ④ 파드를 이름으로 찾는다 — 다시 만들면 ID 가 바뀐다.
#
# **서브셸에서 run 을 부르지 않는다.** 그러면 fails 증가가 부모에 전달되지 않아
# 실패가 있어도 "전부 통과" 로 끝난다(2026-09-18 에 실제로 그랬다).
set -euo pipefail

HERE=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
SCRIPT="$HERE/../runpod.sh"
TMP=$(mktemp -d); trap 'rm -rf "$TMP"' EXIT
mkdir -p "$TMP/bin" "$TMP/state"
STATE="$TMP/state"; export STATE
fails=0

# 가짜 curl.
#   GET  /pods            → $STATE/podlist (이름 해석용 목록)
#   POST graphql          → 상태 큐에서 하나. 항목은 `<desiredStatus>/<기동여부>`
#   POST /start|/stop     → 코드 큐에서 하나. 500 이면 재고 부족 메시지를 붙인다
cat > "$TMP/bin/curl" <<'FAKE'
#!/usr/bin/env bash
case "$*" in
  *graphql*)
    read -r head rest < <(cat "$STATE/queue")
    [ -n "$rest" ] && printf '%s' "$rest" > "$STATE/queue"
    desired=${head%%/*}; up=${head##*/}
    if [ "$up" = yes ]; then runtime='{"uptimeInSeconds":42}'; else runtime='null'; fi
    printf '{"data":{"pod":{"id":"p1","desiredStatus":"%s","runtime":%s}}}\n%s' \
      "$desired" "$runtime" "$(cat "$STATE/gql_code")"
    ;;
  *"/start"*|*"/stop"*)
    echo "POST" >> "$STATE/calls"
    read -r head rest < <(cat "$STATE/post_code")
    [ -n "$rest" ] && printf '%s' "$rest" > "$STATE/post_code"
    body='{}'
    [ "$head" = 500 ] && body='{"error":"start pod: There are not enough free GPUs on the host machine to start this pod."}'
    printf '%s\n%s' "$body" "$head"
    ;;
  *"/pods"*)
    printf '%s\n%s' "$(cat "$STATE/podlist")" "$(cat "$STATE/list_code")"
    ;;
  *)
    printf '{}\n404'
    ;;
esac
FAKE
chmod +x "$TMP/bin/curl"
PATH="$TMP/bin:$PATH"; export PATH

# 기본 응답. 케이스마다 필요한 것만 덮어쓴다.
QUEUE="RUNNING/yes"; POST_CODE=200; GQL_CODE=200; LIST_CODE=200
PODLIST='[{"id":"p1","name":"npick-worker"}]'

run() { # $1=기대코드 $2=설명 나머지=runpod.sh 인자
  local expect=$1 desc=$2; shift 2
  : > "$STATE/calls"
  printf '%s' "$QUEUE"     > "$STATE/queue"
  printf '%s' "$POST_CODE" > "$STATE/post_code"
  printf '%s' "$GQL_CODE"  > "$STATE/gql_code"
  printf '%s' "$PODLIST"   > "$STATE/podlist"
  printf '%s' "$LIST_CODE" > "$STATE/list_code"
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
no_post() { posted && { echo "FAIL $1 — POST 를 치면 안 된다" >&2; fails=$((fails+1)); }; return 0; }
did_post() { posted || { echo "FAIL $1 — POST 를 쳐야 한다" >&2; fails=$((fails+1)); }; return 0; }

export RUNPOD_API_KEY=k RUNPOD_POD_ID=p1
export RUNPOD_API_BASE=http://fake RUNPOD_GRAPHQL_URL=http://fake/graphql
# 폴링을 짧게. 실패 경로가 몇 분씩 걸리면 아무도 이 검사를 안 돌린다.
export RUNPOD_RESUME_TIMEOUT=6 RUNPOD_POLL_INTERVAL=1

# ── 1. 인자·환경 누락은 2다. 1 을 주면 운영자가 API 장애로 오해한다 ──────────
run 2 "인자 없음 → 2"
run 2 "모르는 인자 → 2" bogus
unset RUNPOD_API_KEY
run 2 "API 키 없음 → 2" stop
export RUNPOD_API_KEY=k

# ── 2. 이미 그 상태면 0이고 POST 를 치지 않는다 (cron 멱등성) ─────────────────
QUEUE="EXITED/no"
run 0 "stop / 이미 꺼짐 → 0" stop
no_post "이미 꺼짐"
run 0 "stop 2회 연속 → 0" stop
QUEUE="RUNNING/yes"
run 0 "resume / 이미 떠 있음 → 0" resume
no_post "이미 떠 있음"

# ── 3. stop 은 전이를 확인하지 않는다. 요금이 멎는 것이 목적이다 ──────────────
run 0 "stop / 켜져 있음 → 전이" stop
did_post "stop 전이"

# ── 4. resume 은 runtime 이 생길 때까지 본다 ─────────────────────────────────
# desiredStatus 만 보면 두 번째 항목에서 성공으로 끝난다. 그게 이 검사의 요점이다.
QUEUE="EXITED/no RUNNING/no RUNNING/yes"
run 0 "resume / runtime 생길 때까지" resume
did_post "resume 전이"

# desiredStatus=RUNNING 인데 runtime 이 없으면 "요청만 들어간" 상태다.
QUEUE="RUNNING/no RUNNING/yes"
run 0 "요청만 들어간 상태 → 재요청 없이 대기" resume
no_post "요청이 이미 있음"

QUEUE="EXITED/no"
run 1 "runtime 이 끝내 없으면 1" resume

# ── 5. GPU 재고 부족은 기다린다 ─────────────────────────────────────────────
QUEUE="EXITED/no EXITED/no EXITED/no RUNNING/yes"; POST_CODE="500 500 200"
run 0 "재고 부족 → 기다렸다 성공" resume

QUEUE="EXITED/no"; POST_CODE=500
run 1 "재고가 끝내 없으면 1" resume

# ── 6. 인증·인자 오류는 기다리지 않는다 ─────────────────────────────────────
POST_CODE=401
run 1 "POST 401 → 즉시 1" resume
POST_CODE=200

GQL_CODE=401
run 1 "GraphQL 401 → 1" stop
GQL_CODE=200

# ── 7. 파드를 이름으로 찾는다 ───────────────────────────────────────────────
# 재고가 없어 다시 만들면 ID 가 바뀐다. credential 에 박아 두면 사람이 고쳐야 하고,
# 잊으면 없는 파드를 끄고 성공했다고 보고한다.
unset RUNPOD_POD_ID
QUEUE="RUNNING/yes"
run 0 "이름으로 찾아서 stop" stop
did_post "이름 해석 후 stop"

PODLIST='[]'
run 1 "그 이름의 파드가 없으면 1" stop

PODLIST='[{"id":"other","name":"남의-파드"},{"id":"p1","name":"npick-worker"}]'
run 0 "여러 파드 중 이름이 맞는 것" stop
export RUNPOD_POD_ID=p1

[ "$fails" -eq 0 ] || { echo "$fails 건 실패" >&2; exit 1; }
echo "전부 통과"
