# 로컬·서버 공통 compose 스택 (S15P21A501-130)

PostgreSQL · Spring Boot BE · Next.js FE · Python AI 워커를 한 번에 띄운다.
로컬 개발과 EC2 서버가 같은 구성을 쓴다.

## 빠른 시작

저장소 루트에서 실행한다.

```bash
cp .env.example .env
```

`.env` 의 `POSTGRES_PASSWORD` 를 채운다. 비어 있으면 compose 가 기동을 거부한다.

```bash
openssl rand -base64 24
```

```bash
docker compose up -d --build
```

첫 실행은 Gradle·npm 빌드 때문에 5~10분 걸린다. 이후에는 캐시가 살아 훨씬 빠르다.

```bash
docker compose ps
```

네 서비스가 모두 `healthy` 면 정상이다.

| 서비스 | 접속 |
|---|---|
| FE | http://127.0.0.1:3000 |
| BE health | http://127.0.0.1:8080/actuator/health |
| AI 워커 health | http://127.0.0.1:8000/health |
| PostgreSQL | `127.0.0.1:5432` (DB·계정은 `.env`) |

## 파일 구성

| 파일 | 역할 |
|---|---|
| `compose.yaml` (루트) | 서비스·네트워크·볼륨 정의 |
| `.env.example` (루트) | 포트·계정·이미지 태그. 실제 `.env` 는 커밋되지 않는다 |
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

### BE 기동 시 Flyway 가 스키마를 만든다

BE 는 `backend/src/main/resources/application.yml` 한 파일에서 프로필을 나눠 관리하고,
기동할 때 `db/migration` 의 baseline 을 적용한다. postgres 컨테이너가 healthy 가 된 뒤
backend 가 뜨도록 `depends_on` 이 잡혀 있으므로 별도 순서 조정은 필요 없다.

과거 이 자리에는 BE 의 DataSource 자동설정 exclude 를 비우는 `SPRING_AUTOCONFIGURE_EXCLUDE: ""`
가 있었다. BE 에서 exclude 블록을 정식으로 제거해 더는 필요 없어 지웠다.

### NEXT_PUBLIC_* 는 빌드 시점 값이다

`NEXT_PUBLIC_API_BASE_URL` 을 바꾸면 컨테이너 재시작으로는 반영되지 않는다. 번들에 박히기 때문에
다시 빌드해야 한다.

```bash
docker compose up -d --build frontend
```

### 포트는 전부 loopback 이다

FRD §15.6 Gate D 의 `loopback bind` 에 따라 세 서비스 모두 `127.0.0.1` 에만 바인딩된다.
컨테이너 외부에서 접근하려면 프록시를 앞에 두어야 하며, `0.0.0.0` 으로 바꾸지 않는다.

EC2 에서 브라우저로 볼 때는 SSH 터널을 쓴다.

```bash
ssh -i <키>.pem -L 3000:localhost:3000 -L 8080:localhost:8080 -L 8000:localhost:8000 ubuntu@<EC2-도메인>
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
EC2 에서는 8080 을 Jenkins 가 쓰고 있어 `BACKEND_PORT` 조정이 필요하다.
