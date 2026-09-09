# N-Pick Backend

Spring Boot 기반 N-Pick API 서버.

## 요구 사항

- **JDK 21** (필수). `java -version` 으로 확인.
- Gradle 은 wrapper(`./gradlew`)를 쓰므로 별도 설치 불필요.
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

### Flyway baseline 검증 (S15P21A501-153)

timestamp baseline은 FRD v3.1을 반영한 최종 ERDCloud의 **13개 테이블·134개 컬럼·23개 FK** 기준이다.
기존 baseline이 적용된 DB에는 그대로 실행하지 않고 별도 이관 방식을 결정한다.
그런 DB가 발견되면 checksum을 강제로 repair하거나 데이터를 삭제하지 말고 이관을 별도로 결정한다.
`research/` 원문 반입은 154번 작업이며 저장소의 옛 FRD v2.2와 혼동하지 않는다.

`FlywayBaselineTest`는 전용 테스트 DB 환경 변수가 있을 때만 실행된다. 일반 `test`에서
이 테스트가 생략된 것은 DB 검증 성공을 의미하지 않는다. 기존 npick 객체가 하나라도 있으면
수정 전에 실패하며, Flyway clean은 사용하지 않는다. 기존 컨텍스트 테스트는 DB 없이 유지한다.

Windows PowerShell에서 아래처럼 **새 일회용 컨테이너**를 사용한다(backend 디렉터리 기준).
테스트용 비밀번호이며 실제 개발·운영 DB의 자격증명을 사용하지 않는다.

```powershell
docker run -d --rm --name npick-flyway-test -p 127.0.0.1::5432 `
  -e POSTGRES_USER=npick_test -e POSTGRES_PASSWORD=disposable_test_only `
  -e POSTGRES_DB=npick_schema_test paradedb/paradedb:0.25.6-pg18
docker exec npick-flyway-test pg_isready -U npick_test -d npick_schema_test
# accepting connections 확인 후 진행한다.
$migrationPort = (docker port npick-flyway-test 5432/tcp).Split(':')[-1]
$env:NPICK_MIGRATION_TEST_URL = "jdbc:postgresql://127.0.0.1:${migrationPort}/npick_schema_test"
$env:NPICK_MIGRATION_TEST_USER = 'npick_test'
$env:NPICK_MIGRATION_TEST_PASSWORD = 'disposable_test_only'
try {
  .\gradlew.bat clean build --console=plain
} finally {
  Remove-Item Env:NPICK_MIGRATION_TEST_URL, Env:NPICK_MIGRATION_TEST_USER, Env:NPICK_MIGRATION_TEST_PASSWORD
  docker stop npick-flyway-test
}
```

검증 항목: timestamp baseline 최초 적용·validate·재실행 무변경, ERD 전체 컬럼/주석/FK 대조,
중복·값 조합 제약, 확장 및 BM25/벡터 검색, 후보 변경의 롤백과 검증 기록 저장 가능 여부.
검색 서비스의 순위 품질·권한·동시 확정 로직까지 테스트하는 것은 아니다.
기대 구조 TSV는 2026-09-04 최종 ERD 스냅샷에서 얻은 회귀 테스트 기준이다.

baseline의 확장 설치에는 DB 관리자 권한이 필요하다. 제한된 앱 계정이라면 관리자가 같은 DB의
public 스키마에 `vector`·`pg_search`를 먼저 설치한다. ANN 인덱스의 거리 연산자·튜닝은
100번의 임베딩 모델 확정 후 정하며, 임시 차원은 `vector(1024)`다.

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
| `NPICK_MEDIA_ROOT` | **없음** | 영상 원본 저장 위치. 등록·재생이 같은 값을 읽는다 |
| `CLIP_MEDIA_ROOT` | `NPICK_MEDIA_ROOT` | 재생 전용 오버라이드. 보통 쓰지 않는다 |
| `CLIP_MEDIA_NGINX_ACCEL` | `false` | `true` 면 재생 바이트 전송을 nginx 에 위임한다 |
| `CLIP_MEDIA_INTERNAL_LOCATION` | `/internal-media/` | 위임 대상 location. nginx 설정과 같아야 한다 |

prod 프로파일은 `DB_URL` / `DB_USERNAME` / `DB_PASSWORD` / `CORS_ALLOWED_ORIGINS` 를 쓰며
넷 다 기본값이 없다.

```bash
SERVER_PORT=8081 ./gradlew bootRun
```

## Preview 영상 재생 (S15P21A501-133)

`GET /api/v1/media/{clipId}` 하나다. 로그인한 사용자면 역할과 무관하게 재생할 수 있다(FRD F-07).

- **ID 로만 접근한다.** 경로를 받지 않는다. `clip.storage_key` 를 media root 안에서 해석하며,
  정규화 후 또는 심볼릭 링크를 따라간 뒤 root 를 벗어나면 파일이 있어도 거부한다. 서버 절대 경로는
  응답과 로그에 나가지 않는다 (FR-RES-013, FR-ING-009 연계).
- **Range 를 지원한다.** 만족시킬 수 있는 구간은 `206` + `Content-Range`, 없으면 `200` 전체다.
  이해할 수 없는 range unit 과 여러 구간 요청은 무시하고 전체를 보낸다(RFC 9110 §14.2). 만족시킬 수
  없는 구간과 깨진 문법은 `416 CLIP_416_001` 이다 (FR-RES-014).
- **실패 코드를 구분한다** (FR-RES-015). 영상 없음 `CLIP_404_001`, 원본 파일 누락 `CLIP_404_002`,
  구간 오류 `CLIP_416_001`, 저장 위치 이탈 `CLIP_500_003`, 전송 실패 `CLIP_503_010`,
  설정 누락 `CLIP_503_011`. 성공 응답만 공통 Envelope 를 쓰지 않는다(본문이 영상 바이트다).

바이트 전송 경로는 둘이다. `CLIP_MEDIA_NGINX_ACCEL=true` 면 `X-Accel-Redirect` 로
`/internal-media/` 에 위임하고(`docs/architecture/03-deployment.md` §31), 기본값 `false` 면
애플리케이션이 직접 쓴다. **어느 쪽이든 clip 조회·경로 이탈 차단·Range 검증은 API 가 한다.**
compose 스택은 nginx 가 있어 `true`, 로컬 `bootRun` 은 `false` 다.

첫 프레임 예산의 서버 몫(NFR-PERF-002)은 `com.npick` DEBUG 로그의
`preview first-byte ... elapsedMs=` 로 측정한다. 요청 진입부터 본문 첫 바이트 직전까지, 즉 clip
조회·경로 해석·Range 검증까지이며 전송 시간과 클라이언트 디코딩은 포함하지 않는다.

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

현재 도메인 모듈은 `member` 와 `clip` 이다. 정본 §16에 따라 빈 패키지를 미리 만들지 않고, 실제 기능이
생길 때 추가한다.
