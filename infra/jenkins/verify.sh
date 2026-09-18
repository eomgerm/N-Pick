#!/bin/sh
# 이 배포가 싣는 서비스가 healthy 가 될 때까지 기다리고 실제 응답을 검사한다.
#
# 127.0.0.1 을 쓰면 안 된다. 이 스크립트는 Jenkins 컨테이너 안에서 돌기 때문에
# 127.0.0.1 이 Jenkins 자신을 가리켜 curl 이 000(연결 실패)을 돌려준다.
# 컨테이너 네트워크의 서비스 이름으로 지목한다.
#
# 태그 변수는 필요 없다. ps 와 config --services 는 기본값으로도 프로젝트를 찾는다.
set -eu
: "${DEPLOY_DIR:?}"
cd "$DEPLOY_DIR"

# **이 스크립트의 실패는 곧 롤백이다** (Jenkinsfile:269 → rollback.sh). 그래서 게이트는
# 이 배포가 실제로 싣는 서비스여야 한다. 아래 목록은 그렇지 않은 것들이다.
#
# mlflow 를 뺀 이유가 셋이다.
#   ① 이 배포가 싣지 않는다 — compose.yaml 의 image 가 고정 upstream 태그라
#      deploy.sh 의 BACKEND_TAG·FRONTEND_TAG·AI_TAG 어디에도 걸리지 않는다.
#      **되돌려도 mlflow 는 그대로다.**
#   ② 요청 경로가 여기 묶여 있지 않다 — nginx 가 요청 시점에 upstream 이름을 다시
#      풀어(infra/nginx/snippets/app-routes.conf) /mlflow/ 만 502 고 나머지는 서빙된다.
#   ③ 실패가 자기 복제된다 — rollback.sh 는 같은 mlflow 를 다시 up -d 하므로 다음
#      배포도 같은 자리에서 죽는다. 사람이 손대기 전까지 파이프라인이 잠긴다.
#
# profiles: 를 붙이는 것은 답이 아니다. mlflow 는 EC2 에서 실제로 떠야 하므로 프로필을
# 켜야 하고, config --services 는 활성 프로필을 반영하니 그대로 분모에 남는다.
non_gating='mlflow'

# ps 는 뜬 서비스만 센다. 기동에 실패한 서비스가 분모에서 빠지지 않도록
# 기대 목록은 config --services 에서 가져온다(활성 프로필 반영).
services=$(docker compose config --services | grep -vxF "$non_gating")
total=$(echo "$services" | grep -c .)

n=0
i=1
while [ "$i" -le 30 ]; do
  # 분자도 같은 범위로 좁힌다. 분모만 좁히면 게이트 밖 서비스가 게이트 안의 결원을
  # 메워 깨진 배포가 초록으로 지나간다.
  # shellcheck disable=SC2086
  n=$(docker compose ps $services --format '{{.Health}}' | grep -cx healthy || true)
  echo "[$((i * 10))s] healthy=$n/$total"
  [ "$n" = "$total" ] && break
  sleep 10
  i=$((i + 1))
done

# 게이트 밖 서비스도 **보이기는 해야 한다.** 종료 코드에 싣지 않을 뿐이다.
# shellcheck disable=SC2086
docker compose ps $non_gating --format '게이트 밖 — {{.Service}} {{.Health}}' || true

if [ "$n" != "$total" ]; then
  echo "healthy 가 아닌 서비스가 있다" >&2
  docker compose ps
  exit 1
fi

fail=0

report() {
  if [ "$2" = "$3" ]; then
    printf '%-44s %s\n' "$1" "$2"
  else
    printf '%-44s %s (기대 %s)\n' "$1" "$2" "$3" >&2
    fail=1
  fi
}

# 응답 코드를 그대로 검사한다.
# curl 실패를 $( ) 안에서 처리하면 출력이 이어붙어 301000 같은 값이 된다. 대입 뒤에서 처리한다.
check() {
  got=$(curl -s -o /dev/null -w '%{http_code}' --max-time 10 "$2") || got=000
  report "$2" "$got" "$1"
}

# 리다이렉트를 따라가 최종 코드를 검사한다. web 의 라우팅이 바뀌어도 깨지지 않는다.
# (지금은 FE 가 / 에서 /landing 으로 307 을 준다)
#
# 도메인 주소에만 쓴다. http://nginx/ 는 리다이렉트 대상이 https://nginx/ 가 되어
# 인증서 이름이 맞지 않아 추적이 실패한다.
check_final() {
  got=$(curl -sL -o /dev/null -w '%{http_code}' --max-time 20 "$2") || got=000
  report "$2 (추적)" "$got" "$1"
}

check 200 http://backend:8080/actuator/health
check 200 http://ai-worker:8000/health

# **/health 의 200 만으로는 부족하다.** 그 응답은 무조건 status=ok 라(app.py) 잡을
# 하나도 못 가져가는 워커도 healthy 로 통과한다 — NPICK_AI_JOB_STAGES 오타, 워밍업
# 실패, 기대 버전 불일치가 전부 그 모양이다. 이 배포의 요점이 "ocr 은 CPU 워커가
# 맡는다" 이므로 그 선언을 직접 확인한다 (S15P21A501-187).
#
# 셋 다 본다.
#   declared        NPICK_AI_JOB_STAGES 가 제대로 좁혔는가
#   warmup.ready    그 단계가 실제로 claim 에 실릴 수 있는가(schemas.py 가 둘을 갈라 둔다)
#   polling.running **잡 루프가 살아 있는가** — 토큰이나 fleet 이 틀리면 루프가 죽는데
#                   /health 는 200 이고 warmup 도 true 로 남는다. 이번 배포의 필수
#                   설정이 바로 그 둘이라 이것을 안 보면 검증이 하는 일이 없다.
# jq 는 `infra/jenkins/Dockerfile` 이 넣는다. **이미지를 다시 만들지 않은 Jenkins 에서는
# 없다** — 그 경우 아래 파이프가 조용히 비고 parse-실패 로만 보여서 원인을 찾는 데
# 시간이 든다(2026-09-18 실측). 먼저 확인하고 무엇이 없는지 말한다.
if echo "$services" | grep -qx ai-cpu-worker; then
  command -v jq >/dev/null 2>&1 || {
    echo "jq 가 없다. infra/jenkins/Dockerfile 로 Jenkins 이미지를 다시 만든다 (README 14-6)" >&2
    exit 1
  }
  body=$(curl -s --max-time 10 http://ai-cpu-worker:8000/health || echo "{}")
  got=$(printf '%s' "$body" |
    jq -r '[(.pipeline.declared // [] | index("ocr") | if . then "ocr" else "없음" end),
            (.warmup.ready | tostring),
            (.polling.enabled | tostring),
            (.polling.running | tostring)] | join("/")' 2>/dev/null || echo 'parse-실패')
  report "ai-cpu-worker declared/warm/poll" "$got" "ocr/true/true/true"
fi

if echo "$services" | grep -qx nginx; then
  check 200 http://nginx/healthz
  check 301 http://nginx/
  check 301 http://nginx/api/v1/

  # TLS 종단까지 확인한다. 도메인으로 나가서 다시 들어오므로 인증서 검증이 포함된다.
  domain=$(grep -E '^NPICK_DOMAIN=' .env 2>/dev/null | cut -d= -f2- || true)
  if [ -n "${domain:-}" ] && [ "$domain" != "localhost" ]; then
    check 200 "https://$domain/healthz"
    check_final 200 "https://$domain/"
    # **nginx 를 실제로 통과해 backend 까지 닿는지 본다.** 위의 /healthz 는 nginx 가
    # 직접 답하고 http://nginx/api/v1/ 은 301 만 보므로 둘 다 upstream 을 건드리지
    # 않는다. 그래서 2026-09-17 배포 뒤 `/api/*` 가 502 로 죽어 있는데도 이 스크립트가
    # 통과했다. csrf 는 인증 없이 200 을 주는 유일한 경로라 여기 쓴다 (S15P21A501-187).
    check 200 "https://$domain/api/v1/auth/csrf"
  fi
fi

[ "$fail" = "0" ] || { echo "응답 검증 실패" >&2; exit 1; }
echo "검증 통과"
