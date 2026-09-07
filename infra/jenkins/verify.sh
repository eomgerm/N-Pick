#!/bin/sh
# 모든 서비스가 healthy 가 될 때까지 기다리고 nginx 경유 응답을 확인한다.
set -eu
: "${DEPLOY_DIR:?}" "${IMAGE_TAG:?}"
cd "$DEPLOY_DIR"
export IMAGE_TAG

# ps 는 뜬 서비스만 센다. 기동에 실패한 서비스가 분모에서 빠지지 않도록
# 기대 목록은 config --services 에서 가져온다(활성 프로필 반영).
total=$(docker compose config --services | grep -c . || true)
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

for p in /healthz / /api/v1/; do
  printf '%-12s %s\n' "$p" "$(curl -s -o /dev/null -w '%{http_code}' "http://127.0.0.1$p")"
done
