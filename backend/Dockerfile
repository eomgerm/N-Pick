FROM eclipse-temurin:21-jdk-noble AS build
WORKDIR /workspace

# 의존성 레이어를 소스와 분리해 캐시가 살도록 한다
COPY gradlew settings.gradle build.gradle ./
COPY gradle ./gradle
RUN chmod +x gradlew && ./gradlew dependencies --no-daemon

COPY src ./src
RUN ./gradlew bootJar --no-daemon -x test

FROM eclipse-temurin:21-jre-noble
WORKDIR /app

# compose healthcheck 용. jre 이미지에는 curl 이 없다
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/*

RUN groupadd --gid 10001 npick && useradd --uid 10001 --gid npick --create-home npick

COPY --from=build /workspace/build/libs/*.jar app.jar
RUN chown npick:npick app.jar

# media root 를 이미지에 미리 만들어 둔다. named volume 은 최초 마운트 때 이 경로의
# 소유권을 물려받는다. 없으면 root 소유로 생성되어 npick 유저가 쓰지 못한다.
RUN mkdir -p /srv/npick/media && chown -R npick:npick /srv/npick

USER npick

EXPOSE 8080

# 저장은 UTC 로 고정한다(FRD §15.6). 표시 변환은 앱·FE 책임
ENTRYPOINT ["java", "-Duser.timezone=UTC", "-jar", "app.jar"]
