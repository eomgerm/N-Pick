"""디코딩·VAD 파라미터와 그 버전 (FRD §7.2 기록, §11 실측 후 확정).

`ocr/config.py`·`vlm_metadata/config.py` 와 같은 규약이다. 임계값을 코드에 두지
않는다. 값은 전부 `config/asr.v*.toml` 에 있고 그 해시가 `version_id` 가 된다.

**모델 이름이 여기 없는 이유.** 어느 가중치를 쓸지는 배포마다 다르고 실측 후 확정
대상이라 `settings.asr_model` 에 있다(`vlm_model` 과 같은 판단 — 코드가 임의로 고르면
그게 곧 근거 없는 동결이다). 그 값은 사라지지 않고 `model_version` 으로 재현 튜플에
들어간다. 이 파일이 담는 것은 "같은 모델을 어떻게 돌렸는가" 다.
"""

import re
import tomllib
from functools import lru_cache
from pathlib import Path
from typing import Any, Final

from pydantic import BaseModel, ConfigDict, Field

from npick_worker.versioning import version_id

#: 패키지에 동봉된 기본 설정. 휠에 포함되도록 src/npick_worker/config/ 아래 둔다.
DEFAULT_CONFIG_PATH: Final[Path] = Path(__file__).resolve().parent.parent / "config" / "asr.v1.toml"

_VERSIONED_CONFIG_NAME: Final[re.Pattern[str]] = re.compile(r"asr\.v(?P<version>\d+)\.toml")

#: 설정 schema 이름의 모양. 단계 버전 schema(`npick.stage.asr/v1`)와 접두를 갈라 둔다 —
#: 두 값이 로그에 나란히 찍히는데 접두가 같으면 사람이 반드시 헷갈린다
#: (`jobs/versions.py` 의 같은 판단).
_CONFIG_SCHEMA: Final[str] = "asr-config/v{version}"


class _Frozen(BaseModel):
    # extra="forbid": toml 키 오타가 조용히 무시되면 config_version 은 바뀌는데 동작은
    # 그대로인 상황이 된다. 재현성 기록이 거짓이 되는 경로다.
    model_config = ConfigDict(frozen=True, extra="forbid")


class DecodeParams(_Frozen):
    """디코딩 파라미터. `temperature`·`condition_on_previous_text` 외에는 실측 전 잠정값이다."""

    temperature: float = Field(ge=0.0, le=2.0)
    beam_size: int = Field(gt=0)
    condition_on_previous_text: bool
    word_timestamps: bool
    no_speech_threshold: float = Field(gt=0.0, le=1.0)
    #: 평균 logprob 의 하한. 로그 확률이므로 음수다.
    log_prob_threshold: float = Field(lt=0.0)
    compression_ratio_threshold: float = Field(gt=0.0)


class VadParams(_Frozen):
    """VAD 파라미터. 전부 실측 전 잠정값이다(docs/asr.md §5)."""

    enabled: bool
    threshold: float = Field(gt=0.0, le=1.0)
    min_speech_duration_ms: int = Field(ge=0)
    min_silence_duration_ms: int = Field(ge=0)
    speech_pad_ms: int = Field(ge=0)
    max_speech_duration_s: float = Field(gt=0.0)


class AsrConfig(_Frozen):
    """toml 과 1:1 대응한다. 필드를 늘리면 `config_version` 이 바뀐다."""

    schema_: str = Field(alias="schema")

    #: 인식 언어. 자동 감지를 쓰지 않는다 — 무음 구간에서 언어가 튀면 환각이 는다.
    language: str = Field(min_length=1)
    task: str = Field(min_length=1)

    decode: DecodeParams
    vad: VadParams

    @property
    def version_id(self) -> str:
        """`<schema>:<해시8>`. 파일 전체의 해시다."""
        return version_id(self.schema_, self.model_dump(by_alias=True, mode="json"))


def load_config(path: Path | None = None) -> AsrConfig:
    """toml 을 읽어 설정을 만든다.

    `path` 를 주면 실측 비교에 쓸 수 있다 — VAD on/off·임계값 조합을 별도 파일로 두고
    `report.py` 로 돌리는 것이 docs/asr.md §5 의 비교 방법이다. 동봉 기본 설정을 고쳐
    가며 재면 어느 값으로 잰 표인지 나중에 알 수 없다.
    """
    target = path if path is not None else DEFAULT_CONFIG_PATH
    raw: dict[str, Any] = tomllib.loads(target.read_text(encoding="utf-8"))
    config = AsrConfig.model_validate(raw)
    match = _VERSIONED_CONFIG_NAME.fullmatch(target.name)
    if match is not None:
        expected_schema = _CONFIG_SCHEMA.format(version=match.group("version"))
        if config.schema_ != expected_schema:
            msg = (
                f"설정 파일 버전과 schema가 일치하지 않는다: "
                f"{target.name}에는 schema = {expected_schema!r}가 필요하다"
            )
            raise ValueError(msg)
    return config


@lru_cache(maxsize=1)
def get_default_config() -> AsrConfig:
    """동봉 기본 설정. 프로세스 수명 동안 캐시한다."""
    return load_config()
