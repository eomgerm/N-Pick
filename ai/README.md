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
result.config_version  # 'scene-detect/v1:20dfc0a6'
```

구간은 `[start_time_ms, end_time_ms)` 반열린이고 서로 붙어 있다. 같은 파일 + 같은 설정이면
항상 같은 결과가 나온다(FR-PRC-006). 임계값은 `config/scene_detection.v1.toml` 에 있다.

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
| 기본 | `uv sync` | fastapi·uvicorn·pydantic(-settings)·scenedetect·av | 약 90MB |
| `dev` | `uv sync` (기본 포함) | ruff·mypy·pytest·pytest-asyncio·httpx | |
| `gpu` | `uv sync --group gpu` | torch(cu130)·faster-whisper | 약 1.8GB, 최초 1회 |

`scenedetect` 는 PyAV 백엔드만 쓰더라도 임포트 시점에 `cv2` 를 요구한다. 그래서 `opencv-headless` extra 로 설치한다 — GUI 라이브러리가 붙은 `opencv-python` 이면 헤드리스 컨테이너에서 `libGL.so` 로 죽는다.

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
│   │   └── scene_detection.v1.toml   임계값 정본 (Gate B 미동결)
│   └── scene_detection/ FRD §5.1 1단계. detect_scenes() 순수 함수
│       ├── config.py                 toml 로딩 + version_id
│       ├── models.py                 Scene / SceneDetectionResult
│       ├── detector.py               SceneDetector Protocol
│       ├── pyscenedetect_backend.py  PySceneDetect + PyAV 구현
│       └── report.py                 육안 확인 CLI
├── docs/scene-detection.md   선정 근거·설정 키·Gate B 항목
├── samples/                  로컬 샘플 클립 (영상은 커밋 금지)
└── tests/
    ├── conftest.py           합성 영상 픽스처 (PyAV 로 그 자리에서 인코딩)
    ├── test_health.py        레지스트리가 FRD §5.1 과 일치하는지 검증
    ├── test_scene_detection.py
    └── test_smoke_models.py  -m smoke: torch CUDA + faster-whisper tiny
```

- `stages.py` 는 FRD §5.1 표의 전사다. 단계 구현은 같은 이름의 패키지에 둔다.
- **임계값은 코드가 아니라 `config/*.toml` 에 있다.** 값이 바뀌면 `version_id` 가 바뀐다(FR-PRC-015).
- HTTP 표면은 헬스·운영용이다. 작업 수신 방식(FRD §10.8 outbox claim)과 BE 호출 인터페이스는 S15P21A501-70 에서 합의한다.
- 장치 정보는 프로세스 기동 후 1회만 탐지해 캐시한다. 드라이버를 교체했으면 워커를 재기동한다.
- 빈 패키지를 미리 만들지 않는다. 실제 기능이 생길 때 추가한다.
- 손으로 명령을 칠 때는 `--directory ai`(CWD 를 옮긴다), lefthook 훅에서는 `--project ai`(루트 기준 경로를 보존한다)를 쓴다. 클론 직후 `uv sync --directory ai` 를 먼저 돌리면 첫 커밋 훅이 빠르다.
