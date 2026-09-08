# N-Pick AI Worker

FRD §2.1 `[Pipeline Worker]`. 헬스체크와 1단계 `scene_detection`(FRD §5.1) 이 구현되어 있다.

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
| 기본 | `uv sync` | fastapi·uvicorn·pydantic(-settings)·scenedetect-headless·av | 설치 약 240MB (cv2 113 · av 67 · numpy 45) |
| `dev` | `uv sync` (기본 포함) | ruff·mypy·pytest·pytest-asyncio·httpx | |
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

민감값은 하드코딩하지 않고 환경 변수로만 주입한다. `.env` 류 파일은 커밋 금지(`.gitignore` 처리됨).

| 변수 | 기본값 | 설명 |
| --- | --- | --- |
| `NPICK_AI_HOST` | `127.0.0.1` | 바인드 주소. 컨테이너에서는 `0.0.0.0` |
| `NPICK_AI_PORT` | `8000` | backend 8080 과 분리 |
| `NPICK_AI_LOG_LEVEL` | `INFO` | `DEBUG` / `INFO` / `WARNING` / `ERROR` |
| `NPICK_AI_DEVICE` | `auto` | `auto` / `cuda` / `cpu`. `cuda` 를 지정해도 불가하면 경고 후 `cpu` 로 내려간다 |
| `NPICK_AI_MEDIA_ROOT` | 없음 | backend 와 공유하는 미디어 마운트. 없으면 입력을 HTTP 로 받는다 |

잡 수신(파이프라인 워커 전용). 계약은 [../docs/contracts/job-api.md](../docs/contracts/job-api.md) 다.

| 변수 | 기본값 | 설명 |
| --- | --- | --- |
| `NPICK_AI_JOB_POLL_ENABLED` | `false` | **켜야 잡을 받는다.** 이 값이 배포 단위를 가른다(아래) |
| `NPICK_AI_JOB_API_BASE_URL` | 없음 | 서비스 서버 주소. 비어 있으면 폴링하지 않는다 |
| `NPICK_AI_JOB_API_TOKEN` | 없음 | fleet 별 Bearer 토큰. 로그·`/health` 에 절대 나오지 않는다 |
| `NPICK_AI_WORKER_ID` | 자동 생성 | 진단용 식별자 |
| `NPICK_AI_JOB_FLEET` | `local` | `prod` / `dev` 등. 토큰과 짝이 맞아야 한다 |
| `NPICK_AI_JOB_POLL_WAIT_SECONDS` | `25` | claim 롱폴 대기 상한 |
| `NPICK_AI_JOB_HEARTBEAT_SECONDS` | `10` | heartbeat 주기의 상한. 서버가 준 주기가 더 작으면 그쪽을 쓴다 |
| `NPICK_AI_JOB_CONNECT_TIMEOUT_SECONDS` | `5` | |
| `NPICK_AI_JOB_READ_TIMEOUT_SECONDS` | `30` | claim 이외 |
| `NPICK_AI_JOB_MAX_BACKOFF_SECONDS` | `60` | |
| `NPICK_AI_JOB_CONCURRENCY` | `1` | GPU 한 장 전제 |

단계 재시도 횟수와 단계 타임아웃은 여기 없다. 워커가 구현하지 않고 BE 가 소유한다
(`infra/compose/profiles/pipeline.yml` 에서 Gate D 까지 `null`).

```powershell
$env:NPICK_AI_PORT = "8001"; uv run --directory ai npick-worker
```

## 패키지 구조

`ai/` 는 배포 단위 **둘**을 담는다 — 질의 리졸버와 파이프라인 워커
([02-container.md](../docs/architecture/02-container.md) 의 *요소* 표). 아직 컨테이너
하나에 들어 있으므로 패키지로 구분한다.

```
ai/
├── src/npick_worker/
│   ├── __main__.py      console script 진입점 (uvicorn 부트스트랩)
│   ├── app.py           FastAPI 앱 + GET /health + 잡 루프 lifespan
│   ├── settings.py      NPICK_AI_* 환경 변수
│   ├── device.py        장치 탐지 (torch 지연 임포트, CPU 폴백)
│   ├── schemas.py       /health 응답 스키마
│   ├── versioning.py    ── 공용: <schema>:<sha256[:8]> 버전 형식 ──
│   ├── stages.py        [워커] 10단계 선언적 메타데이터 (FRD 전사)
│   ├── config/
│   │   └── scene_detection.v1.toml   임계값 정본 (Gate B 미동결)
│   ├── scene_detection/ [워커] 1단계. detect_scenes() 순수 함수
│   │   ├── config.py                 toml 로딩 + version_id
│   │   ├── models.py                 Scene / SceneDetectionResult
│   │   ├── detector.py               SceneDetector Protocol
│   │   ├── pyscenedetect_backend.py  PySceneDetect + PyAV 구현
│   │   └── report.py                 육안 확인 CLI
│   └── jobs/            [워커] BE 잡 API 클라이언트와 실행 루프
│       ├── client.py                 claim/heartbeat/complete/artifacts
│       ├── runner.py                 claim→실행→heartbeat→complete
│       ├── registry.py               단계 디스패치 + 워밍업
│       ├── media.py                  입력 해석 (공유 마운트 / 다운로드)
│       ├── models.py                 와이어 모델 + 결과 봉투
│       ├── versions.py               stageVersion / pipeline_version
│       └── errors.py                 오류 어휘 + 일시·영구 분류
├── docs/scene-detection.md   선정 근거·설정 키·Gate B 항목
├── samples/                  로컬 샘플 클립 (영상은 커밋 금지)
└── tests/
    ├── conftest.py           합성 영상 픽스처 + 가짜 BE(httpx2.MockTransport)
    ├── test_health.py        레지스트리가 FRD 단계 표와 일치하는지 검증
    ├── test_versioning.py    (test_job_contract.py 안) 버전 형식
    ├── test_job_*.py         계약·클라이언트·러너·미디어·레지스트리
    ├── test_scene_detection.py
    └── test_smoke_models.py  -m smoke: torch CUDA + faster-whisper tiny
```

질의 리졸버 코드는 아직 없다. 생기면 자기 패키지에 둔다 — `jobs/` 에 넣지 않는다.
long-poll·lease·멱등성은 리졸버에 해당 사항이 없다(동기 호출 전용·재시도 없음).

- `stages.py` 는 FRD §5.1 표의 전사다. 단계 구현은 같은 이름의 패키지에 둔다.
- **임계값은 코드가 아니라 `config/*.toml` 에 있다.** 값이 바뀌면 `version_id` 가 바뀐다(FR-PRC-015).
- HTTP 표면은 헬스·운영용이다. **잡은 워커가 BE 에서 받아 온다** — 인바운드 잡 엔드포인트가 없다. 잡 루프는 라우트가 아니라 lifespan 태스크로 돈다.
- `NPICK_AI_JOB_POLL_ENABLED` 가 배포 단위를 가른다. 같은 이미지가 이 값 하나로 "잡을 도는 파이프라인 워커" 와 "폴링하지 않는 질의 리졸버" 가 된다.
- 장치 정보는 프로세스 기동 후 1회만 탐지해 캐시한다. 드라이버를 교체했으면 워커를 재기동한다.
- 빈 패키지를 미리 만들지 않는다. 실제 기능이 생길 때 추가한다.
- 손으로 명령을 칠 때는 `--directory ai`(CWD 를 옮긴다), lefthook 훅에서는 `--project ai`(루트 기준 경로를 보존한다)를 쓴다. 클론 직후 `uv sync --directory ai` 를 먼저 돌리면 첫 커밋 훅이 빠르다.
