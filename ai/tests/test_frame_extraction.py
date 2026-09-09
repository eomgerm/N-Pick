"""FRD F-03 프레임 추출 검증.

기대값은 코드가 아니라 FRD 와 config toml 에서 옮겨 적는다(tests/test_health.py 규약).
구현 모듈을 참조해 기대값을 만들면 검증이 자기 자신을 확인하는 셈이 된다.
"""

from collections.abc import Callable, Mapping, Sequence
from pathlib import Path

import pytest

from npick_worker.frame_extraction import (
    DEFAULT_CONFIG_PATH,
    ChosenFrame,
    FrameExtractionConfig,
    MediaProfile,
    SceneRequest,
    SceneSpan,
    ScoredFrame,
    SlotCandidates,
    SlotPlan,
    WrittenImage,
    extract_keyframes,
    frames_in_span,
    load_config,
    order_for_output,
    plan_slots,
    select,
)
from npick_worker.frame_extraction.models import Keyframe, SceneKeyframes
from npick_worker.timecode import frames_to_ms, ms_to_frame

MakeVideo = Callable[[str, Sequence[tuple[str, int]]], Path]

#: conftest 의 합성 영상 규격. 10fps, 20프레임 = 2000ms.
VIDEO_WIDTH = 160
VIDEO_HEIGHT = 120
VIDEO_FPS = 10.0
BLOCK_FRAMES = 20
BLOCK_MS = 2000

#: JPEG 파일의 시작 바이트. 인코더를 바꿔도 이건 바뀌지 않는다.
JPEG_MAGIC = b"\xff\xd8\xff"


def _cfg(**overrides: object) -> FrameExtractionConfig:
    """기본 설정에서 일부만 바꾼 사본. 테스트가 toml 을 건드리지 않게 한다."""
    return load_config().model_copy(update=overrides)


def _span(start_ms: int, end_ms: int, index: int = 0) -> SceneSpan:
    return SceneSpan(scene_index=index, start_time_ms=start_ms, end_time_ms=end_ms)


def _plan(
    span: SceneSpan, config: FrameExtractionConfig | None = None, fps: float = VIDEO_FPS
) -> tuple[SlotPlan, ...]:
    """`plan_slots` 호출. 기본 fps 는 conftest 합성 영상과 같은 값이다.

    순수 테스트와 영상 테스트가 같은 fps 를 말하면 두 쪽의 기대 장 수를 함께 읽을 수 있다.
    """
    return plan_slots(span, config if config is not None else load_config(), fps)


# ── 설정: FRD 가 요구하는 하한 ──────────────────────────────────────────


def test_default_config_never_produces_a_single_keyframe() -> None:
    """FRD F-03 은 "장면의 **복수** 키프레임" 을 요구한다(docs/frd.md:121).

    설정 하나로 그 요구가 조용히 깨지지 않아야 하므로 하한을 여기서 못 박는다.
    """
    assert load_config().min_keyframes_per_scene >= 2


def test_config_rejects_single_keyframe_lower_bound() -> None:
    raw = DEFAULT_CONFIG_PATH.read_text(encoding="utf-8")
    with pytest.raises(ValueError, match="min_keyframes_per_scene"):
        FrameExtractionConfig.model_validate(
            {
                **_toml(raw),
                "min_keyframes_per_scene": 1,
            }
        )


def test_config_rejects_max_below_min() -> None:
    """상한이 하한보다 작으면 실제 장 수가 max 도 min 도 아닌 값이 된다."""
    raw = _toml(DEFAULT_CONFIG_PATH.read_text(encoding="utf-8"))
    with pytest.raises(ValueError, match="max_keyframes_per_scene"):
        FrameExtractionConfig.model_validate(
            {**raw, "min_keyframes_per_scene": 4, "max_keyframes_per_scene": 3}
        )


def test_config_version_is_the_pinned_vector() -> None:
    """계약 §7 의 고정 테스트 벡터. BE 가 같은 값을 Java 로 계산한다.

    값이 바뀌면 설정이 바뀐 것이다. 그때는 이 줄과 docs/contracts/job-api.md 를 함께
    고친다 — 한쪽만 고치면 어긋난 것을 아무도 못 잡는다.
    """
    assert load_config().version_id == "frame-extract/v1:5b266b10"


def test_config_version_changes_when_a_threshold_changes() -> None:
    assert _cfg(jpeg_qscale=5).version_id != load_config().version_id


def test_config_rejects_unknown_key() -> None:
    # toml 키 오타가 조용히 무시되면 version_id 는 바뀌는데 동작은 그대로다.
    raw = _toml(DEFAULT_CONFIG_PATH.read_text(encoding="utf-8"))
    with pytest.raises(ValueError, match="jpeg_quality"):
        FrameExtractionConfig.model_validate({**raw, "jpeg_quality": 92})


def _toml(text: str) -> dict[str, object]:
    import tomllib

    return tomllib.loads(text)


# ── 후보 계획 ──────────────────────────────────────────────────────────


def test_short_scene_still_gets_multiple_slots() -> None:
    """scene_detection 의 최소 장면 길이(1000ms)에서도 복수 keyframe 이 나와야 한다."""
    slots = _plan(_span(0, 1000))
    assert len(slots) == 2


def test_slot_count_follows_the_interval() -> None:
    # interval_ms 3000 → 9000ms 짜리 장면은 3장.
    slots = _plan(_span(0, 9000), _cfg(interval_ms=3000))
    assert len(slots) == 3


def test_slot_count_is_capped_by_max() -> None:
    # 60초 장면이면 간격상 20장이지만 상한이 이긴다. 상한은 후속 VLM·OCR 의 비용 상한이다.
    slots = _plan(_span(0, 60_000), _cfg(interval_ms=3000, max_keyframes_per_scene=5))
    assert len(slots) == 5


def test_slots_stay_inside_the_edge_margin() -> None:
    """컷 직후·직전 프레임은 전환 잔상이 걸리기 쉬우므로 여백 안에 후보를 두지 않는다."""
    config = _cfg(edge_margin_ms=250)
    slots = _plan(_span(10_000, 20_000), config)
    for slot in slots:
        for candidate_ms in slot.candidates_ms:
            assert 10_250 <= candidate_ms < 19_750


def test_edge_margin_is_dropped_for_a_scene_too_short_to_afford_it() -> None:
    """여백은 품질을 위한 선호이고 장 수 확보가 요구사항이다.

    여백을 지키려고 빈 창을 돌려주면 그 scene 의 keyframe 이 0 장이 되는데, 이 단계는
    치명 단계다.
    """
    slots = _plan(_span(0, 400), _cfg(edge_margin_ms=250))
    assert slots
    for slot in slots:
        for candidate_ms in slot.candidates_ms:
            assert 0 <= candidate_ms < 400


def test_candidates_are_ordered_from_the_slot_center_outward() -> None:
    """선호 순위가 곧 동점 규칙이다. 같은 점수면 중심에 가까운 쪽이 이겨야 한다."""
    config = _cfg(candidates_per_slot=3, candidate_step_ms=120, edge_margin_ms=0)
    (slot,) = _plan(_span(0, 4000), config.model_copy(update={"max_keyframes_per_scene": 2}))[:1]
    center = slot.center_ms
    assert slot.candidates_ms[0] == center
    assert set(slot.candidates_ms[1:]) == {center - 120, center + 120}


def test_candidates_are_deduplicated_when_clamped() -> None:
    """창이 좁으면 후보가 같은 ms 로 몰린다. 같은 자리를 두 번 재지 않는다."""
    config = _cfg(candidates_per_slot=5, candidate_step_ms=1000, edge_margin_ms=0)
    for slot in _plan(_span(0, 1000), config):
        assert len(set(slot.candidates_ms)) == len(slot.candidates_ms)


def test_slot_count_cannot_exceed_the_frames_in_the_window() -> None:
    """창에 프레임이 1장이면 서로 다른 프레임을 받을 슬롯도 1개뿐이다.

    상한이 창의 ms 가 아니라 프레임 수인 것이 `_check_keyframe_count` 의 기대치와 같은
    단위가 되는 근거다. 10fps 에서 100ms 창은 정규 시각이 0ms 하나만 들어온다.
    """
    slots = _plan(_span(0, 100), _cfg(edge_margin_ms=0))
    assert len(slots) == 1


def test_plan_rejects_a_scene_with_no_frame_in_it() -> None:
    """구간이 한 프레임 간격보다 짧으면 계획할 자리가 없다.

    슬롯 0개를 돌려주면 그 scene 의 keyframe 이 조용히 0장이 되고, 1개로 올려 주면 없는
    프레임을 향해 계획하는 것이다. `frames_in_span` 과 같은 어휘로 거절한다.

    10fps 의 정규 시각은 0·100·200ms 이므로 `[1, 2)` 에는 어느 프레임도 들어오지 않는다.
    """
    with pytest.raises(ValueError, match="프레임이 없다"):
        _plan(_span(1, 2), _cfg(edge_margin_ms=0))


def test_plan_rejects_an_empty_scene() -> None:
    with pytest.raises(ValueError, match="길이가 0 이하"):
        _plan(_span(1000, 1000))


# ── 선정 ───────────────────────────────────────────────────────────────


def _scored(**frames: tuple[float, float]) -> Mapping[int, ScoredFrame]:
    """`{"12": (score, luma_std)}` 를 측정값 맵으로 바꾼다."""
    return {
        int(number): ScoredFrame(
            frame_number=int(number),
            timestamp_ms=int(number) * 100,
            score=score,
            luma_std=luma_std,
        )
        for number, (score, luma_std) in frames.items()
    }


def test_select_picks_the_sharpest_candidate() -> None:
    chosen = select(
        [SlotCandidates(slot_index=0, frame_numbers=(10, 11, 12))],
        _scored(**{"10": (5.0, 40.0), "11": (90.0, 40.0), "12": (7.0, 40.0)}),
        load_config(),
    )
    assert [frame.frame_number for frame in chosen] == [11]
    assert chosen[0].blank is False


def test_select_skips_blank_candidates() -> None:
    """암전·화이트아웃은 선명도가 높게 나올 수 있어도 대표가 되면 안 된다."""
    chosen = select(
        [SlotCandidates(slot_index=0, frame_numbers=(10, 11))],
        _scored(**{"10": (90.0, 1.0), "11": (5.0, 40.0)}),
        _cfg(min_luma_std=8.0),
    )
    assert [frame.frame_number for frame in chosen] == [11]


def test_select_uses_a_blank_frame_when_there_is_no_alternative() -> None:
    """치명 단계이므로 keyframe 0 장으로 끝내는 것이 블랭크 한 장보다 나쁘다."""
    chosen = select(
        [SlotCandidates(slot_index=0, frame_numbers=(10, 11))],
        _scored(**{"10": (2.0, 1.0), "11": (9.0, 1.0)}),
        _cfg(min_luma_std=8.0),
    )
    assert [frame.frame_number for frame in chosen] == [11]
    # 그 사실을 지우지도 않는다. 0 이 아니면 사람이 한 번 봐야 한다는 신호다.
    assert chosen[0].blank is True


def test_select_never_returns_the_same_frame_twice() -> None:
    """같은 프레임을 두 번 저장하면 OCR 이 같은 화면을 두 번 읽는다."""
    chosen = select(
        [
            SlotCandidates(slot_index=0, frame_numbers=(10, 11)),
            SlotCandidates(slot_index=1, frame_numbers=(10, 12)),
        ],
        _scored(**{"10": (90.0, 40.0), "11": (5.0, 40.0), "12": (7.0, 40.0)}),
        load_config(),
    )
    assert [frame.frame_number for frame in chosen] == [10, 12]


def test_select_drops_a_slot_whose_candidates_are_all_taken() -> None:
    chosen = select(
        [
            SlotCandidates(slot_index=0, frame_numbers=(10,)),
            SlotCandidates(slot_index=1, frame_numbers=(10,)),
        ],
        _scored(**{"10": (90.0, 40.0)}),
        load_config(),
    )
    assert [frame.frame_number for frame in chosen] == [10]


def test_select_ignores_candidates_the_decoder_never_reached() -> None:
    chosen = select(
        [SlotCandidates(slot_index=0, frame_numbers=(10, 99))],
        _scored(**{"10": (5.0, 40.0)}),
        load_config(),
    )
    assert [frame.frame_number for frame in chosen] == [10]


def test_select_returns_timestamp_order() -> None:
    chosen = select(
        [
            SlotCandidates(slot_index=0, frame_numbers=(30,)),
            SlotCandidates(slot_index=1, frame_numbers=(10,)),
        ],
        _scored(**{"30": (5.0, 40.0), "10": (5.0, 40.0)}),
        load_config(),
    )
    assert [frame.timestamp_ms for frame in chosen] == [1000, 3000]


# ── 대표 이미지 ────────────────────────────────────────────────────────


def _chosen(timestamp_ms: int, score: float, *, blank: bool = False) -> ChosenFrame:
    return ChosenFrame(
        frame_number=timestamp_ms // 100, timestamp_ms=timestamp_ms, score=score, blank=blank
    )


def test_representative_is_the_first_element() -> None:
    """`keyframe` 에 대표 표시 컬럼이 없어 순서가 곧 표시다.

    ERD 주석("결과 목록의 대표 이미지는 첫 장을 쓴다")이 이 규약으로 성립한다.
    """
    ordered = order_for_output([_chosen(1000, 5.0), _chosen(2000, 90.0), _chosen(3000, 7.0)])
    assert ordered[0].timestamp_ms == 2000


def test_the_rest_stay_in_timestamp_order() -> None:
    ordered = order_for_output([_chosen(3000, 7.0), _chosen(2000, 90.0), _chosen(1000, 5.0)])
    assert [frame.timestamp_ms for frame in ordered] == [2000, 1000, 3000]


def test_representative_tie_is_broken_by_the_earlier_timestamp() -> None:
    """동점 규칙이 없으면 대표가 흔들려 결과 카드의 이미지가 재처리마다 바뀐다."""
    ordered = order_for_output([_chosen(3000, 42.0), _chosen(1000, 42.0)])
    assert ordered[0].timestamp_ms == 1000


# ── scene 묶음 불변식 ──────────────────────────────────────────────────


def _keyframe(timestamp_ms: int, scene_index: int = 0) -> Keyframe:
    return Keyframe(
        scene_index=scene_index,
        timestamp_ms=timestamp_ms,
        frame_number=timestamp_ms // 100,
        file_name=f"s{scene_index:04d}/kf-{timestamp_ms:09d}.jpg",
        byte_size=1,
        content_sha256="0" * 64,
        score=1.0,
        blank=False,
    )


def test_scene_without_keyframes_is_rejected() -> None:
    """FRD §3 은 대표 이미지 없이 검색 가능으로 표시하지 않도록 요구한다."""
    with pytest.raises(ValueError, match="keyframe 이 없는 scene"):
        SceneKeyframes(scene_index=0, keyframes=())


def test_duplicate_timestamps_are_rejected() -> None:
    """UNIQUE(scene_id, timestamp_ms) 를 BE 에서 터지기 전에 잡는다."""
    with pytest.raises(ValueError, match="timestamp_ms"):
        SceneKeyframes(scene_index=0, keyframes=(_keyframe(1000), _keyframe(1000)))


def test_keyframes_from_another_scene_are_rejected() -> None:
    with pytest.raises(ValueError, match="다른 scene"):
        SceneKeyframes(scene_index=0, keyframes=(_keyframe(1000), _keyframe(2000, scene_index=1)))


# ── ms ↔ 프레임 변환 ───────────────────────────────────────────────────


def test_ms_to_frame_is_the_inverse_of_frames_to_ms() -> None:
    """두 단계가 같은 규칙을 써야 keyframe 이 자기 scene 안의 프레임을 가리킨다."""
    for frame_number in range(200):
        assert ms_to_frame(frames_to_ms(frame_number, 29.97), 29.97) == frame_number


def test_ms_to_frame_picks_the_nearest_frame() -> None:
    """가장 가까운 프레임이다. 그래서 정규 시각이 목표보다 뒤일 수도 있다."""
    # 30fps 에서 프레임 1 의 정규 시각은 33ms 다. 30ms 는 프레임 0(0ms)보다 그쪽에 가깝다.
    assert ms_to_frame(30, 30.0) == 1
    assert frames_to_ms(1, 30.0) == 33


def test_frames_in_span_stays_inside_the_half_open_interval() -> None:
    """양 끝에서 반 프레임이 옆 scene 으로 새지 않아야 한다.

    30fps 에서 반 프레임은 17ms 다. 새면 `keyframe.timestamp_ms` 가 자기 scene 밖을
    가리키고, 검수자가 근거 프레임을 눌렀을 때 다른 장면이 열린다.
    """
    for start_ms in range(0, 2000, 13):
        span = _span(start_ms, start_ms + 1000)
        first, last = frames_in_span(span, 29.97)
        assert start_ms <= frames_to_ms(first, 29.97)
        assert frames_to_ms(last, 29.97) < start_ms + 1000
        # 바로 밖의 프레임은 정말로 밖이어야 한다 — 구간을 필요 이상으로 좁히지 않는다.
        assert frames_to_ms(first - 1, 29.97) < start_ms
        assert frames_to_ms(last + 1, 29.97) >= start_ms + 1000


def test_frames_in_span_rejects_a_span_shorter_than_one_frame() -> None:
    """정규 시각이 들어오는 프레임이 없으면 조용히 빈 결과를 내지 않는다."""
    with pytest.raises(ValueError, match="프레임이 없다"):
        frames_in_span(_span(1, 2), 10.0)


# ── 실제 영상 ──────────────────────────────────────────────────────────


def _spans(count: int) -> tuple[SceneSpan, ...]:
    return tuple(_span(index * BLOCK_MS, (index + 1) * BLOCK_MS, index) for index in range(count))


def test_extract_produces_multiple_keyframes_per_scene(
    make_video: MakeVideo, tmp_path: Path
) -> None:
    video = make_video("frames", [("bars", BLOCK_FRAMES), ("noise", BLOCK_FRAMES)])
    result = extract_keyframes(video, _spans(2), tmp_path / "out")

    assert len(result.scenes) == 2
    for scene in result.scenes:
        assert len(scene.keyframes) >= 2


def test_extract_keeps_the_source_resolution(make_video: MakeVideo, tmp_path: Path) -> None:
    """작은 글자 OCR 이 축소된 대표 이미지가 아니라 원본 해상도 프레임을 써야 한다
    (docs/frd.md:131). 이 단계는 다운스케일하지 않는다."""
    video = make_video("res", [("bars", BLOCK_FRAMES)])
    result = extract_keyframes(video, _spans(1), tmp_path / "out")

    assert (result.image_width, result.image_height) == (VIDEO_WIDTH, VIDEO_HEIGHT)


def test_extract_writes_readable_jpeg_files(make_video: MakeVideo, tmp_path: Path) -> None:
    out = tmp_path / "out"
    video = make_video("files", [("bars", BLOCK_FRAMES), ("noise", BLOCK_FRAMES)])
    result = extract_keyframes(video, _spans(2), out)

    for scene in result.scenes:
        for keyframe in scene.keyframes:
            body = (out / keyframe.file_name).read_bytes()
            assert body.startswith(JPEG_MAGIC)
            # 봉투가 신고하는 크기·해시가 실제 파일과 같아야 한다. 다르면 BE 의
            # X-Content-SHA256 검사(JOB_400_002)가 업로드를 거절한다.
            assert keyframe.byte_size == len(body)


def test_extract_links_every_keyframe_to_its_scene(make_video: MakeVideo, tmp_path: Path) -> None:
    """ "각 keyframe 이 어떤 scene 에서 생성된 것인지 추적 가능" 이 티켓의 요구다."""
    video = make_video("link", [("bars", BLOCK_FRAMES), ("noise", BLOCK_FRAMES)])
    result = extract_keyframes(video, _spans(2), tmp_path / "out")

    for scene in result.scenes:
        for keyframe in scene.keyframes:
            assert keyframe.scene_index == scene.scene_index
            assert keyframe.file_name.startswith(f"s{scene.scene_index:04d}/")


def test_keyframe_timestamps_stay_inside_their_scene(make_video: MakeVideo, tmp_path: Path) -> None:
    video = make_video("bounds", [("bars", BLOCK_FRAMES), ("noise", BLOCK_FRAMES)])
    spans = _spans(2)
    result = extract_keyframes(video, spans, tmp_path / "out")

    for scene, span in zip(result.scenes, spans, strict=True):
        for keyframe in scene.keyframes:
            assert span.start_time_ms <= keyframe.timestamp_ms < span.end_time_ms


def test_extract_marks_exactly_one_representative_per_scene(
    make_video: MakeVideo, tmp_path: Path
) -> None:
    video = make_video("rep", [("bars", BLOCK_FRAMES), ("noise", BLOCK_FRAMES)])
    result = extract_keyframes(video, _spans(2), tmp_path / "out")

    for scene in result.scenes:
        assert scene.representative is scene.keyframes[0]


def test_extract_is_deterministic(make_video: MakeVideo, tmp_path: Path) -> None:
    """같은 입력 + 같은 재현 식별자면 항상 같은 결과다.

    재처리가 산출물의 의미를 바꾸지 않아야 한다는 FRD §3 F-03 의 전제다.
    """
    video = make_video("determinism", [("bars", BLOCK_FRAMES), ("noise", BLOCK_FRAMES)])
    first = extract_keyframes(video, _spans(2), tmp_path / "a")
    second = extract_keyframes(video, _spans(2), tmp_path / "b")

    def digest(result: object) -> list[tuple[int, int, str]]:
        assert hasattr(result, "scenes")
        return [
            (keyframe.scene_index, keyframe.timestamp_ms, keyframe.content_sha256)
            for scene in result.scenes
            for keyframe in scene.keyframes
        ]

    assert digest(first) == digest(second)


def test_extract_rejects_overlapping_scenes(make_video: MakeVideo, tmp_path: Path) -> None:
    """겹치면 같은 프레임이 두 scene 의 keyframe 이 되고 근거가 흐려진다."""
    video = make_video("overlap", [("bars", BLOCK_FRAMES), ("noise", BLOCK_FRAMES)])
    scenes = (_span(0, 3000, 0), _span(2000, 4000, 1))
    with pytest.raises(ValueError, match="겹친다"):
        extract_keyframes(video, scenes, tmp_path / "out")


def test_extract_rejects_duplicate_scene_index(make_video: MakeVideo, tmp_path: Path) -> None:
    video = make_video("dupindex", [("bars", BLOCK_FRAMES)])
    scenes = (_span(0, 1000, 0), _span(1000, 2000, 0))
    with pytest.raises(ValueError, match="scene_index 가 중복"):
        extract_keyframes(video, scenes, tmp_path / "out")


def test_extract_rejects_an_empty_scene_list(make_video: MakeVideo, tmp_path: Path) -> None:
    video = make_video("noscenes", [("bars", BLOCK_FRAMES)])
    with pytest.raises(ValueError, match="scene 이 없다"):
        extract_keyframes(video, (), tmp_path / "out")


def test_extract_fails_when_a_scene_is_past_the_end_of_the_media(
    make_video: MakeVideo, tmp_path: Path
) -> None:
    """상류 scene 구간이 이 미디어의 것이 아니라는 뜻이다. 빈 결과로 넘어가지 않는다."""
    video = make_video("past-end", [("bars", BLOCK_FRAMES)])
    scenes = (_span(0, BLOCK_MS, 0), _span(600_000, 601_000, 1))
    with pytest.raises(ValueError, match="프레임을 얻지 못했다"):
        extract_keyframes(video, scenes, tmp_path / "out")


def test_extract_fails_when_a_scene_only_partly_overlaps_the_media(
    make_video: MakeVideo, tmp_path: Path
) -> None:
    """구간의 앞부분만 미디어에 있으면 뒤쪽 슬롯이 조용히 사라진다.

    구간 전체가 미디어 밖인 경우와 같은 사실(상류 scene 이 이 미디어의 것이 아니다)인데,
    0 장만 막으면 부분 초과는 한 장으로 **성공**한다. FRD F-03 의 "복수 키프레임" 이
    거기서 조용히 깨지므로 정도에 관계없이 같게 다룬다.
    """
    video = make_video("partly-past-end", [("bars", BLOCK_FRAMES)])
    scenes = (_span(0, 1200, 0), _span(1200, BLOCK_MS + 800, 1))
    with pytest.raises(ValueError, match="기대보다 적다"):
        extract_keyframes(video, scenes, tmp_path / "out")


def test_extract_allows_one_keyframe_when_the_scene_holds_a_single_frame(
    make_video: MakeVideo, tmp_path: Path
) -> None:
    """구간에 프레임이 한 장뿐이면 1 장이 그 구간의 전부다.

    부족분 검사가 **없는 프레임을 요구하지 않는다**는 것을 못 박는다. 이 경계가 없으면
    아주 짧은 scene 하나가 치명 단계를 실패로 만든다.
    """
    video = make_video("single-frame-span", [("bars", BLOCK_FRAMES)])
    # 10fps 의 정규 시각은 100ms 간격이므로 이 반열린 구간 안에는 1200ms 한 장만 있다.
    result = extract_keyframes(video, (_span(1150, 1250, 0),), tmp_path / "out")

    (scene,) = result.scenes
    assert len(scene.keyframes) == 1
    assert scene.keyframes[0].timestamp_ms == 1200
    assert scene.representative is scene.keyframes[0]


# ── 주입 경로 ──────────────────────────────────────────────────────────


class FakeGrabber:
    """provenance 가 결과에 그대로 실리는지 확인하는 대역."""

    name = "fake-grabber"
    version = "test-4.5.6"

    def profile(self, video_path: Path) -> MediaProfile:
        return MediaProfile(frame_rate=10.0, width=320, height=240)

    def measure(
        self,
        video_path: Path,
        requests: Sequence[SceneRequest],
        cfg: FrameExtractionConfig,
    ) -> Mapping[int, Mapping[int, ScoredFrame]]:
        return {
            request.scene_index: {
                number: ScoredFrame(
                    frame_number=number,
                    timestamp_ms=frames_to_ms(number, 10.0),
                    # 슬롯 순번이 클수록 선명하게 둬서 대표가 첫 슬롯이 아니게 만든다.
                    score=float(slot.slot_index + 1),
                    luma_std=40.0,
                )
                for slot in request.slots
                for number in slot.frame_numbers[:1]
            }
            for request in requests
        }

    def write(
        self,
        video_path: Path,
        targets: Mapping[int, Path],
        cfg: FrameExtractionConfig,
    ) -> Mapping[int, WrittenImage]:
        return {
            number: WrittenImage(path=path, byte_size=11, content_sha256="a" * 64)
            for number, path in targets.items()
        }


def test_injected_grabber_provenance_reaches_the_result(tmp_path: Path) -> None:
    result = extract_keyframes(
        tmp_path / "missing.mp4", _spans(2), tmp_path / "out", grabber=FakeGrabber()
    )

    assert (result.engine, result.engine_version) == ("fake-grabber", "test-4.5.6")
    assert (result.image_width, result.image_height) == (320, 240)
    # 대표는 시각 순서가 아니라 점수로 뽑힌다. 첫 슬롯이 아닌 것이 여기서 확인된다.
    for scene in result.scenes:
        assert scene.representative.timestamp_ms == max(
            keyframe.timestamp_ms for keyframe in scene.keyframes
        )


def test_write_failure_is_not_reported_as_fewer_keyframes(tmp_path: Path) -> None:
    """반쯤 채운 결과를 내면 BE 는 keyframe 이 원래 그만큼인 줄 안다."""

    class HalfWriteGrabber(FakeGrabber):
        def write(
            self,
            video_path: Path,
            targets: Mapping[int, Path],
            cfg: FrameExtractionConfig,
        ) -> Mapping[int, WrittenImage]:
            return {}

    with pytest.raises(ValueError, match="저장하지 못했다"):
        extract_keyframes(
            tmp_path / "missing.mp4", _spans(1), tmp_path / "out", grabber=HalfWriteGrabber()
        )


def test_unreached_candidates_are_not_reported_as_fewer_keyframes(tmp_path: Path) -> None:
    """디코드가 뒤쪽 후보에 닿지 못한 경우. 미디어가 1000ms 에서 끝난 것과 같다.

    `select` 는 `scored` 에 없는 후보를 조용히 건너뛰므로 이 상황이 그대로 통과하면
    keyframe 이 한 장인 채로 succeeded 가 된다. BE 는 그 장면이 원래 그만큼인 줄 안다.
    """

    class TruncatedGrabber(FakeGrabber):
        def measure(
            self,
            video_path: Path,
            requests: Sequence[SceneRequest],
            cfg: FrameExtractionConfig,
        ) -> Mapping[int, Mapping[int, ScoredFrame]]:
            return {
                index: {
                    number: frame
                    for number, frame in frames.items()
                    if frames_to_ms(number, 10.0) < 1000
                }
                for index, frames in super().measure(video_path, requests, cfg).items()
            }

    with pytest.raises(ValueError, match="기대보다 적다"):
        extract_keyframes(
            tmp_path / "missing.mp4", _spans(1), tmp_path / "out", grabber=TruncatedGrabber()
        )


# ── 장 수 보증: 어떤 scene 길이도 기대치를 밑돌지 않는다 ──────────────────

#: 방송·웹에서 실제로 만나는 CFR 값 전부와, conftest 합성 영상의 10fps.
SWEEP_FRAME_RATES = (10.0, 23.976, 24.0, 25.0, 29.97, 30.0, 50.0, 59.94, 60.0, 120.0)

#: scene 시작 시각을 섞는 이유는 `frames_to_ms` 가 반올림이라 프레임 격자와 구간 경계의
#: 위상차가 결과를 바꾸기 때문이다. 0 만 보면 위상이 어긋난 구간을 놓친다.
SWEEP_SCENE_STARTS = (0, 1, 7, 13, 499, 997, 60_000, 60_001, 75_533)

#: 이 값까지 훑는다. 계획이 여백 없는 창으로 갈리는 최대 duration 이 기본 설정에서
#: 653ms(10fps)이므로 두 배 여유를 둔다. `edge_margin_ms` 를 키우면 이 경계도 커진다.
SWEEP_MAX_DURATION_MS = 1200


class _AllCandidatesGrabber:
    """모든 후보 프레임을 재는 대역. 디코드가 후보 전부에 닿은 최선의 경우다.

    `FakeGrabber` 와 달리 슬롯의 후보를 하나로 줄이지 않는다. 여기서 보려는 것은
    "디코드가 닿지 못해 슬롯이 사라지는" 경우가 아니라 **계획 자체가 몇 장을 가능하게
    하는가** 이기 때문이다. 받은 `requests` 를 그대로 들고 있어서 테스트가 실제 슬롯의
    프레임 집합을 볼 수 있다 — `_to_frames` 를 테스트가 다시 구현하면 프로덕션과 다른
    규칙을 검증하게 된다.
    """

    name = "all-candidates-grabber"
    version = "test-0.0.0"

    def __init__(self, frame_rate: float) -> None:
        self.frame_rate = frame_rate
        self.requests: list[SceneRequest] = []

    def profile(self, video_path: Path) -> MediaProfile:
        return MediaProfile(frame_rate=self.frame_rate, width=64, height=48)

    def measure(
        self,
        video_path: Path,
        requests: Sequence[SceneRequest],
        cfg: FrameExtractionConfig,
    ) -> Mapping[int, Mapping[int, ScoredFrame]]:
        self.requests.extend(requests)
        return {
            request.scene_index: {
                number: ScoredFrame(
                    frame_number=number,
                    timestamp_ms=frames_to_ms(number, self.frame_rate),
                    score=1.0 + number,
                    luma_std=40.0,
                )
                for slot in request.slots
                for number in slot.frame_numbers
            }
            for request in requests
        }

    def write(
        self,
        video_path: Path,
        targets: Mapping[int, Path],
        cfg: FrameExtractionConfig,
    ) -> Mapping[int, WrittenImage]:
        return {
            number: WrittenImage(path=path, byte_size=11, content_sha256="a" * 64)
            for number, path in targets.items()
        }


def _survives_any_score_order(slots: Sequence[SlotCandidates], expected: int) -> bool:
    """어떤 점수 배치에서도 `expected` 개의 서로 다른 프레임을 고르는가.

    `select` 규칙 3 은 슬롯 순서·점수 순 그리디다. 슬롯마다 남은 후보 중 무엇이든 최고
    점수가 될 수 있다고 보고 전부 갈라 본다 — 실제로는 하나의 전역 점수 순서만 가능하므로
    이 탐색이 실패를 못 찾으면 어떤 영상에서도 실패가 없다. 슬롯 <= 5, 후보 <= 3 이라 싸다.
    """

    def walk(index: int, taken: frozenset[int], picked: int) -> bool:
        if picked + (len(slots) - index) < expected:
            return False
        if index == len(slots):
            return picked >= expected
        available = [number for number in slots[index].frame_numbers if number not in taken]
        if not available:
            return walk(index + 1, taken, picked)
        return all(walk(index + 1, taken | {number}, picked + 1) for number in available)

    return walk(0, frozenset(), 0)


@pytest.mark.parametrize("frame_rate", SWEEP_FRAME_RATES)
def test_no_scene_length_yields_fewer_keyframes_than_expected(
    frame_rate: float, tmp_path: Path
) -> None:
    """계약 §4.3.1 의 "장 수가 줄어든 성공은 오지 않는다" 를 전수로 잠근다.

    상한을 창의 ms 로 두면 여백을 뺀 창이 한 프레임 간격보다 좁은 duration 구간에서
    슬롯 중심과 후보가 같은 프레임으로 모여 1 장이 됐다. 기본 설정 기준 501~599ms 이고
    fps 가 낮을수록 넓다(10fps 599, 30fps 534, 120fps 508). 치명 단계의 영구 실패였다.
    """
    config = load_config()
    for start in SWEEP_SCENE_STARTS:
        for duration in range(1, SWEEP_MAX_DURATION_MS + 1):
            scene = _span(start, start + duration)
            try:
                first, last = frames_in_span(scene, frame_rate)
            except ValueError:
                continue  # 구간에 프레임이 없는 것은 이 검사의 대상이 아니다
            grabber = _AllCandidatesGrabber(frame_rate)
            result = extract_keyframes(
                Path("sweep.mp4"), (scene,), tmp_path / "sweep", None, grabber
            )
            expected = min(config.min_keyframes_per_scene, last - first + 1)
            (kept,) = result.scenes
            assert len(kept.keyframes) >= expected, (frame_rate, start, duration)
            (request,) = grabber.requests
            assert _survives_any_score_order(request.slots, expected), (
                frame_rate,
                start,
                duration,
            )


@pytest.mark.parametrize(
    ("frame_rate", "duration_ms"),
    [(30.0, 501), (30.0, 534), (10.0, 501), (10.0, 599), (120.0, 508)],
)
def test_a_scene_just_past_twice_the_edge_margin_gets_the_lower_bound(
    frame_rate: float, duration_ms: int, tmp_path: Path
) -> None:
    """여백을 뺀 창이 한 프레임도 담지 못하는 구간. 각 fps 의 실패 띠 경계다.

    `duration > 2 * edge_margin_ms` 라 여백을 적용하는데 남는 창이 몇 ms 뿐이었고, 그
    안의 목표 시각이 전부 같은 프레임으로 반올림됐다. 여백을 포기하는 기준이 프레임 수라야
    이 구간이 하한을 받는다.
    """
    grabber = _AllCandidatesGrabber(frame_rate)
    result = extract_keyframes(
        Path("edge.mp4"), (_span(0, duration_ms),), tmp_path / "edge", None, grabber
    )

    (scene,) = result.scenes
    assert len(scene.keyframes) == load_config().min_keyframes_per_scene
    assert len({keyframe.timestamp_ms for keyframe in scene.keyframes}) == len(scene.keyframes)
    assert all(0 <= keyframe.timestamp_ms < duration_ms for keyframe in scene.keyframes)


def test_extract_gets_two_keyframes_from_a_narrow_window_scene(
    make_video: MakeVideo, tmp_path: Path
) -> None:
    """위 경계를 실제 디코드·인코드 경로로도 확인한다. 10fps 501ms 는 합성 영상으로 만들 수 있다."""
    video = make_video("narrow-window", [("bars", BLOCK_FRAMES)])
    result = extract_keyframes(video, (_span(0, 501),), tmp_path / "out")

    (scene,) = result.scenes
    assert len(scene.keyframes) == 2
    assert scene.keyframes[0].timestamp_ms != scene.keyframes[1].timestamp_ms
    assert all(0 <= keyframe.timestamp_ms < 501 for keyframe in scene.keyframes)


@pytest.mark.parametrize("frame_rate", SWEEP_FRAME_RATES)
def test_normal_scenes_keep_the_margin_and_the_interval_count(frame_rate: float) -> None:
    """상류 최소 길이(1000ms) 이상인 scene 의 계획은 프레임 상한에 걸리지 않는다.

    프레임 기준으로 옮긴 것이 정상 구간의 계획을 건드리지 않았다는 뜻이다. 옛 계획을
    스냅샷으로 떠 두는 대신 두 성질로 잠근다 — 여백이 유지되고, 장 수가 간격·상하한만으로
    정해진다. 스냅샷은 옛 코드가 사라지면 자기 자신을 확인하는 셈이 된다.
    """
    config = load_config()
    for start in SWEEP_SCENE_STARTS:
        for duration in (1000, 1500, 2999, 3000, 9000, 60_000):
            slots = _plan(_span(start, start + duration), config, frame_rate)
            assert len(slots) == min(
                max(duration // config.interval_ms, config.min_keyframes_per_scene),
                config.max_keyframes_per_scene,
            ), (frame_rate, start, duration)
            for slot in slots:
                for candidate_ms in slot.candidates_ms:
                    assert (
                        start + config.edge_margin_ms
                        <= candidate_ms
                        < start + duration - config.edge_margin_ms
                    ), (frame_rate, start, duration)
