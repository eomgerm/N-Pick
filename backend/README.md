# N-Pick Backend

## 내부 워커 잡 API

`NPICK_WORKER_JOBS_ENABLED`는 기본 `false`다. 활성화하면 기존 실행기의 배정·heartbeat·완료 UseCase에
HTTP 전송을 연결한다. 단계 정본 저장 어댑터는 실행기의 fencing·멱등성 검사가 끝난 완료 트랜잭션
안에서 호출된다. 저장 어댑터는 HTTP 활성화와 독립적으로 제공된다. 저장 미지원 단계는 HTTP 배정에서
제외하며, 내부 호출로 들어온 미지원 출력도 성공으로 저장하지 않는다.

`NPICK_WORKER_JOBS_TOKENS`는 쉼표로 구분한 32바이트 이상 Bearer 토큰 목록이며 회전 중 두 토큰을
함께 둘 수 있다. `NPICK_WORKER_JOBS_FLEET`는 기본 `local`이다. 운영에는 운영 토큰만 설정한다.
HTTP 전송이 기본이며 `NPICK_WORKER_JOBS_SHARED_MEDIA_VOLUME=true`와 워커의 공유 볼륨 선언이
모두 참일 때만 공유 경로를 배정한다. artifact GET·PUT은 `X-Job-Lease-Id`를 함께 보낸다.
상세 입출력은 [잡 계약](../docs/contracts/job-api.md)을 따른다.

HTTP 왕복 테스트는 `ffmpeg`·`ffprobe`가 PATH에 있고 AI 테스트 의존성이 설치되어 있어야 한다.
`NPICK_TEST_PYTHON`으로 해당 Python 실행 파일을 지정한다(기본: Windows `../ai/.venv/Scripts/python.exe`,
Linux `../ai/.venv/bin/python`).
이 테스트는 실제 실행기·DB와 Python 워커를 연결하고 AI 단계 함수만 테스트 대역으로 실행한다.

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

로그인은 Spring Session JDBC로 기존 PostgreSQL의 `npick.spring_session`과
`npick.spring_session_attributes`에 보존한다. `JSESSIONID`는 기본 30분의 짧은 access 인증이며
일반 요청으로 절대 만료 시각이 늘어나지 않는다. `NPICK_REFRESH`는 HttpOnly·Secure·SameSite=Strict
쿠키로만 발급하는 256비트 불투명 토큰이다. `npick.login_refresh`에는 SHA-256 해시만 저장한다.
`POST /api/v1/auth/refresh`는 CSRF 검증 후 access를 재발급하며, 최초 로그인부터 최대 8시간인
refresh 기한은 연장하지 않는다. 새 access도 이 기한을 넘지 못한다. 동시 갱신은 DB 행 잠금으로
직렬화하여 같은 유효 access를 반환한다. refresh 토큰 자체는 고정 기한 동안 유지한다.
로그아웃은 access와 refresh를 모두 폐기하며 access가 이미 만료돼도 가능하다.
만료 access는 Spring Session이 정리하고, 만료 refresh 행은 다음 로그인에서 정리한다.
테이블은 Flyway가 생성하며 별도 DB 초기화는 하지 않는다.

최초 전환 배포에서는 이전 프로세스의 메모리 세션을 옮길 수 없어 한 번 재로그인해야 한다.
이후에는 같은 DB를 사용하는 백엔드 재시작·컨테이너 교체 후에도 만료 전 로그인이 유지된다.
8시간 access만 사용하던 버전에서 전환할 때도 한 번 재로그인해야 한다.
이전 버전으로 롤백하면 다시 로그인해야 하며, 추가된 테이블과 Flyway 이력은 삭제하지 않는다.
인증 principal의 직렬화 형태를 변경할 때는 저장된 세션과의 호환성을 확인한다.

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

`V20260910140000__tag_match_value_invisible_chars.sql` 을 적용한 로컬 DB에서 변경된 버전으로
전환할 때는, 먼저 해당 migration 이 만든 제약과 적용 이력을 순서대로 제거한 뒤 재기동한다.
운영 DB에는 이 버전이 적용된 적이 없으며, 아래 절차는 영향받은 로컬 DB에만 사용한다.

```sql
ALTER TABLE npick.tag DROP CONSTRAINT IF EXISTS ck_tag_match_value_invisible_chars;
DELETE FROM npick.flyway_schema_history WHERE version = '20260910140000';
```

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
| `ACCESS_TOKEN_TTL` | `30m` | 짧은 인증의 절대 수명과 세션 idle 상한 |
| `REFRESH_TOKEN_TTL` | `8h` | 최초 로그인부터 refresh를 사용할 수 있는 최대 기간 |
| `LOCAL_DB_URL` | `jdbc:postgresql://localhost:5432/npick` | local 프로파일 접속 주소 |
| `LOCAL_DB_USERNAME` | `npick` | `.env` 의 `POSTGRES_USER` 와 같아야 한다 |
| `LOCAL_DB_PASSWORD` | **없음** | `.env` 의 `POSTGRES_PASSWORD` 를 넘긴다 |
| `LOCAL_CORS_ALLOWED_ORIGINS` | `http://localhost:3000` | 허용 origin |
| `NPICK_MEDIA_ROOT` | **없음** | 영상 원본과 keyframe 저장 위치. 등록·재생·썸네일이 같은 값을 읽는다 |
| `CLIP_MEDIA_ROOT` | `NPICK_MEDIA_ROOT` | 재생·썸네일 오버라이드. 보통 쓰지 않는다 |
| `CLIP_MEDIA_NGINX_ACCEL` | `false` | `true` 면 재생 바이트 전송을 nginx 에 위임한다 |
| `CLIP_MEDIA_INTERNAL_LOCATION` | `/internal-media/` | 위임 대상 location. nginx 설정과 같아야 한다 |
| `CLIP_MEDIA_EXTRACTION_TIMEOUT` | `2m` | 장면 다운로드 ffmpeg 추출 최대 시간 |

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
nginx 는 compose 의 `proxy` 프로필 뒤에 있으므로 compose 도 기본값은 `false` 다.
`COMPOSE_PROFILES=proxy` 를 켜는 배포 환경에서만 `.env` 에 `true` 를 넣는다.

첫 프레임 예산의 서버 몫(NFR-PERF-002)은 `com.npick` DEBUG 로그의
`preview first-byte ... elapsedMs=` 로 측정한다. 요청 진입부터 본문 첫 바이트 직전까지, 즉 clip
조회·경로 해석·Range 검증까지이며 전송 시간과 클라이언트 디코딩은 포함하지 않는다.

## 영상 다운로드 (S15P21A501-269)

`GET /api/v1/media/{clipId}/download`는 원본 전체를, `GET /api/v1/media/scenes/{sceneId}/download`는
DB에 저장된 장면 경계를 MP4로 추출해 내려준다. 둘 다 로그인한 `EDITOR`·`REVIEWER`가 사용할 수 있다.
장면 추출은 서버의 `ffmpeg` 실행 파일을 사용하며 `CLIP_MEDIA_EXTRACTION_TIMEOUT` 안에 끝나지 않으면
`CLIP_503_012`로 실패한다. `CLIP_MEDIA_MAX_CONCURRENT_EXTRACTIONS`(기본 1)가 동시 재인코딩 상한이며,
자리가 없으면 같은 코드로 즉시 거절한다. 추출된 임시 파일은 응답 준비·전송의 모든 성공·실패 경로에서 삭제한다.
다운로드 요청은 member ID와 clip 또는 scene ID만 INFO 로그로 남기고 파일 경로는 기록하지 않는다.

## 장면 대표 이미지

`GET /api/v1/scenes/{sceneId}/thumbnail` — 로그인한 `EDITOR`·`REVIEWER` 가 장면의 대표 keyframe 이미지를 받는다.
요청·응답 계약은 [웹 API 계약](../docs/contracts/web-api.md) §6.7 이 정본이다.

keyframe 파일은 워커가 영상 원본과 같은 media root 아래에 남기므로 `CLIP_MEDIA_ROOT` 를 그대로 읽는다.
**썸네일 전용 환경 변수는 없다.** 경로 이탈 차단은 재생과 한 벌(`MediaRootResolver`)을 공유하고 실패 어휘만 다르다.

응답은 `Cache-Control: private, no-cache` 와 `ETag` 를 함께 보낸다. 브라우저는 바이트를 보관하되 쓰기 전에 매번
재검증하므로, 권한과 클립 논리 삭제 판정이 조회마다 다시 내려진다.

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

- 실행 버전은 빌드에 포함한 `infra/compose/profiles/pipeline.yml`의 `stage_versions`로 계산한다.
  외부 파일은 `NPICK_PIPELINE_PROFILE=file:/absolute/path/pipeline.yml`로 지정한다.
  기대 버전이 없는 단계는 `unknown`으로 남고 배정되지 않는다. 기존 `CLIP_PIPELINE_VERSION`·
  `CLIP_STAGE_NAMES` 설정 대신 이 프로파일을 사용한다.
- 실행기는 내부 claim/heartbeat/complete 유스케이스를 제공한다. 성공 결과 수락에는
  `StageOutputPort`의 단계별 형식 검사·정본 저장 어댑터가 필요하며, 없으면 성공을 기록하지 않는다.
  HTTP·artifact 전송은 위 설정으로 활성화한다. 실제 AI 단계와 해당 출력 저장 지원은 함께 연결한다.
- `transcript_selection` 배정은 보관 영상의 ffprobe 길이와 DB 자막 키로 입력을 준비한 뒤
  `inputs.upstream.transcript`를 전달한다. 준비 중에는 DB 트랜잭션을 열지 않고 lease를 갱신한다.
  준비 산출물은 배정 기록 성공 또는 커밋 결과 불명확 시 보존하며, 회수된 lease의 입력은 반영하지 않는다.
- 기존 10개 단계의 평면 JSON은 실행 시 버전 봉투로 읽고 저장한다. 단계나 기대 버전을 확인할 수 없는
  과거 run은 임의로 현재 버전으로 바꾸지 않는다. 현재 프로파일과 일치하는 기존 run만 버전을 보완한다.
- mock 전체 흐름은 `./gradlew test --tests 'com.npick.pipeline.*'`로 검증한다.
  PostgreSQL은 Testcontainers를 사용하고 mock 단계는 실제 AI를 호출하지 않는다.

- 영상 검사에 `ffmpeg`·`ffprobe`가 필요하다. 등록용 저장 경로·입력 제한·파이프라인 설정은
  [환경 변수 예시](.env.example)와 [애플리케이션 설정](src/main/resources/application.yml)을 참고한다.
- 중복 처리에는 PostgreSQL 세션 advisory lock을 사용하므로 DB 직결 또는 세션 유지형 풀이 필요하다.
  PgBouncer transaction pooling은 지원하지 않는다. 등록당 연결 2개와 다른 요청의 여유를 고려해 풀을 설정한다.
- 여러 인스턴스는 등록된 영상에 동일하게 접근할 수 있어야 한다. 저장소 디렉터리는 운영 계정만 변경할 수 있게 한다.
- 요청 키 기록은 자동 만료되지 않는다. 보관 정책을 변경할 때는 클라이언트의 재전송 기간과 함께 검토한다.
- 결과가 불명확한 요청은 시간이 지났다는 이유로 재실행하거나 파일을 삭제하지 않는다.
  재요청으로 결과를 복원할 수 없다면 clip·최초 run·진행 중 DB 트랜잭션·소유 파일을 대조한다.
  커밋 부재와 파일 소유권을 확인한 경우에만 고아 파일 정리 및 요청 상태의 `failed` 전환으로 재시도를 허용한다.
