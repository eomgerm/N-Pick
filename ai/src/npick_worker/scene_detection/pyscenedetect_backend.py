"""PySceneDetect 기반 detector.

디코드 백엔드를 **PyAV 로 고정**한다. 이유가 두 가지다.
1. PyAV 휠에 ffmpeg 이 번들되어 있어 시스템 ffmpeg 설치가 필요 없다.
2. 백엔드가 환경에 따라 OpenCV/PyAV 로 갈리면 같은 파일에서 프레임 수와
   타임스탬프가 달라질 수 있다. 재시도 멱등성(FR-PRC-006)이 깨진다.
"""

import math
from pathlib import Path

import scenedetect
from scenedetect import SceneDetector as _LibDetector
from scenedetect import SceneManager
from scenedetect.backends.pyav import VideoStreamAv
from scenedetect.detectors import AdaptiveDetector, ContentDetector

from npick_worker.scene_detection.config import SceneDetectionConfig
from npick_worker.scene_detection.detector import RawDetection
from npick_worker.timecode import frames_to_ms


def _min_scene_len_frames(min_scene_len_ms: int, frame_rate: float) -> int:
    """ms 기준 최소 길이를 PySceneDetect 가 쓰는 프레임 수로 바꾼다.

    올림한다. 내림하면 설정보다 짧은 scene 이 통과해 `min_scene_len_ms` 가
    보장이 아니라 권고가 되어버린다.
    """
    return max(1, math.ceil(min_scene_len_ms * frame_rate / 1000))


def _build_detector(cfg: SceneDetectionConfig, min_scene_len: int) -> _LibDetector:
    if cfg.detector == "content":
        return ContentDetector(
            threshold=cfg.content.threshold,
            min_scene_len=min_scene_len,
            luma_only=cfg.content.luma_only,
        )
    return AdaptiveDetector(
        adaptive_threshold=cfg.adaptive.adaptive_threshold,
        min_scene_len=min_scene_len,
        window_width=cfg.adaptive.window_width,
        min_content_val=cfg.adaptive.min_content_val,
    )


class PySceneDetectDetector:
    """`SceneDetector` Protocol 구현체."""

    @property
    def name(self) -> str:
        return "pyscenedetect"

    @property
    def version(self) -> str:
        """설치된 scenedetect 버전. 하드코딩하면 휠과 조용히 어긋난다.

        배포판이 `scenedetect` 와 `scenedetect-headless` 로 갈리므로 임포트 이름
        (`scenedetect.__version__`)에서 읽는다. 배포판 이름으로 조회하면 headless
        변종에서 PackageNotFoundError 가 난다.
        """
        return str(scenedetect.__version__)

    def detect(self, video_path: Path, cfg: SceneDetectionConfig) -> RawDetection:
        video = VideoStreamAv(str(video_path))
        # 0.7 부터 frame_rate 는 Fraction 이다. 경계에서 float 로 내려 이 함수 밖으로는
        # 라이브러리 타입이 새지 않게 한다 (JSON 직렬화도 Fraction 을 못 받는다).
        frame_rate = float(video.frame_rate)
        if frame_rate <= 0:
            msg = f"프레임레이트를 읽을 수 없다: {video_path}"
            raise ValueError(msg)

        total_frames = video.duration.frame_num if video.duration is not None else 0
        if total_frames <= 0:
            msg = f"프레임이 없는 영상이다: {video_path}"
            raise ValueError(msg)

        manager = SceneManager()
        # auto_downscale 은 해상도에 따라 배율이 달라진다. 설정값을 그대로 쓴다.
        manager.auto_downscale = False
        manager.downscale = cfg.downscale
        manager.add_detector(
            _build_detector(cfg, _min_scene_len_frames(cfg.min_scene_len_ms, frame_rate))
        )
        manager.detect_scenes(video=video, frame_skip=cfg.frame_skip, show_progress=False)

        # start_in_scene=True: 컷이 하나도 없어도 영상 전체를 덮는 구간 1개를 돌려준다.
        # FR-PRC-010 의 "한 개 이상" 하한이 여기서 보장된다.
        spans = manager.get_scene_list(start_in_scene=True)
        boundaries = tuple(frames_to_ms(start.frame_num, frame_rate) for start, _ in spans)

        return RawDetection(
            boundaries_ms=boundaries,
            duration_ms=frames_to_ms(total_frames, frame_rate),
            frame_rate=frame_rate,
        )
