"""OCR 단계 (S15P21A501-94).

엔진을 실제로 돌리는 테스트는 `smoke` 뿐이다. 나머지는 전부 가짜 엔진을 쓴다 —
이 단계에서 규약인 것은 "무엇을 읽었는가" 가 아니라 **읽은 것을 어떤 행으로 옮기는가**
이고, 그건 모델 없이 표로 검증된다(`test_frame_extraction.py` 가 선정 규칙을 영상 없이
검증하는 것과 같은 판단).
"""

from pathlib import Path

import pytest

from npick_worker import korean_tokens
from npick_worker.ocr import (
    BoundingBox,
    KeyframeRef,
    OcrConfig,
    TextDetection,
    get_default_config,
    load_config,
    read_keyframes,
    text_key,
    to_observations,
)
from npick_worker.ocr.config import DEFAULT_CONFIG_PATH
from npick_worker.query_normalization import normalize

_BOX = ((0.0, 0.0), (10.0, 0.0), (10.0, 4.0), (0.0, 4.0))


def _keyframe(scene: int = 0, timestamp: int = 1000) -> KeyframeRef:
    return KeyframeRef(
        scene_index=scene,
        timestamp_ms=timestamp,
        storage_key=f"runs/1/frame_extraction/a1/s{scene:04d}/kf-{timestamp:09d}.jpg",
    )


def _detection(text: str, confidence: float = 0.9) -> TextDetection:
    return TextDetection(text=text, confidence=confidence, points=_BOX)


class _FakeEngine:
    """`OcrEngine` 구현. keyframe 마다 미리 정한 것을 돌려준다."""

    name = "fake"
    version = "0"

    def __init__(self, by_path: dict[str, list[TextDetection]] | None = None) -> None:
        self._by_path = by_path or {}
        self.calls: list[Path] = []

    def read(self, image_path: Path) -> tuple[TextDetection, ...]:
        self.calls.append(image_path)
        return tuple(self._by_path.get(image_path.name, []))


# ── 설정 ────────────────────────────────────────────────────────────


def test_default_config_loads_and_has_a_version() -> None:
    config = get_default_config()
    assert config.schema_ == "ocr/v1"
    assert config.version_id.startswith("ocr/v1:")


def test_unknown_key_is_rejected(tmp_path: Path) -> None:
    """toml 키 오타가 조용히 무시되면 version_id 는 바뀌는데 동작은 그대로가 된다."""
    target = tmp_path / "ocr.v1.toml"
    target.write_text(
        DEFAULT_CONFIG_PATH.read_text(encoding="utf-8") + "\nmin_confidenc = 0.5\n",
        encoding="utf-8",
    )
    with pytest.raises(ValueError, match="min_confidenc"):
        load_config(target)


def test_file_version_and_schema_must_agree(tmp_path: Path) -> None:
    target = tmp_path / "ocr.v2.toml"
    target.write_text(DEFAULT_CONFIG_PATH.read_text(encoding="utf-8"), encoding="utf-8")
    with pytest.raises(ValueError, match="ocr/v2"):
        load_config(target)


def test_confidence_threshold_is_a_probability() -> None:
    """`ocr_observation.confidence` 가 `CHECK (confidence BETWEEN 0 AND 1)` 이다."""
    raw = get_default_config().model_dump(by_alias=True)
    raw["min_confidence"] = 1.5
    with pytest.raises(ValueError, match="min_confidence"):
        OcrConfig.model_validate(raw)


# ── 관측 만들기 ─────────────────────────────────────────────────────


def test_empty_text_is_not_an_observation() -> None:
    """상자는 잡혔는데 읽어 낸 글자가 없는 경우. `raw_text` 가 NOT NULL 이다."""
    result = to_observations(
        _keyframe(), [_detection(""), _detection("   "), _detection("강원도")], min_confidence=0.7
    )
    assert [obs.raw_text for obs in result.observations] == ["강원도"]


def test_raw_text_is_kept_verbatim() -> None:
    """정규화한 문자열을 넣지 않는다 — 컬럼 주석이 "절대 덮어쓰지 않는다" 다."""
    result = to_observations(_keyframe(), [_detection("Life Style")], min_confidence=0.7)
    observation = result.observations[0]
    assert observation.raw_text == "Life Style"
    # 정규화는 토큰 쪽에만 걸린다.
    assert observation.tokens_text == "life style"


def test_confidence_is_rounded_to_the_column_precision() -> None:
    """`numeric(5,4)`. 다섯째 자리를 보내면 저장된 값과 워커 로그가 갈린다."""
    result = to_observations(_keyframe(), [_detection("강원도", 0.987654321)], min_confidence=0.7)
    assert result.observations[0].confidence == 0.9877


@pytest.mark.parametrize(
    ("confidence", "expected_unverified"),
    [
        (0.6999, True),
        # 임계값과 **같은 값은 검증된 쪽**이다. 설정에 적은 수치가 "이 값부터 믿는다"
        # 로 읽히는 것이 자연스럽다.
        (0.7, False),
        (0.9, False),
    ],
)
def test_threshold_boundary(confidence: float, expected_unverified: bool) -> None:
    result = to_observations(_keyframe(), [_detection("강원도", confidence)], min_confidence=0.7)
    assert result.observations[0].unverified is expected_unverified


def test_low_confidence_is_marked_not_dropped() -> None:
    """티켓 제약: 미달 결과도 검색 후보로는 쓸 수 있어야 한다."""
    result = to_observations(
        _keyframe(), [_detection("RG", 0.29), _detection("강원도", 0.99)], min_confidence=0.7
    )
    assert len(result.observations) == 2
    assert [obs.unverified for obs in result.observations] == [True, False]


def test_tokens_use_the_same_rule_as_the_query_side() -> None:
    """색인과 질의가 다른 Kiwi 설정을 쓰면 검색이 0 건이 된다.

    `docs/architecture/02-container.md:110` 이 못 박은 요구이고, 이 테스트가 그
    한 문장을 지킨다. 규칙은 `npick_worker.korean_tokens` 하나다.
    """
    phrase = "강원도 대표 볼거리관"
    result = to_observations(_keyframe(), [_detection(phrase)], min_confidence=0.7)
    assert result.observations[0].tokens == normalize(phrase).search_tokens


def test_symbol_only_text_keeps_the_observation_with_empty_tokens() -> None:
    """검색에 걸릴 것이 없을 뿐 관측은 그 프레임의 사실이다."""
    result = to_observations(_keyframe(), [_detection("···")], min_confidence=0.7)
    assert result.observations[0].tokens_text == ""
    assert result.observations[0].raw_text == "···"


# ── 병합하지 않는다 ─────────────────────────────────────────────────


def test_same_phrase_in_two_frames_stays_two_observations() -> None:
    """티켓 요구: 병합해도 원본 관측과 keyframe 으로 역추적할 수 있어야 한다.

    이 구현의 답은 **애초에 합치지 않는 것**이다. 두 관측이 각자 남고 `text_key` 가
    같아 필요한 소비자는 묶을 수 있다.
    """
    first = to_observations(_keyframe(8, 71833), [_detection("강원도")], min_confidence=0.7)
    second = to_observations(_keyframe(8, 72600), [_detection("강원도")], min_confidence=0.7)

    assert first.observations[0].text_key == second.observations[0].text_key
    assert first.observations[0].keyframe != second.observations[0].keyframe
    assert first.observations[0].keyframe.timestamp_ms == 71833
    assert second.observations[0].keyframe.timestamp_ms == 72600


def test_text_key_absorbs_spacing_case_and_width_differences() -> None:
    """같은 문구의 표기 차이. 토큰이 같으면 같은 키다 — 유사도 임계값이 필요 없다.

    전각 문자가 일부러 들어 있다(RUF001 을 끈 이유). 화면 글자에는 전각 영숫자가
    실제로 섞이고, NFKC 가 그것을 흡수하는지가 이 테스트의 관심사다.
    """
    wide = "ＬＩＦＥ  style"  # noqa: RUF001
    assert text_key("Life Style", korean_tokens.index_tokens("Life Style")) == text_key(
        wide, korean_tokens.index_tokens(wide)
    )


def test_text_key_separates_different_phrases() -> None:
    assert text_key("강원도", korean_tokens.index_tokens("강원도")) != text_key(
        "우수상품관", korean_tokens.index_tokens("우수상품관")
    )


def test_token_less_texts_are_keyed_by_their_raw_text() -> None:
    """토큰이 비었다고 서로 다른 기호를 한 덩어리로 만들면 안 된다."""
    assert text_key("···", ()) != text_key("▶▶", ())


# ── 상자 ────────────────────────────────────────────────────────────


def test_bounding_box_derives_the_axis_aligned_rectangle() -> None:
    box = BoundingBox(points=((10.0, 4.0), (30.0, 6.0), (30.0, 20.0), (10.0, 18.0)))
    assert (box.x, box.y, box.width, box.height) == (10.0, 4.0, 20.0, 16.0)


def test_bounding_box_keeps_the_polygon() -> None:
    """기울어진 현판·배너를 축에 맞춰 펴면 실제보다 넓은 영역을 가리킨다."""
    points = ((10.0, 4.0), (30.0, 6.0), (30.0, 20.0), (10.0, 18.0))
    assert BoundingBox(points=points).to_json()["points"] == [list(p) for p in points]


def test_bounding_box_rejects_a_degenerate_polygon() -> None:
    with pytest.raises(ValueError, match="세 점"):
        BoundingBox(points=((0.0, 0.0), (1.0, 1.0)))


# ── 여러 장 읽기 ────────────────────────────────────────────────────


def test_read_keyframes_keeps_a_group_per_keyframe(tmp_path: Path) -> None:
    """글자가 없는 프레임도 빈 묶음으로 남는다.

    "읽었는데 없었다" 와 "읽지 않았다" 는 다르고, 뒤엣것은 이 단계의 결함이다.
    """
    keyframes = [_keyframe(0, 1000), _keyframe(0, 2000)]
    paths = {kf.storage_key: tmp_path / f"{kf.timestamp_ms}.jpg" for kf in keyframes}
    engine = _FakeEngine({"1000.jpg": [_detection("강원도")]})

    result = read_keyframes(keyframes, paths, engine=engine)

    assert len(result.keyframes) == 2
    assert result.observation_count == 1
    assert result.keyframes[1].observations == ()


def test_read_keyframes_refuses_when_an_image_is_missing(tmp_path: Path) -> None:
    """일부만 읽고 성공으로 반납하면 "글자가 없었다" 는 거짓이 정본에 남는다."""
    keyframes = [_keyframe(0, 1000), _keyframe(0, 2000)]
    paths = {keyframes[0].storage_key: tmp_path / "1000.jpg"}

    with pytest.raises(KeyError, match="kf-000002000"):
        read_keyframes(keyframes, paths, engine=_FakeEngine())


def test_read_keyframes_reports_the_threshold_it_used() -> None:
    """결과에 임계값이 없으면 나중에 `unverified` 를 재현할 수 없다."""
    result = read_keyframes([], {}, engine=_FakeEngine())
    assert result.min_confidence == get_default_config().min_confidence


def test_result_counts_distinguish_observations_from_phrases(tmp_path: Path) -> None:
    keyframes = [_keyframe(8, 71833), _keyframe(8, 72600)]
    paths = {kf.storage_key: tmp_path / f"{kf.timestamp_ms}.jpg" for kf in keyframes}
    engine = _FakeEngine(
        {
            "71833.jpg": [_detection("강원도"), _detection("RG", 0.29)],
            "72600.jpg": [_detection("강원도")],
        }
    )

    result = read_keyframes(keyframes, paths, engine=engine)

    assert result.observation_count == 3
    # 문구는 둘이다. 관측 셋이 그대로 남아 있고 묶을 수 있을 뿐이다.
    assert result.text_group_count == 2
    assert result.unverified_count == 1


def test_reproducibility_tuple_is_carried_on_the_result() -> None:
    result = read_keyframes([], {}, engine=_FakeEngine())
    assert result.engine == "fake"
    assert result.engine_version == "0"
    assert result.tokenizer == korean_tokens.tokenizer_version()
    assert result.config_version == get_default_config().version_id


# ── 실제 엔진 ───────────────────────────────────────────────────────


@pytest.mark.smoke
def test_real_engine_reads_the_sample_keyframe() -> None:
    """`-m smoke` 로만 돈다. 모델 가중치와 샘플 keyframe 이 있어야 한다.

    티켓의 완료 조건("샘플 뉴스 영상의 추출 keyframe 에서 OCR 실제 동작 확인")을
    자동으로 확인하는 자리다. 샘플은 커밋되지 않으므로(`ai/.gitignore`) 없으면 건너뛴다.
    """
    sample = Path("samples/out/KNI_02205-frames/s0008/kf-000071833.jpg")
    if not sample.is_file():
        pytest.skip(f"샘플 keyframe 이 없다: {sample}")

    from npick_worker.ocr import RapidOcrEngine

    keyframe = KeyframeRef(scene_index=8, timestamp_ms=71833, storage_key=sample.as_posix())
    result = read_keyframes([keyframe], {keyframe.storage_key: sample}, engine=RapidOcrEngine())

    observations = result.keyframes[0].observations
    assert observations, "현판이 있는 프레임인데 아무것도 읽지 못했다"
    assert any("강원도" in obs.raw_text for obs in observations)
    for observation in observations:
        assert 0 <= observation.confidence <= 1
        assert observation.box.width > 0
        assert observation.keyframe.timestamp_ms == 71833
