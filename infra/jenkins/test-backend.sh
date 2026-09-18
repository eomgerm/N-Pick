#!/bin/sh
# MR 게이트 BE 테스트. Jenkins agent 에 JDK 가 없어 temurin:21 컨테이너에서 gradlew test 를 돌린다.
#
# 경로(이다인 P1): Jenkins 가 컨테이너 안에서 돌면, docker.sock 으로 띄운 sibling 컨테이너의
# bind-mount 는 "호스트 Docker 데몬" 기준으로 해석된다. Jenkins 컨테이너 안의 $PWD/$WORKSPACE 는
# 호스트 파일시스템엔 없으므로 `-v "$PWD:/repo"` 는 빈 디렉터리로 마운트돼 검색이 항상 실패한다.
# 대신 Jenkins 컨테이너의 볼륨을 그대로 상속(--volumes-from)해 워크스페이스를 같은 경로에 노출하고,
# 그 경로($WORKSPACE)에서 실행한다. (JENKINS_CONTAINER 로 컨테이너명을 덮어쓸 수 있다 — 기본은
# 컨테이너 hostname = 컨테이너 ID.)
#
# testcontainers(paradedb) sibling 도달: Docker Desktop 은 host.docker.internal, 네이티브 리눅스는
# host-gateway 로 해석하므로 --add-host + TESTCONTAINERS_HOST_OVERRIDE 로 강제한다. --network host
# 유지(격리보다 sibling 도달성 우선). gradle 홈은 named volume 으로 재사용한다.
set -eu
: "${WORKSPACE:?WORKSPACE 가 설정돼야 한다(Jenkins 워크스페이스 경로)}"
docker run --rm \
  --network host \
  --add-host=host.docker.internal:host-gateway \
  -e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal \
  -v /var/run/docker.sock:/var/run/docker.sock \
  --volumes-from "${JENKINS_CONTAINER:-$(hostname)}" \
  -v npick-ci-gradle:/root/.gradle \
  -w "$WORKSPACE/backend" \
  eclipse-temurin:21-jdk-noble \
  ./gradlew test --no-daemon --init-script "$WORKSPACE/infra/jenkins/be-gate-exclusions.gradle"
