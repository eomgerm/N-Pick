#!/usr/bin/env bash
# verify.sh 의 분기와 종료 코드를 검증한다. 프레임워크 없이 PATH 앞에 가짜
# docker·curl·jq·sleep 을 놓는다 — 실제 배포도 네트워크도 건드리지 않는다.
#
#   bash infra/jenkins/tests/verify-test.sh
#
# 이 검사가 있는 이유는 하나다. **verify.sh 의 실패가 곧 롤백이다**(Jenkinsfile:269).
# 게이트를 넓게 잡으면 멀쩡한 배포가 되돌아가고, 좁게 잡으면 깨진 배포가 초록으로
# 지나간다. 두 방향을 같이 고정한다.
#
# 가짜 sleep 을 넣는다. 대기 루프가 10초 × 30회라 실패 경로 하나가 5분이고,
# 그러면 아무도 이 검사를 돌리지 않는다.
#
# 가짜 jq 는 PATH 앞에 둬서 진짜 jq 를 가린다. 있는 기계와 없는 기계가 다른
# 결과를 내면 그 검사는 신호가 아니다.
set -euo pipefail

HERE=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
SCRIPT="$HERE/../verify.sh"
TMP=$(mktemp -d); trap 'rm -rf "$TMP"' EXIT
mkdir -p "$TMP/bin" "$TMP/state" "$TMP/deploy"
STATE="$TMP/state"; export STATE
fails=0

# 가짜 docker.
#   compose config --services  → $STATE/services (활성 프로필 반영분을 흉내)
#   compose ps [서비스...]     → $STATE/ps 중 **뜬 것만**. 서비스 인자가 있으면 거른다.
#                                --format 의 {{.Service}}·{{.Health}} 를 치환한다.
cat > "$TMP/bin/docker" <<'FAKE'
#!/usr/bin/env bash
shift                     # compose
sub=${1:-}; shift || true
[ "$sub" = config ] && { cat "$STATE/services"; exit 0; }
[ "$sub" = ps ] || exit 0
fmt=''; want=''
while [ $# -gt 0 ]; do
  case "$1" in
    --format)   fmt=$2; shift 2 ;;
    --format=*) fmt=${1#--format=}; shift ;;
    -*)         shift ;;
    *)          want="$want $1 "; shift ;;
  esac
done
while read -r svc health; do
  [ -n "$svc" ] || continue
  [ -z "$want" ] || case "$want" in *" $svc "*) ;; *) continue ;; esac
  if [ -z "$fmt" ]; then printf '%s %s\n' "$svc" "$health"; continue; fi
  line=${fmt//\{\{.Service\}\}/$svc}
  printf '%s\n' "${line//\{\{.Health\}\}/$health}"
done < "$STATE/ps"
FAKE

# 가짜 curl. -w '%{http_code}' 가 있으면 코드를, 없으면 본문을 낸다.
# 코드는 $STATE/codes 의 `<url> <코드>` 에서 찾고 없으면 200 이다.
cat > "$TMP/bin/curl" <<'FAKE'
#!/usr/bin/env bash
url=${@: -1}
case "$*" in
  *http_code*)
    code=$(awk -v u="$url" '$1 == u {print $2}' "$STATE/codes")
    printf '%s' "${code:-200}"
    ;;
  *) cat "$STATE/body" ;;
esac
FAKE

cat > "$TMP/bin/jq" <<'FAKE'
#!/usr/bin/env bash
cat "$STATE/jq_out"
FAKE

printf '#!/usr/bin/env bash\nexit 0\n' > "$TMP/bin/sleep"
chmod +x "$TMP/bin"/*
PATH="$TMP/bin:$PATH"; export PATH
export DEPLOY_DIR="$TMP/deploy"

# 기본값 — 전부 정상. 케이스마다 필요한 것만 덮어쓴다.
# .env 를 두지 않으므로 NPICK_DOMAIN 이 비고 https 검사는 건너뛴다.
SERVICES='postgres
backend
frontend
ai-worker
ai-cpu-worker
mlflow
nginx'
PS='postgres healthy
backend healthy
frontend healthy
ai-worker healthy
ai-cpu-worker healthy
mlflow healthy
nginx healthy'
CODES='http://nginx/ 301
http://nginx/api/v1/ 301'
JQ_OUT='ocr/true/true/true'

run() { # $1=기대코드 $2=설명 → 표준출력을 $STATE/out 에 남긴다
  local expect=$1 desc=$2 got
  printf '%s\n' "$SERVICES" > "$STATE/services"
  printf '%s\n' "$PS"       > "$STATE/ps"
  printf '%s\n' "$CODES"    > "$STATE/codes"
  printf '%s\n' "$JQ_OUT"   > "$STATE/jq_out"
  printf '{}\n'             > "$STATE/body"
  set +e
  sh "$SCRIPT" > "$STATE/out" 2>&1; got=$?
  set -e
  if [ "$got" = "$expect" ]; then
    printf 'ok   %s\n' "$desc"
  else
    printf 'FAIL %s — 기대 %s, 실제 %s\n%s\n' "$desc" "$expect" "$got" "$(cat "$STATE/out")" >&2
    fails=$((fails + 1))
  fi
}
says() { grep -q "$1" "$STATE/out" || { echo "FAIL $2 — 출력에 '$1' 이 있어야 한다" >&2; fails=$((fails+1)); }; return 0; }

# ── 1. 전부 정상이면 통과한다 ───────────────────────────────────────────────
run 0 "전부 healthy + 응답 정상 → 0"

# ── 2. 이 배포가 싣지 않는 서비스는 롤백을 부르지 않는다 ────────────────────
# mlflow 는 compose.yaml:246 이 고정 upstream 태그로 잡는다. deploy.sh 의 태그
# 어디에도 안 걸리므로 backend·frontend·ai 를 되돌려도 이 서비스는 그대로다.
# nginx 는 요청 시점에 upstream 을 다시 풀어(app-routes.conf:36) /mlflow/ 만 502 다.
# 여기서 실패로 세면 rollback.sh:46 이 같은 mlflow 를 다시 띄우고 다음 배포도
# 같은 자리에서 죽는다 — 사람이 손대기 전까지 파이프라인이 잠긴다.
PS=${PS/mlflow healthy/mlflow unhealthy}
run 0 "mlflow 만 unhealthy → 0"
says mlflow "mlflow 상태가 콘솔에 보여야 한다"

# ── 3. 그래도 게이트는 살아 있다 ────────────────────────────────────────────
# 위를 "무시" 로 구현하면 아래 두 개가 같이 새어 나간다.
PS=${PS/backend healthy/backend starting}
run 1 "backend unhealthy → 1"
PS=${PS/backend starting/backend healthy}

# 기동 자체에 실패하면 ps 에서 빠진다. 분모를 config --services 에서 가져오는
# 이유가 이것이다 — ps 만 보면 없는 서비스가 분모에서도 빠져 초록이 된다.
PS=$(printf '%s\n' "$PS" | grep -v '^backend ')
run 1 "게이트 서비스가 뜨지 않음 → 1"
PS="${PS}
backend healthy"
PS=${PS/mlflow unhealthy/mlflow healthy}

# ── 4. 응답 검사와 워커 선언 검사가 그대로 돈다 ─────────────────────────────
JQ_OUT='없음/true/true/true'
run 1 "ai-cpu-worker 가 ocr 을 선언하지 않음 → 1"
JQ_OUT='ocr/true/true/false'
run 1 "잡 루프가 죽어 있음 → 1"
JQ_OUT='ocr/true/true/true'

CODES="$CODES
http://backend:8080/actuator/health 503"
run 1 "backend actuator 503 → 1"

[ "$fails" -eq 0 ] || { echo "$fails 건 실패" >&2; exit 1; }
echo "전부 통과"
