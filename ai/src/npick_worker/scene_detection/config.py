"""scene detection 설정과 그 버전(FR-PRC-015).

임계값을 코드에 두지 않는다. 값은 전부 `config/scene_detection.v*.toml` 에 있고,
그 값들의 해시가 `version_id` 가 된다. 재처리 결과를 비교할 때 "어떤 설정으로
나온 scene 인가" 를 이 문자열 하나로 판정할 수 있어야 한다(FRD §5.3).
"""

import hashlib
import json
import re
import tomllib
from functools import lru_cache
from pathlib import Path
from typing import Any, Final, Literal

from pydantic import BaseModel, ConfigDict, Field

DetectorName = Literal["content", "adaptive"]

#: 패키지에 동봉된 기본 설정. 휠에 포함되도록 src/npick_worker/config/ 아래 둔다.
DEFAULT_CONFIG_PATH: Final[Path] = (
    Path(__file__).resolve().parent.parent / "config" / "scene_detection.v1.toml"
)

#: version_id 뒤에 붙는 해시 길이. 충돌 확률보다 로그 가독성을 우선한 값이다.
_HASH_LENGTH: Final[int] = 8

_VERSIONED_CONFIG_NAME: Final[re.Pattern[str]] = re.compile(
    r"scene_detection\.v(?P<version>\d+)\.toml"
)


class _Frozen(BaseModel):
    # extra="forbid": toml 키 오타가 조용히 무시되면 "설정을 바꿨는데 결과가 같다"
    # 는 최악의 상황이 된다. version_id 는 바뀌는데 동작은 그대로이기 때문이다.
    model_config = ConfigDict(frozen=True, extra="forbid")


class ContentDetectorParams(_Frozen):
    """`detector = "content"` 일 때 쓰인다."""

    threshold: float = Field(gt=0)
    luma_only: bool


class AdaptiveDetectorParams(_Frozen):
    """`detector = "adaptive"` 일 때 쓰인다. threshold 와 스케일이 다르다."""

    adaptive_threshold: float = Field(gt=0)
    min_content_val: float = Field(ge=0)
    window_width: int = Field(ge=1)


class SceneDetectionConfig(_Frozen):
    """toml 파일과 1:1 대응한다. 필드를 늘리면 version_id 가 바뀐다."""

    schema_: str = Field(alias="schema")
    detector: DetectorName
    min_scene_len_ms: int = Field(ge=0)
    downscale: int = Field(ge=1)
    frame_skip: int = Field(ge=0)
    content: ContentDetectorParams
    adaptive: AdaptiveDetectorParams

    @property
    def version_id(self) -> str:
        """`<schema>:<해시8>`.

        선택되지 않은 detector 의 파라미터까지 해시에 넣는다. 파일 하나가 통째로
        설정 단위이고, detector 를 바꾸면 당연히 다른 version 이어야 하기 때문이다.

        이 값은 scene detection **단계의 몫**이다. FRD `pipeline_run.pipeline_version`
        은 파이프라인 전체 값이므로, 여러 단계의 version_id 를 묶는 일은
        S15P21A501-70 에서 한다.
        """
        payload = self.model_dump(by_alias=True, mode="json")
        # sort_keys + 고정 separators: 같은 값이면 항상 같은 바이트열이어야 한다.
        canonical = json.dumps(payload, sort_keys=True, separators=(",", ":"), ensure_ascii=False)
        digest = hashlib.sha256(canonical.encode("utf-8")).hexdigest()
        return f"{self.schema_}:{digest[:_HASH_LENGTH]}"


def load_config(path: Path | None = None) -> SceneDetectionConfig:
    """toml 을 읽어 설정을 만든다. `path` 를 주면 임계값 실험에 쓸 수 있다."""
    target = path if path is not None else DEFAULT_CONFIG_PATH
    raw: dict[str, Any] = tomllib.loads(target.read_text(encoding="utf-8"))
    config = SceneDetectionConfig.model_validate(raw)
    match = _VERSIONED_CONFIG_NAME.fullmatch(target.name)
    if match is not None:
        expected_schema = f"scene-detect/v{match.group('version')}"
        if config.schema_ != expected_schema:
            msg = (
                f"설정 파일 버전과 schema가 일치하지 않는다: "
                f"{target.name}에는 schema = {expected_schema!r}가 필요하다"
            )
            raise ValueError(msg)
    return config


@lru_cache(maxsize=1)
def get_default_config() -> SceneDetectionConfig:
    """동봉 기본 설정. 프로세스 수명 동안 캐시한다."""
    return load_config()
