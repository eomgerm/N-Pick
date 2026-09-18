# Jenkins 가 호스트 Docker 데몬으로 빌드·배포를 실행하기 위한 이미지.
# 데몬이 아니라 CLI 만 넣는다(Docker-outside-of-Docker). 소켓은 compose 가 아니라
# docker run 으로 마운트한다 — Jenkins 는 compose 스택 밖 컨테이너다.
FROM jenkins/jenkins:lts

# 호스트의 docker 그룹 GID. 소켓 소유 그룹과 일치해야 접근된다.
#   확인: getent group docker
ARG DOCKER_GID=988

USER root

# jq: scripts/seed-clips.sh 가 사이드카와 응답 봉투를 읽는다. 베이스 이미지에 없어서
# npick-ops 의 SEED 가 "로그인에 실패했다" 로 죽는다 — jq 가 없으면 파이프 앞단이
# 실패하고 그 메시지가 curl 쪽 핸들러에서 나오기 때문이다 (S15P21A501-187).

RUN apt-get update \
 && apt-get install -y --no-install-recommends ca-certificates curl gnupg jq \
 && install -m 0755 -d /etc/apt/keyrings \
 && curl -fsSL https://download.docker.com/linux/debian/gpg -o /etc/apt/keyrings/docker.asc \
 && chmod a+r /etc/apt/keyrings/docker.asc \
 && echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/debian $(. /etc/os-release && echo "$VERSION_CODENAME") stable" \
    > /etc/apt/sources.list.d/docker.list \
 && apt-get update \
 && apt-get install -y --no-install-recommends docker-ce-cli docker-buildx-plugin docker-compose-plugin \
 && rm -rf /var/lib/apt/lists/*

# 이미 같은 GID 의 그룹이 있으면 그 그룹에 넣는다. 없으면 만든다.
RUN if getent group "$DOCKER_GID" >/dev/null; then \
      usermod -aG "$(getent group "$DOCKER_GID" | cut -d: -f1)" jenkins; \
    else \
      groupadd --gid "$DOCKER_GID" docker && usermod -aG docker jenkins; \
    fi

USER jenkins
