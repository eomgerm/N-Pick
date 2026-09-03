# 로컬·서버 공통 compose 스택 (S15P21A501-130, -131)

PostgreSQL · Spring Boot BE · Next.js FE · Python AI 워커를 한 번에 띄운다.
로컬 개발과 EC2 서버가 같은 구성을 쓴다.

## 빠른 시작

저장소 루트에서 실행한다.

```bash
cp .env.example .env
cp frontend/.env.example frontend/.env
```

`.env` 의 `POSTGRES_PASSWORD` 를 채운다. 비어 있으면 compose 가 기동을 거부한다.

```bash
openssl rand -base64 24
```

`frontend/.env` 의 `NEXT_PUBLIC_API_BASE_URL` 은 **nginx 를 거치는 주소**로 바꾼다.
`.env.example` 의 기본값은 BE 직접 주소라 프록시 구성과 맞지 않는다.

```
NEXT_PUBLIC_API_BASE_URL=http://127.0.0.1/api/v1
```

```bash
docker compose up -d --build
```

첫 실행은 Gradle·npm 빌드 때문에 5~10분 걸린다. 이후에는 캐시가 살아 훨씬 빠르다.

```bash
docker compose ps
```

다섯 서비스가 모두 `healthy` 면 정상이다.

| 대상 | 접속 |
|---|---|
| 서비스 | http://127.0.0.1/ (nginx → FE) |
| API | http://127.0.0.1/api/ (nginx → BE) |
| nginx 자체 | http://127.0.0.1/healthz |
| BE health | http://127.0.0.1:8081/actuator/health (프록시 미경유) |
| AI 워커 health | http://127.0.0.1:8000/health (프록시 미경유) |
| PostgreSQL | `127.0.0.1:5432` (DB·계정은 `.env`) |

`NGINX_HTTP_PORT` 를 바꿨으면 위 주소에 그 포트를 붙인다.

## 배포 환경에서 다른 값

포트는 로컬과 배포가 같다. 환경마다 다른 값은 아래 둘뿐이다.

| 변수 | 배포 값 | 이유 |
|---|---|---|
| `POSTGRES_PASSWORD` | 팀 비밀 채널의 값 | 볼륨 초기화 시점에 고정되어 나중에 바꿀 수 없다 |
| `NPICK_DOMAIN` | 실제 도메인 | nginx `server_name` |

`BACKEND_PORT` 가 8080 이 아니라 **8081** 인 것은 의도된 값이다. EC2 의 8080 은 Jenkins 가
`0.0.0.0` 으로 점유하고 있어 `127.0.0.1:8080` 바인딩도 실패한다. [배포 다이어그램](../../docs/architecture/03-deployment.md)
의 확정 값이므로 8080 으로 되돌리지 않는다.

`frontend/.env` 의 `NEXT_PUBLIC_API_BASE_URL` 도 배포 도메인으로 바꾼다. 번들에 박히는 값이라
바꾼 뒤 재빌드가 필요하다.

```
NEXT_PUBLIC_API_BASE_URL=https://<도메인>/api/v1
```

## 파일 구성

| 파일 | 역할 |
|---|---|
| `compose.yaml` (루트) | 서비스·네트워크·볼륨 정의 |
| `.env.example` (루트) | **인프라 값 전용.** 포트·계정·도메인·이미지 태그 |
| `frontend/.env` | **FE 팀 소유.** `NEXT_PUBLIC_*`. 빌드 필수 |
| `backend/.env` | **BE 팀 소유.** 없어도 기동한다 |
| `ai/.env` | **AI 팀 소유.** 없어도 기동한다 |
| `infra/compose/postgres-init/` | 최초 기동 1회만 실행되는 SQL. `npick` 스키마를 만든다 |
| `infra/nginx/` | 리버스 프록시 템플릿·스니펫 |
| `infra/compose/profiles/` | **Gate D 값이 들어갈 자리.** 아래 참고 |
| `backend/Dockerfile` | JDK 21 멀티스테이지 → JRE 런타임 |
| `frontend/Dockerfile` | Node 24 멀티스테이지 |
| `ai/Dockerfile` | uv + Python 3.12 멀티스테이지 |

## 설정 프로필 — Gate D 자리

FRD §15.6 은 *"정확 file limit, retry count, page layout, endpoint 세부 이름은 versioned config 로
관리"* 하라고 한다. §4.2 도 media 수치를 *"미확정 숫자를 코드에 산재시키지 않고 versioned media
profile 하나에서 관리"* 하라고 한다. 그 자리를 미리 만들어 둔 것이다.

| 파일 | 담는 것 |
|---|---|
| `profiles/media.yml` | 파일 크기·길이·허용 codec |
| `profiles/pipeline.yml` | 단계별 retry·timeout·동시성, CPU/GPU 배치<br>단계 목록 자체는 `ai/src/npick_worker/stages.py` 가 정본 |
| `profiles/runtime.yml` | 타임존·로그·telemetry·bind·API 규칙 |

**세 파일 모두 `version: 0`, `frozen: false` 이고 수치는 `null` 이다.** 아직 동결되지 않았기 때문이며,
임의 숫자를 채워 넣지 않았다. 실측 근거가 생기면 값을 넣고 `version` 을 올린다.

BE 컨테이너에는 `/app/config/profiles` 로 read-only 마운트되고 `NPICK_PROFILES_DIR` 로 경로가
전달된다. **아직 이 파일을 읽는 코드는 없다.** 자리와 경로만 잡아둔 상태다.

## 알아둘 것

### 스키마는 postgres-init 이 만들고 Flyway 는 baseline 만 적용한다

BE 의 Flyway 는 `create-schemas: false` 라 스키마를 만들지 않는다. `npick` 스키마는
`infra/compose/postgres-init/01-create-schema.sql` 이 만든다. 이 스크립트는 데이터 볼륨이
비어 있을 때 1회만 실행되므로, 이미 쓰던 볼륨에는 적용되지 않는다.

```bash
docker compose exec postgres psql -U npick -d npick -c 'CREATE SCHEMA IF NOT EXISTS npick'
```

BE 는 `backend/src/main/resources/application.yml` 한 파일에서 프로필을 나눠 관리하고,
기동할 때 `db/migration` 의 baseline 을 적용한다. postgres 컨테이너가 healthy 가 된 뒤
backend 가 뜨도록 `depends_on` 이 잡혀 있으므로 별도 순서 조정은 필요 없다.

과거 이 자리에는 BE 의 DataSource 자동설정 exclude 를 비우는 `SPRING_AUTOCONFIGURE_EXCLUDE: ""`
가 있었다. BE 에서 exclude 블록을 정식으로 제거해 더는 필요 없어 지웠다.

### env 파일은 소유자별로 나뉜다

각 팀이 자기 디렉터리의 `.env` 에 변수를 추가한다. 인프라 파일을 고치지 않아도 된다.

| 파일 | 소유 | compose 가 쓰는 방식 | 없으면 |
|---|---|---|---|
| `.env` (루트) | 인프라 | 변수 치환(`${...}`) | `POSTGRES_PASSWORD` 만 필수 |
| `frontend/.env` | FE | `next build` 가 컨테이너 안에서 직접 읽는다 | **`up` 이 빌드 전에 멈춘다** |
| `backend/.env` | BE | `env_file` 로 런타임 주입 | 기동한다 |
| `ai/.env` | AI | `env_file` 로 런타임 주입 | 기동한다 |

env 배선은 전부 `compose.yaml` 에 있다. Dockerfile 은 env 파일을 모른다.

이름이 겹치면 compose 의 `environment:` 가 이긴다. 팀 파일이 인프라 값을 덮을 수 없다.

### NEXT_PUBLIC_* 는 빌드 시점 값이다

`next build` 가 변수 참조를 문자열 리터럴로 치환하므로 **런타임 환경변수는 효과가 없다.**
컨테이너 재시작이 아니라 재빌드가 필요하다.

```bash
docker compose up -d --build frontend
```

`frontend/.env` 가 없으면 코드 기본값이 박힌 이미지가 조용히 만들어진다. 그래서 compose 의
`env_file` 에 `required: true` 를 걸어 `up` 이 빌드에 들어가기 전에 멈추게 했다. 여기 선언은
존재 강제가 목적이고, 런타임 주입은 이미 번들에 박힌 값을 바꾸지 못한다.

`docker compose build frontend` 단독 실행은 이 검사를 거치지 않는다. 빌드는 항상
`docker compose up -d --build` 로 한다.

`backend/.env.example` 은 `backend/.gitignore` 가 `.env.*` 를 예외 없이 무시해 커밋되지 않는다.
BE 변수가 생기면 그 규칙에 `!.env.example` 을 추가해야 한다.

### 외부에 열리는 것은 nginx 뿐이다

FRD §15.6 Gate D 의 `loopback bind` 에 따라 애플리케이션 서비스 네 개는 모두 `127.0.0.1` 에만
바인딩된다. `0.0.0.0` 으로 바꾸지 않는다.

외부에 열리는 것은 nginx 의 80·443 뿐이고, 나머지는 컨테이너 네트워크 안에서만 닿는다.
그래서 브라우저로 볼 때 SSH 터널이 필요하지 않다. 프록시를 거치지 않는 health 엔드포인트만
터널이 필요하다.

```bash
ssh -i <키>.pem -L 8081:localhost:8081 -L 8000:localhost:8000 <계정>@<도메인>
```

### AI 워커는 CPU 전용으로 뜬다

`NPICK_AI_DEVICE=cpu` 로 고정했다. GPU 단계는 이 스택에 포함하지 않는다(SSAFY GPU 서버 배치).
`ai/pyproject.toml` 의 `gpu` 그룹(torch, faster-whisper)은 opt-in 이라 이미지 빌드 시 받지 않는다.

모델 캐시는 `model-cache` 볼륨에 남는다(`HF_HOME`, `TORCH_HOME`). 컨테이너를 다시 만들어도
재다운로드하지 않는다.

현재 워커는 `/health` 만 제공한다. 파이프라인 단계 구현과 작업 수신 방식은 S15P21A501-70 에서
정한다. 어느 단계를 CPU 워커가 맡을지는 `profiles/pipeline.yml` 의 `placement` 에 기록한다.

## 자주 쓰는 명령

```bash
docker compose logs -f backend
```

```bash
docker compose restart backend
```

```bash
docker compose down
```

볼륨까지 지우려면 아래를 쓴다. **DB 데이터와 media 가 모두 사라진다.**

```bash
docker compose down -v
```

## 트러블슈팅

**`POSTGRES_PASSWORD 를 .env 에 설정해야 한다`**
`.env` 가 없거나 비밀번호가 비어 있다. `cp .env.example .env` 후 값을 채운다.

**`env file ... frontend/.env not found` 로 멈춘다**
```bash
cp frontend/.env.example frontend/.env
```
`NEXT_PUBLIC_API_BASE_URL` 을 nginx 주소로 바꾼 뒤 다시 빌드한다.

**backend 가 `unhealthy` 로 남는다**
```bash
docker compose logs backend | tail -50
```
DB 연결 실패면 `postgres` 가 healthy 인지, `.env` 의 계정이 양쪽에서 같은지 본다.

**frontend 빌드가 메모리 부족으로 죽는다**
EC2 사양이 낮으면 Next.js 빌드가 OOM 될 수 있다. 로컬에서 이미지를 빌드해 registry 로 옮기거나
swap 을 늘린다.

**포트가 이미 사용 중이다**
`.env` 에서 `BACKEND_PORT`·`FRONTEND_PORT`·`POSTGRES_PORT`·`AI_WORKER_PORT` 를 바꾼다.
EC2 는 아래 「배포 환경에서 다른 값」을 참고한다.
