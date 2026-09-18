#!/usr/bin/env bash
# seed-clips.sh 의 인자 검증·0건 가드·대기 블록을 검증한다 (S15P21A501-187).
# 가짜 BE 를 python http.server 로 띄워 클립 상태만 바꿔 가며 종료 코드를 본다.
#
#   bash scripts/tests/seed-wait-test.sh
#
# **bash 로 부른다.** seed-clips.sh 가 bash 전용이고(set -o pipefail, shopt)
# Debian/Jenkins 의 /bin/sh 는 dash 라 그 줄에서 죽는다.
#
# 이 검사가 있는 이유: Jenkins SEED 잡의 성패가 여기서 갈린다. 조건을 잘못 읽으면 처리가
# 안 끝났는데 초록으로 끝나고, 아무도 시연 직전까지 모른다. 0 건 등록도 같은 계열이다 —
# 경로를 잘못 주면 아무것도 안 하고 성공했다고 보고한다.
set -euo pipefail

HERE=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
SCRIPT="$HERE/../seed-clips.sh"
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"; [ -n "${SRV:-}" ] && kill "$SRV" 2>/dev/null || true' EXIT
STATE="$TMP/status.json"
fails=0

# 영상 1건을 둔다. 등록 0 건은 그 자체로 실패라 빈 폴더로는 대기 블록까지 못 간다.
# 내용은 상관없다 — 가짜 BE 가 형식을 보지 않는다.
mkdir -p "$TMP/videos" "$TMP/empty"
printf 'not-a-real-video' > "$TMP/videos/clip.mp4"
printf '{"clipType":"broadcast","title":"t","rightsConfirmed":true}' > "$TMP/videos/clip.json"

cat > "$TMP/fake_be.py" <<'PY'
import json, os, sys
from http.server import BaseHTTPRequestHandler, HTTPServer

STATE = sys.argv[1]


class H(BaseHTTPRequestHandler):
    def log_message(self, *a):
        pass

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
        if self.path.startswith("/clips/"):
            with open(STATE) as f:
                queue = json.load(f)
            status = queue[0]
            # 한 번 읽을 때마다 다음 상태로 넘어간다. 마지막 값은 계속 유지된다.
            with open(STATE, "w") as f:
                json.dump(queue[1:] or queue[-1:], f)
            run = None if status == "none" else {"status": status}
            # 실제 응답은 요약을 `clip` 으로 감싼다(ClipDetailResponse).
            # 픽스처가 이걸 틀리면 구현이 틀려도 검사가 통과한다.
            return self._send({"isSuccess": True, "data": {"clip": {"latest_run": run}}})
        self.send_error(404)

    def do_POST(self):
        # 등록 응답의 clip_id 를 대기 블록이 모은다.
        self._send({"isSuccess": True, "data": {"clip_id": "398021847361024"}})


HTTPServer(("127.0.0.1", int(os.environ["PORT"])), H).serve_forever()
PY

run() { # $1=기대코드 $2=설명 $3=대기초 $4=상태열(JSON) [$5=영상폴더]
  local expect=$1 desc=$2 wait=$3 state=$4 dir=${5:-$TMP/videos} out got
  printf '%s' "$state" > "$STATE"
  set +e
  out=$(NPICK_API_BASE="http://127.0.0.1:$PORT" NPICK_LOGIN_ID=u NPICK_PASSWORD=p \
        NPICK_SEED_WAIT_SECONDS="$wait" bash "$SCRIPT" "$dir" 2>&1)
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
PY_BIN=""
for c in python3 python; do
  if command -v "$c" >/dev/null 2>&1 && "$c" -c "print(1)" >/dev/null 2>&1; then
    PY_BIN=$c; break
  fi
done
[ -n "$PY_BIN" ] || { echo "python 이 없어 이 검사를 돌릴 수 없다" >&2; exit 1; }
"$PY_BIN" "$TMP/fake_be.py" "$STATE" & SRV=$!
i=0; while [ $i -lt 40 ]; do
  printf '["succeeded"]' > "$STATE"
  curl -fsS -o /dev/null "http://127.0.0.1:$PORT/auth/csrf" 2>/dev/null && break
  i=$((i + 1)); sleep 0.25
done

# 1. 인자 검증은 업로드 **전에** 끝난다. 배치를 다 보낸 뒤 실패하면 검증이 아니다.
run 2 "WAIT 이 숫자가 아니면 2"  abc '["succeeded"]'
run 2 "없는 폴더 → 2"            0   '["succeeded"]' "$TMP/nope"

# 2. 0 건 등록은 성공이 아니다. 경로 오타의 유일한 신호다.
run 1 "영상 0 건 → 1"            0   '["succeeded"]' "$TMP/empty"

# 3. WAIT=0(기본)이면 대기 자체를 하지 않는다 — 현행 동작이 바뀌지 않아야 한다.
run 0 "WAIT=0 → 대기 없이 0"     0   '["running"]'

# 4. 이번 실행이 등록한 클립만 본다. 그 클립이 끝나면 0.
run 0 "이미 완료 → 0"            60  '["succeeded"]'
run 0 "running 이 끝나면 0"      60  '["running","succeeded"]'

# 5. run 이 아직 없으면(null) 완료가 아니라 대기다. 그대로 두면 제한 시간에 걸린다.
run 1 "latest_run 이 null → 대기" 1  '["none"]'

# 6. 실패로 끝나면 1.
run 1 "failed → 1"               60  '["failed"]'

# 7. 시간 안에 안 끝나면 1.
run 1 "제한 시간 초과 → 1"        1   '["running"]'

[ "$fails" -eq 0 ] || { echo "$fails 건 실패" >&2; exit 1; }
echo "전부 통과"
