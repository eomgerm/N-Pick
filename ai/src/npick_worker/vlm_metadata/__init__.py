"""FRD §3 F-03 영상 설명 생성 (`stages.py` 7단계 `vlm_metadata`, 비치명).

`frame_extraction` 이 뽑은 **장면당 복수 keyframe** 을 함께 보고 그 장면의 설명·샷 유형·
태그 후보를 만든다(`docs/frd.md:121`). 어휘와 임계값은 버전이 붙은 설정에 있다
(`config/vlm_metadata.v2.toml`).

여섯 가지를 이 모듈이 지킨다.

- **장면을 하나로 본다.** 한 scene 의 keyframe 여러 장을 한 호출에 넣는다. 한 장씩 따로
  설명하면 "앵커에서 자료 화면으로 넘어간다" 같은 판단이 불가능하고, 그 판단이 이 단계가
  존재하는 이유다.
- **상류 텍스트를 재사용한다.** 화면 글자 추출은 OCR 단계가 담당한다.
  VLM은 추출된 OCR·채택 대사와 화면의 관계를 해석한다.
- **근거 없는 값을 만들지 않는다.** 판단할 시각 근거가 없으면 `caption`·`scene_type` 은
  비우고 `shot_type` 은 `unknown` 이다. 모든 판단은 입력 이미지·텍스트의 원본 근거를
  가리킨다(F-04의 "값만 저장하지 않고 출처와 확인 가능한 근거 위치를 연결한다").
- **부분 반영을 하지 않는다.** 형식·어휘·근거 중 하나라도 어긋나면 그 출력을 통째로
  거부한다(`validator.py`). 거부는 `VLM_SCHEMA_INVALID` — 계약 §9.2 의 **영구** 실패다.
- **날짜 태그를 만들지 않는다.** `schema.TagCandidateType` 에 `filmed_date`·
  `broadcast_date` 가 없다. 화면에 날짜가 보인다는 사실과 그것이 방송일·촬영일이라는
  판단은 다르다(`docs/frd.md:157`).
- **검증된 사실로 올리지 않는다.** 이 단계의 모든 결과는 미검증이다. 저장될 때
  `tag_evidence.source` 는 `vlm`, `verification_status` 는 `unverified` 다 — AI 의 높은
  신뢰도만으로 verified 가 되지 않는다(`docs/frd.md:158`).

**scene_type 은 `scene` 의 칸이 아니라 태그 후보다.** 칸으로 남은 분류값은 `shot_type`
하나뿐이다(`docs/frd.md:148`, `docs/frd.md:159`). 그래서 검증을 통과한 결과에는
`scene_type` 이라는 필드가 없고 `type="scene_type"` 인 태그 후보만 있다.

이 모듈은 순수 함수만 제공한다. 모델 호출은 `VlmClient` Protocol 뒤에 있고, 이미지
내려받기와 pipeline run 배선은 `jobs/` 의 몫이다. 외부 제공자를 쓸지 고르는 일도 이
모듈이 하지 않는다 — PRD §12.4 의 조건 판정은 어댑터를 **고르는 쪽**의 책임이다.
"""

from npick_worker.vlm_metadata.client import (
    LabeledImage,
    VlmCallError,
    VlmClient,
    VlmModelUnavailableError,
)
from npick_worker.vlm_metadata.config import (
    DEFAULT_CONFIG_PATH,
    CallParams,
    VlmMetadataConfig,
    get_default_config,
    load_config,
)
from npick_worker.vlm_metadata.describer import (
    SceneDescription,
    describe_scene,
    describe_scenes,
    select_keyframes,
)
from npick_worker.vlm_metadata.models import (
    CONFIDENCE_DECIMALS,
    Caption,
    Judgement,
    KeyframeRef,
    SceneKeyframes,
    SceneMetadata,
    ShotTypeJudgement,
    TagCandidate,
    VlmResult,
)
from npick_worker.vlm_metadata.prompt import (
    label_for,
    labels_for,
    prompt_tag_types,
    prompt_version,
    render_system_prompt,
    render_user_prompt,
)
from npick_worker.vlm_metadata.schema import (
    SCHEMA_VERSION,
    RawSceneMetadata,
    ShotType,
    TagCandidateType,
)
from npick_worker.vlm_metadata.validator import (
    VLM_SCHEMA_INVALID,
    VlmSchemaInvalidError,
    parse_raw,
    validate,
)

__all__ = [
    "CONFIDENCE_DECIMALS",
    "DEFAULT_CONFIG_PATH",
    "SCHEMA_VERSION",
    "VLM_SCHEMA_INVALID",
    "CallParams",
    "Caption",
    "Judgement",
    "KeyframeRef",
    "LabeledImage",
    "RawSceneMetadata",
    "SceneDescription",
    "SceneKeyframes",
    "SceneMetadata",
    "ShotType",
    "ShotTypeJudgement",
    "TagCandidate",
    "TagCandidateType",
    "VlmCallError",
    "VlmClient",
    "VlmMetadataConfig",
    "VlmModelUnavailableError",
    "VlmResult",
    "VlmSchemaInvalidError",
    "describe_scene",
    "describe_scenes",
    "get_default_config",
    "label_for",
    "labels_for",
    "load_config",
    "parse_raw",
    "prompt_tag_types",
    "prompt_version",
    "render_system_prompt",
    "render_user_prompt",
    "select_keyframes",
    "validate",
]
