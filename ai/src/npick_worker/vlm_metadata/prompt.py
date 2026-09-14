"""프롬프트 렌더링, 근거 라벨, 그리고 `prompt_version`.

채우는 값은 전부 **다른 곳이 정본인 것**이다. 어휘를 프롬프트에 손으로 적지 않는 이유는
`query_resolver/prompt.py` 와 같다 — 정본을 고쳤을 때 프롬프트가 그대로면 모델은 계속 옛
어휘를 내고, 그 출력은 전부 schema 검증에서 떨어진다.

| 자리 | 정본 |
| --- | --- |
| `{shot_types}` | `schema.ShotType` |
| `{tag_types}` | `schema.TagCandidateType` **에서 `scene_type` 을 뺀 것** |
| `{scene_types}` | 설정의 `scene_type_vocabulary` |
| `{caption_max_chars}` | 설정의 `caption_max_chars` |
| `{max_tag_candidates}` | 설정의 `max_tag_candidates_per_scene` |
| `{keyframe_count}` | 그 scene 에 실제로 넣은 장 수 |

**`scene_type` 을 태그 유형 목록에서 빼는 이유.** 장면 유형은 전용 필드로 받는다
(`schema.RawSceneType`). 목록에 남겨 두면 모델이 같은 값을 두 곳에 낼 수 있고, 그러면
`validator.py` 가 거부한다 — 지시하지 않은 규칙으로 거부하는 셈이라 불공정하다. 검증이
거부하는 것과 프롬프트가 금지하는 것은 항상 같아야 한다.

**라벨도 여기 있다.** 모델이 근거를 말할 수 있는 유일한 어휘가 라벨이고, 그것을 만드는
쪽과 되돌리는 쪽(`validator.py`)이 같은 규칙을 써야 한다. 두 곳에서 따로 만들면 "근거가
있는데 없다고 거부되는" 실패가 되고, 증상이 schema 오류라 원인을 찾기 어렵다.

**`prompt_version` 이 여기 있는 이유**는 `config.py` 의 모듈 docstring 에 있다 — 요약하면
해시의 입력이 템플릿이 아니라 **렌더링된 결과**여야 하고, 무엇이 채워지는지 아는 쪽이
여기이기 때문이다.
"""

from collections.abc import Sequence
from typing import Final, get_args

from npick_worker.versioning import version_id
from npick_worker.vlm_metadata.config import VlmMetadataConfig
from npick_worker.vlm_metadata.models import KeyframeRef
from npick_worker.vlm_metadata.schema import ShotType, TagCandidateType

#: 라벨 접두. `schema.EVIDENCE_LABEL_PATTERN` 과 같은 모양이어야 한다.
_LABEL_PREFIX: Final[str] = "kf_"

#: 목록을 프롬프트에 적을 때의 구분자.
_VOCABULARY_SEPARATOR: Final[str] = ", "

#: 프롬프트 schema 이름. 버전 숫자는 설정 파일에서 온다(`VlmMetadataConfig.version_number`).
_PROMPT_SCHEMA: Final[str] = "vlm-metadata-prompt/v{version}"

#: 전용 필드로 받는 태그 유형. 프롬프트의 `{tag_types}` 목록에서 빼낸다(모듈 docstring).
_DEDICATED_TAG_TYPE: Final[str] = "scene_type"


def label_for(position: int) -> str:
    """0-base 위치를 근거 라벨로 바꾼다.

    1-base 로 적는 이유는 모델 쪽 사정이다. `kf_0` 은 사람이 쓰는 목록 표기가 아니라
    모델이 `kf_1` 로 고쳐 부르기 쉽고, 그러면 멀쩡한 근거가 미지의 라벨로 거부된다.
    """
    return f"{_LABEL_PREFIX}{position + 1}"


def labels_for(keyframes: Sequence[KeyframeRef]) -> dict[str, KeyframeRef]:
    """모델에 넣는 순서대로 라벨을 붙인다. `validator.py` 가 이 표로 근거를 되돌린다."""
    return {label_for(position): keyframe for position, keyframe in enumerate(keyframes)}


def prompt_tag_types() -> tuple[str, ...]:
    """프롬프트에 적는 태그 유형. 전용 필드가 있는 `scene_type` 은 뺀다."""
    return tuple(name for name in get_args(TagCandidateType) if name != _DEDICATED_TAG_TYPE)


def render_system_prompt(cfg: VlmMetadataConfig) -> str:
    """어휘와 상한을 채운 system prompt."""
    return (
        cfg.prompt.system.replace("{shot_types}", _join(get_args(ShotType)))
        .replace("{tag_types}", _join(prompt_tag_types()))
        .replace("{scene_types}", _join(cfg.scene_type_vocabulary))
        .replace("{caption_max_chars}", str(cfg.caption_max_chars))
        .replace("{max_tag_candidates}", str(cfg.max_tag_candidates_per_scene))
    )


def render_user_prompt(cfg: VlmMetadataConfig, keyframe_count: int) -> str:
    """그 scene 에 실제로 넣은 장 수를 채운 user prompt.

    장 수를 말해 주는 이유는 모델이 받은 이미지를 전부 보았는지 스스로 대조할 수 있게
    하기 위해서다. 장면당 장 수가 고정이 아니므로(F-03 은 장 수를 장면 안의 변화량으로
    정한다) 프롬프트에 상수로 적을 수 없다.
    """
    return cfg.prompt.user.replace("{keyframe_count}", str(keyframe_count))


def prompt_version(cfg: VlmMetadataConfig) -> str:
    """모델에게 실제로 한 말의 해시.

    입력이 **렌더링된** system prompt 와 user 템플릿이다. 템플릿만 해시하면 어휘를 바꿔도
    값이 그대로여서 기록이 거짓이 된다(`config.py` 모듈 docstring).

    user 쪽은 템플릿 그대로 넣는다. 채워지는 값이 `keyframe_count` 하나이고 그것은 장면마다
    다른 **입력**이다 — 장면 수만큼 다른 `prompt_version` 이 생기면 그 값으로 무엇도 비교할
    수 없다.
    """
    payload = {
        "system": render_system_prompt(cfg),
        "user": cfg.prompt.user,
    }
    return version_id(_PROMPT_SCHEMA.format(version=cfg.version_number), payload)


def _join(values: Sequence[str]) -> str:
    return _VOCABULARY_SEPARATOR.join(values)
