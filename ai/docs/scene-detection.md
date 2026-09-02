# scene detection 선정과 설정

FRD §5.3 `FR-PRC-010`·`FR-PRC-015` 구현 근거. 구현은 `src/npick_worker/scene_detection/`.

## 1. 라이브러리·모델 비교

뉴스 영상 기준으로 후보를 넷 놓고 봤다.

| | PySceneDetect (선정) | TransNetV2 | AutoShot | ffmpeg `select=gt(scene,N)` |
| --- | --- | --- | --- | --- |
| 방식 | 프레임 간 HSV 차이(`content`) / 이동 평균 대비 상대 변화(`adaptive`) | CNN 기반 shot boundary 검출 | TransNetV2 계열 재학습 | ffmpeg 내장 scene score |
| hard cut | 강함 | 강함 | 강함 | 보통 |
| 디졸브·와이프 | **약함** (점진 전환을 놓친다) | 강함 (이 모델의 존재 이유) | 강함 | 약함 |
| 모델 가중치 | 없음 | 필요 (수십 MB) | 필요 | 없음 |
| 의존성 | `scenedetect` + `av` + opencv-headless (약 42MB) | torch — `gpu` 그룹 opt-in 성질을 깬다 | 동일 | **시스템 ffmpeg 설치 필요** |
| 결정론 | 완전. 같은 파일·같은 설정이면 동일 | 가중치·torch 버전까지 고정해야 성립 | 동일 | 결정론적이나 후처리를 직접 짜야 함 |
| 최소 장면 길이 | 내장 (`min_scene_len`) | 없음 — 직접 구현 | 없음 | 없음 |
| 임계값 조정 | 파라미터로 노출 | 재학습 또는 후처리 임계값 | 동일 | scene score 하나뿐 |

### 왜 PySceneDetect 인가

1. **Gate B 가 아직 안 열렸다.** 지금 필요한 건 최고 정확도가 아니라 **측정 대상이 될 조정 가능한 기준선**이다.
   임계값을 돌려가며 개발셋을 볼 수 있어야 나중에 근거를 갖고 동결한다(FRD §15.4).
2. **GPU 선택 의존성을 지킨다.** `ai/AGENTS.md` 는 "GPU 없이도 워커가 기동하는 성질을 깨지 않는다"를 규약으로 둔다.
   TransNetV2 는 torch 를 기본 그룹으로 끌어올리거나 scene detection 을 GPU 필수로 만든다. 1단계가 치명 단계라 후자는 곧 GPU 없으면 파이프라인 전체 정지다.
3. **뉴스는 hard cut 이 지배적이다.** 앵커 ↔ 현장 ↔ 인터뷰 전환은 대부분 컷이다. 디졸브는 코너 전환과 b-roll 몽타주에 몰린다.
   약점이 어디에 얼마나 있는지는 샘플 클립 3번으로 확인한다(§4).
4. **재시도 멱등성**(FR-PRC-006)이 공짜다. 가중치 해시·torch 버전·연산 결정론을 관리할 필요가 없다.

### 언제 다시 볼 것인가

샘플 클립 3번(디졸브)에서 놓침이 실무적으로 문제가 되면 TransNetV2 를 `SceneDetector` Protocol
(`scene_detection/detector.py`) 뒤에 두 번째 구현체로 붙인다. 상위 코드는 바뀌지 않는다.
그때는 가중치 해시를 `version_id` 에 포함시켜야 한다.

세 후보의 정확도 순위는 논문·벤치마크에 있지만 **뉴스 도메인 수치는 우리 Gold Set 으로만 말할 수 있다.**
근거 없는 숫자를 이 문서에 옮겨 적지 않는다(FRD §15 원칙).

### 디코드 백엔드를 PyAV 로 고정한 이유

PySceneDetect 는 OpenCV 와 PyAV 백엔드를 모두 지원하고 기본값은 환경에 따라 갈린다.

- PyAV 휠에 ffmpeg 이 번들되어 있어 **시스템 ffmpeg 설치가 필요 없다.**
- 백엔드가 환경마다 다르면 같은 파일에서 프레임 수와 타임스탬프가 달라질 수 있다. 멱등성이 깨진다.

`scenedetect` 는 PyAV 만 쓰더라도 임포트 시점에 `cv2` 를 요구한다. 그래서 `scenedetect[opencv-headless]`
로 설치한다. `opencv-python`(GUI 포함)이 아니라 headless 여야 컨테이너에서 `libGL.so` 로 죽지 않는다.

## 2. 설정과 버전 (FR-PRC-015)

정본: `src/npick_worker/config/scene_detection.v1.toml`. **임계값을 코드에 두지 않는다.**

| 키 | 의미 | 비고 |
| --- | --- | --- |
| `schema` | 설정 스키마 이름 | `version_id` 앞부분 |
| `detector` | `content` \| `adaptive` | `adaptive` 는 핸드헬드·빠른 팬 오탐이 적다 |
| `min_scene_len_ms` | 이보다 짧은 scene 을 만들지 않는다 | 플래시·1~2프레임 튐 흡수 |
| `downscale` | 분석 해상도 배율. `1` = 원본 | `auto` 를 쓰지 않는다 — 해상도마다 값이 달라지면 클립 간 비교가 깨진다 |
| `frame_skip` | 건너뛸 프레임 수 | `0` 이 아니면 경계가 그 배수로 거칠어진다 |
| `content.threshold` | HSV 차이 임계값 | 스케일이 `adaptive` 와 다르다 |
| `content.luma_only` | 색 무시, 밝기만 | 흑백 아카이브용 |
| `adaptive.adaptive_threshold` | 이동 평균 대비 비율 | `content.threshold` 와 단위가 다르다 |
| `adaptive.min_content_val` | 이 값 미만은 컷 후보에서 제외 | |
| `adaptive.window_width` | 이동 평균 창 크기 | |

`SceneDetectionConfig.version_id` = `<schema>:<정규화 JSON 의 sha256 앞 8자리>`.
선택되지 않은 detector 의 파라미터까지 해시에 넣는다 — 파일 하나가 통째로 설정 단위이고,
detector 를 바꾸면 당연히 다른 version 이어야 하기 때문이다.

이 값은 **scene detection 단계의 몫**이다. FRD `pipeline_run.pipeline_version` 은 파이프라인 전체
값이므로 여러 단계의 `version_id` 를 묶는 일은 S15P21A501-70 에서 한다.

설정을 바꾸려면 이 파일을 `v2` 로 복사하고 아래 §4 에 근거를 남긴다. v1 을 그 자리에서 고치면
과거 결과의 `version_id` 가 무엇을 뜻했는지 알 수 없게 된다.

## 3. Gate B 로 미룬 항목

전부 `scene_detection.v1.toml` 의 잠정값이며 개발셋 측정 전에는 근거가 없다.

| 항목 | 현재 잠정값 | 동결에 필요한 것 |
| --- | --- | --- |
| `content.threshold` | 27.0 (라이브러리 기본값) | 뉴스 클립 Gold Set 의 컷 시각 대비 precision/recall |
| `min_scene_len_ms` | 1000 | 검색 단위로서 유의미한 최소 길이. 너무 길면 짧은 인서트 컷이 사라진다 |
| `detector` 선택 | `content` | 샘플 6번(핸드헬드)에서 `adaptive` 와 비교 |
| `downscale` | 1 | 처리 시간 대비 정확도 손실 측정 (FRD `NFR-PERF-003`) |

참고 실측: 색만 다른 합성 프레임의 `content_val` 은 20 근처로 기본 임계값 27 을 넘지 못한다.
구조까지 바뀌면 70~90 이 나온다. 테스트 픽스처가 단색이 아니라 줄무늬·노이즈 패널을 쓰는 이유다
(`tests/conftest.py`).

## 4. 샘플 확인 결과

> 아직 샘플 뉴스 클립을 확보하지 못했다. 클립이 들어오면 아래 표를 채운다.
> 필요한 클립 종류와 메타데이터 형식은 [`samples/README.md`](../samples/README.md) 참고.

| 클립 | expected_cut_count | 산출 scene 수 | 과분할 지점 | 미분할 지점 | detector / threshold |
| --- | --- | --- | --- | --- | --- |
| | | | | | |

확인 절차:

```bash
uv run --directory ai python -m npick_worker.scene_detection.report \
    samples/01-standard-report.mp4 --out samples/out/01
```

`samples/out/01/scene-*.png` 이 실제 컷 지점인지 눈으로 보고, `scenes.json` 의 scene 수를
`manifest.json` 의 `expected_cut_count` 와 비교한다. 2번 클립(앵커 고정 샷)은 scene 이 정확히
1개여야 한다 — 쪼개지면 `content.threshold` 가 낮은 것이다.
