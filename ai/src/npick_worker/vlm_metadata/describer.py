"""scene 마다 keyframe 을 골라 모델에 넣고 검증된 metadata 를 만든다. 이 단계의 본체다.

`ocr/reader.py` 와 같은 자리이고 같은 규약을 지킨다 — 파일을 내려받지 않고, 결과를 올리지
않고, `pipeline_run_id` 도 미디어 루트도 모른다. 바이트를 가져오는 일은 잡 레이어의 몫이다.

**scene 하나에 호출 하나다.** 여러 장면을 한 프롬프트에 넣으면 토큰이 줄지만 근거 라벨이
장면 경계를 넘어 섞이고, 한 장면의 출력이 깨질 때 어느 장면이 깨졌는지가 사라진다. 티켓이
요구하는 입력 단위도 "scene 의 selected keyframe 들" 이다.
"""

from collections.abc import Mapping, Sequence
from dataclasses import dataclass
from pathlib import Path

from npick_worker import korean_tokens
from npick_worker.vlm_metadata.client import LabeledImage, VlmClient
from npick_worker.vlm_metadata.config import VlmMetadataConfig, get_default_config
from npick_worker.vlm_metadata.models import (
    KeyframeRef,
    SceneKeyframes,
    SceneMetadata,
    VlmResult,
)
from npick_worker.vlm_metadata.prompt import (
    label_for,
    prompt_version,
    render_system_prompt,
    render_user_prompt,
)
from npick_worker.vlm_metadata.schema import SCHEMA_VERSION
from npick_worker.vlm_metadata.validator import VlmSchemaInvalidError, parse_raw, validate


@dataclass(frozen=True, slots=True)
class SceneDescription:
    """scene 하나의 결과와 그 **원문**.

    원문을 함께 들고 나오는 이유는 티켓이 "평가에 사용한 설정 version 과 원시 결과를
    보존한다" 를 요구하기 때문이다. 단계 산출물(`VlmResult`)에는 넣지 않는다 — 담을
    컬럼이 없고, 모델 출력 원문은 정본이 아니라 조사용 기록이다. 후보 비교 CLI
    (`report.py`)가 이 값을 파일로 남긴다.
    """

    metadata: SceneMetadata
    #: 모델이 낸 텍스트 그대로. 검증을 통과한 출력의 원문이다.
    raw_output: str
    #: 실제로 모델에 넣은 keyframe. 골라 넣었으므로 상류가 준 전부와 다를 수 있다.
    inputs: tuple[KeyframeRef, ...]


def select_keyframes(
    scene: SceneKeyframes, cfg: VlmMetadataConfig | None = None
) -> tuple[KeyframeRef, ...]:
    """모델에 넣을 keyframe 을 고른다. **시간 순서**로 돌려준다.

    상류는 대표 이미지를 맨 앞에 두고 나머지를 시각 오름차순으로 준다(계약 §4.3.1).
    그 순서를 그대로 넣으면 모델이 보는 첫 장이 장면 중간일 수 있고, 프롬프트가
    "시간 순서대로 주어진다" 고 말하는 것이 거짓이 된다. 장면의 흐름을 이해해서 답하라는
    요구(복수 keyframe 입력의 목적)도 시간 순서를 전제한다.

    상한을 넘으면 **양 끝을 포함해 고르게** 고른다. 앞에서 n 장을 자르면 장면 뒷부분이
    통째로 빠지는데, 뉴스 장면은 앵커에서 자료 화면으로 넘어가는 식으로 뒤에서 바뀌는
    경우가 많다. 고르는 규칙이 결정론이어야 하는 이유는 계약 §8 의 멱등성이다 — 같은
    입력이면 같은 프레임을 넣고 같은 출력을 받아야 한다.
    """
    config = cfg if cfg is not None else get_default_config()
    ordered = tuple(sorted(scene.keyframes, key=lambda keyframe: keyframe.timestamp_ms))
    limit = config.max_keyframes_per_scene
    if len(ordered) <= limit:
        return ordered
    if limit == 1:
        # 한 장으로 장면을 요약해야 한다면 경계보다 가운데가 낫다. 장면 시작·끝은
        # 전환 잔상이 남는 자리다(`frame_extraction` 의 `edge_margin_ms` 와 같은 이유).
        return (ordered[len(ordered) // 2],)
    last = len(ordered) - 1
    positions = sorted({round(index * last / (limit - 1)) for index in range(limit)})
    return tuple(ordered[position] for position in positions)


def describe_scene(
    scene: SceneKeyframes,
    image_paths: Mapping[str, Path],
    client: VlmClient,
    cfg: VlmMetadataConfig | None = None,
) -> SceneDescription:
    """scene 하나를 모델에 넣고 검증한다.

    Raises:
        KeyError: `image_paths` 에 없는 `storage_key` 가 있다. 상류 산출물과 실제로 받은
            파일이 어긋난 것이므로 일부만 보고 성공으로 반납하지 않는다 — 그러면 "그
            장면은 이렇게 보였다" 는 거짓이 정본에 남는다(`ocr/reader.py` 와 같은 판단).
        validator.VlmSchemaInvalidError: 출력이 계약과 다르다(영구).
        client.VlmCallError: 호출이 실패했다(일시).
        client.VlmModelUnavailableError: 가중치를 준비하지 못했다(일시).
    """
    config = cfg if cfg is not None else get_default_config()
    selected = select_keyframes(scene, config)
    images = tuple(
        LabeledImage(label=label_for(position), path=_image_path(keyframe, image_paths))
        for position, keyframe in enumerate(selected)
    )
    raw_output = client.describe(
        images,
        render_system_prompt(config),
        render_user_prompt(config, len(images)),
        config.call,
    )
    try:
        metadata = validate(parse_raw(raw_output), scene.scene_index, selected, config)
    except VlmSchemaInvalidError as exc:
        # 어휘·근거 검사에서 떨어진 경우 `validate` 는 원문을 모른다(`RawSceneMetadata` 만
        # 받는다). 원문을 아는 곳이 여기뿐이라 여기서 붙인다 — 거부된 출력이야말로 프롬프트를
        # 고칠 근거다(`validator.VlmSchemaInvalidError`).
        if exc.raw_output is None:
            exc.raw_output = raw_output
        raise
    return SceneDescription(metadata=metadata, raw_output=raw_output, inputs=selected)


def describe_scenes(
    scenes: Sequence[SceneKeyframes],
    image_paths: Mapping[str, Path],
    client: VlmClient,
    cfg: VlmMetadataConfig | None = None,
) -> VlmResult:
    """장면 전부를 처리한다. 하나라도 검증에서 떨어지면 **전체가 실패다**.

    장면 단위로 건너뛰지 않는 이유는 `validator.py` 의 모듈 docstring 에 있다 — 빠진
    장면은 "설명이 없는 장면" 으로 저장되어 실패가 정상 데이터로 보이게 된다.
    """
    config = cfg if cfg is not None else get_default_config()
    described = tuple(
        describe_scene(scene, image_paths, client, config).metadata for scene in scenes
    )
    return VlmResult(
        scenes=described,
        schema_version=SCHEMA_VERSION,
        config_version=config.version_id,
        prompt_version=prompt_version(config),
        engine=client.name,
        engine_version=client.version,
        model_version=client.model_version,
        tokenizer=korean_tokens.tokenizer_version(),
    )


def _image_path(keyframe: KeyframeRef, image_paths: Mapping[str, Path]) -> Path:
    path = image_paths.get(keyframe.storage_key)
    if path is None:
        msg = f"keyframe 이미지를 받지 못했다: {keyframe.storage_key}"
        raise KeyError(msg)
    return path
