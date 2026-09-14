# N-Pick AI Worker

실제 세 후보의 품질·속도·메모리 비교는 [VLM 문서 §9.6](docs/vlm-metadata.md#96-2026-09-13-실제-세-후보-비교)에 있다.
Qwen3.5-9B를 선정하고 Qwen3.5-4B를 메모리 제약 시 대체 모델로 정했다. 품질 수치는 AI 예비 검토로 사람 검수·Gold Set 합격과 구분한다.

외부 Linux GPU에서 VLM 후보를 비교하려면 [VLM 문서 §9.5](docs/vlm-metadata.md#95-linux-gpu-서버에서-직접-실행하기)를 따른다.
`bash run-vlm-smoke.sh <frames-dir> <out-root> <memory-budget-gib> [model ...]`로
BE 없이 10장면 smoke와 실행 기록을 생성한다.

헬스체크, `scene_detection`, `frame_extraction`, `vlm_metadata`, `ocr`, Query Resolver 프롬프트·출력 계약(FRD F-04~06)이 구현되어 있다.

Query Resolver는 검색 시점에 쓰이며 파이프라인 단계가 아니다. 배포 경계는
[Container 요소 표](../docs/architecture/02-container.md#요소)를 따른다. 이 모듈은
프롬프트·모델 호출 어댑터·출력 검증을 제공하며 검색 오케스트레이션은 호출부 책임이다.

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
    "stages": [
      { "order": 1, "name": "scene_detection", "fatal": true },
      { "order": 2, "name": "frame_extraction", "fatal": true }
    ]
  }
}
```

`pipeline.stages` 는 FRD 단계 표 10개를 전부 싣는다 (위 예시는 앞 둘만 옮겼다).

`gpu` 그룹을 설치하지 않은 환경에서는 `torch_available: false`, `resolved: "cpu"` 로 응답한다. GPU 부재는 오류가 아니다.

## scene 분할 (FRD F-03)

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
나온다**(모듈 재현성 계약).
`config_version` 은 설정 파일만 해시하므로 라이브러리를 올리면 값이 그대로인데 경계는 달라질
수 있다 — 그래서 세 필드를 다 싣는다. 임계값은 `config/scene_detection.v1.toml` 에 있다.

샘플 클립 육안 확인:

```bash
uv run --directory ai python -m npick_worker.scene_detection.report     samples/01-standard-report.mp4 --out samples/out/01
```

scene 표를 출력하고 `--out` 에 `scenes.json` 과 경계 프레임 PNG 를 남긴다.
필요한 샘플 클립 종류는 [samples/README.md](samples/README.md), 선정 근거와 설정 키는
[docs/scene-detection.md](docs/scene-detection.md).

## keyframe 추출 (FRD F-03)

scene 마다 **복수 keyframe** 을 뽑고 그중 **결과 카드에 쓸 대표 이미지 1개** 를 고른다.
구간은 상류 `scene_detection` 산출물에서 받는다 — 실제 파이프라인에서 이 단계에 scene 이
도착하는 경로가 BE 가 되돌려 주는 JSON 이기 때문이다.

```python
from pathlib import Path
from npick_worker.frame_extraction import SceneSpan, extract_keyframes

result = extract_keyframes(
    Path("clip.mp4"),
    [SceneSpan(scene_index=0, start_time_ms=0, end_time_ms=4200)],
    Path("out"),
)
scene = result.scenes[0]
scene.representative  # Keyframe(...) ← 결과 카드에 쓸 한 장
scene.keyframes[0]  # 같은 객체. 대표는 **목록의 첫 원소**다
len(scene.keyframes)  # 2 이상. 장 수는 장면 안의 변화량이 정한다 (docs/frame-extraction.md §3.1)
result.image_width  # 원본 해상도. 다운스케일하지 않는다
result.config_version  # 'frame-extract/v2:a0684794'
result.engine_version  # '18.1.0+numpy2.5.2'  ← PyAV + numpy (둘 다 결과를 바꾼다)
```

`keyframe` 테이블에 대표를 표시할 컬럼이 없어서 **대표는 순서로 전달된다.** BE 는 이 순서대로
INSERT 하고 대표가 그 scene 의 최소 `keyframe_id` 가 된다. 규약 정본은
[../docs/contracts/job-api.md](../docs/contracts/job-api.md) §4.3.1.

**작은 글자 OCR 을 위해 원본 해상도를 유지한다**(FRD §3 F-03). 축소본 파일은 만들지 않는다 —
담을 컬럼이 없고, 결과 카드용 축소는 ID 기반 조회 응답에서 만들 수 있다. 원본 영상 자체도
`clip.storage_key` 로 계속 접근할 수 있으므로 필요하면 프레임을 다시 뽑을 수 있다.

같은 입력 + 같은 `(config_version, engine, engine_version)` 이면 **같은 프레임을 고르고 같은
바이트를 쓴다.** 샘플 클립 23장이 두 실행에서 sha256 까지 동일했다.

샘플 클립 육안 확인:

```bash
uv run --directory ai python -m npick_worker.frame_extraction.report \
    samples/KNI_02205.mp4 --out samples/out/KNI_02205-frames
```

scene 마다 대표(`*`)와 전체 keyframe 시각을 표로 출력하고 `--out` 에 `keyframes.json` 과 JPEG 을
남긴다. `--scenes` 로 `scene_detection.report` 가 만든 `scenes.json` 을 주면 분할을 다시 돌리지
않는다. 선정 근거·인코딩 실측·설정 키는 [docs/frame-extraction.md](docs/frame-extraction.md).

## 장면 metadata 생성 (FRD F-03)

scene 마다 **복수 keyframe 을 함께** 모델에 넣어 장면 설명·샷 유형·태그 후보를 만든다.
근거가 없는 값은 지어내지 않고 `null`·`unknown` 으로 둔다.

```python
from pathlib import Path
from npick_worker.vlm_metadata import KeyframeRef, SceneKeyframes, describe_scenes
from npick_worker.vlm_metadata.transformers_backend import shared_client

scene = SceneKeyframes(
    scene_index=0,
    keyframes=(
        KeyframeRef(scene_index=0, timestamp_ms=4200, storage_key="s0000/kf-000004200.jpg"),
        KeyframeRef(scene_index=0, timestamp_ms=9100, storage_key="s0000/kf-000009100.jpg"),
    ),
)
paths = {kf.storage_key: Path("out") / kf.storage_key for kf in scene.keyframes}

result = describe_scenes([scene], paths, shared_client())
result.scenes[0].shot_type.value  # 'anchor' | 'interview' | 'b_roll' | 'unknown'
result.scenes[0].caption  # Caption(...) 또는 None — 근거가 없으면 비운다
result.scenes[0].scene_type  # type='scene_type' 인 **태그 후보**. scene 컬럼이 아니다
result.scenes[0].caption.evidence  # 이 판단의 근거가 된 keyframe 들
result.config_version  # 'vlm-metadata-config/v1:...'
result.model_version  # '<모델>@<리비전>'
```

**모델 이름은 코드에 없다.** `NPICK_AI_VLM_MODEL` 로 준다 — 후보 비교로 정할 값이라
코드가 고르면 근거 없는 동결이 된다(FRD §11). 비어 있으면 이 단계는 `capabilities` 에서
빠지고 BE 가 배정하지 않는다. 가중치 실행에는 `uv sync --group gpu` 가 필요하다.

형식·어휘·근거 중 하나라도 어긋난 출력은 **통째로 거부한다**(계약 §9.2 `VLM_SCHEMA_INVALID`,
영구). 일부 필드만 골라 쓰지 않는다.

샘플 클립으로 확인·후보 비교:

```bash
uv run --directory ai python -m npick_worker.vlm_metadata.report \
    samples/out/KNI_02205-frames --out samples/out/KNI_02205-vlm --model <후보> --smoke
```

`--smoke` 는 티켓의 조건(장면 10건 이상, 장면마다 **실제로 모델에 넣는** keyframe 2장 이상)을
검사한다. 저장되는 JSON 에는 통과한 출력뿐 아니라 **결과를 얻지 못한 장면의 원문·오류·입력·
소요 시간**과 device·peak VRAM 이 함께 남고, 파일은 장면마다 다시 쓴다 — 중간에 OOM 으로
끊겨도 그때까지의 실측이 남는다.

선정 근거·어휘·설정 키·외부 처리 게이트는 [docs/vlm-metadata.md](docs/vlm-metadata.md).

## Query Resolver (FRD F-04~06)

한국어 질의를 구조화 조건으로 바꾼다. **검색 시점**에 쓰이며 파이프라인 단계가 아니다.

```python
from npick_worker.query_resolver import resolve_query
from npick_worker.query_resolver.ollama_backend import OllamaResolver

result = resolve_query("2022년 촬영한 서울역", resolver)
result.resolution.locations  # (Location(type='facility', value='서울역', origin='explicit_query', ...),)
result.resolution_schema_version  # 'query-resolver/v2'
result.prompt_version  # 'query-resolver-prompt/v1:4d0caca2'
result.model_version  # 'gpt-5.4-mini-2026-03-17' (게이트웨이가 응답에 실어 준 이름)
result.findings  # 검증이 무엇을 바꿨는지 (호출자가 기록할 변경 내역)
```

`origin` 이 `explicit_query` 면 **원문에 그 문자열이 실제로 있다**는 뜻이다. 원문에 없는
값을 그렇게 주장하면 `inferred` 로 강등된다(F-05를 충족하기 위한 구현 선택). `inferred` 는 hard filter 가
되지 않는다(FRD F-06).

출력 v2에서 날짜 값은 **`broadcast_date | filmed_date`**다. BE 소비자는 날짜 enum과
`resolution_schema_version`·`schema_version`의 `query-resolver/v2`를 반영해야 한다.
v1의 `filming_date`는 거부하며 호환 별칭은 없다. BE 코드는 이번 변경에 포함하지 않는다.
프롬프트 TOML 파일명과 `query-resolver-prompt/v1` 접두사는 출력 버전과 별개로 유지한다.

`classifications`는 F-04의 `season`, `weather`, `scene_type`을 표현하는 출력 계약이다.
기존 분류 동작을 유지하며 이 필드가 별도 DB 컬럼을 요구하는 것은 아니다.

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
| `dropped` | 뒤집힌 날짜 구간, 또는 `locations` 와 겹친 `entities`; locations 우선은 F-05의 중복 계산 방지를 위한 구현 선택 |

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

`ollama` 기본값은 외부 전송을 기본으로 켜지 않기 위한 구현 선택이다. FRD §6.4는
검색어·필터 전송 승인을 영상 전송 승인과 구분한다. 권리·외부 처리 허용 및 제공자
조건을 확인하기 전에는 외부 AI로 보내면 안 된다. §11에 따라 별도 정책 테이블은
전제하지 않는다. 현재 GMS 어댑터에는 승인 검사가 없으므로 호출자가 승인된
제공자·목적지·데이터 범위를 확인해야 한다. 환경 변수 설정만으로 승인되지는 않는다.

호출·schema 오류 코드는 모듈 계약이다. 호출부는 §6.2에 따라 원 검색어 BM25로
전환하고 누락을 안내하며 동기 AI 재시도를 하지 않는다. 반환하는 해석·findings·
세 버전은 §7.2 기록을 지원하는 메타데이터이며 특정 snapshot 테이블을 요구하지 않는다.

```powershell
$env:NPICK_AI_RESOLVER_BACKEND = "gms"
$env:NPICK_AI_GMS_BASE_URL = "<게이트웨이 주소>"
$env:NPICK_AI_GMS_API_KEY = "<키>"
$env:NPICK_AI_GMS_MODEL = "<모델명>"
uv run --directory ai python -m npick_worker.query_resolver.report
```

대표 질의 20개를 돌려 표로 출력한다. `--out result.json` 으로 저장, `--only 15` 로 하나만.
프롬프트는 `config/query_resolver.v1.toml` 에 있고 그 해시가 `prompt_version` 이다.
**모델·프롬프트·timeout은 FRD §11에 따라 실측 후 확정한다.** 검색 p95 10초는 §8.2의 품질 목표이며 현재 설정이 이를 달성했다는 뜻은 아니다.

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
| 기본 | `uv sync` | fastapi·uvicorn·pydantic(-settings)·scenedetect-headless·av·kiwipiepy·rapidocr·onnxruntime·httpx2 | 설치 약 240MB + OCR 약 90MB |
| `dev` | `uv sync` (기본 포함) | ruff·mypy·pytest·pytest-asyncio | |
| `gpu` | `uv sync --group gpu` | torch(cu130)·faster-whisper·transformers·pillow | 약 1.8GB, 최초 1회. **가중치는 별도** |

`scenedetect` 는 PyAV 백엔드만 쓰더라도 임포트 시점에 `cv2` 를 요구한다. GUI 라이브러리가 붙은 `opencv-python` 이면 헤드리스 컨테이너에서 `libGL.so` 로 죽으므로 headless 변종을 쓴다 — 0.7 부터 이건 extra 가 아니라 **`scenedetect-headless` 별도 배포판**이다. 임포트 이름은 그대로 `scenedetect` 이고, 두 배포판을 같이 설치하면 임포트 이름을 다투므로 한쪽만 선언한다.

`scenedetect-headless` 를 `<0.8` 로 묶은 것은 상한 관례를 따른 것이지만, **`<0.7` 처럼 좁게 묶으면 안 된다** — 0.6.x 는 `click<8.3` 을 요구해서 `click` 과 `huggingface-hub`(ASR 단계에서 쓴다)를 함께 끌어내린다.

`rapidocr`(OCR 단계)가 `opencv-python` 을 요구하는데 그건 위와 같은 이유로 들이면 안 되는 배포판이다. **둘은 같은 `cv2` 를 설치하므로 함께 깔면 나중에 깔린 쪽이 이긴다.** `pyproject.toml` 의 `[tool.uv] override-dependencies` 가 항상 거짓인 marker 로 그 요구를 지워 headless 하나만 남긴다 — rapidocr 이 쓰는 것은 `import cv2` 뿐이라 구현체가 headless 여도 된다.

OCR 을 `gpu` 처럼 opt-in 그룹에 두지 않은 이유는 `infra/compose/profiles/pipeline.yml` 이 "CPU 워커와 GPU 파드가 같은 이미지를 쓴다" 로 적었기 때문이다. 그룹으로 빼면 배포 이미지가 OCR 을 못 한다. 실행기가 onnxruntime(CPU)이라 GPU 없이 돌아간다 — 샘플 클립에서 장당 약 430ms 다([docs/ocr.md](docs/ocr.md) §9).

`transformers`(VLM 단계)를 `gpu` 그룹에 둔 이유는 OCR 과 반대다. 이 단계는 GPU 파드의
것이고 CPU 워커는 배정받지 않는다 — 모델 이름이 설정되지 않은 워커는 `capabilities` 에
`vlm_metadata` 를 싣지 않으므로(`jobs/registry.py`) 배정 자체가 오지 않는다. 가중치는 패키지에
들어 있지 않고 `NPICK_AI_VLM_MODEL_DIR` 이 가리키는 곳에 받는다.

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
| `NPICK_AI_PORT` | `8000` | backend 8080 과 분리 |
| `NPICK_AI_LOG_LEVEL` | `INFO` | `DEBUG` / `INFO` / `WARNING` / `ERROR` |
| `NPICK_AI_DEVICE` | `auto` | `auto` / `cuda` / `cpu`. `cuda` 를 지정해도 불가하면 경고 후 `cpu` 로 내려간다 |
| `NPICK_AI_MEDIA_ROOT` | 없음 | backend 와 공유하는 미디어 마운트. 없으면 입력을 HTTP 로 받는다 |
| `NPICK_AI_OCR_MODEL_DIR` | 없음 | OCR 모델 가중치를 둘 곳. **컨테이너에서는 반드시 준다** — 기본값이 site-packages 안이라 컨테이너를 다시 만들 때마다 약 19MB 를 새로 받는다 |
| `NPICK_AI_VLM_MODEL` | (없음) | VLM 가중치 식별자. **기본값을 두지 않는다** — 후보 비교로 정할 값이라 코드가 고르면 근거 없는 동결이다(FRD §11). 비어 있으면 이 단계가 `capabilities` 에서 빠진다 |
| `NPICK_AI_VLM_MODEL_REVISION` | `main` | 가중치 리비전. 재현 식별자에 들어간다 |
| `NPICK_AI_VLM_MODEL_DIR` | 없음 | VLM 가중치를 둘 곳. **컨테이너에서는 반드시 준다** — 파드 디스크가 휘발성이라 띄울 때마다 수 GB 를 다시 받는다 |
| `NPICK_AI_VLM_BACKEND` | `transformers` | `transformers`(자체 GPU) / `external`. 기본이 자체 호스팅인 이유는 [02-container.md](../docs/architecture/02-container.md) 요소 표 |
| `NPICK_AI_VLM_EXTERNAL_*` | 전부 닫힘 | 외부 제공자 조건(PRD §12.4). **전부 채워도 clip 별 권리 확인 없이는 전송하지 않는다** — `.env.example` 과 [docs/vlm-metadata.md](docs/vlm-metadata.md) §8 |
| `NPICK_AI_ASR_MODEL` | (없음) | ASR 가중치 식별자(예: `large-v3-turbo`). **기본값을 두지 않는다** — 모델 크기가 결과와 처리 시간을 바꾸고 실측 후 확정이라(FRD §11) 코드가 고르면 근거 없는 동결이다. 비어 있으면 이 단계가 `capabilities` 에서 빠진다 |
| `NPICK_AI_ASR_COMPUTE_TYPE` | 장치 기본값 | `float16`(CUDA) / `int8`(CPU) 등. 결과를 바꾸므로 재현 식별자의 `modelVersion` 에 함께 들어간다 |
| `NPICK_AI_ASR_MODEL_DIR` | 없음 | ASR 가중치를 둘 곳. **컨테이너에서는 반드시 준다** — VLM 과 같은 이유다 |
| `NPICK_AI_RESOLVER_BACKEND` | `ollama` | `ollama` / `gms`. 기본이 local 인 이유는 FRD §6.4 |
| `NPICK_AI_OLLAMA_URL` | `http://127.0.0.1:11434` | Query Resolver 가 부를 Ollama 주소 |
| `NPICK_AI_OLLAMA_MODEL` | (없음) | 쓸 모델 태그. **기본값을 두지 않는다** — 모델이 결과를 바꾸고 실측 후 확정이라 코드가 임의로 고르면 근거 없는 동결이 된다 |
| `NPICK_AI_GMS_BASE_URL` | (없음) | 승인된 GMS 게이트웨이 주소. 추정하지 않는다 |
| `NPICK_AI_GMS_API_KEY` | (없음) | `Authorization: Bearer` 토큰. `SecretStr` 로 받아 로그·예외에 실리지 않는다 |
| `NPICK_AI_GMS_MODEL` | (없음) | `model_version` 에 실려 나가는 모델명 |
| `NPICK_AI_GMS_JSON_MODE` | `true` | `response_format={"type":"json_object"}` 를 보낼지. 게이트웨이가 거부하면 `false` |

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
(`infra/compose/profiles/pipeline.yml` 에서 `null`).

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
│   ├── stages.py        [워커] 기존 10단계 선언적 메타데이터
│   ├── korean_tokens.py ── 공용: Kiwi 색인 토큰 규칙 (색인·질의가 같이 쓴다) ──
│   ├── config/
│   │   ├── scene_detection.v1.toml   임계값 정본 (실측 후 확정)
│   │   ├── frame_extraction.v1.toml  임계값 정본 (실측 후 확정)
│   │   ├── vlm_metadata.v1.toml      프롬프트·어휘·상한 정본 (실측 후 확정)
│   │   ├── ocr.v1.toml               임계값 정본 (실측 후 확정)
│   │   ├── query_normalization.v1.toml  정규화 규칙 정본
│   │   └── query_resolver.v1.toml    프롬프트 정본 (실측 후 확정)
│   ├── scene_detection/ [워커] 장면 분할. detect_scenes() 순수 함수
│   │   ├── config.py                 toml 로딩 + version_id
│   │   ├── models.py                 Scene / SceneDetectionResult
│   │   ├── detector.py               SceneDetector Protocol
│   │   ├── pyscenedetect_backend.py  PySceneDetect + PyAV 구현
│   │   └── report.py                 육안 확인 CLI
│   ├── frame_extraction/ [워커] keyframe 추출. extract_keyframes() 순수 함수
│   │   ├── selector.py               슬롯 계획·선정 (영상 없이 검증된다)
│   │   ├── pyav_backend.py           FrameGrabber Protocol 구현
│   │   └── report.py                 육안 확인 CLI
│   ├── vlm_metadata/    [워커] 장면 설명·샷 유형·태그 후보. describe_scenes()
│   │   ├── schema.py                 모델 출력 계약 정본 + SCHEMA_VERSION
│   │   ├── models.py                 검증을 통과한 값의 어휘 + VlmResult
│   │   ├── config.py                 toml 로딩 + config_version
│   │   ├── prompt.py                 렌더링·근거 라벨·prompt_version
│   │   ├── client.py                 VlmClient Protocol
│   │   ├── transformers_backend.py   자체 GPU 구현 (정본 경로)
│   │   ├── external_policy.py        외부 전송 게이트 (PRD §12.4, fail-closed)
│   │   ├── validator.py              JSON → schema → 어휘·근거 → 거부
│   │   ├── describer.py              장면당 keyframe 선정 + 호출
│   │   └── report.py                 후보 비교·smoke CLI
│   ├── ocr/             [워커] 화면 글자 관측. read_keyframes() 순수 함수
│   │   ├── rapidocr_backend.py       OcrEngine Protocol 구현
│   │   ├── postprocess.py            관측 변환·textKey
│   │   └── report.py                 육안 확인 CLI
│   ├── jobs/            [워커] BE 잡 API 클라이언트와 실행 루프
│   │   ├── client.py                 claim/heartbeat/complete/artifacts
│   │   ├── runner.py                 claim→실행→heartbeat→complete
│   │   ├── registry.py               단계 디스패치 + 워밍업
│   │   ├── media.py                  입력 해석 (공유 마운트 / 다운로드)
│   │   ├── models.py                 와이어 모델 + 결과 봉투
│   │   ├── versions.py               stageVersion / pipeline_version
│   │   └── errors.py                 오류 어휘 + 일시·영구 분류
│   └── query_resolver/  [리졸버] FRD F-04~06. resolve_query() 모델 호출·검증
│       ├── schema.py                 출력 schema 정본 + SCHEMA_VERSION
│       ├── config.py                 toml 로딩 + prompt_version
│       ├── prompt.py                 템플릿 렌더링
│       ├── validator.py              schema → semantic → 강등
│       ├── resolver.py               QueryResolver Protocol
│       ├── ollama_backend.py         Ollama HTTP 구현 (local)
│       ├── gms_backend.py            승인된 GMS HTTP 구현 (OpenAI 호환)
│       ├── report.py                 대표 질의 20개 확인 CLI
│       └── fixtures/                 대표 질의 20개
├── docs/
│   ├── scene-detection.md    선정 근거·설정 키·실측 후 확정 항목
│   ├── frame-extraction.md   대표 이미지 규약·인코딩 실측·설정 키
│   ├── vlm-metadata.md       출력 계약·어휘의 자리·거부 규칙·외부 게이트
│   └── ocr.md                엔진 선정 실측·임계값 실측·설정 키
├── samples/                  로컬 샘플 클립 (영상은 커밋 금지)
└── tests/
    ├── conftest.py           합성 영상 픽스처 + 가짜 BE(httpx2.MockTransport)
    ├── test_health.py        단계 레지스트리·장치·워밍업·폴링 상태 검증
    ├── test_versioning.py    (test_job_contract.py 안) 버전 형식
    ├── test_job_*.py         계약·클라이언트·러너·미디어·레지스트리
    ├── test_scene_detection.py
    ├── test_frame_extraction.py
    ├── test_vlm_metadata.py  출력 계약·거부 규칙·입력 선정·외부 정책 게이트
    ├── test_ocr.py
    ├── test_query_normalization.py
    ├── test_query_resolver.py
    └── test_smoke_models.py  -m smoke: torch CUDA + faster-whisper tiny
```

long-poll·lease·멱등성은 리졸버에 해당 사항이 없다(동기 호출 전용·재시도 없음). 둘이
공유하는 것은 `versioning.py` 의 버전 형식뿐이다.

- `stages.py`는 기존 단계 선언이다. v3.1과의 파이프라인 정합성 정리는 별도 작업이다. 단계 구현은 같은 이름의 패키지에 둔다.
- **임계값은 코드가 아니라 `config/*.toml` 에 있다.** 값이 바뀌면 `version_id` 가 바뀐다(FRD §7.2 기록 지원).
- HTTP 표면은 헬스·운영용이다. **잡은 워커가 BE 에서 받아 온다** — 인바운드 잡 엔드포인트가 없다. 잡 루프는 라우트가 아니라 lifespan 태스크로 돈다. 계약 정본은 [../docs/contracts/job-api.md](../docs/contracts/job-api.md) 다.
- `NPICK_AI_JOB_POLL_ENABLED` 가 배포 단위를 가른다. 같은 이미지가 이 값 하나로 "잡을 도는 파이프라인 워커" 와 "폴링하지 않는 질의 리졸버" 가 된다.
- 장치 정보는 프로세스 기동 후 1회만 탐지해 캐시한다. 드라이버를 교체했으면 워커를 재기동한다.
- 빈 패키지를 미리 만들지 않는다. 실제 기능이 생길 때 추가한다.
- 손으로 명령을 칠 때는 `--directory ai`(CWD 를 옮긴다), lefthook 훅에서는 `--project ai`(루트 기준 경로를 보존한다)를 쓴다. 클론 직후 `uv sync --directory ai` 를 먼저 돌리면 첫 커밋 훅이 빠르다.
