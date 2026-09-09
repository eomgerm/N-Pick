"""FRD §5.1 1단계 `scene_detection` (치명).

clip 을 `[start_time_ms, end_time_ms)` scene 목록으로 나눈다(FR-PRC-010).
임계값은 전부 버전이 붙은 설정에 있다(FR-PRC-015).

이 모듈은 순수 함수만 제공한다. pipeline run 배선·작업 수신·HTTP 표면은
S15P21A501-70 의 몫이다.
"""

from pathlib import Path

from npick_worker.media_errors import MediaUnreadableError
from npick_worker.scene_detection.config import (
    DEFAULT_CONFIG_PATH,
    AdaptiveDetectorParams,
    ContentDetectorParams,
    SceneDetectionConfig,
    get_default_config,
    load_config,
)
from npick_worker.scene_detection.detector import RawDetection, SceneDetector
from npick_worker.scene_detection.models import Scene, SceneDetectionResult
from npick_worker.scene_detection.pyscenedetect_backend import PySceneDetectDetector

# ms 변환 규칙은 `npick_worker.timecode` 하나다. frame_extraction 이 같은 규칙으로
# ms 를 프레임 번호로 되돌리므로 여기 사본을 두면 두 단계가 조용히 갈라진다.
# 기존 호출부(report.py·테스트)를 위해 이름은 계속 이 패키지에서 노출한다.
from npick_worker.timecode import frames_to_ms

__all__ = [
    "DEFAULT_CONFIG_PATH",
    "AdaptiveDetectorParams",
    "ContentDetectorParams",
    "PySceneDetectDetector",
    "RawDetection",
    "Scene",
    "SceneDetectionConfig",
    "SceneDetectionResult",
    "SceneDetector",
    "detect_scenes",
    "frames_to_ms",
    "get_default_config",
    "load_config",
]


def _to_scenes(detection: RawDetection, min_scene_len_ms: int) -> tuple[Scene, ...]:
    """경계 목록을 `[start,end)` scene 으로 바꾼다.

    detector 가 무엇이든 여기서 불변식을 강제한다.
    - 첫 scene 은 0 에서 시작하고 마지막 scene 은 duration 에서 끝난다
    - 인접 scene 은 붙어 있다: `scenes[i].end_time_ms == scenes[i+1].start_time_ms`
    - scene 은 최소 1개다 (FR-PRC-010)
    """
    duration_ms = detection.duration_ms
    if duration_ms <= 0:
        msg = f"detector가 유효하지 않은 영상 길이를 반환했다: duration_ms={duration_ms}"
        raise MediaUnreadableError(msg)
    # 0 을 강제로 넣고 중복·역순·범위 밖을 걷어낸다. detector 를 믿지 않는다.
    starts = sorted({0, *(b for b in detection.boundaries_ms if 0 < b < duration_ms)})

    # 가장 가까운 정수 ms 로 반올림하면서 서로 다른 프레임 경계가 같은 ms 로 뭉갤 수 있다.
    # 그때 생기는
    # 길이 0 scene 과, 설정보다 짧은 꼬리 scene 을 직전 scene 에 흡수시킨다.
    kept: list[int] = [0]
    for start in starts[1:]:
        if start - kept[-1] >= min_scene_len_ms:
            kept.append(start)
    if len(kept) > 1 and duration_ms - kept[-1] < min_scene_len_ms:
        kept.pop()

    ends = [*kept[1:], duration_ms]
    return tuple(
        Scene(scene_index=i, start_time_ms=start, end_time_ms=end)
        for i, (start, end) in enumerate(zip(kept, ends, strict=True))
    )


def detect_scenes(
    video_path: Path,
    cfg: SceneDetectionConfig | None = None,
    detector: SceneDetector | None = None,
) -> SceneDetectionResult:
    """영상 하나를 scene 목록으로 나눈다.

    재현성 식별자는 `(config_version, engine, engine_version)` 튜플이다. 같은
    `video_path` 와 같은 식별자면 항상 같은 결과를 돌려준다. 재시도가 산출물의
    의미를 바꾸지 않아야 한다는 FR-PRC-006 의 전제다.
    """
    config = cfg if cfg is not None else get_default_config()
    engine = detector if detector is not None else PySceneDetectDetector()

    detection = engine.detect(video_path, config)
    return SceneDetectionResult(
        scenes=_to_scenes(detection, config.min_scene_len_ms),
        config_version=config.version_id,
        detector=config.detector,
        engine=engine.name,
        engine_version=engine.version,
        duration_ms=detection.duration_ms,
        frame_rate=detection.frame_rate,
    )
