FROM eclipse-temurin:21-jdk-noble AS build
WORKDIR /workspace/backend

# 의존성 레이어를 소스와 분리해 캐시가 살도록 한다
COPY backend/gradlew backend/settings.gradle backend/build.gradle ./
COPY backend/gradle ./gradle
RUN chmod +x gradlew && ./gradlew dependencies --no-daemon

COPY backend/src ./src
COPY infra/compose/profiles/pipeline.yml /workspace/infra/compose/profiles/pipeline.yml
RUN ./gradlew bootJar --no-daemon -x test

FROM eclipse-temurin:21-jre-noble
WORKDIR /app

# curl: compose healthcheck, ffmpeg 패키지의 ffprobe: 영상 정보 추출
RUN apt-get update && apt-get install -y --no-install-recommends curl ffmpeg && rm -rf /var/lib/apt/lists/*

RUN groupadd --gid 10001 npick && useradd --uid 10001 --gid npick --create-home npick

COPY --from=build /workspace/backend/build/libs/*.jar app.jar
RUN chown npick:npick app.jar

# media root 를 이미지에 미리 만들어 둔다. named volume 은 최초 마운트 때 이 경로의
# 소유권을 물려받는다. 없으면 root 소유로 생성되어 npick 유저가 쓰지 못한다.
RUN mkdir -p /srv/npick/media && chown -R npick:npick /srv/npick

USER npick

EXPOSE 8080

# 저장은 UTC 로 고정한다(FRD §15.6). 표시 변환은 앱·FE 책임
#
# -Xmx1g: 상한이 없으면 JVM 이 컨테이너 가용 메모리의 25%(약 3.75GB)까지 힙을 키운다.
# EC2 는 15GB 에 swap 이 없어서 그 상태로 워커가 함께 오르면 커널 OOM killer 가
# postgres 를 고른다. 실측 사용량은 433MB 이므로 1g 는 두 배 이상의 여유다.
# compose 의 mem_limit 2g 는 이 힙 밖(메타스페이스·스레드 스택·ffprobe)의 몫이다.
# 올릴 때는 실사용량을 먼저 재고 compose 쪽 상한도 함께 올린다 (S15P21A501-187).
ENTRYPOINT ["java", "-Xmx1g", "-Duser.timezone=UTC", "-jar", "app.jar"]
