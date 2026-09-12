"""VLM 장면 metadata 단계 (S15P21A501-92).

모델을 실제로 부르는 테스트는 `smoke` 뿐이다. 나머지는 전부 가짜 클라이언트를 쓴다 —
이 단계에서 규약인 것은 "모델이 무엇을 보았는가" 가 아니라 **그 출력을 무엇으로 받아
주고 무엇을 거부하는가** 이고, 그건 가중치 없이 표로 검증된다(`test_ocr.py` 가 가짜
엔진으로 행 변환을 검증하는 것과 같은 판단).
"""

import json
from collections.abc import Sequence
from pathlib import Path

import pytest
from pydantic import SecretStr

from npick_worker.settings import Settings
from npick_worker.vlm_metadata import (
    CallParams,
    KeyframeRef,
    LabeledImage,
    SceneKeyframes,
    VlmMetadataConfig,
    VlmSchemaInvalidError,
    describe_scene,
    describe_scenes,
    get_default_config,
    load_config,
    parse_raw,
    prompt_tag_types,
    prompt_version,
    render_system_prompt,
    render_user_prompt,
    report,
    select_keyframes,
    validate,
)
from npick_worker.vlm_metadata.config import DEFAULT_CONFIG_PATH
from npick_worker.vlm_metadata.external_policy import (
    PAYLOAD_CATEGORY_SELECTED_KEYFRAMES,
    ExternalCallRequest,
    ExternalProcessingNotAllowedError,
    authorize,
)
from npick_worker.vlm_metadata.transformers_backend import TransformersVlmClient


def _keyframe(scene: int = 0, timestamp: int = 1000) -> KeyframeRef:
    return KeyframeRef(
        scene_index=scene,
        timestamp_ms=timestamp,
        storage_key=f"runs/1/frame_extraction/a1/s{scene:04d}/kf-{timestamp:09d}.jpg",
    )


def _scene(scene: int = 0, timestamps: Sequence[int] = (1000, 2000)) -> SceneKeyframes:
    return SceneKeyframes(
        scene_index=scene,
        keyframes=tuple(_keyframe(scene, timestamp) for timestamp in timestamps),
    )


def _image_paths(scene: SceneKeyframes) -> dict[str, Path]:
    return {keyframe.storage_key: Path(keyframe.storage_key) for keyframe in scene.keyframes}


def _payload(**overrides: object) -> dict[str, object]:
    """어휘·근거가 모두 맞는 기본 출력. 테스트마다 한 조각만 비튼다."""
    payload: dict[str, object] = {
        "caption": {
            "value": "앵커가 스튜디오에서 소식을 전한다",
            "confidence": 0.9123456,
            "evidence": ["kf_1"],
        },
        "shot_type": {"value": "anchor", "confidence": 0.88, "evidence": ["kf_1", "kf_2"]},
        "scene_type": {"value": "스튜디오", "confidence": 0.77, "evidence": ["kf_1"]},
        "tag_candidates": [
            {"type": "location", "value": "서울", "confidence": 0.5, "evidence": ["kf_2"]}
        ],
    }
    payload.update(overrides)
    return payload


def _raw(**overrides: object) -> str:
    return json.dumps(_payload(**overrides), ensure_ascii=False)


class _FakeClient:
    """`VlmClient` 구현. 정해진 텍스트를 돌려주고 무엇을 받았는지 기록한다."""

    name = "fake"
    version = "0"
    model_version = "fake-model@0"

    def __init__(self, outputs: Sequence[str] | None = None) -> None:
        self._outputs = list(outputs) if outputs is not None else [_raw()]
        self.calls: list[tuple[tuple[str, ...], str, str]] = []

    def describe(
        self,
        images: Sequence[LabeledImage],
        system_prompt: str,
        user_prompt: str,
        params: CallParams,
    ) -> str:
        self.calls.append((tuple(image.label for image in images), system_prompt, user_prompt))
        return self._outputs[min(len(self.calls) - 1, len(self._outputs) - 1)]


def _config_with(tmp_path: Path, **overrides: object) -> VlmMetadataConfig:
    """기본 설정을 한 곳만 바꿔 만든다. 파일명 규약을 피해 임의 이름으로 쓴다."""
    raw = get_default_config().model_dump(by_alias=True, mode="json")
    raw.update(overrides)
    target = tmp_path / "custom.toml"
    target.write_text(_to_toml(raw), encoding="utf-8")
    return load_config(target)


def _to_toml(raw: dict[str, object]) -> str:
    """테스트용 최소 직렬화. 중첩 절이 둘(`call`·`prompt`)뿐이라 이것으로 충분하다."""
    lines: list[str] = []
    tables: list[tuple[str, dict[str, object]]] = []
    for key, value in raw.items():
        if isinstance(value, dict):
            tables.append((key, value))
        else:
            lines.append(f"{key} = {json.dumps(value, ensure_ascii=False)}")
    for name, table in tables:
        lines.append(f"[{name}]")
        for key, value in table.items():
            lines.append(f"{key} = {json.dumps(value, ensure_ascii=False)}")
    return "\n".join(lines) + "\n"


# ── 설정과 버전 ──────────────────────────────────────────────────────


def test_default_config_loads_and_has_two_versions() -> None:
    config = get_default_config()
    assert config.schema_ == "vlm-metadata-config/v1"
    assert config.version_id.startswith("vlm-metadata-config/v1:")
    # 접두가 갈려야 한다 — 두 값이 로그에 나란히 찍힌다.
    assert prompt_version(config).startswith("vlm-metadata-prompt/v1:")


def test_unknown_key_is_rejected(tmp_path: Path) -> None:
    """toml 키 오타가 조용히 무시되면 config_version 은 바뀌는데 동작은 그대로가 된다."""
    target = tmp_path / "vlm_metadata.v1.toml"
    target.write_text(
        DEFAULT_CONFIG_PATH.read_text(encoding="utf-8") + '\nunknown_key = "x"\n',
        encoding="utf-8",
    )
    with pytest.raises(ValueError, match="unknown_key"):
        load_config(target)


def test_file_version_and_schema_must_agree(tmp_path: Path) -> None:
    target = tmp_path / "vlm_metadata.v2.toml"
    target.write_text(DEFAULT_CONFIG_PATH.read_text(encoding="utf-8"), encoding="utf-8")
    with pytest.raises(ValueError, match="vlm-metadata-config/v2"):
        load_config(target)


def test_duplicate_vocabulary_is_rejected(tmp_path: Path) -> None:
    with pytest.raises(ValueError, match="같은 값이 두 번"):
        _config_with(tmp_path, scene_type_vocabulary=["스튜디오", "스튜디오"])


def test_call_params_do_not_change_prompt_version(tmp_path: Path) -> None:
    """설정이 바뀐 실행과 모델에게 한 말이 바뀐 실행은 구분돼야 한다(§7.2)."""
    default = get_default_config()
    changed = _config_with(
        tmp_path,
        call={"temperature": 0.0, "max_output_tokens": 2048, "timeout_seconds": 120.0},
    )
    assert changed.version_id != default.version_id
    assert prompt_version(changed) == prompt_version(default)


def test_vocabulary_change_moves_prompt_version(tmp_path: Path) -> None:
    """어휘는 템플릿 밖에 있지만 모델에게 한 말의 일부다."""
    default = get_default_config()
    changed = _config_with(tmp_path, scene_type_vocabulary=["스튜디오", "거리"])
    assert prompt_version(changed) != prompt_version(default)


# ── 프롬프트 ─────────────────────────────────────────────────────────


def test_system_prompt_quotes_the_canonical_vocabularies() -> None:
    config = get_default_config()
    rendered = render_system_prompt(config)
    assert "{scene_types}" not in rendered
    assert "{shot_types}" not in rendered
    assert "{tag_types}" not in rendered
    assert "{caption_max_chars}" not in rendered
    assert "{max_tag_candidates}" not in rendered
    for value in config.scene_type_vocabulary:
        assert value in rendered
    for shot_type in ("anchor", "interview", "b_roll", "unknown"):
        assert shot_type in rendered
    assert str(config.caption_max_chars) in rendered


def test_prompt_hides_the_dedicated_tag_type() -> None:
    """검증이 거부하는 것과 프롬프트가 금지하는 것은 같아야 한다."""
    assert "scene_type" not in prompt_tag_types()
    assert "person" in prompt_tag_types()
    rendered = render_system_prompt(get_default_config())
    tag_line = next(
        line for line in rendered.splitlines() if "tag_candidates" in line and "type" in line
    )
    assert "scene_type" not in tag_line.split("쓸 수 있는 값:")[-1]


def test_user_prompt_carries_the_actual_frame_count() -> None:
    assert "3장" in render_user_prompt(get_default_config(), 3)


# ── 검증: 통과 ───────────────────────────────────────────────────────


def test_valid_output_becomes_scene_metadata() -> None:
    scene = _scene()
    metadata = validate(parse_raw(_raw()), scene.scene_index, scene.keyframes, get_default_config())

    assert metadata.scene_index == 0
    assert metadata.shot_type.value == "anchor"
    assert metadata.caption is not None
    # confidence 는 numeric(5,4) 자리에 들어간다. 다섯째 자리를 보내면 DB 가 반올림한다.
    assert metadata.caption.confidence == 0.9123
    # 색인 토큰은 워커가 만든다(BE 에 Kiwi 가 없다).
    assert metadata.caption.tokens
    assert metadata.caption.tokens_text == " ".join(metadata.caption.tokens)
    # 근거는 라벨이 아니라 실제 keyframe 이다.
    assert metadata.caption.evidence == (scene.keyframes[0],)
    assert metadata.shot_type.evidence == scene.keyframes


def test_scene_type_becomes_a_tag_candidate_not_a_column() -> None:
    """장면 유형은 `scene` 의 칸이 아니다(`docs/frd.md:148`)."""
    scene = _scene()
    metadata = validate(parse_raw(_raw()), 0, scene.keyframes, get_default_config())

    scene_type = metadata.scene_type
    assert scene_type is not None
    assert scene_type.type == "scene_type"
    assert scene_type.value == "스튜디오"
    assert scene_type in metadata.tag_candidates
    assert not hasattr(metadata, "scene_type_value")


def test_missing_basis_is_allowed_as_null_and_unknown() -> None:
    """근거가 없으면 지어내지 않고 비운다(티켓 제약)."""
    raw = _raw(
        caption=None,
        scene_type=None,
        shot_type={"value": "unknown", "confidence": 0.2, "evidence": []},
        tag_candidates=[],
    )
    metadata = validate(parse_raw(raw), 0, _scene().keyframes, get_default_config())

    assert metadata.caption is None
    assert metadata.scene_type is None
    assert metadata.tag_candidates == ()
    assert metadata.shot_type.value == "unknown"
    assert metadata.shot_type.evidence == ()


def test_code_fence_is_stripped() -> None:
    fenced = f"```json\n{_raw()}\n```"
    assert parse_raw(fenced).shot_type.value == "anchor"


def test_duplicate_candidates_merge_instead_of_failing() -> None:
    """값이 같으므로 버리는 정보가 없고, BE 쪽에는 UNIQUE 제약이 있다."""
    scene = _scene()
    raw = _raw(
        tag_candidates=[
            {"type": "location", "value": "서울", "confidence": 0.4, "evidence": ["kf_1"]},
            {"type": "location", "value": "서울", "confidence": 0.6, "evidence": ["kf_2", "kf_2"]},
        ]
    )
    metadata = validate(parse_raw(raw), 0, scene.keyframes, get_default_config())

    locations = [tag for tag in metadata.tag_candidates if tag.type == "location"]
    assert len(locations) == 1
    assert locations[0].confidence == 0.6
    assert locations[0].evidence == scene.keyframes


# ── 검증: 거부 ───────────────────────────────────────────────────────


@pytest.mark.parametrize(
    "payload",
    [
        pytest.param("설명하겠습니다: {", id="JSON 이 아니다"),
        pytest.param("[]", id="객체가 아니다"),
        pytest.param('{"caption": null}', id="shot_type 이 없다"),
        pytest.param(json.dumps(_payload(visible_text="속보")), id="schema 에 없는 키 (OCR 흉내)"),
        pytest.param(
            json.dumps(
                _payload(shot_type={"value": "studio", "confidence": 1, "evidence": ["kf_1"]})
            ),
            id="shot_type 어휘 밖",
        ),
        pytest.param(
            json.dumps(
                _payload(
                    tag_candidates=[
                        {
                            "type": "broadcast_date",
                            "value": "2026-09-11",
                            "confidence": 0.9,
                            "evidence": ["kf_1"],
                        }
                    ]
                )
            ),
            id="날짜 태그 유형은 존재하지 않는다",
        ),
        pytest.param(
            json.dumps(
                _payload(caption={"value": "설명", "confidence": 1.5, "evidence": ["kf_1"]})
            ),
            id="confidence 가 범위 밖",
        ),
        pytest.param(
            json.dumps(
                _payload(caption={"value": "설명", "confidence": 0.9, "evidence": ["첫 번째"]})
            ),
            id="근거 라벨의 모양이 다르다",
        ),
    ],
)
def test_malformed_output_is_rejected_before_validation(payload: str) -> None:
    with pytest.raises(VlmSchemaInvalidError):
        parse_raw(payload)


@pytest.mark.parametrize(
    ("overrides", "match"),
    [
        pytest.param(
            {"scene_type": {"value": "우주", "confidence": 0.9, "evidence": ["kf_1"]}},
            "닫힌 어휘",
            id="scene_type 어휘 밖",
        ),
        pytest.param(
            {"caption": {"value": "설명", "confidence": 0.9, "evidence": []}},
            "근거 keyframe 이 없다",
            id="caption 에 근거가 없다",
        ),
        pytest.param(
            {"shot_type": {"value": "anchor", "confidence": 0.9, "evidence": []}},
            "근거 keyframe 이 없다",
            id="unknown 이 아닌데 근거가 없다",
        ),
        pytest.param(
            {"caption": {"value": "설명", "confidence": 0.9, "evidence": ["kf_9"]}},
            "입력에 없다",
            id="없는 라벨을 근거로 든다",
        ),
        pytest.param(
            {"caption": {"value": "   ", "confidence": 0.9, "evidence": ["kf_1"]}},
            "공백뿐",
            id="caption 이 공백",
        ),
        pytest.param(
            {
                "tag_candidates": [
                    {
                        "type": "scene_type",
                        "value": "스튜디오",
                        "confidence": 0.9,
                        "evidence": ["kf_1"],
                    }
                ]
            },
            "전용 필드",
            id="scene_type 을 태그 배열에 넣었다",
        ),
    ],
)
def test_contract_violations_are_rejected(overrides: dict[str, object], match: str) -> None:
    scene = _scene()
    with pytest.raises(VlmSchemaInvalidError, match=match):
        validate(parse_raw(_raw(**overrides)), 0, scene.keyframes, get_default_config())


def test_caption_over_the_limit_is_rejected_not_truncated() -> None:
    config = get_default_config()
    long_caption = "가" * (config.caption_max_chars + 1)
    scene = _scene()
    with pytest.raises(VlmSchemaInvalidError, match="자를 넘는다"):
        validate(
            parse_raw(
                _raw(caption={"value": long_caption, "confidence": 0.9, "evidence": ["kf_1"]})
            ),
            0,
            scene.keyframes,
            config,
        )


def test_too_many_tag_candidates_is_rejected() -> None:
    config = get_default_config()
    tags = [
        {"type": "keyword", "value": f"값{index}", "confidence": 0.5, "evidence": ["kf_1"]}
        for index in range(config.max_tag_candidates_per_scene + 1)
    ]
    with pytest.raises(VlmSchemaInvalidError, match="상한을 넘는다"):
        validate(parse_raw(_raw(tag_candidates=tags)), 0, _scene().keyframes, config)


def test_valid_fields_of_an_invalid_output_are_not_partially_applied() -> None:
    """티켓 제약의 핵심 — 멀쩡한 caption 이 있어도 그 출력은 통째로 버려진다."""
    raw = _raw(shot_type={"value": "studio", "confidence": 0.9, "evidence": ["kf_1"]})
    assert "앵커가 스튜디오에서" in raw  # caption 자체는 유효하다

    with pytest.raises(VlmSchemaInvalidError):
        parse_raw(raw)


# ── 입력 선정 ────────────────────────────────────────────────────────


def test_keyframes_go_in_time_order() -> None:
    """상류는 대표 이미지를 맨 앞에 준다. 프롬프트는 시간 순서라고 말한다."""
    scene = SceneKeyframes(
        scene_index=0,
        keyframes=(_keyframe(0, 5000), _keyframe(0, 1000), _keyframe(0, 3000)),
    )
    assert [kf.timestamp_ms for kf in select_keyframes(scene)] == [1000, 3000, 5000]


def test_selection_keeps_both_ends_and_is_deterministic(tmp_path: Path) -> None:
    config = _config_with(tmp_path, max_keyframes_per_scene=3)
    scene = _scene(timestamps=(1000, 2000, 3000, 4000, 5000, 6000))

    picked = select_keyframes(scene, config)

    assert [kf.timestamp_ms for kf in picked] == [1000, 3000, 6000]
    assert select_keyframes(scene, config) == picked


def test_single_frame_budget_takes_the_middle(tmp_path: Path) -> None:
    config = _config_with(tmp_path, max_keyframes_per_scene=1)
    scene = _scene(timestamps=(1000, 2000, 3000))
    assert [kf.timestamp_ms for kf in select_keyframes(scene, config)] == [2000]


def test_scene_without_keyframes_is_a_contract_violation() -> None:
    with pytest.raises(ValueError, match="keyframe 이 없는 scene"):
        SceneKeyframes(scene_index=0, keyframes=())


# ── 단계 실행 ────────────────────────────────────────────────────────


def test_describe_scene_labels_images_in_order() -> None:
    scene = _scene(timestamps=(1000, 2000))
    client = _FakeClient()

    described = describe_scene(scene, _image_paths(scene), client)

    labels, _, user_prompt = client.calls[0]
    assert labels == ("kf_1", "kf_2")
    assert "2장" in user_prompt
    assert described.raw_output == _raw()
    assert described.inputs == scene.keyframes
    assert described.metadata.caption is not None


def test_missing_image_is_not_silently_skipped() -> None:
    scene = _scene()
    with pytest.raises(KeyError, match="받지 못했다"):
        describe_scene(scene, {}, _FakeClient())


def test_result_carries_every_version_axis() -> None:
    """무엇이 이 결과를 만들었는지 다섯 축으로 추적할 수 있어야 한다(§7.2)."""
    scene = _scene()
    config = get_default_config()

    result = describe_scenes([scene], _image_paths(scene), _FakeClient(), config)

    assert result.scene_count == 1
    assert result.schema_version == "vlm-metadata/v1"
    assert result.config_version == config.version_id
    assert result.prompt_version == prompt_version(config)
    assert result.engine == "fake"
    assert result.engine_version == "0"
    assert result.model_version == "fake-model@0"
    assert result.tokenizer
    assert result.caption_count == 1
    assert result.tag_candidate_count == 2
    assert result.unknown_shot_type_count == 0


def test_one_bad_scene_fails_the_whole_stage() -> None:
    """장면 하나를 버리고 넘어가면 그 실패가 "설명이 없는 장면" 으로 저장된다."""
    first = _scene(scene=0)
    second = _scene(scene=1)
    paths = _image_paths(first) | _image_paths(second)
    client = _FakeClient([_raw(), "not json"])

    with pytest.raises(VlmSchemaInvalidError):
        describe_scenes([first, second], paths, client)


# ── 외부 처리 정책 (PRD §12.4) ───────────────────────────────────────


def _approved_settings(**overrides: object) -> Settings:
    """조건이 전부 맞는 배포 설정. 테스트마다 한 곳만 비튼다.

    **clip 별 권리는 여기 없다.** 설정으로 만들 수 없는 값이고, 그것이 이 게이트의
    설계다(`external_policy.py` 모듈 docstring).
    """
    values: dict[str, object] = {
        "vlm_external_enabled": True,
        "vlm_external_provider_profile": "profile-1",
        "vlm_external_endpoint": "https://provider.example/v1/chat",
        "vlm_external_model": "approved-vlm",
        "vlm_external_provider_terms_confirmed": True,
        "vlm_external_max_payload_bytes": 1_000_000,
        "vlm_external_api_key": SecretStr("injected"),
    }
    values.update(overrides)
    return Settings(**values)  # type: ignore[arg-type]


def _request(**overrides: object) -> ExternalCallRequest:
    values: dict[str, object] = {
        "model": "approved-vlm",
        "endpoint": "https://provider.example/v1/chat",
        "payload_category": PAYLOAD_CATEGORY_SELECTED_KEYFRAMES,
        "payload_bytes": 1024,
        "clip_rights_confirmed": True,
    }
    values.update(overrides)
    return ExternalCallRequest(**values)  # type: ignore[arg-type]


def test_defaults_refuse_external_processing() -> None:
    """기본값은 닫힘이다. 아무것도 설정하지 않은 배포는 외부로 나가지 않는다."""
    with pytest.raises(ExternalProcessingNotAllowedError):
        authorize(_request(clip_rights_confirmed=False), Settings())


def test_all_conditions_met_is_allowed_and_recorded() -> None:
    record = authorize(_request(), _approved_settings())

    assert record.allowed is True
    assert record.reason is None
    assert record.component == "vlm"
    assert record.provider_profile == "profile-1"
    # 감사 기록에 원문이 없다(PRD §12.4). 크기·종류·판정만 있다.
    fields = record.as_log_fields()
    assert set(fields) == {
        "component",
        "providerProfile",
        "model",
        "payloadCategory",
        "payloadBytes",
        "allowed",
        "reason",
    }


@pytest.mark.parametrize(
    ("request_overrides", "settings_overrides", "match"),
    [
        pytest.param(
            {"clip_rights_confirmed": False}, {}, "clip 의 외부 처리 권리", id="clip 권리 미확인"
        ),
        pytest.param(
            {},
            {"vlm_external_provider_terms_confirmed": False},
            "보관·학습·삭제",
            id="provider 조건 미확인",
        ),
        pytest.param(
            {}, {"vlm_external_enabled": False}, "활성 deployment policy", id="정책 비활성"
        ),
        pytest.param(
            {}, {"vlm_external_provider_profile": ""}, "활성 deployment policy", id="프로파일 없음"
        ),
        pytest.param({"model": "other-vlm"}, {}, "allowlist 에 없는 모델", id="모델 allowlist 밖"),
        pytest.param(
            {"endpoint": "https://elsewhere.example/v1"},
            {},
            "allowlist 에 없는 endpoint",
            id="endpoint allowlist 밖",
        ),
        pytest.param(
            {"endpoint": "http://provider.example/v1/chat"},
            {"vlm_external_endpoint": "http://provider.example/v1/chat"},
            "TLS",
            id="평문 endpoint",
        ),
        pytest.param({"payload_bytes": 2_000_000}, {}, "상한을 넘는다", id="payload 초과"),
        pytest.param({}, {"vlm_external_api_key": SecretStr("")}, "secret", id="secret 미주입"),
        pytest.param(
            {"payload_category": "full_video"}, {}, "P0 에서 금지된", id="full video 는 절대 금지"
        ),
        pytest.param(
            {"payload_category": "scene_thumbnails"},
            {},
            "allowlist 에 없는 payload category",
            id="payload category allowlist 밖",
        ),
    ],
)
def test_each_missing_condition_fails_closed(
    request_overrides: dict[str, object],
    settings_overrides: dict[str, object],
    match: str,
) -> None:
    """조건 하나라도 빠지면 **보내기 전에** 멈춘다."""
    with pytest.raises(ExternalProcessingNotAllowedError, match=match):
        authorize(_request(**request_overrides), _approved_settings(**settings_overrides))


def test_deployment_flags_cannot_stand_in_for_clip_rights() -> None:
    """PRD: media clip 별 승인과 deployment-level 승인은 서로 대신할 수 없다."""
    with pytest.raises(ExternalProcessingNotAllowedError, match="clip 의 외부 처리 권리"):
        authorize(_request(clip_rights_confirmed=False), _approved_settings())


# ── 리뷰 회귀: 실측 도구가 증거가 되려면 (S15P21A501-92) ────────────


def _frames_dir(tmp_path: Path, scenes: int, per_scene: int) -> Path:
    """`frame_extraction.report` 가 만드는 구조를 흉내낸다. 바이트는 가짜여도 된다 —
    이 테스트들은 가짜 클라이언트를 쓰므로 파일을 여는 쪽이 없다."""
    root = tmp_path / "frames"
    for scene_index in range(scenes):
        scene_dir = root / f"s{scene_index:04d}"
        scene_dir.mkdir(parents=True)
        for position in range(per_scene):
            timestamp = 1000 * (position + 1) + scene_index * 100_000
            (scene_dir / f"kf-{timestamp:09d}.jpg").write_bytes(b"jpeg")
    return root


def test_smoke_flag_requires_ten_scenes(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    """출력이 전부 유효해도 장면이 모자라면 티켓이 요구한 것을 증명하지 못한다."""
    monkeypatch.setattr(report, "_client", lambda model, revision: _FakeClient())
    frames = _frames_dir(tmp_path, scenes=9, per_scene=2)

    assert report.main([str(frames), "--smoke"]) == 1


def test_smoke_flag_requires_multiple_keyframes_per_scene(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    """한 장짜리 장면이 섞이면 "복수 keyframe 을 함께 본다" 가 검증되지 않는다."""
    monkeypatch.setattr(report, "_client", lambda model, revision: _FakeClient())
    frames = _frames_dir(tmp_path, scenes=10, per_scene=1)

    assert report.main([str(frames), "--smoke"]) == 1


def test_smoke_flag_passes_when_both_conditions_hold(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.setattr(report, "_client", lambda model, revision: _FakeClient())
    frames = _frames_dir(tmp_path, scenes=10, per_scene=2)

    assert report.main([str(frames), "--smoke"]) == 0


def test_rejected_output_keeps_its_raw_text(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    """거부된 출력이야말로 프롬프트를 고칠 근거다. 카운트만 남기면 그것이 사라진다."""
    broken = '{"caption": {"value": "설명", "confidence": 0.9, "evidence": ["kf_9"]}}'
    monkeypatch.setattr(report, "_client", lambda model, revision: _FakeClient([broken]))
    frames = _frames_dir(tmp_path, scenes=1, per_scene=2)
    out = tmp_path / "out"

    assert report.main([str(frames), "--out", str(out)]) == 1

    saved = json.loads((out / "vlm-metadata.json").read_text(encoding="utf-8"))
    assert saved["scenes"] == []
    assert len(saved["rejected"]) == 1
    rejected = saved["rejected"][0]
    assert rejected["sceneIndex"] == 0
    assert rejected["rawOutput"] == broken
    assert rejected["reason"]


def test_semantic_rejection_also_carries_the_raw_text() -> None:
    """어휘·근거 검사에서 떨어진 경우 validate 는 원문을 모른다. describer 가 붙인다."""
    scene = _scene()
    client = _FakeClient(
        [_raw(scene_type={"value": "우주", "confidence": 0.9, "evidence": ["kf_1"]})]
    )

    with pytest.raises(VlmSchemaInvalidError) as caught:
        describe_scene(scene, _image_paths(scene), client)

    assert caught.value.raw_output is not None
    assert "우주" in caught.value.raw_output


def test_candidate_comparison_client_gets_a_device() -> None:
    """--model 경로가 device 를 빠뜨리면 CPU 로 돌고, 시간·VRAM 비교가 무의미해진다.

    가중치를 올리지 않는다 — 생성자는 이름만 검사하고 로딩은 첫 호출까지 미룬다.
    """
    client = report._client("example/vlm", "")

    assert isinstance(client, TransformersVlmClient)
    assert client.device is not None
    assert client.model_version == "example/vlm@main"
