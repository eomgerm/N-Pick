# N-Pick Backend

Spring Boot 기반 N-Pick API 서버.

## 요구 사항

- **JDK 21** (필수). `java -version` 으로 확인.
- Gradle 은 wrapper(`./gradlew`)를 쓰므로 별도 설치 불필요.
- **Docker** (필수). 테스트가 Testcontainers 로 DB 를 직접 띄운다. 도커가 없으면 DB 테스트는 스킵이 아니라 **실패**한다.
- **PostgreSQL 18.6 + pg_search 0.25.6 + pgvector** (필수). compose 기준 이미지는
  `paradedb/paradedb:0.25.6-pg18`이다. 스키마는 `npick` 이고, 기동 시 Flyway 가 `db/migration` 의
  baseline 을 이 스키마에 적용한다. `npick` 스키마 자체는 compose 의
  `infra/compose/postgres-init` 초기화 스크립트가 만든다(Flyway 는 `create-schemas=false`).

## 실행

DB 를 먼저 띄우고, 그 접속 정보를 backend 프로세스에 넘긴다. compose 는 `.env` 를 읽지만
`./gradlew bootRun` 은 읽지 않으므로 **환경 변수를 직접 넘겨야 한다.**

```bash
# 1) 저장소 루트에서 DB 기동 (.env 필요 — .env.example 참고)
cp .env.example .env          # 최초 1회. POSTGRES_PASSWORD 를 채운다
docker compose up -d postgres

# 2) .env 의 값을 셸로 불러와 backend 에 전달
set -a && . ./.env && set +a
export LOCAL_DB_USERNAME="$POSTGRES_USER" LOCAL_DB_PASSWORD="$POSTGRES_PASSWORD"

# 3) 기동
cd backend && ./gradlew bootRun            # 기본 프로파일: local
```

Windows PowerShell:

```powershell
docker compose up -d postgres
Get-Content .env | Where-Object { $_ -match '^\s*[^#].*=' } | ForEach-Object {
    $k, $v = $_ -split '=', 2
    Set-Item -Path "env:$($k.Trim())" -Value $v.Trim()
}
$env:LOCAL_DB_USERNAME = $env:POSTGRES_USER
$env:LOCAL_DB_PASSWORD = $env:POSTGRES_PASSWORD
cd backend; .\gradlew.bat bootRun
```

`LOCAL_DB_PASSWORD` 는 기본값이 없다. 2) 단계를 빠뜨리면 기동이 이렇게 실패한다.

```
Unable to obtain connection from database:
FATAL: password authentication failed for user "npick"
```

기동 확인:

```bash
curl http://localhost:8080/actuator/health   # {"status":"UP"}
```

## 빌드 / 테스트

```bash
./gradlew build              # 컴파일 + 테스트 + jar
./gradlew test
```

### 통합 테스트

DB 를 쓰는 테스트는 [Testcontainers](https://testcontainers.com/) 가 compose 와 같은
`paradedb/paradedb:0.25.6-pg18` 컨테이너를 띄워 쓴다. 환경 변수도 수동 `docker run` 도 필요 없다.

- 컨테이너는 `com.npick.support.NpickPostgres` 가 JVM 당 하나만 띄우고, 기동 직후 `npick` 스키마와
  migration 을 한 번 적용한다. 종료는 Testcontainers 의 Ryuk 이 처리한다.
- 롤백 없이 커밋하는 테스트와 비어 있는 DB 가 필요한 테스트는 같은 컨테이너 안에 전용 DB 를 받는다.
  `FlywayBaselineTest` 와 `RegistrationDeduplicationIntegrationTest` 는 migration 적용 건수를
  단언하므로 비어 있는 DB 가 아니면 성립하지 않는다.
- **도커가 없으면 스킵이 아니라 실패한다.** 의도한 동작이며, 검증되지 않은 것을 초록불로 위장하지 않기 위함이다.

실제 미디어 테스트는 `ffmpeg` 와 `ffprobe` 를 PATH 에 설치하고 `NPICK_MEDIA_TESTS=true` 로 활성화한다.
파일 시스템 테스트에는 심볼릭 링크 생성 권한이 필요하다. 이 조건을 충족하지 않은 테스트는 생략된다.

이미 적용된 migration 을 수정하거나 checksum 을 강제로 repair 하지 않는다. 스키마 변경은 후속
migration 으로 관리한다. `vector`·`pg_search` 확장 설치에는 관리자 권한이 필요하다.

테스트 설정은 `src/test/resources/application-test.yml` 이며 `test` 프로파일로 활성화된다(`build.gradle`).
같은 이름의 `application.yml` 을 테스트 클래스패스에 두면 main 의 설정을 통째로 가려 운영 설정이
검증되지 않으므로, 프로파일 파일로 둔다.

## 프로파일

| 프로파일 | 용도 | 비고 |
| --- | --- | --- |
| `local` | 로컬 개발 (기본값) | `com.npick` DEBUG 로그, health 상세 노출 |
| `prod` | 배포 | root WARN 로그, health 상세 비노출 |

프로파일별 설정은 `src/main/resources/application.yml` 한 파일에 `---` 로 나뉘어 들어 있다
(`spring.config.activate.on-profile`). 프로파일을 추가할 때도 파일을 늘리지 않는다.

프로파일 지정:

```bash
./gradlew bootRun --args='--spring.profiles.active=prod'
java -jar build/libs/npick-0.0.1-SNAPSHOT.jar --spring.profiles.active=prod
```

## 환경 변수

민감값은 하드코딩하지 않고 환경 변수로만 주입한다. `.env` 류 파일은 커밋 금지(`.gitignore` 처리됨).

| 변수 | 기본값 | 설명 |
| --- | --- | --- |
| `SERVER_PORT` | `8080` | 포트 충돌 시 오버라이드 |
| `LOCAL_DB_URL` | `jdbc:postgresql://localhost:5432/npick` | local 프로파일 접속 주소 |
| `LOCAL_DB_USERNAME` | `npick` | `.env` 의 `POSTGRES_USER` 와 같아야 한다 |
| `LOCAL_DB_PASSWORD` | **없음** | `.env` 의 `POSTGRES_PASSWORD` 를 넘긴다 |
| `LOCAL_CORS_ALLOWED_ORIGINS` | `http://localhost:3000` | 허용 origin |

prod 프로파일은 `DB_URL` / `DB_USERNAME` / `DB_PASSWORD` / `CORS_ALLOWED_ORIGINS` 를 쓰며
넷 다 기본값이 없다.

```bash
SERVER_PORT=8081 ./gradlew bootRun
```

## 패키지 구조

최상위를 기술 계층이 아니라 **도메인 단위**로 나누는 실용적 DDD 구조를 따른다.

```
com.npick
├── NpickApplication.java
├── common/          # 여러 도메인이 공유하는 기술 설정과 공통 계약
└── <domain>/        # Bounded Context 또는 업무 도메인
    ├── presentation/
    ├── application/
    ├── domain/
    └── infrastructure/
```

설계 규약 정본은 [docs/ddd-package-architecture.md](docs/ddd-package-architecture.md)에 있다. **코드를 쓰기 전에 읽는다.**

필요한 도메인 패키지만 생성하며 빈 패키지를 미리 만들지 않는다.

## 등록 운영

- 영상 검사에 `ffmpeg`·`ffprobe`가 필요하다. 등록용 저장 경로·입력 제한·파이프라인 설정은
  [환경 변수 예시](.env.example)와 [애플리케이션 설정](src/main/resources/application.yml)을 참고한다.
- 중복 처리에는 PostgreSQL 세션 advisory lock을 사용하므로 DB 직결 또는 세션 유지형 풀이 필요하다.
  PgBouncer transaction pooling은 지원하지 않는다. 등록당 연결 2개와 다른 요청의 여유를 고려해 풀을 설정한다.
- 여러 인스턴스는 등록된 영상에 동일하게 접근할 수 있어야 한다. 저장소 디렉터리는 운영 계정만 변경할 수 있게 한다.
- 요청 키 기록은 자동 만료되지 않는다. 보관 정책을 변경할 때는 클라이언트의 재전송 기간과 함께 검토한다.
- 결과가 불명확한 요청은 시간이 지났다는 이유로 재실행하거나 파일을 삭제하지 않는다.
  재요청으로 결과를 복원할 수 없다면 clip·최초 run·진행 중 DB 트랜잭션·소유 파일을 대조한다.
  커밋 부재와 파일 소유권을 확인한 경우에만 고아 파일 정리 및 요청 상태의 `failed` 전환으로 재시도를 허용한다.
