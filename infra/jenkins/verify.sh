#!/bin/sh
# 모든 서비스가 healthy 가 될 때까지 기다리고 실제 응답을 검사한다.
#
# 127.0.0.1 을 쓰면 안 된다. 이 스크립트는 Jenkins 컨테이너 안에서 돌기 때문에
# 127.0.0.1 이 Jenkins 자신을 가리켜 curl 이 000(연결 실패)을 돌려준다.
# 컨테이너 네트워크의 서비스 이름으로 지목한다.
#
# 태그 변수는 필요 없다. ps 와 config --services 는 기본값으로도 프로젝트를 찾는다.
set -eu
: "${DEPLOY_DIR:?}"
cd "$DEPLOY_DIR"

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

if echo "$services" | grep -qx nginx; then
  check 200 http://nginx/healthz
  check 301 http://nginx/
  check 301 http://nginx/api/v1/

  # TLS 종단까지 확인한다. 도메인으로 나가서 다시 들어오므로 인증서 검증이 포함된다.
  domain=$(grep -E '^NPICK_DOMAIN=' .env 2>/dev/null | cut -d= -f2- || true)
  if [ -n "${domain:-}" ] && [ "$domain" != "localhost" ]; then
    check 200 "https://$domain/healthz"
    check_final 200 "https://$domain/"
  fi
fi

[ "$fail" = "0" ] || { echo "응답 검증 실패" >&2; exit 1; }
echo "검증 통과"
