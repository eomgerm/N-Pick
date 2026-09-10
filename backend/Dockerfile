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
ENTRYPOINT ["java", "-Duser.timezone=UTC", "-jar", "app.jar"]
