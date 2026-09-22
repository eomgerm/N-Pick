"""OCR 단계 (S15P21A501-94).

엔진을 실제로 돌리는 테스트는 `smoke` 뿐이다. 나머지는 전부 가짜 엔진을 쓴다 —
이 단계에서 규약인 것은 "무엇을 읽었는가" 가 아니라 **읽은 것을 어떤 행으로 옮기는가**
이고, 그건 모델 없이 표로 검증된다(`test_frame_extraction.py` 가 선정 규칙을 영상 없이
검증하는 것과 같은 판단).
"""

from collections.abc import Iterator
from pathlib import Path

import pytest

from npick_worker import korean_tokens
from npick_worker.jobs.errors import classify
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
    assert observation.tokens_text == "life/sl style/sl"


def test_raw_text_keeps_the_engine_whitespace() -> None:
    """앞뒤 공백을 떼는 것도 원문을 고치는 것이다.

    `strip()` 은 **빈 원문 판정에만** 쓴다. 값에 쓰면 엔진이 준 것과 저장된 것이
    갈리고, 그 순간 `raw_text` 가 "엔진이 읽은 그대로" 가 아니게 된다.
    """
    result = to_observations(_keyframe(), [_detection("  Life Style ")], min_confidence=0.7)
    observation = result.observations[0]
    assert observation.raw_text == "  Life Style "
    # 정규화가 필요한 값들은 각자 처리하므로 공백에 흔들리지 않는다.
    assert observation.tokens_text == "life/sl style/sl"
    assert observation.text_key == text_key("Life Style", observation.tokens)


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


# ── 관측은 병합해도 남는다 ──────────────────────────────────────────


def test_same_phrase_in_two_frames_stays_two_observations() -> None:
    """티켓 요구: 병합해도 원본 관측과 keyframe 으로 역추적할 수 있어야 한다.

    `to_observations` 는 프레임 한 장만 본다. 프레임 사이의 그룹화는 `merge.py` 가
    따로 하고(`test_ocr_merge.py`), 그룹을 만든 뒤에도 여기서 만든 관측은 그대로
    남는다 — 그룹은 이 배열의 인덱스를 가리킬 뿐이다.
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
    # 문구 그룹은 둘이다. 관측 셋은 원본으로 그대로 남는다.
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


# ── 원본 해상도 (FRD docs/frd.md:131) ────────────────────────────────


def test_engine_params_turn_off_the_whole_image_downscale() -> None:
    """`Det.limit_side_len` 과 **다른 축**이 하나 더 있다.

    rapidocr 는 검출기 리사이즈 앞에 전체 이미지 전처리를 돌리고(기본
    `max_side_len=2000`), 인식 조각을 그 줄인 이미지에서 잘라낸다. 그대로 두면 1440p
    이상에서 FRD 의 "원본 해상도의 프레임" 이 깨진다 — 상자 좌표는 복원되지만 인식에
    들어간 픽셀은 돌아오지 않는다.
    """
    from npick_worker.ocr.rapidocr_backend import _engine_params

    params = _engine_params(get_default_config())
    assert params["Global.use_preprocess_img"] is False


def test_the_downscale_we_turn_off_is_real() -> None:
    """왜 저 플래그가 필요한지를 벤더 동작으로 고정한다.

    이 테스트가 깨지면 rapidocr 가 전처리를 바꾼 것이다. 그때 위 플래그가 여전히
    필요한지 다시 판단해야 하므로, 근거를 문서가 아니라 여기에도 남긴다.
    """
    import numpy as np
    from rapidocr.utils.process_img import resize_image_within_bounds

    frame = np.zeros((2160, 3840, 3), dtype=np.uint8)
    shrunk, _, _ = resize_image_within_bounds(frame, 30, 2000)
    assert shrunk.shape[:2] != frame.shape[:2], "전처리가 4K 를 줄이지 않는다면 플래그가 불필요하다"


@pytest.mark.smoke
def test_real_engine_does_not_shrink_a_4k_frame() -> None:
    """설정한 엔진에서 전처리가 실제로 항등인지 본다. `-m smoke` 로만 돈다.

    위의 `_engine_params` 테스트는 우리가 무엇을 넘겼는지만 보고, 이 테스트는
    **넘긴 값이 먹혔는지**를 본다. 키 이름이 바뀌면 생성자가 이미 거절하지만
    (`ParseParams.update_batch`), 기본값이 바뀌는 종류의 회귀는 여기서만 잡힌다.
    """
    import numpy as np

    from npick_worker.ocr import RapidOcrEngine

    engine = RapidOcrEngine()
    frame = np.zeros((2160, 3840, 3), dtype=np.uint8)
    # 어댑터 내부를 들여다본다. 이 테스트의 관심사가 **rapidocr 가 실제로 무엇을
    # 하는가** 라 우리 타입만 봐서는 확인할 수 없다.
    prepared, _ = engine._engine.preprocess_img(frame)
    assert prepared.shape == frame.shape


# ── 엔진 재사용 ─────────────────────────────────────────────────────


@pytest.fixture
def engine_build_count(monkeypatch: pytest.MonkeyPatch) -> Iterator[list[int]]:
    """`RapidOcrEngine` 을 몇 번 만들었는지 센다. 진짜 모델은 올리지 않는다."""
    from npick_worker.ocr import rapidocr_backend

    count = [0]

    class _Counted:
        name = "rapidocr"
        version = "counted"

        def __init__(self, config: OcrConfig | None = None) -> None:
            count[0] += 1

        def read(self, image_path: Path) -> tuple[TextDetection, ...]:
            return ()

    monkeypatch.setattr(rapidocr_backend, "RapidOcrEngine", _Counted)
    rapidocr_backend.shared_engine.cache_clear()
    yield count
    rapidocr_backend.shared_engine.cache_clear()


def test_shared_engine_builds_once_per_process(engine_build_count: list[int]) -> None:
    """세션 생성은 비싸고 인스턴스는 상태가 없다. 한 번만 만든다."""
    from npick_worker.ocr import rapidocr_backend

    config = get_default_config()
    first = rapidocr_backend.shared_engine(config)
    second = rapidocr_backend.shared_engine(config)

    assert first is second
    assert engine_build_count[0] == 1


def test_read_keyframes_does_not_build_an_engine_per_job(engine_build_count: list[int]) -> None:
    """잡마다 엔진을 만들면 워밍업이 앞당기는 것이 가중치 내려받기뿐이 된다."""
    read_keyframes([], {})
    read_keyframes([], {})

    assert engine_build_count[0] == 1


def test_a_failed_build_is_not_cached(monkeypatch: pytest.MonkeyPatch) -> None:
    """가중치를 못 받은 워커가 영원히 못 받는 워커가 되면 안 된다.

    `capability_versions` 가 ocr 를 빼는 근거가 "지금 만들어 보니 실패한다" 이므로,
    실패를 캐시하면 캐시 볼륨이 늦게 붙은 워커가 되살아나지 못한다.
    """
    from npick_worker.ocr import rapidocr_backend

    attempts = [0]

    def _fail_once(config: OcrConfig | None = None) -> object:
        attempts[0] += 1
        if attempts[0] == 1:
            raise rapidocr_backend.OcrModelUnavailableError("가중치 없음")
        return object()

    monkeypatch.setattr(rapidocr_backend, "RapidOcrEngine", _fail_once)
    rapidocr_backend.shared_engine.cache_clear()
    try:
        with pytest.raises(rapidocr_backend.OcrModelUnavailableError):
            rapidocr_backend.shared_engine(get_default_config())
        assert rapidocr_backend.shared_engine(get_default_config()) is not None
        assert attempts[0] == 2
    finally:
        rapidocr_backend.shared_engine.cache_clear()


# ── 실패 분류 (계약 §9.2) ───────────────────────────────────────────


def _engine_raising(exc: BaseException) -> object:
    """모델을 올리지 않고 `read()` 만 시험한다. 생성자는 진짜 세션을 만들기 때문이다."""
    from npick_worker.ocr.rapidocr_backend import RapidOcrEngine

    def _raise(_: str) -> None:
        raise exc

    engine = object.__new__(RapidOcrEngine)
    # 진짜 `RapidOCR` 자리에 던지는 것을 끼운다. 타입은 다르지만 `read()` 가 부르는
    # 것은 `__call__` 하나다.
    engine._engine = _raise  # type: ignore[assignment]
    return engine


@pytest.mark.parametrize("exc", [MemoryError(), OSError("입출력 오류")])
def test_environment_failures_are_not_disguised_as_a_broken_image(exc: BaseException) -> None:
    """`OcrReadError` 로 감싸면 `UNSUPPORTED_MEDIA`(영구)가 되어 attempts 가 동결된다.

    작은 파드에서 난 OOM 은 더 큰 워커에서 성공할 수 있는 잡이다. 그대로 올려야
    `jobs/errors.classify` 가 재시도 가능한 코드로 옮긴다.
    """
    from npick_worker.ocr import OcrReadError

    engine = _engine_raising(exc)
    with pytest.raises(type(exc)):
        engine.read(Path("kf.jpg"))  # type: ignore[attr-defined]

    code, retryable = classify(exc, "ocr")
    assert (code, retryable) != ("UNSUPPORTED_MEDIA", False)
    assert retryable is True
    # 감쌌다면 이 단언이 깨진다.
    assert not isinstance(exc, OcrReadError)


def test_an_unreadable_image_is_still_a_permanent_failure() -> None:
    """되던지기가 "깨진 JPEG" 까지 통과시키면 안 된다.

    rapidocr 는 열 수 없는 파일에 `LoadImageError`(맨 `Exception` 하위)를 던진다.
    """
    from npick_worker.ocr import OcrReadError

    engine = _engine_raising(ValueError("cannot identify image file"))
    with pytest.raises(OcrReadError):
        engine.read(Path("kf.jpg"))  # type: ignore[attr-defined]
