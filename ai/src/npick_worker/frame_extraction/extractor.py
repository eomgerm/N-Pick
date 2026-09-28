"""프레임 추출 경계.

`scene_detection/detector.py` 와 같은 취지다 — 호출부에 `av` 심볼을 노출하지 않는다.
나중에 GPU 디코드(NVDEC)나 다른 인코더로 바꿀 때 이 Protocol 만 만족시키면 되고,
상위 코드는 바뀌지 않는다.
"""

from collections.abc import Mapping, Sequence
from dataclasses import dataclass
from pathlib import Path
from typing import Protocol

from npick_worker.frame_extraction.config import FrameExtractionConfig
from npick_worker.frame_extraction.selector import SceneMeasurement, SlotCandidates


@dataclass(frozen=True, slots=True)
class MediaProfile:
    """영상의 디코드 성질. 결과에 그대로 실려 재현 근거가 된다."""

    frame_rate: float
    width: int
    height: int


@dataclass(frozen=True, slots=True)
class WrittenImage:
    """디스크에 쓴 이미지 한 장."""

    path: Path
    byte_size: int
    content_sha256: str


@dataclass(frozen=True, slots=True)
class SceneRequest:
    """scene 하나에서 볼 후보. 프레임 번호로 표현한다.

    ms 가 아니라 프레임 번호인 이유는 중복 제거의 단위가 프레임이기 때문이다. 서로
    다른 두 ms 가 같은 프레임을 가리키면 같은 화면을 두 번 저장하게 된다.
    """

    scene_index: int
    slots: tuple[SlotCandidates, ...]


class FrameGrabber(Protocol):
    """영상에서 후보를 재고, 고른 프레임을 이미지 파일로 쓴다."""

    @property
    def name(self) -> str: ...

    @property
    def version(self) -> str:
        """결과를 바꿀 수 있는 구현들의 버전.

        `config_version` 은 설정만 해시하므로 이 값이 따로 필요하다. 디코더가 바뀌면
        같은 프레임 번호가 다른 픽셀을 줄 수 있고, 점수 계산 구현이 바뀌면 같은
        픽셀에서 다른 프레임이 뽑힌다. 재현성 식별자는
        `(config_version, engine, engine_version)` 튜플이다.
        """
        ...

    def profile(self, video_path: Path) -> MediaProfile: ...

    def measure(
        self, video_path: Path, requests: Sequence[SceneRequest], cfg: FrameExtractionConfig
    ) -> Mapping[int, SceneMeasurement]:
        """후보를 재서 `scene_index → SceneMeasurement` 를 돌려준다.

        재는 것이 둘이다 — 후보 한 장의 성질(선명도·휘도)과 **후보 두 장 사이의 변화량**
        (`content_val`). 후자를 여기서 재는 이유는 그것이 픽셀을 맞대어 보는 일이라
        프레임 자체가 필요하기 때문이다. 그 프레임을 손에 들고 있는 것은 이 구현뿐이다.

        변화량을 값으로 돌려주고 프레임을 돌려주지 않는 것이 중요하다. 픽셀을 밖으로
        내보내면 호출부가 영상 전체의 후보 평면을 쥐게 된다 — 장면 수에 비례해 늘어나는
        메모리이고, 이 단계가 후보 때문에 메모리를 늘리지 않기로 한 판단(`pyav_backend`
        모듈 문서)과 어긋난다.

        선정 규칙은 여기 없다. 이 구현은 재기만 하고 고르는 일은 `selector` 가 한다 —
        그래야 "몇 장을 어디서 뽑고 무엇을 대표로 하는가" 를 영상 없이 검증할 수 있다.
        """
        ...

    def write(
        self,
        video_path: Path,
        targets: Mapping[int, Path],
        cfg: FrameExtractionConfig,
    ) -> Mapping[int, WrittenImage]:
        """`{프레임 번호: 저장 경로}` 를 받아 실제로 쓴다. 원본 해상도를 유지한다."""
        ...
