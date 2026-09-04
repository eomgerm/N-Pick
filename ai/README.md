# N-Pick AI Worker

FRD §2.1 `[Pipeline Worker]`. 헬스체크, 1단계 `scene_detection`(FRD §5.1), Query Resolver
프롬프트·출력 schema(FRD §6) 가 구현되어 있다.

Query Resolver 는 파이프라인 단계가 아니다 — 검색 시점에 쓰이고 FRD §2.1 에서는
`[Search Service]` 아래 adapter 로 붙는다. 여기에는 프롬프트·schema·검증만 있다.

## 요구 사항

- **uv 0.12 이상** (필수). `uv --version` 으로 확인. 설치: https://docs.astral.sh/uv/
- Python 은 별도 설치가 필요 없다. uv 가 `.python-version`(3.12)을 보고 자동으로 받는다.
- NVIDIA GPU 는 **선택**. 없어도 워커는 기동하고 `/health` 는 정상 응답한다.

## 실행

```bash
cd ai
uv sync                 # dev 그룹까지. torch 는 받지 않는다 (수십 초)
uv run npick-worker     # http://127.0.0.1:8000
```

Windows PowerShell 에서는 저장소 루트에서 `uv run --directory ai npick-worker`.

기동 확인:

```bash
curl http://127.0.0.1:8000/health
```

PowerShell 의 `curl` 은 `Invoke-WebRequest` 별칭이므로 `curl.exe` 를 쓰거나

```powershell
Invoke-RestMethod http://127.0.0.1:8000/health | ConvertTo-Json -Depth 5
```

응답 예시 (GPU 있는 환경):

```json
{
  "status": "ok",
  "service": "npick-ai-worker",
  "version": "0.1.0",
  "device": {
    "requested": "auto",
    "resolved": "cuda",
    "torch_available": true,
    "torch_version": "2.13.0+cu130",
    "cuda_available": true,
    "cuda_version": "13.0",
    "device_name": "NVIDIA GeForce RTX 4070 Laptop GPU",
    "total_memory_mb": 8187
  },
  "pipeline": {
    "stage_count": 10,
    "stages": [{ "order": 1, "name": "scene_detection", "fatal": true }]
  }
}
```

`gpu` 그룹을 설치하지 않은 환경에서는 `torch_available: false`, `resolved: "cpu"` 로 응답한다. GPU 부재는 오류가 아니다.

## scene 분할 (FRD §5.1 1단계)

```python
from pathlib import Path
from npick_worker.scene_detection import detect_scenes

result = detect_scenes(Path("clip.mp4"))
result.scenes  # (Scene(scene_index=0, start_time_ms=0, end_time_ms=2000), ...)
result.config_version  # 'scene-detect/v1:20dfc0a6'  ← 설정 해시
result.engine  # 'pyscenedetect'                     ← 구현 이름
result.engine_version  # '0.7.1'                     ← 구현 버전
```

구간은 `[start_time_ms, end_time_ms)` 반열린이고 서로 붙어 있다. **같은 파일 + 같은
`(config_version, engine, engine_version)` 재현성 식별자가 같으면 항상 같은 결과가
나온다**(FR-PRC-006).
`config_version` 은 설정 파일만 해시하므로 라이브러리를 올리면 값이 그대로인데 경계는 달라질
수 있다 — 그래서 세 필드를 다 싣는다. 임계값은 `config/scene_detection.v1.toml` 에 있다.

샘플 클립 육안 확인:

```bash
uv run --directory ai python -m npick_worker.scene_detection.report     samples/01-standard-report.mp4 --out samples/out/01
```

scene 표를 출력하고 `--out` 에 `scenes.json` 과 경계 프레임 PNG 를 남긴다.
필요한 샘플 클립 종류는 [samples/README.md](samples/README.md), 선정 근거와 설정 키는
[docs/scene-detection.md](docs/scene-detection.md).

## Query Resolver (FRD §6)

한국어 질의를 구조화 조건으로 바꾼다. **검색 시점**에 쓰이며 파이프라인 단계가 아니다.

```python
from npick_worker.query_resolver import resolve_query
from npick_worker.query_resolver.ollama_backend import OllamaResolver

result = resolve_query("2022년 촬영한 서울역", resolver)
result.resolution.locations  # (Location(type='facility', value='서울역', origin='explicit_query', ...),)
result.resolution_schema_version  # 'query-resolver/v1'
result.prompt_version  # 'query-resolver-prompt/v1:4d0caca2'
result.model_version  # 'gpt-5.4-mini-2026-03-17' (게이트웨이가 응답에 실어 준 이름)
result.findings  # 검증이 무엇을 바꿨는지 (explicit_anchor_validation_json)
```

`origin` 이 `explicit_query` 면 **원문에 그 문자열이 실제로 있다**는 뜻이다. 원문에 없는
값을 그렇게 주장하면 `inferred` 로 강등된다(`FR-QRY-011`). `inferred` 는 hard filter 가
되지 않는다(`AC-SRH-005`).

### span 은 모델이 준 숫자를 쓰지 않는다

`query_span` 은 validator 가 원문에서 직접 찾는다. 대표 질의 20개 실측에서 **11개
질의**의 인덱스가 어긋났고 **9건 전부 값은 원문에 실제로 있었다**(오차 `+1` 7건,
`+2` 2건). LLM 은 문자 오프셋 계산에 약하다.

그 숫자를 믿으면 사용자가 직접 입력한 `서울역` 이 `inferred` 로 떨어져 hard filter 에서
빠진다. 검증해야 하는 명제는 "이 값이 원문에 있는가" 이고 그건 substring 검색이 더
정확하게 답한다. 없으면 그대로 강등하므로 **창작 방어는 그대로다.**

`findings` 의 `action` 세 가지:

| `action` | 뜻 |
| --- | --- |
| `span_corrected` | 값은 원문에 있고 인덱스만 고쳤다. `explicit_query` 유지 |
| `demoted_to_inferred` | 원문에 없는 값이거나 resolver 가 `explicit_filter` 를 주장했다 |
| `dropped` | 뒤집힌 날짜 구간, 또는 `locations` 와 겹친 `entities` (`AC-QRY-005`) |

### backend 2개

`QueryResolver` Protocol 구현이 둘이다. `NPICK_AI_RESOLVER_BACKEND` 가 고른다.

| 값 | 구현 | 어디로 나가나 |
| --- | --- | --- |
| `ollama` (기본) | `ollama_backend.py` | 로컬 [Ollama](https://ollama.com) `/api/chat` |
| `gms` | `gms_backend.py` | 승인된 GMS `/v1/chat/completions` (OpenAI 호환) |

`NPICK_AI_GMS_BASE_URL` 은 base 만 줘도 되고 전체 엔드포인트를 줘도 된다 — SSAFY GMS 처럼
`.../v1/chat/completions` 까지 안내하는 게이트웨이가 있어서 양쪽을 받는다.
토큰 상한은 `max_completion_tokens` 로 보낸다. 옛 이름 `max_tokens` 는 최신 모델이
400 으로 거부한다.

`ollama` 를 기본으로 두는 이유는 FRD §13.4 가 query 외부 전송을 **별도 승인 대상**으로
두기 때문이다. 어느 쪽이든 **없어도 워커는 기동한다** — 실패는 실제로 호출할 때만 난다.

> ⚠️ **외부 전송 승인 검사가 아직 코드에 없다.** `NFR-GMS-002` 는 active deployment
> policy·provider profile 이 없으면 외부 호출 **전에** fail-closed 하라고 요구하고,
> §13.4 는 deployment-level `query_external_processing_allowed=yes` 와 provider
> allowlist 를 요구한다. `deployment_external_policy`·`external_provider_profile`
> 테이블은 비어 있고 BE 도메인 코드도 없다. `gms` 를 쓰는 것은 **승인 절차를 우회하는
> 것**이며, 검사가 생기면 `gms_backend.complete()` 진입부가 그 자리다.

```powershell
$env:NPICK_AI_RESOLVER_BACKEND = "gms"
$env:NPICK_AI_GMS_BASE_URL = "<게이트웨이 주소>"
$env:NPICK_AI_GMS_API_KEY = "<키>"
$env:NPICK_AI_GMS_MODEL = "<모델명>"
uv run --directory ai python -m npick_worker.query_resolver.report
```

대표 질의 20개를 돌려 표로 출력한다. `--out result.json` 으로 저장, `--only 15` 로 하나만.
프롬프트는 `config/query_resolver.v1.toml` 에 있고 그 해시가 `prompt_version` 이다.
**프롬프트 문구와 timeout 은 Gate B 미동결**이다.

## 빌드 / 테스트

```bash
uv run pytest                  # 기본. smoke 제외, 1초 내
uv run pytest -m smoke         # 실제 모델 로딩·GPU 확인. gpu 그룹 필요
uv run ruff check .
uv run ruff format --check .
uv run mypy
```

## 의존성 그룹

Spring profile 에 대응하는 개념이 없으므로 의존성 그룹으로 실행 환경을 나눈다.

| 그룹 | 설치 | 내용 | 비고 |
| --- | --- | --- | --- |
| 기본 | `uv sync` | fastapi·uvicorn·pydantic(-settings)·scenedetect-headless·av·httpx2 | 설치 약 240MB (cv2 113 · av 67 · numpy 45) |
| `dev` | `uv sync` (기본 포함) | ruff·mypy·pytest·pytest-asyncio | |
| `gpu` | `uv sync --group gpu` | torch(cu130)·faster-whisper | 약 1.8GB, 최초 1회 |

`scenedetect` 는 PyAV 백엔드만 쓰더라도 임포트 시점에 `cv2` 를 요구한다. GUI 라이브러리가 붙은 `opencv-python` 이면 헤드리스 컨테이너에서 `libGL.so` 로 죽으므로 headless 변종을 쓴다 — 0.7 부터 이건 extra 가 아니라 **`scenedetect-headless` 별도 배포판**이다. 임포트 이름은 그대로 `scenedetect` 이고, 두 배포판을 같이 설치하면 임포트 이름을 다투므로 한쪽만 선언한다.

`scenedetect-headless` 를 `<0.8` 로 묶은 것은 상한 관례를 따른 것이지만, **`<0.7` 처럼 좁게 묶으면 안 된다** — 0.6.x 는 `click<8.3` 을 요구해서 `click` 과 `huggingface-hub`(ASR 단계에서 쓴다)를 함께 끌어내린다.

**torch 는 PyPI 가 아니라 `download.pytorch.org/whl/cu130` 에서 온다.** PyPI 의 Windows torch 휠은 CPU 전용(약 122MB)이라 그대로 설치하면 CUDA 가 조용히 비활성화된다. `pyproject.toml` 의 `[[tool.uv.index]]` 와 `[tool.uv.sources]` 가 이걸 막는다. macOS 는 CUDA 휠이 없으므로 marker 로 제외되어 PyPI 의 arm64(MPS) 휠로 해석된다.

`uv lock` 은 설치 여부와 무관하게 모든 그룹을 함께 해석한다. 따라서 **`gpu` 를 한 번도 설치하지 않는 사람의 `uv.lock` 에도 torch 엔트리가 있다** — 다운로드는 하지 않으니 정상이다.

대용량 휠이 끊기면 `UV_HTTP_TIMEOUT` 을 늘린다 (기본 30초):

```powershell
$env:UV_HTTP_TIMEOUT = "600"
uv sync --directory ai --group gpu
```

## 환경 변수

**설정 파일은 `ai/.env` 다.** `cp ai/.env.example ai/.env` 로 만든다. 저장소 루트의 `.env` 와
다른 파일이다 — 루트 쪽은 compose 가 `${AI_LOG_LEVEL}` 같은 자기 치환에 쓰고 접두사 없는
이름을 쓴다. `compose.yaml` 의 `ai-worker` 도 `env_file: ./ai/.env` 하나만 읽으므로,
루트에 `NPICK_AI_*` 를 넣으면 로컬 실행도 컨테이너도 그 값을 보지 못한다.


민감값은 하드코딩하지 않고 환경 변수로만 주입한다. `.env` 류 파일은 커밋 금지(`.gitignore` 처리됨).

| 변수 | 기본값 | 설명 |
| --- | --- | --- |
| `NPICK_AI_HOST` | `127.0.0.1` | 바인드 주소. 컨테이너에서는 `0.0.0.0` |
| `NPICK_AI_PORT` | `8000` | backend 8081 과 분리 |
| `NPICK_AI_LOG_LEVEL` | `INFO` | `DEBUG` / `INFO` / `WARNING` / `ERROR` |
| `NPICK_AI_DEVICE` | `auto` | `auto` / `cuda` / `cpu`. `cuda` 를 지정해도 불가하면 경고 후 `cpu` 로 내려간다 |
| `NPICK_AI_RESOLVER_BACKEND` | `ollama` | `ollama` / `gms`. 기본이 local 인 이유는 FRD §13.4 |
| `NPICK_AI_OLLAMA_URL` | `http://127.0.0.1:11434` | Query Resolver 가 부를 Ollama 주소 |
| `NPICK_AI_OLLAMA_MODEL` | (없음) | 쓸 모델 태그. **기본값을 두지 않는다** — 모델이 결과를 바꾸고 Gate B 미동결이라 코드가 임의로 고르면 근거 없는 동결이 된다 |
| `NPICK_AI_GMS_BASE_URL` | (없음) | 승인된 GMS 게이트웨이 주소. 추정하지 않는다 |
| `NPICK_AI_GMS_API_KEY` | (없음) | `Authorization: Bearer` 토큰. `SecretStr` 로 받아 로그·예외에 실리지 않는다 |
| `NPICK_AI_GMS_MODEL` | (없음) | `model_version` 에 실려 나가는 모델명 |
| `NPICK_AI_GMS_JSON_MODE` | `true` | `response_format={"type":"json_object"}` 를 보낼지. 게이트웨이가 거부하면 `false` |

```powershell
$env:NPICK_AI_PORT = "8001"; uv run --directory ai npick-worker
```

## 패키지 구조

```
ai/
├── src/npick_worker/
│   ├── __main__.py      console script 진입점 (uvicorn 부트스트랩)
│   ├── app.py           FastAPI 앱 + GET /health
│   ├── settings.py      NPICK_AI_* 환경 변수
│   ├── device.py        장치 탐지 (torch 지연 임포트, CPU 폴백)
│   ├── schemas.py       /health 응답 스키마
│   ├── stages.py        FRD §5.1 10단계 선언적 메타데이터
│   ├── config/
│   │   ├── scene_detection.v1.toml   임계값 정본 (Gate B 미동결)
│   │   └── query_resolver.v1.toml    프롬프트 정본 (Gate B 미동결)
│   ├── scene_detection/ FRD §5.1 1단계. detect_scenes() 순수 함수
│   │   ├── config.py                 toml 로딩 + version_id
│   │   ├── models.py                 Scene / SceneDetectionResult
│   │   ├── detector.py               SceneDetector Protocol
│   │   ├── pyscenedetect_backend.py  PySceneDetect + PyAV 구현
│   │   └── report.py                 육안 확인 CLI
│   └── query_resolver/  FRD §6. resolve_query() 순수 함수
│       ├── schema.py                 출력 schema 정본 + SCHEMA_VERSION
│       ├── config.py                 toml 로딩 + prompt_version
│       ├── prompt.py                 템플릿 렌더링
│       ├── validator.py              schema → semantic → 강등
│       ├── resolver.py               QueryResolver Protocol
│       ├── ollama_backend.py         Ollama HTTP 구현 (local)
│       ├── gms_backend.py            승인된 GMS HTTP 구현 (OpenAI 호환)
│       ├── report.py                 대표 질의 20개 확인 CLI
│       └── fixtures/                 대표 질의 20개
├── docs/scene-detection.md   선정 근거·설정 키·Gate B 항목
├── samples/                  로컬 샘플 클립 (영상은 커밋 금지)
└── tests/
    ├── conftest.py           합성 영상 픽스처 (PyAV 로 그 자리에서 인코딩)
    ├── test_health.py        레지스트리가 FRD §5.1 과 일치하는지 검증
    ├── test_scene_detection.py
    ├── test_query_resolver.py
    └── test_smoke_models.py  -m smoke: torch CUDA + faster-whisper tiny
```

- `stages.py` 는 FRD §5.1 표의 전사다. 단계 구현은 같은 이름의 패키지에 둔다.
- **임계값은 코드가 아니라 `config/*.toml` 에 있다.** 값이 바뀌면 `version_id` 가 바뀐다(FR-PRC-015).
- HTTP 표면은 헬스·운영용이다. 작업 수신 방식(FRD §10.8 outbox claim)과 BE 호출 인터페이스는 S15P21A501-70 에서 합의한다.
- 장치 정보는 프로세스 기동 후 1회만 탐지해 캐시한다. 드라이버를 교체했으면 워커를 재기동한다.
- 빈 패키지를 미리 만들지 않는다. 실제 기능이 생길 때 추가한다.
- 손으로 명령을 칠 때는 `--directory ai`(CWD 를 옮긴다), lefthook 훅에서는 `--project ai`(루트 기준 경로를 보존한다)를 쓴다. 클론 직후 `uv sync --directory ai` 를 먼저 돌리면 첫 커밋 훅이 빠르다.
