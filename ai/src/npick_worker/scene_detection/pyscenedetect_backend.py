"""PySceneDetect 기반 detector.

디코드 백엔드를 **PyAV 로 고정**한다. 이유가 두 가지다.
1. PyAV 휠에 ffmpeg 이 번들되어 있어 시스템 ffmpeg 설치가 필요 없다.
2. 백엔드가 환경에 따라 OpenCV/PyAV 로 갈리면 같은 파일에서 프레임 수와
   타임스탬프가 달라질 수 있다. 재시도 멱등성(FR-PRC-006)이 깨진다.
"""

import math
from pathlib import Path

from scenedetect import SceneManager
from scenedetect.backends.pyav import VideoStreamAv
from scenedetect.detectors import AdaptiveDetector, ContentDetector
from scenedetect.scene_detector import SceneDetector as _LibDetector

from npick_worker.scene_detection.config import SceneDetectionConfig
from npick_worker.scene_detection.detector import RawDetection


def frames_to_ms(frame_num: int, frame_rate: float) -> int:
    """프레임 번호를 정수 ms 로 내린다. **ms 변환은 이 함수 하나만 쓴다.**

    부동소수 초를 여기저기서 반올림하면 재실행 간 1ms 가 흔들린다. 규칙을 한 곳에
    모아 "같은 프레임 번호 + 같은 fps = 항상 같은 ms" 를 보장한다.

    한계: PySceneDetect 의 타임코드는 프레임 번호 기반이라 VFR(가변 프레임레이트)
    소스에서는 실제 PTS 와 어긋날 수 있다. 결정론은 유지되지만 정확도가 떨어지므로
    샘플 클립은 CFR 을 쓴다(docs/scene-detection.md).
    """
    return round(frame_num * 1000 / frame_rate)


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

    def detect(self, video_path: Path, cfg: SceneDetectionConfig) -> RawDetection:
        video = VideoStreamAv(str(video_path))
        frame_rate = video.frame_rate
        if frame_rate <= 0:
            msg = f"프레임레이트를 읽을 수 없다: {video_path}"
            raise ValueError(msg)

        total_frames = video.duration.get_frames() if video.duration is not None else 0
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
        boundaries = tuple(frames_to_ms(start.get_frames(), frame_rate) for start, _ in spans)

        return RawDetection(
            boundaries_ms=boundaries,
            duration_ms=frames_to_ms(total_frames, frame_rate),
            frame_rate=frame_rate,
        )
