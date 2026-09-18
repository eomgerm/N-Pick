"""프롬프트·호출 파라미터·어휘와 그 버전 (FRD §7.2 기록, §11 실측 후 확정).

`ocr/config.py` 와 같은 규약이다. 임계값을 코드에 두지 않는다. 값은 전부
`config/vlm_metadata.v*.toml` 에 있고 그 해시가 `version_id` 가 된다.

**버전이 둘인 이유.** `config_version`(이 파일의 `version_id`)은 파일 전체의 해시이고
`prompt_version`(`prompt.py`)은 프롬프트가 실제로 무엇이 됐는지의 해시다. 둘을 나누는
것은 §7.2 기록에서 물을 수 있는 질문이 다르기 때문이다 — "설정이 바뀌었나" 와 "모델에게
한 말이 바뀌었나" 는 다른 질문이고, `max_keyframes_per_scene` 만 고친 실행과 프롬프트
문구를 고친 실행을 구분할 수 있어야 한다.

`prompt_version` 이 **여기 없는** 이유는 그 값의 입력이 템플릿만이 아니기 때문이다.
템플릿은 `{scene_types}` 같은 자리만 갖고 있고 실제 목록은 렌더링할 때 채워지므로,
템플릿만 해시하면 어휘를 바꿔도 값이 그대로다 — 모델에게 한 말은 달라졌는데 기록은
같다고 말하는 셈이다. 그래서 해시를 만드는 쪽은 무엇이 채워지는지 아는 `prompt.py` 다.
"""

import re
import tomllib
from functools import lru_cache
from pathlib import Path
from typing import Any, Final

from pydantic import BaseModel, ConfigDict, Field, field_validator

from npick_worker.versioning import version_id

#: 패키지에 동봉된 기본 설정. 휠에 포함되도록 src/npick_worker/config/ 아래 둔다.
DEFAULT_CONFIG_PATH: Final[Path] = (
    Path(__file__).resolve().parent.parent / "config" / "vlm_metadata.v2.toml"
)

_VERSIONED_CONFIG_NAME: Final[re.Pattern[str]] = re.compile(
    r"vlm_metadata\.v(?P<version>\d+)\.toml"
)

#: 설정 schema 이름의 모양. 앞부분이 `prompt_version` 과 갈려야 한다 — 두 값이 로그에
#: 나란히 찍히는데 접두가 같으면 사람이 반드시 헷갈린다(`jobs/versions.py` 의 같은 판단).
_CONFIG_SCHEMA: Final[str] = "vlm-metadata-config/v{version}"
_SCHEMA_VERSION_PART: Final[re.Pattern[str]] = re.compile(
    r"^vlm-metadata-config/v(?P<version>\d+)$"
)


class _Frozen(BaseModel):
    # extra="forbid": toml 키 오타가 조용히 무시되면 config_version 은 바뀌는데 동작은
    # 그대로인 상황이 된다. 재현성 기록이 거짓이 되는 경로다.
    model_config = ConfigDict(frozen=True, extra="forbid")


class CallParams(_Frozen):
    """모델 호출 파라미터. 전부 실측 후 확정 잠정값이다."""

    #: 0.0 이 기본이다. 재현성 때문이지 품질 때문이 아니다(toml 주석).
    temperature: float = Field(ge=0.0, le=2.0)
    max_output_tokens: int = Field(gt=0)
    timeout_seconds: float = Field(gt=0)
    #: 사고 과정을 쓰게 둘지. **기본은 끔이다** (S15P21A501-214).
    #:
    #: 이 축이 여기 있는 이유는 `config_version` 이다. 채팅 템플릿의 기본값은 모델마다
    #: 다르고 Qwen3.5 계열은 켜져 있다. 켜진 채로 나가면 JSON 앞에 사고 과정이 붙어
    #: 첫 글자에서 `VLM_SCHEMA_INVALID` 가 되는데, 이 단계는 fatal 이 아니라 run 은
    #: 성공으로 끝난다 — 캡션과 임베딩만 통째로 빈다. 값을 식별자 밖에 두면 같은
    #: `stage_version` 이 서로 다른 출력을 가리키게 된다.
    enable_thinking: bool = False


class PromptTemplates(_Frozen):
    """프롬프트 템플릿. 자리 표시자는 `prompt.py` 가 채운다."""

    system: str = Field(min_length=1)
    user: str = Field(min_length=1)


class VlmMetadataConfig(_Frozen):
    """toml 과 1:1 대응한다. 필드를 늘리면 `config_version` 이 바뀐다."""

    schema_: str = Field(alias="schema")

    max_keyframes_per_scene: int = Field(gt=0)
    max_tag_candidates_per_scene: int = Field(gt=0)
    caption_max_chars: int = Field(gt=0)
    # 0은 제한 없음. 운영 상한은 실제 샘플 측정으로 정한다.
    max_ocr_chars: int = Field(default=0, ge=0)
    max_transcript_chars: int = Field(default=0, ge=0)
    #: 장면 유형의 닫힌 어휘 초안. 검증은 `validator.py` 가 이 목록으로 한다.
    scene_type_vocabulary: tuple[str, ...] = Field(min_length=1)

    call: CallParams
    prompt: PromptTemplates

    @field_validator("scene_type_vocabulary")
    @classmethod
    def _vocabulary_is_a_set(cls, value: tuple[str, ...]) -> tuple[str, ...]:
        """빈 값과 중복을 막는다.

        중복이 있으면 프롬프트에 같은 값이 두 번 나가고, 어느 쪽을 지웠는지 모르는 채로
        어휘가 바뀐 것처럼 `prompt_version` 만 달라진다.
        """
        if any(not item.strip() for item in value):
            msg = "scene_type_vocabulary 에 빈 값이 있다"
            raise ValueError(msg)
        if len(set(value)) != len(value):
            msg = "scene_type_vocabulary 에 같은 값이 두 번 있다"
            raise ValueError(msg)
        return value

    @property
    def version_id(self) -> str:
        """`<schema>:<해시8>`. 파일 전체의 해시다."""
        payload = self.model_dump(by_alias=True, mode="json")
        if self.version_number == "1":
            if self.max_ocr_chars == 0:
                payload.pop("max_ocr_chars")
            if self.max_transcript_chars == 0:
                payload.pop("max_transcript_chars")
            # v1 은 이전 모델 비교의 재현용이라 해시가 바뀌면 그 목적이 사라진다.
            # 뒤에 더한 키는 v1 의 payload 에서 뺀다 — 위 두 줄과 같은 처리다.
            payload["call"].pop("enable_thinking", None)
        return version_id(self.schema_, payload)

    @property
    def version_number(self) -> str:
        """`schema` 문자열에서 버전 숫자를 꺼낸다.

        `prompt.py` 가 프롬프트 schema 이름(`vlm-metadata-prompt/v<n>`)을 만들 때 쓴다.
        그쪽에 숫자를 따로 적지 않는 이유는 두 값이 갈라지는 것을 막기 위해서다 —
        설정 파일을 v2 로 복사하면 프롬프트 버전도 자동으로 v2 가 된다.
        """
        match = _SCHEMA_VERSION_PART.fullmatch(self.schema_)
        if match is None:  # load_config 가 먼저 막지만 직접 만든 설정은 여기로 온다
            msg = f"설정 schema 의 모양이 다르다: {self.schema_!r}"
            raise ValueError(msg)
        return match.group("version")


def load_config(path: Path | None = None) -> VlmMetadataConfig:
    """toml 을 읽어 설정을 만든다. `path` 를 주면 프롬프트·어휘 실험에 쓸 수 있다."""
    target = path if path is not None else DEFAULT_CONFIG_PATH
    raw: dict[str, Any] = tomllib.loads(target.read_text(encoding="utf-8"))
    config = VlmMetadataConfig.model_validate(raw)
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
def get_default_config() -> VlmMetadataConfig:
    """동봉 기본 설정. 프로세스 수명 동안 캐시한다."""
    return load_config()
