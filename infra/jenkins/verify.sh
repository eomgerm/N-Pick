#!/bin/sh
# 모든 서비스가 healthy 가 될 때까지 기다리고 실제 응답을 검사한다.
#
# 127.0.0.1 을 쓰면 안 된다. 이 스크립트는 Jenkins 컨테이너 안에서 돌기 때문에
# 127.0.0.1 이 Jenkins 자신을 가리켜 curl 이 000(연결 실패)을 돌려준다.
# 컨테이너 네트워크의 서비스 이름으로 지목한다.
set -eu
: "${DEPLOY_DIR:?}" "${IMAGE_TAG:?}"
cd "$DEPLOY_DIR"
export IMAGE_TAG

# ps 는 뜬 서비스만 센다. 기동에 실패한 서비스가 분모에서 빠지지 않도록
# 기대 목록은 config --services 에서 가져온다(활성 프로필 반영).
services=$(docker compose config --services)
total=$(echo "$services" | grep -c .)

n=0
i=1
while [ "$i" -le 30 ]; do
  n=$(docker compose ps --format '{{.Health}}' | grep -cx healthy || true)
  echo "[$((i * 10))s] healthy=$n/$total"
  [ "$n" = "$total" ] && break
  sleep 10
  i=$((i + 1))
done

if [ "$n" != "$total" ]; then
  echo "healthy 가 아닌 서비스가 있다" >&2
  docker compose ps
  exit 1
fi

fail=0
check() {
  want=$1; url=$2
  got=$(curl -s -o /dev/null -w '%{http_code}' --max-time 10 "$url" || echo 000)
  if [ "$got" = "$want" ]; then
    printf '%-46s %s\n' "$url" "$got"
  else
    printf '%-46s %s (기대 %s)\n' "$url" "$got" "$want" >&2
    fail=1
  fi
}

# 애플리케이션 컨테이너 직접 확인. 프록시 유무와 무관하다.
check 200 http://backend:8080/actuator/health
check 200 http://ai-worker:8000/health

# nginx 는 proxy 프로필 뒤에 있다. 켜져 있을 때만 확인한다.
if echo "$services" | grep -qx nginx; then
  # /healthz 는 리다이렉트에서 제외돼 200, 나머지는 https 로 301 이다.
  check 200 http://nginx/healthz
  check 301 http://nginx/
  check 301 http://nginx/api/v1/

  # TLS 종단까지 확인한다. 도메인으로 나가서 다시 들어오므로 인증서 검증이 포함된다.
  domain=$(grep -E '^NPICK_DOMAIN=' .env 2>/dev/null | cut -d= -f2- || true)
  if [ -n "${domain:-}" ] && [ "$domain" != "localhost" ]; then
    check 200 "https://$domain/healthz"
    check 200 "https://$domain/"
  fi
fi

[ "$fail" = "0" ] || { echo "응답 검증 실패" >&2; exit 1; }
echo "검증 통과"
