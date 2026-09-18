#!/bin/sh
# RunPod 파드를 재개하거나 정지한다. Jenkins 의 npick-ops 잡이 부른다.
#
#   RUNPOD_API_KEY=... RUNPOD_POD_ID=... infra/jenkins/runpod.sh resume|stop
#
# 종료 코드
#   0  요청한 상태가 되었다. **이미 그 상태였어도 0 이다** — cron 이 매일 새벽 stop 을
#      치는데(Jenkinsfile.ops) 이미 꺼져 있다고 빨간 빌드가 되면 아무도 안 본다.
#   1  RunPod API 오류, 또는 resume 이 제한 시간 안에 실제로 뜨지 않았다
#   2  인자나 환경 변수가 없다
#
# **`desiredStatus` 는 실제 상태가 아니다.** 그것은 "요청한 상태" 이고, 이미지 내려받기와
# 컨테이너 초기화 중에도 RUNNING 이다. 그래서 그 값만 보면 아직 뜨지도 않은 파드를 두고
# 잡이 초록으로 끝나고, 워커가 없다는 사실은 시연 직전에야 드러난다.
# 실제 기동은 GraphQL 의 `runtime` 으로 본다 — 컨테이너가 돌기 전에는 null 이고
# 돌기 시작하면 `uptimeInSeconds` 가 담긴 객체가 된다(runpodctl 이 보는 값과 같다).
#
#   REST     start/stop 전이 요청. 엔드포인트가 단순하고 문서화돼 있다
#   GraphQL  runtime 조회. REST v1 의 Pod 스키마에는 이 값이 아예 없다
#
# runpodctl 을 쓰지 않는다. Jenkins 이미지에 curl 은 이미 있고, 여기서 쓰는 것은 호출
# 셋뿐이라 바이너리를 하나 더 얹을 값을 못 한다.
set -eu

API_BASE="${RUNPOD_API_BASE:-https://rest.runpod.io/v1}"
GQL_URL="${RUNPOD_GRAPHQL_URL:-https://api.runpod.io/graphql}"

fail2() { echo "$1" >&2; exit 2; }

ACTION="${1:-}"
case "$ACTION" in
  resume|stop) ;;
  *) fail2 "사용법: $0 resume|stop" ;;
esac
[ -n "${RUNPOD_API_KEY:-}" ] || fail2 "RUNPOD_API_KEY 가 없다"
[ -n "${RUNPOD_POD_ID:-}" ]  || fail2 "RUNPOD_POD_ID 가 없다"

# -f 를 쓰지 않는다. 본문을 버리면 401 인지 404 인지 운영자가 알 수 없다.
api() { # $1=METHOD $2=PATH  →  본문을 $API_BODY, 상태를 $API_CODE
  API_BODY=$(curl -sS -X "$1" \
    -H "Authorization: Bearer $RUNPOD_API_KEY" \
    -w '\n%{http_code}' "$API_BASE$2" 2>&1) || {
      echo "RunPod API 에 닿지 못했다 ($1 $2)" >&2; exit 1; }
  API_CODE=$(printf '%s' "$API_BODY" | tail -n 1)
  API_BODY=$(printf '%s' "$API_BODY" | sed '$d')
}

# 파드의 desiredStatus 와 runtime 을 한 번에 읽는다.
#   STATUS    RUNNING | EXITED | TERMINATED | (빈 문자열)
#   RUNNING_  yes = 컨테이너가 실제로 돌고 있다 / no = 아직이거나 꺼져 있다
poll_state() {
  API_BODY=$(curl -sS -X POST "$GQL_URL?api_key=$RUNPOD_API_KEY" \
    -H 'Content-Type: application/json' \
    --data-binary "{\"query\":\"query { pod(input:{podId:\\\"$RUNPOD_POD_ID\\\"}) { id desiredStatus runtime { uptimeInSeconds } } }\"}" \
    -w '\n%{http_code}' 2>&1) || {
      echo "RunPod GraphQL 에 닿지 못했다" >&2; exit 1; }
  API_CODE=$(printf '%s' "$API_BODY" | tail -n 1)
  API_BODY=$(printf '%s' "$API_BODY" | sed '$d')
  [ "$API_CODE" = "200" ] || return 1

  # jq 를 쓰지 않는다. 뽑는 것이 열거형 하나와 null 여부뿐이라 이 정도로 충분하다.
  # **함수로 모은다** — 같은 sed 를 두 곳에 적었더니 한쪽의 치환 참조가 빠져 조용히
  # 빈 값을 냈다 (S15P21A501-187 리뷰 대응 중 발견).
  STATUS=$(printf '%s' "$API_BODY" |
    sed -n 's/.*"desiredStatus"[[:space:]]*:[[:space:]]*"\([A-Z_]*\)".*/\1/p' | head -n 1)
  # runtime 이 객체면 떠 있는 것, null 이면 아직이다.
  if printf '%s' "$API_BODY" | grep -q '"runtime"[[:space:]]*:[[:space:]]*{'; then
    RUNNING_=yes
  else
    RUNNING_=no
  fi
  return 0
}

poll_state || { echo "파드를 조회하지 못했다 (HTTP $API_CODE): $API_BODY" >&2; exit 1; }
echo "현재 desiredStatus=${STATUS:-알수없음} · 실제 기동=$RUNNING_"

if [ "$ACTION" = "stop" ]; then
  # 정지는 desiredStatus 로 충분하다. 요금이 멎는 것이 목적이고 EXITED 반영이
  # 늦어도 손해가 없다.
  [ "$STATUS" != "RUNNING" ] && { echo "이미 꺼져 있다. 아무것도 하지 않는다"; exit 0; }
  api POST "/pods/$RUNPOD_POD_ID/stop"
  case "$API_CODE" in
    2*) echo "stop 요청 완료"; exit 0 ;;
    *)  echo "stop 실패 (HTTP $API_CODE): $API_BODY" >&2; exit 1 ;;
  esac
fi

# ── resume ────────────────────────────────────────────────────────────────
# **이미 떠 있을 때만 건너뛴다.** desiredStatus 가 RUNNING 인데 runtime 이 없으면
# 전이 요청만 들어간 상태다 — 그때는 POST 를 다시 치지 않고 기다리기만 한다.
if [ "$STATUS" = "RUNNING" ] && [ "$RUNNING_" = "yes" ]; then
  echo "이미 켜져 있다. 아무것도 하지 않는다"
  exit 0
fi
if [ "$STATUS" != "RUNNING" ]; then
  api POST "/pods/$RUNPOD_POD_ID/start"
  case "$API_CODE" in
    2*) ;;
    *)  echo "resume 실패 (HTTP $API_CODE): $API_BODY" >&2; exit 1 ;;
  esac
else
  echo "전이 요청이 이미 들어가 있다. 기동만 기다린다"
fi

TIMEOUT="${RUNPOD_RESUME_TIMEOUT:-180}"
# 폴링 간격. 검사에서만 줄인다 — 실패 경로가 3분씩 걸리면 아무도 안 돌린다.
INTERVAL="${RUNPOD_POLL_INTERVAL:-10}"
echo "실제로 뜰 때까지 기다린다 (최대 ${TIMEOUT}초)"
deadline=$(( $(date +%s) + TIMEOUT ))
while :; do
  if poll_state; then
    [ "$RUNNING_" = "yes" ] && { echo "resume 완료 (실제 기동 확인)"; exit 0; }
    echo "  desiredStatus=${STATUS:-알수없음} · 실제 기동=$RUNNING_"
  else
    echo "  조회 실패 (HTTP $API_CODE)" >&2
  fi
  [ "$(date +%s)" -lt "$deadline" ] || {
    echo "${TIMEOUT}초 안에 컨테이너가 뜨지 않았다. GPU 재고와 이미지 크기를 확인한다" >&2
    exit 1; }
  sleep "$INTERVAL"
done
