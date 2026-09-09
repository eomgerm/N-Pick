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

### DB 테스트

DB 를 쓰는 테스트는 [Testcontainers](https://testcontainers.com/) 가 compose 와 같은 `paradedb/paradedb:0.25.6-pg18`
컨테이너를 띄워 쓴다. 환경 변수도 수동 `docker run` 도 필요 없고, 도커만 돌아가면 된다.

```bash
./gradlew test
```

- 컨테이너는 `com.npick.support.NpickPostgres` 가 JVM 당 하나만 띄우고 기동 직후 `npick` 스키마와 baseline 을 한 번 적용한다.
  종료는 Testcontainers 의 Ryuk 이 처리한다.
- `FlywayBaselineTest` 만 같은 컨테이너 안에 전용 DB(`npick_baseline`)를 따로 만든다. 빈 DB 에서만 성립하는 단언을 하기 때문이다.
- **도커가 없으면 스킵이 아니라 실패한다.** 의도한 동작이며, 검증되지 않은 것을 초록불로 위장하지 않기 위함이다.
- 영상 인코딩을 실제로 돌리는 테스트는 로컬에 ffmpeg/ffprobe 가 있을 때만 돌린다(`NPICK_MEDIA_TESTS=true`).

#### Flyway baseline 검증 (S15P21A501-153)

timestamp baseline은 FRD v3.1을 반영한 최종 ERDCloud의 **13개 테이블·134개 컬럼·23개 FK** 기준이다.
기존 baseline이 적용된 DB에는 그대로 실행하지 않고 별도 이관 방식을 결정한다.
그런 DB가 발견되면 checksum을 강제로 repair하거나 데이터를 삭제하지 말고 이관을 별도로 결정한다.
`research/` 원문 반입은 154번 작업이며 저장소의 옛 FRD v2.2와 혼동하지 않는다.

검증 항목: timestamp baseline 최초 적용·validate·재실행 무변경, ERD 전체 컬럼/주석/FK 대조,
중복·값 조합 제약, 확장 및 BM25/벡터 검색, 후보 변경의 롤백과 검증 기록 저장 가능 여부.
검색 서비스의 순위 품질·권한·동시 확정 로직까지 테스트하는 것은 아니다.
기대 구조 TSV는 2026-09-04 최종 ERD 스냅샷에서 얻은 회귀 테스트 기준이다.

baseline의 확장 설치에는 DB 관리자 권한이 필요하다. 제한된 앱 계정이라면 관리자가 같은 DB의
public 스키마에 `vector`·`pg_search`를 먼저 설치한다. ANN 인덱스의 거리 연산자·튜닝은
100번의 임베딩 모델 확정 후에 정하며, 임시 차원은 `vector(1024)`다.

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

도메인 모듈은 아직 없다. 정본 §16에 따라 빈 패키지를 미리 만들지 않고, 실제 기능이 생길 때 추가한다.
