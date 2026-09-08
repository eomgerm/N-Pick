#!/bin/sh
# 인증서를 갱신하고 nginx 를 리로드한다. cron 에서 root 로 실행한다.
#   0 3,15 * * * /home/<계정>/S15P21A501/infra/nginx/renew-cert.sh >> /var/log/npick-certbot.log 2>&1
#
# certbot 은 만료 30일 전부터만 실제로 갱신하므로 자주 돌려도 안전하다.
# 갱신 경로는 발급 때 기록된 webroot 를 그대로 쓴다.
set -eu
cd "$(dirname "$0")/../.."
docker compose run --rm certbot renew
docker compose exec -T nginx nginx -s reload
echo "[$(date -u +%FT%TZ)] renew 완료"
