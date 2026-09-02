"""detector 경계.

`ai/AGENTS.md` 의 adapter 규칙과 같은 취지다 — 호출부에 `scenedetect` 심볼을
노출하지 않는다. 나중에 TransNetV2 같은 모델 기반 detector 를 붙일 때
이 Protocol 만 만족시키면 되고, 상위 코드는 바뀌지 않는다.
"""

from dataclasses import dataclass
from pathlib import Path
from typing import Protocol

from npick_worker.scene_detection.config import SceneDetectionConfig


@dataclass(frozen=True, slots=True)
class RawDetection:
    """detector 의 원시 출력.

    scene 객체가 아니라 **경계 목록**을 돌려받는다. 이렇게 하면 상위에서
    `[start,end)` 연속성을 보장할 여지가 남는다 — 구간 목록을 받으면 detector
    구현마다 겹침·틈이 생길 수 있고 그걸 사후에 고치는 편이 더 위험하다.
    """

    #: 각 scene 이 시작하는 ms. 오름차순이고 첫 값은 항상 0 이다.
    boundaries_ms: tuple[int, ...]
    #: clip 전체 길이. 마지막 scene 의 끝이 된다.
    duration_ms: int
    #: 디코더가 보고한 프레임레이트.
    frame_rate: float


class SceneDetector(Protocol):
    """영상 하나를 경계 목록으로 바꾼다."""

    @property
    def name(self) -> str: ...

    def detect(self, video_path: Path, cfg: SceneDetectionConfig) -> RawDetection: ...
