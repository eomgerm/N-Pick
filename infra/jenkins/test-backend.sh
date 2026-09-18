#!/bin/sh
# MR 게이트 BE 테스트. Jenkins agent 에 JDK 가 없으므로 배포 이미지와 같은 temurin:21 컨테이너
# 안에서 gradlew test 를 돌린다. BE 통합 테스트는 testcontainers 로 paradedb 를 띄우는데,
# 이 컨테이너는 마운트한 호스트 Docker 데몬(docker.sock)에 sibling 으로 뜬다. gradle JVM 이
# 그 sibling 의 매핑 포트에 닿아야 한다.
#
# 도달 경로: Docker Desktop(WSL2) 데몬에서는 컨테이너에 발행된 포트가 컨테이너의 localhost
# (127.0.0.1)나 브리지 게이트웨이(172.17.0.1)로는 닿지 않는다 — 오직 host-gateway 주소
# (host.docker.internal)로만 닿는다. testcontainers 는 컨테이너 안이라고 감지되면 호스트를
# 172.17.0.1 로 잡아 Ryuk·DB 연결이 전부 refused 로 실패한다. 그래서
#   1) --add-host 로 host.docker.internal 을 host-gateway 에 매핑하고,
#   2) TESTCONTAINERS_HOST_OVERRIDE 로 testcontainers 가 그 주소를 쓰도록 강제한다.
# (테스트 실행이므로 컨테이너 격리보다 sibling 도달성이 우선이라 --network host 를 유지한다.)
#
# gradle 홈은 named volume 으로 재사용해 매 MR 마다 의존성을 새로 받지 않는다.
set -eu
cd "$(dirname "$0")/../.."
docker run --rm \
  --network host \
  --add-host=host.docker.internal:host-gateway \
  -e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal \
  -v /var/run/docker.sock:/var/run/docker.sock \
  -v "$PWD:/repo" \
  -v npick-ci-gradle:/root/.gradle \
  -w /repo/backend \
  eclipse-temurin:21-jdk-noble \
  ./gradlew test --no-daemon --init-script /repo/infra/jenkins/be-gate-exclusions.gradle
