# 로컬·서버 공통 compose 스택 (S15P21A501-130, -131)

PostgreSQL · Spring Boot BE · Next.js FE · Python AI 워커를 한 번에 띄운다.

nginx 는 `proxy` 프로필 뒤에 있어 기본 `up` 에는 뜨지 않는다. 인증서 파일이 없으면
nginx 가 기동을 거부하므로, 인증서를 발급한 환경에서만 띄운다. 로컬은 FE 가 BE 를
직접 호출한다(CORS 구성됨).

## 빠른 시작

저장소 루트에서 실행한다.

네 파일 모두 있어야 한다. 하나라도 없으면 `up` 이 멈춘다.

```bash
cp .env.example .env
cp frontend/.env.example frontend/.env
cp backend/.env.example backend/.env
cp ai/.env.example ai/.env
```

`.env` 의 `POSTGRES_PASSWORD` 와 `MLFLOW_DB_PASSWORD` 를 채운다. 둘 중 하나라도 비어 있으면
compose 가 기동을 거부한다.

```bash
openssl rand -hex 24
```

> `MLFLOW_DB_PASSWORD` 는 접속 URI 에 그대로 들어간다. `base64` 는 `/` `+` `=` 가 섞여
> URI 파싱을 깨뜨리므로 **`-hex` 를 쓴다.**

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
| FE | http://127.0.0.1:3000 |
| BE health | http://127.0.0.1:8080/actuator/health |
| AI 워커 health | http://127.0.0.1:8000/health |
| MLflow UI | http://127.0.0.1:5000/mlflow |
| PostgreSQL | `127.0.0.1:5432` (DB·계정은 `.env`) |

`proxy` 프로필을 켠 환경에서는 nginx 가 앞에 붙어 `/` 와 `/api/` 를 한 오리진으로 묶고
`/healthz` 를 제공한다.

## 배포 환경에서 다른 값

포트는 로컬과 배포가 같다. 다른 값은 아래뿐이다.

| 변수 | 배포 값 | 이유 |
|---|---|---|
| `POSTGRES_PASSWORD` | 팀 비밀 채널의 값 | 볼륨 초기화 시점에 고정되어 나중에 바꿀 수 없다 |
| `MLFLOW_DB_PASSWORD` | 팀 비밀 채널의 값 | 같은 이유로 볼륨 초기화 시점에 role 에 박힌다 |
| `MLFLOW_ALLOWED_HOSTS` | 기본값 + `,<도메인>` | 빠지면 basic auth 통과 후 403. 아래 「MLflow」 참고 |
| `NPICK_DOMAIN` | 실제 도메인 | nginx `server_name` 과 인증서 경로 |
| `BACKEND_PROFILE` | `prod` | `local` 은 SQL echo 와 DEBUG 로깅이 켜져 로그가 과하다 |
| `COMPOSE_PROFILES` | `proxy` | nginx 를 띄운다. 인증서 발급 후에 넣는다 |
| `CORS_ALLOWED_ORIGINS` | `https://<도메인>` | 아래 「CORS」 참고 |

`BACKEND_PORT` 는 **8080** 이다. EC2 의 8080 을 점유하던 Jenkins 를 **18080** 으로 옮겨 충돌을
없앴다(`S15P21A501-151`). 이전 절차는 [infra/jenkins/README.md](../jenkins/README.md) 4장에 있다.
Jenkins 를 옮기지 않은 서버에서는 `docker compose up` 이 `EADDRINUSE` 로 실패한다.

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
| `frontend/.env` | **FE 팀 소유.** `NEXT_PUBLIC_*` |
| `backend/.env` | **BE 팀 소유.** |
| `ai/.env` | **AI 팀 소유.** |
| `infra/compose/postgres-init/` | 최초 기동 1회만 실행. `npick` 스키마, 검색 확장, `mlflow` DB·role |
| `infra/nginx/htpasswd` | MLflow basic auth. **커밋되지 않는다** — 배포 환경에서 직접 만든다 |
| `infra/nginx/templates/` | nginx 설정. HTTP→HTTPS 리다이렉트 + TLS 종단 |
| `infra/nginx/snippets/` | 두 템플릿이 공유하는 upstream·라우팅·프록시 헤더 |
| `infra/nginx/renew-cert.sh` | 인증서 갱신 + nginx 리로드. cron 에서 실행 |
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

### CORS

`CORS_ALLOWED_ORIGINS` 는 Spring 의 완화 바인딩으로 `cors.allowed-origins` 프로퍼티에
매핑되고, 환경변수가 yml 보다 우선한다. 그래서 **프로필과 무관하게 이 값이 이기고**
`application.yml` 의 `LOCAL_CORS_ALLOWED_ORIGINS` 는 도달하지 않는다. 변수를 하나로 둔다.

기본값은 로컬 FE 오리진이다. `proxy` 프로필을 쓰는 환경은 FE 와 API 가 같은 오리진이라
CORS 가 실제로 쓰이지 않지만, `prod` 프로필이 값이 비면 기동하지 않으므로 `.env` 에서
실제 오리진으로 바꾼다.

### TLS

nginx 는 인증서 파일이 없으면 기동을 거부한다. 그래서 `proxy` 프로필 뒤에 두고
인증서를 발급한 환경에서만 띄운다.

**최초 발급은 nginx 없이 한다.** certbot 이 직접 80 번을 점유하는 `--standalone` 을 쓴다.
`--dry-run` 은 staging 서버라 운영 발급 한도를 소모하지 않으니 먼저 돌린다.

```bash
docker compose run --rm -p 80:80 certbot certonly --standalone -d <도메인> --agree-tos --no-eff-email -m <메일> --dry-run
```

성공하면 `--dry-run` 을 빼고 발급한다. 인증서는 `certbot-conf` 볼륨에 남는다.

그다음 `.env` 에 두 줄을 넣고 스택을 다시 올린다.

```
COMPOSE_PROFILES=proxy
CORS_ALLOWED_ORIGINS=https://<도메인>
```

```bash
docker compose up -d
```

`frontend/.env` 의 `NEXT_PUBLIC_API_BASE_URL` 을 `https://<도메인>/api/v1` 로 고치고
FE 를 재빌드한다. 번들에 박히는 값이라 재시작으로는 반영되지 않으며, 고치지 않으면
HTTPS 페이지가 HTTP 로 API 를 호출해 브라우저가 mixed content 로 차단한다.

**갱신은 `--webroot` 를 쓴다.** nginx 가 떠 있는 상태이므로 80 번을 점유할 수 없고,
설정에 ACME 경로가 열려 있다.

리다이렉트에서 두 경로를 제외했다. `/.well-known/acme-challenge/` 는 ACME 가 평문으로
와야 해서, `/healthz` 는 compose healthcheck 가 HTTP 로 확인해서다.

갱신 절차는 `infra/nginx/renew-cert.sh` 에 있다. certbot 은 만료 30 일 전부터만 실제로
갱신하므로 자주 돌려도 안전하다.

**현재 cron 에 등록하지 않았다.** 인증서 만료가 프로젝트 기간 이후라 기간 중에는 갱신이
한 번도 필요하지 않다. 운영을 이어갈 경우 아래를 root cron 에 넣는다.

```
0 3,15 * * * /home/<계정>/S15P21A501/infra/nginx/renew-cert.sh >> /var/log/npick-certbot.log 2>&1
```

cron 은 `PATH` 가 로그인 셸과 달라 `docker` 를 못 찾을 수 있다. 등록한다면 스크립트를
직접 한 번 실행해 확인한다.

### BE 프로필은 BACKEND_PROFILE 로 고른다

`local` 과 `prod` 두 프로필의 변수를 compose 가 모두 넘기므로 `BACKEND_PROFILE` 만 바꾸면 된다.
`prod` 는 DB 접속 정보를 기본값 없이 요구하는데, 그 값을 주지 않으면 기동에 실패한다.

| | `local` | `prod` |
|---|---|---|
| `com.npick` 로그 | `DEBUG` | `INFO` |
| root 로그 | 기본 | `WARN` |
| SQL echo | 켜짐 | 꺼짐 |
| health 상세 | `always` | `never` |

기동 로그가 58줄에서 21줄로 줄어든다. CORS 허용 오리진은 `CORS_ALLOWED_ORIGINS` 로 덮을 수
있고 기본값은 `https://${NPICK_DOMAIN}` 이다. nginx 경유라 같은 오리진이어서 실제로는 쓰이지
않지만, 값이 비면 `prod` 가 기동하지 않는다.

### env 파일은 소유자별로 나뉜다

각 팀이 자기 디렉터리의 `.env` 에 변수를 추가한다. 인프라 파일을 고치지 않아도 된다.

| 파일 | 소유 | compose 가 쓰는 방식 |
|---|---|---|
| `.env` (루트) | 인프라 | 변수 치환(`${...}`) |
| `frontend/.env` | FE | `next build` 가 컨테이너 안에서 직접 읽는다 |
| `backend/.env` | BE | `env_file` 로 런타임 주입 |
| `ai/.env` | AI | `env_file` 로 런타임 주입 |

팀 파일 세 개는 모두 `required: true` 다. 없으면 `up` 이 멈춘다. 내용이 비어 있어도 되지만
파일 자체는 있어야 한다 — 어느 팀이 무엇을 소유하는지가 파일 존재로 드러난다.

이름이 겹치면 compose 의 `environment:` 가 이긴다. 팀 파일이 인프라 값을 덮을 수 없다.

env 배선은 전부 `compose.yaml` 에 있다. Dockerfile 은 env 파일을 모른다.

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

`proxy` 프로필을 켠 환경에서 외부에 열리는 것은 nginx 의 80·443 뿐이다. 프로필을 켜지
않으면 외부에 열리는 포트가 하나도 없다.

프록시를 거치지 않는 health 엔드포인트는 SSH 터널로 확인한다.

```bash
ssh -i <키>.pem -L 8080:localhost:8080 -L 8000:localhost:8000 -L 5000:localhost:5000 <계정>@<도메인>
```

### PostgreSQL 은 ParadeDB 이미지다 — 볼륨 경로가 PG17 과 다르다

`paradedb/paradedb:0.25.6-pg18` 은 `postgres:18-trixie` 위에 `pg_search` 와 `pgvector` 를 얹은
이미지다. BM25 인덱스(`USING bm25`)와 dense 검색이 같은 인스턴스에 있어야 하므로 이 조합이 전제다.

**PG18 부터 데이터 경로가 바뀌었다.** `PGDATA` 가 `/var/lib/postgresql/18/docker` 이고 이미지가
선언하는 볼륨은 `/var/lib/postgresql` 이다. 예전처럼 `/var/lib/postgresql/data` 에 마운트하면
**에러 없이** 볼륨 밖에 데이터가 쓰이고, 컨테이너를 다시 만들 때마다 DB 가 초기화된다.
`compose.yaml` 은 `/var/lib/postgresql` 에 마운트한다. 바꾸지 않는다.

**PG17 볼륨은 PG18 이 읽지 못한다.** 이미 `postgres:17-alpine` 으로 띄운 적이 있으면 볼륨을 비운다.
아래는 `npick-postgres-data` 만 지운다 — `docker compose down -v` 는 media 볼륨까지 날린다.
스키마는 `postgres-init` 이, 테이블은 BE 의 Flyway baseline 이 다시 만든다.

```bash
docker compose down
docker volume rm npick-postgres-data
docker compose up -d
```

확장이 붙었는지 확인한다.

```bash
docker compose exec postgres psql -U npick -d npick -c "\dx"
```

`pg_search` 와 `vector` 가 보이면 정상이다.

### MLflow 트래킹 서버

STT/OCR/LLM 비교 실험 기록을 팀이 공유해 본다. run 메타데이터는 `mlflow` DB 에, artifact 파일은
`npick-mlflow-artifacts` 볼륨에 남는다. 컨테이너를 다시 만들어도 둘 다 보존된다.

```bash
docker compose up -d mlflow      # 기동
docker compose stop mlflow       # 중지
docker compose logs -f mlflow    # 로그
```

내부망 응답 확인 — 완료 조건의 검증 명령이다.

```bash
docker compose exec backend curl -sS -o /dev/null -w '%{http_code}\n' http://mlflow:5000/mlflow/health
```

**DB 격리**: 같은 PostgreSQL 인스턴스 안에 `mlflow` DB 와 `mlflow` role 을 따로 만들고,
`CONNECTION LIMIT 20` 으로 실험 트래픽이 서비스 커넥션을 잠식하지 못하게 막는다. 서비스 DB 로는
접속이 거부된다.

```bash
docker compose exec postgres psql "postgresql://mlflow:<비밀번호>@127.0.0.1:5432/npick" -c "select 1"
# FATAL: permission denied for database "npick" 가 나와야 정상
```

`REVOKE ... FROM mlflow` 만으로는 막히지 않는다. PUBLIC 이 기본 `CONNECT` 를 갖고 있어
`REVOKE CONNECT ... FROM PUBLIC` 이 필요하다. `postgres-init/10-mlflow.sh` 가 그렇게 한다.
이미 초기화된 볼륨에는 스크립트가 돌지 않으므로 그 SQL 을 직접 실행한다.

**UI 는 nginx 의 `/mlflow/` 로 연다.** MLflow 서버가 `--static-prefix /mlflow` 로 뜨고 nginx 가
경로를 그대로 넘긴다. 서브도메인이 아니라 서브패스인 이유는 SSAFY 도메인의 하위 도메인을
팀이 만들 수 없고, 인증서를 다시 발급할 필요도 없기 때문이다. REST 라우트에 prefix 가 붙지
않던 버그는 MLflow 3.12 에서 해결됐다([#22159](https://github.com/mlflow/mlflow/pull/22159)).

**basic auth 파일은 배포 환경에서 직접 만든다.** MLflow 는 기본이 무인증이라 이 파일이 유일한
관문이다. 커밋되지 않는다(`.gitignore`).

**만들지 않으면 조용히 깨진다.** nginx 는 이 파일을 기동 시점에 검사하지 않으므로 정상적으로
뜨고, Docker 가 마운트 대상 자리에 **디렉터리를 만들어** `/mlflow/` 요청만 500 이 된다.
`.gitignore` 에 걸려 `git status` 에도 보이지 않는다. 스택을 올리기 전에 먼저 만든다.

```bash
printf '%s:%s\n' <아이디> "$(openssl passwd -apr1)" > infra/nginx/htpasswd
```

`.env` 의 `MLFLOW_ALLOWED_HOSTS` 에 실제 도메인을 추가한다. 이 값을 주면 MLflow 의 기본 허용
목록이 통째로 대체되고, nginx 는 **포트 없는** `Host` 를 넘긴다. 빠지면 basic auth 를 통과한 뒤
`Invalid Host header - possible DNS rebinding attack detected` 로 403 이 난다.

```
MLFLOW_ALLOWED_HOSTS=mlflow,mlflow:5000,localhost,localhost:5000,127.0.0.1,127.0.0.1:5000,<도메인>
```

**주의 사항**

- `--host 0.0.0.0` 은 보안 설정이 아니라 컨테이너 내부 바인딩이다. 접근 제한은 `--allowed-hosts`,
  loopback 퍼블리시, nginx 의 basic auth 가 담당한다. 5000 을 공인 IP 에 직접 노출하지 않는다.
- 파이썬 클라이언트로 기록할 때는 인증 정보를 환경변수로 준다.
  `MLFLOW_TRACKING_URI=https://<도메인>/mlflow`, `MLFLOW_TRACKING_USERNAME`, `MLFLOW_TRACKING_PASSWORD`.
- `--workers 2` 는 커넥션 상한과 맞춘 값이다. 기본 4 로 두면 워커마다 풀이 잡혀
  `CONNECTION LIMIT 20` 을 넘긴다. MLflow 쪽 풀 크기 설정은
  [#19379](https://github.com/mlflow/mlflow/issues/19379) 로 실효가 없다.
- 서비스 DB 와 인스턴스를 공유하므로 DB 가 죽으면 MLflow 도 멈춘다. 실험 기록 손실은 서비스
  가용성에 영향이 없으므로 감수한다.

### 백업은 DB 별로 뜬다

한 인스턴스에 DB 가 둘이라 `pg_dump` 도 둘이다.

```bash
docker compose exec postgres pg_dump -U npick -d npick  -Fc -f /tmp/npick.dump
docker compose exec postgres pg_dump -U npick -d mlflow -Fc -f /tmp/mlflow.dump
docker compose cp postgres:/tmp/npick.dump  ./npick.dump
docker compose cp postgres:/tmp/mlflow.dump ./mlflow.dump
```

artifact 파일은 DB 에 없다. 볼륨을 따로 받는다.

```bash
docker run --rm -v npick-mlflow-artifacts:/src -v "$PWD":/out alpine \
  tar czf /out/mlflow-artifacts.tgz -C /src .
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

`proxy` 프로필을 켠 환경에서 nginx 까지 내리려면 프로필을 지정한다.

```bash
docker compose --profile proxy down
```

볼륨까지 지우려면 아래를 쓴다. **DB 데이터와 media 가 모두 사라진다.**

```bash
docker compose down -v
```

## 트러블슈팅

**`POSTGRES_PASSWORD 를 .env 에 설정해야 한다`**
`.env` 가 없거나 비밀번호가 비어 있다. `cp .env.example .env` 후 값을 채운다.

**`env file ... not found` 로 멈춘다**
팀 `.env` 가 없다. 「빠른 시작」의 `cp` 네 줄을 모두 실행했는지 확인한다.
`frontend/.env` 였다면 `NEXT_PUBLIC_API_BASE_URL` 을 `BACKEND_PORT` 에 맞춘 뒤 다시 빌드한다.

**브라우저 콘솔에 CORS 오류가 난다**
BE 로그에 `Invalid CORS request` 가 있으면 `CORS_ALLOWED_ORIGINS` 가 FE 오리진과 다르다.
프로필과 무관하게 이 환경변수가 이기므로 `.env` 에서 맞춘다.

```bash
docker compose exec backend printenv CORS_ALLOWED_ORIGINS
```

**nginx 가 뜨지 않는다**
`proxy` 프로필이 꺼져 있으면 정상이다. 켜려면 `.env` 에 `COMPOSE_PROFILES=proxy` 를 넣는다.

켰는데 `nginx: [emerg] cannot load certificate` 로 죽으면 인증서가 없다. 「TLS」 를 참고해
먼저 발급한다.

```bash
docker compose run --rm --entrypoint sh certbot -c 'ls /etc/letsencrypt/live/'
```

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
