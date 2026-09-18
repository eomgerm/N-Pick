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
# 소유권(이다인 P1 후속): 게이트 컨테이너는 root 로 돌아 gradle 산출물(build/·.gradle)을 공유
# 워크스페이스에 root 소유로 남긴다. 같은 워크스페이스를 쓰는 호스트 개발자가 이후 덮어쓰지 못해
# 로컬 빌드가 "Permission denied" 로 막힌다(2026-09-18 실측: host backend/build 1964개 root 소유).
# gradle 캐시 named volume 은 유지(성능)하고, 실행 뒤 산출물만 워크스페이스 소유자로 되돌린다.
# uid 는 하드코딩하지 않고 워크스페이스 소유자에서 읽어 어느 호스트에서도 맞는다. 종료코드는 보존한다.
HOST_UID="$(stat -c %u "$WORKSPACE")"
HOST_GID="$(stat -c %g "$WORKSPACE")"
docker run --rm \
  --network host \
  --add-host=host.docker.internal:host-gateway \
  -e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal \
  -v /var/run/docker.sock:/var/run/docker.sock \
  --volumes-from "${JENKINS_CONTAINER:-$(hostname)}" \
  -v npick-ci-gradle:/root/.gradle \
  -w "$WORKSPACE/backend" \
  eclipse-temurin:21-jdk-noble \
  sh -c "./gradlew test --no-daemon --init-script \"$WORKSPACE/infra/jenkins/be-gate-exclusions.gradle\"; rc=\$?; chown -R $HOST_UID:$HOST_GID \"$WORKSPACE/backend/build\" \"$WORKSPACE/backend/.gradle\" 2>/dev/null || true; exit \$rc"
