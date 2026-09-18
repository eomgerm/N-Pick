#!/bin/sh
# runpod.sh 의 분기와 종료 코드를 검증한다. 프레임워크 없이 PATH 앞에 가짜 curl 을
# 놓고 응답을 흉내낸다 — 실제 RunPod 을 부르지 않으므로 과금도 네트워크도 없다.
#
#   sh infra/jenkins/tests/runpod-test.sh
#
# 이 스크립트가 존재하는 이유는 멱등성 때문이다. cron 이 매일 stop 을 치는데
# "이미 꺼짐"이 1 로 끝나면 빌드가 매일 빨개지고, 그 빨강은 곧 무시된다.
set -eu

HERE=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
SCRIPT="$HERE/../runpod.sh"
TMP=$(mktemp -d); trap 'rm -rf "$TMP"' EXIT
fails=0

# 가짜 curl. GET 이면 $FAKE_STATUS 를, POST 면 $FAKE_POST_CODE 를 돌려준다.
# runpod.sh 가 `-w '\n%{http_code}'` 로 상태를 본문 뒤에 붙이는 것까지 흉내낸다.
mkdir -p "$TMP/bin"
cat > "$TMP/bin/curl" <<'FAKE'
#!/bin/sh
method=GET
for a in "$@"; do case "$prev" in -X) method=$a;; esac; prev=$a; done
case "$*" in *"/start"*|*"/stop"*) method=POST;; esac
if [ "$method" = GET ]; then
  printf '{"id":"p1","desiredStatus":"%s"}\n%s' "$FAKE_STATUS" "${FAKE_GET_CODE:-200}"
else
  printf '{}\n%s' "${FAKE_POST_CODE:-200}"
  echo "POST-CALLED" >> "$FAKE_CALLS"
fi
FAKE
chmod +x "$TMP/bin/curl"
PATH="$TMP/bin:$PATH"; export PATH

run() { # $1=기대코드 $2=설명 나머지=인자
  expect=$1; desc=$2; shift 2
  FAKE_CALLS="$TMP/calls"; export FAKE_CALLS; : > "$FAKE_CALLS"
  set +e
  out=$(sh "$SCRIPT" "$@" 2>&1); got=$?
  set -e
  if [ "$got" = "$expect" ]; then
    printf 'ok   %s\n' "$desc"
  else
    printf 'FAIL %s — 기대 %s, 실제 %s\n%s\n' "$desc" "$expect" "$got" "$out" >&2
    fails=$((fails + 1))
  fi
}
posted() { [ -s "$TMP/calls" ]; }

export RUNPOD_API_KEY=k RUNPOD_POD_ID=p1 RUNPOD_API_BASE=http://fake

# 1. 환경·인자 누락은 2다. 여기서 1을 돌려주면 운영자가 API 장애로 오해한다.
run 2 "인자 없음 → 2" 
run 2 "모르는 인자 → 2" bogus
( unset RUNPOD_API_KEY; run 2 "API 키 없음 → 2" stop )
( unset RUNPOD_POD_ID;  run 2 "파드 ID 없음 → 2" stop )

# 2. 이미 그 상태면 0이고 POST 를 치지 않는다 (cron 멱등성).
FAKE_STATUS=EXITED  export FAKE_STATUS
run 0 "stop / 이미 꺼짐 → 0" stop
posted && { echo "FAIL 이미 꺼졌는데 POST 를 쳤다" >&2; fails=$((fails + 1)); }
run 0 "stop 2회 연속 → 0" stop

FAKE_STATUS=RUNNING
run 0 "resume / 이미 켜짐 → 0" resume
posted && { echo "FAIL 이미 켜졌는데 POST 를 쳤다" >&2; fails=$((fails + 1)); }

# 3. 실제 전이는 POST 를 치고 0이다.
run 0 "stop / 켜져 있음 → 전이" stop
posted || { echo "FAIL 전이인데 POST 를 안 쳤다" >&2; fails=$((fails + 1)); }
FAKE_STATUS=EXITED
run 0 "resume / 꺼져 있음 → 전이" resume
posted || { echo "FAIL 전이인데 POST 를 안 쳤다" >&2; fails=$((fails + 1)); }

# 4. API 오류는 1이다.
FAKE_STATUS=EXITED FAKE_POST_CODE=500 run 1 "POST 500 → 1" resume
FAKE_GET_CODE=401 run 1 "GET 401 → 1" stop

[ "$fails" -eq 0 ] || { echo "$fails 건 실패" >&2; exit 1; }
echo "전부 통과"
