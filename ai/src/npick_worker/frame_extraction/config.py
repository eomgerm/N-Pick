"""frame extraction 설정과 그 버전.

`scene_detection/config.py` 와 같은 규약이다. 임계값을 코드에 두지 않는다. 값은 전부
`config/frame_extraction.v*.toml` 에 있고, 그 값들의 해시가 `version_id` 가 된다.
재처리 결과를 비교할 때 "어떤 설정으로 고른 keyframe 인가" 를 이 문자열 하나로
판정할 수 있어야 한다.
"""

import re
import tomllib
from functools import lru_cache
from pathlib import Path
from typing import Any, Final

from pydantic import BaseModel, ConfigDict, Field, model_validator

from npick_worker.versioning import version_id

#: 패키지에 동봉된 기본 설정. 휠에 포함되도록 src/npick_worker/config/ 아래 둔다.
DEFAULT_CONFIG_PATH: Final[Path] = (
    Path(__file__).resolve().parent.parent / "config" / "frame_extraction.v1.toml"
)

_VERSIONED_CONFIG_NAME: Final[re.Pattern[str]] = re.compile(
    r"frame_extraction\.v(?P<version>\d+)\.toml"
)

#: ffmpeg mjpeg qscale 의 유효 범위. 2 가 최고 품질이다.
MIN_JPEG_QSCALE: Final[int] = 2
MAX_JPEG_QSCALE: Final[int] = 31


class FrameExtractionConfig(BaseModel):
    """toml 파일과 1:1 대응한다. 필드를 늘리면 version_id 가 바뀐다."""

    # extra="forbid": toml 키 오타가 조용히 무시되면 "설정을 바꿨는데 결과가 같다" 는
    # 최악의 상황이 된다. version_id 는 바뀌는데 동작은 그대로이기 때문이다.
    model_config = ConfigDict(frozen=True, extra="forbid")

    schema_: str = Field(alias="schema")
    interval_ms: int = Field(gt=0)
    #: FRD F-03 의 "복수 키프레임" 이 하한 2 의 근거다. 1 을 허용하지 않는다.
    min_keyframes_per_scene: int = Field(ge=2)
    max_keyframes_per_scene: int = Field(ge=2)
    edge_margin_ms: int = Field(ge=0)
    candidates_per_slot: int = Field(ge=1)
    candidate_step_ms: int = Field(gt=0)
    score_stride: int = Field(ge=1)
    min_luma_std: float = Field(ge=0)
    jpeg_qscale: int = Field(ge=MIN_JPEG_QSCALE, le=MAX_JPEG_QSCALE)

    @model_validator(mode="after")
    def _bounds_are_ordered(self) -> "FrameExtractionConfig":
        """상한이 하한보다 작으면 clamp 가 하한을 조용히 이긴다.

        그 상태에서도 코드는 돌지만 실제 장 수가 `max` 도 `min` 도 아닌 값이 되고,
        설정 파일을 읽은 사람은 그것을 예측할 수 없다.
        """
        if self.max_keyframes_per_scene < self.min_keyframes_per_scene:
            msg = (
                "max_keyframes_per_scene 가 min_keyframes_per_scene 보다 작다: "
                f"{self.max_keyframes_per_scene} < {self.min_keyframes_per_scene}"
            )
            raise ValueError(msg)
        return self

    @property
    def version_id(self) -> str:
        """`<schema>:<해시8>`.

        이 값은 frame extraction **단계의 몫**이다. 단계 하나의 재현 식별자를
        `stageVersion` 으로 묶는 일은 `npick_worker.jobs.versions` 가, 파이프라인 전체의
        `pipeline_run.pipeline_version` 은 BE 가 한다(`docs/contracts/job-api.md`).
        """
        return version_id(self.schema_, self.model_dump(by_alias=True, mode="json"))


def load_config(path: Path | None = None) -> FrameExtractionConfig:
    """toml 을 읽어 설정을 만든다. `path` 를 주면 임계값 실험에 쓸 수 있다."""
    target = path if path is not None else DEFAULT_CONFIG_PATH
    raw: dict[str, Any] = tomllib.loads(target.read_text(encoding="utf-8"))
    config = FrameExtractionConfig.model_validate(raw)
    match = _VERSIONED_CONFIG_NAME.fullmatch(target.name)
    if match is not None:
        expected_schema = f"frame-extract/v{match.group('version')}"
        if config.schema_ != expected_schema:
            msg = (
                f"설정 파일 버전과 schema가 일치하지 않는다: "
                f"{target.name}에는 schema = {expected_schema!r}가 필요하다"
            )
            raise ValueError(msg)
    return config


@lru_cache(maxsize=1)
def get_default_config() -> FrameExtractionConfig:
    """동봉 기본 설정. 프로세스 수명 동안 캐시한다."""
    return load_config()
