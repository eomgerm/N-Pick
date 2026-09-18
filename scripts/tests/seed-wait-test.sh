#!/bin/sh
# seed-clips.sh 의 인자 검증·0건 가드·대기 블록을 검증한다 (S15P21A501-187).
# 가짜 BE 를 python http.server 로 띄워 run_counts 만 바꿔 가며 종료 코드를 본다.
#
#   sh scripts/tests/seed-wait-test.sh
#
# 이 검사가 있는 이유: Jenkins SEED 잡의 성패가 여기서 갈린다. 조건을 잘못 읽으면 처리가
# 안 끝났는데 초록으로 끝나고, 아무도 시연 직전까지 모른다. 0 건 등록도 같은 계열이다 —
# 경로를 잘못 주면 아무것도 안 하고 성공했다고 보고한다.
set -eu

HERE=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
SCRIPT="$HERE/../seed-clips.sh"
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"; [ -n "${SRV:-}" ] && kill "$SRV" 2>/dev/null || true' EXIT
STATE="$TMP/counts.json"
fails=0

# 영상 1건을 둔다. 등록 0 건은 그 자체로 실패라(seed-clips.sh 의 가드) 빈 폴더로는
# 대기 블록까지 도달하지 못한다. 내용은 상관없다 — 가짜 BE 가 형식을 보지 않는다.
mkdir -p "$TMP/videos"
printf 'not-a-real-video' > "$TMP/videos/clip.mp4"
printf '{"clipType":"broadcast","title":"t","rightsConfirmed":true}' > "$TMP/videos/clip.json"

cat > "$TMP/fake_be.py" <<'PY'
import json, os, sys
from http.server import BaseHTTPRequestHandler, HTTPServer

STATE = sys.argv[1]

class H(BaseHTTPRequestHandler):
    def log_message(self, *a): pass

    def _send(self, body, cookie=None):
        raw = json.dumps(body).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        if cookie:
            self.send_header("Set-Cookie", cookie)
        self.send_header("Content-Length", str(len(raw)))
        self.end_headers()
        self.wfile.write(raw)

    def do_GET(self):
        if self.path.startswith("/auth/csrf"):
            # seed-clips.sh 는 Netscape jar 의 XSRF-TOKEN 을 읽는다.
            return self._send({"isSuccess": True}, "XSRF-TOKEN=t; Path=/")
        if self.path.startswith("/clips"):
            with open(STATE) as f:
                counts = json.load(f)
            # 한 번 읽을 때마다 다음 상태로 넘어간다. 큐가 줄어드는 것을 흉내낸다.
            rest = counts[1:] or counts[-1:]
            with open(STATE, "w") as f:
                json.dump(rest, f)
            return self._send({"isSuccess": True, "data": {"run_counts": counts[0]}})
        self.send_error(404)

    def do_POST(self):
        self._send({"isSuccess": True})

HTTPServer(("127.0.0.1", int(os.environ["PORT"])), H).serve_forever()
PY

run() { # $1=기대코드 $2=설명 $3=대기초 $4=상태열(JSON) [$5=영상폴더]
  expect=$1; desc=$2; wait=$3; state=$4; dir=${5:-$TMP/videos}
  printf '%s' "$state" > "$STATE"
  set +e
  out=$(NPICK_API_BASE="http://127.0.0.1:$PORT" NPICK_LOGIN_ID=u NPICK_PASSWORD=p \
        NPICK_SEED_WAIT_SECONDS="$wait" sh "$SCRIPT" "$dir" 2>&1)
  got=$?
  set -e
  if [ "$got" = "$expect" ]; then
    printf 'ok   %s\n' "$desc"
  else
    printf 'FAIL %s — 기대 %s, 실제 %s\n%s\n' "$desc" "$expect" "$got" "$out" >&2
    fails=$((fails + 1))
  fi
}

PORT=18799; export PORT
# python3 은 리눅스 이름이고 Windows(Git Bash)에는 python 만 있다. 있는 쪽을 쓴다.
# **이름이 있는 것만으로는 부족하다** — Windows 는 python3 자리에 "스토어에서 설치"
# 안내만 찍고 끝나는 더미를 놓아 둔다. 실제로 도는지 한 번 돌려 보고 고른다.
PY_BIN=""
for c in python3 python; do
  if command -v "$c" >/dev/null 2>&1 && "$c" -c "print(1)" >/dev/null 2>&1; then
    PY_BIN=$c; break
  fi
done
[ -n "$PY_BIN" ] || { echo "python 이 없어 이 검사를 돌릴 수 없다" >&2; exit 1; }
"$PY_BIN" "$TMP/fake_be.py" "$STATE" & SRV=$!
# 기동을 기다린다. 바로 쏘면 연결 거부로 CSRF 단계에서 죽는다.
i=0; while [ $i -lt 40 ]; do
  printf '[]' > "$STATE"
  curl -fsS -o /dev/null "http://127.0.0.1:$PORT/auth/csrf" 2>/dev/null && break
  i=$((i + 1)); sleep 0.25
done

Z='{"queued":0,"running":0,"failed":0,"succeeded":2}'
R='{"queued":0,"running":1,"failed":0,"succeeded":1}'
F='{"queued":0,"running":0,"failed":1,"succeeded":1}'

# 1. 인자 검증은 업로드 **전에** 끝난다. 배치를 다 보낸 뒤 실패하면 검증이 아니다.
run 2 "WAIT 이 숫자가 아니면 2"   abc "[$Z]"
run 2 "없는 폴더 → 2"             0   "[$Z]" "$TMP/nope"

# 2. 0 건 등록은 성공이 아니다. 경로 오타의 유일한 신호다.
mkdir -p "$TMP/empty"
run 1 "영상 0 건 → 1"             0   "[$Z]" "$TMP/empty"

# 3. WAIT=0(기본)이면 대기 자체를 하지 않는다 — 현행 동작이 바뀌지 않아야 한다.
run 0 "WAIT=0 → 대기 없이 0"      0   "[$R]"

# 4. 큐가 비어 있고 실패가 없으면 0.
run 0 "이미 완료 → 0"             60  "[$Z]"

# 5. running 이 남아 있다가 비면 0. (10초 간격이라 두 번째 폴링에서 끝난다)
run 0 "running 이 빠지면 0"       60  "[$R,$Z]"

# 6. 큐가 비었는데 failed 가 있으면 1. failed 가 0 이 되기를 기다리지 않는다.
run 1 "failed 가 남으면 1"        60  "[$F]"

# 7. 시간 안에 안 끝나면 1.
run 1 "제한 시간 초과 → 1"        1   "[$R,$R,$R]"

[ "$fails" -eq 0 ] || { echo "$fails 건 실패" >&2; exit 1; }
echo "전부 통과"
