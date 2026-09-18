#!/bin/sh
# RunPod 파드를 재개하거나 정지한다. Jenkins 의 npick-ops 잡이 부른다.
#
#   RUNPOD_API_KEY=... RUNPOD_POD_ID=... infra/jenkins/runpod.sh resume|stop
#
# 종료 코드
#   0  요청한 상태가 되었다. **이미 그 상태였어도 0 이다** — cron 이 매일 새벽 stop 을
#      치는데(Jenkinsfile.ops) 이미 꺼져 있다고 빨간 빌드가 되면 아무도 안 본다.
#   1  RunPod API 오류, 또는 resume 이 제한 시간 안에 RUNNING 이 되지 않았다
#   2  인자나 환경 변수가 없다
#
# **resume 은 전이를 확인한다.** start 가 2xx 를 줘도 GPU 재고가 없으면 파드가 올라오지
# 않는다. 그 경우 잡은 초록인데 워커는 없고, 시연 직전에야 알게 된다. 그래서 상태를
# RUNPOD_RESUME_TIMEOUT 초(기본 180) 동안 폴링한다. stop 은 확인하지 않는다 — 요금이
# 멎는 것이 목적이고 EXITED 가 늦게 반영돼도 손해가 없다.
#
# runpodctl 을 쓰지 않는다. Jenkins 이미지에 curl 은 이미 있고(infra/jenkins/Dockerfile)
# 여기서 쓰는 것은 엔드포인트 셋뿐이라 바이너리를 하나 더 얹을 값을 못 한다.
# 엔드포인트는 rest.runpod.io 의 openapi.json 으로 대조했다 (2026-09-18).
set -eu

API_BASE="${RUNPOD_API_BASE:-https://rest.runpod.io/v1}"

fail2() { echo "$1" >&2; exit 2; }

ACTION="${1:-}"
case "$ACTION" in
  resume|stop) ;;
  *) fail2 "사용법: $0 resume|stop" ;;
esac
[ -n "${RUNPOD_API_KEY:-}" ] || fail2 "RUNPOD_API_KEY 가 없다"
[ -n "${RUNPOD_POD_ID:-}" ]  || fail2 "RUNPOD_POD_ID 가 없다"

# -f 를 쓰지 않는다. 본문을 버리면 401 인지 404 인지 운영자가 알 수 없다.
api() { # $1=METHOD $2=PATH  →  본문을 stdout, 상태를 $API_CODE 로
  API_BODY=$(curl -sS -X "$1" \
    -H "Authorization: Bearer $RUNPOD_API_KEY" \
    -w '\n%{http_code}' "$API_BASE$2" 2>&1) || {
      echo "RunPod API 에 닿지 못했다 ($1 $2)" >&2; exit 1; }
  API_CODE=$(printf '%s' "$API_BODY" | tail -n 1)
  API_BODY=$(printf '%s' "$API_BODY" | sed '$d')
}

# jq 를 쓰지 않는다. 뽑는 값이 대문자 열거형 하나뿐이라 이 정도로 충분하고, 값이
# 없으면 빈 문자열이 되어 호출부가 전이 시도로 떨어진다.
# **함수로 모은다.** 같은 sed 를 두 곳에 적었더니 한쪽의 치환 참조가 빠져 조용히
# 빈 값을 냈다 (S15P21A501-187 리뷰 대응 중 발견).
desired_status() {
  printf '%s' "$API_BODY" |
    sed -n 's/.*"desiredStatus"[[:space:]]*:[[:space:]]*"\([A-Z_]*\)".*/\1/p' |
    head -n 1
}

api GET "/pods/$RUNPOD_POD_ID"
if [ "$API_CODE" != "200" ]; then
  echo "파드를 조회하지 못했다 (HTTP $API_CODE): $API_BODY" >&2
  exit 1
fi

# jq 를 쓰지 않는다. 뽑는 값이 대문자 열거형 하나뿐이라
# 이 정도로 충분하고, 값이 없으면 빈 문자열이 되어 아래에서 전이 시도로 떨어진다.
STATUS=$(desired_status)
echo "현재 desiredStatus=${STATUS:-알수없음}"

if [ "$ACTION" = "resume" ]; then
  [ "$STATUS" = "RUNNING" ] && { echo "이미 켜져 있다. 아무것도 하지 않는다"; exit 0; }
  ENDPOINT="start"
else
  [ "$STATUS" != "RUNNING" ] && { echo "이미 꺼져 있다. 아무것도 하지 않는다"; exit 0; }
  ENDPOINT="stop"
fi

api POST "/pods/$RUNPOD_POD_ID/$ENDPOINT"
case "$API_CODE" in
  2*) ;;
  *)  echo "$ACTION 실패 (HTTP $API_CODE): $API_BODY" >&2; exit 1 ;;
esac

if [ "$ACTION" = "stop" ]; then
  echo "stop 요청 완료"
  exit 0
fi

TIMEOUT="${RUNPOD_RESUME_TIMEOUT:-180}"
echo "RUNNING 이 되기를 기다린다 (최대 ${TIMEOUT}초)"
deadline=$(( $(date +%s) + TIMEOUT ))
while :; do
  api GET "/pods/$RUNPOD_POD_ID"
  if [ "$API_CODE" = "200" ]; then
    STATUS=$(desired_status)
[ "$STATUS" = "RUNNING" ] && { echo "resume 완료"; exit 0; }
    echo "  desiredStatus=${STATUS:-알수없음}"
  else
    echo "  조회 실패 (HTTP $API_CODE)" >&2
  fi
  [ "$(date +%s)" -lt "$deadline" ] || {
    echo "${TIMEOUT}초 안에 RUNNING 이 되지 않았다. GPU 재고를 확인한다" >&2
    exit 1; }
  sleep 10
done
