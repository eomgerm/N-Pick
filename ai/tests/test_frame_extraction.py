"""FRD F-03 프레임 추출 검증.

기대값은 코드가 아니라 FRD 와 config toml 에서 옮겨 적는다(tests/test_health.py 규약).
구현 모듈을 참조해 기대값을 만들면 검증이 자기 자신을 확인하는 셈이 된다.
"""

from collections.abc import Callable, Iterator, Mapping, Sequence
from fractions import Fraction
from pathlib import Path
from types import SimpleNamespace
from typing import Any, cast

import av
import numpy as np
import pytest
from scenedetect.backends.pyav import VideoStreamAv

from npick_worker import frame_extraction
from npick_worker.frame_extraction import (
    DEFAULT_CONFIG_PATH,
    ChosenFrame,
    FrameExtractionConfig,
    MediaProfile,
    PyAvFrameGrabber,
    SceneMeasurement,
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
    prune_by_change,
    pyav_backend,
    select,
)
from npick_worker.frame_extraction.models import Keyframe, SceneKeyframes
from npick_worker.media_errors import MediaUnreadableError
from npick_worker.timecode import frame_number_from_pts, frames_to_ms, ms_to_frame

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
    assert load_config().version_id == "frame-extract/v2:a0684794"


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
    assert len(slots) >= load_config().min_keyframes_per_scene


def test_slot_count_does_not_follow_the_scene_length() -> None:
    """길이가 9배 늘어도 자리 수는 그대로다. v2 에서 장 수를 정하는 것은 길이가 아니다.

    v1 은 `interval_ms` 로 길이에 비례해 장 수를 정했고, 그래서 정적인 긴 장면이 같은
    그림을 여러 장 남겼다. v2 에서 길이가 정하는 것은 자리의 **간격**뿐이고 장 수는
    `prune_by_change` 가 내용으로 정한다(FRD v3.2 F-03, `docs/frd.md:131`).
    """
    config = _cfg(planned_slots_per_scene=5)
    assert len(_plan(_span(0, 3000), config)) == len(_plan(_span(0, 27_000), config)) == 5


def test_slot_count_is_capped_by_the_planned_count() -> None:
    # 60초 장면이라도 자리는 계획한 수만큼만 놓는다. 이 값이 후보의 해상도 상한이다.
    slots = _plan(_span(0, 60_000), _cfg(planned_slots_per_scene=5))
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


# ── 적응형: 장 수를 장면 안의 변화량으로 정한다 ────────────────────────
# FRD v3.2 F-03 (`docs/frd.md:131`). 정적 장면은 적게, 동적 장면은 많게.


#: 블랭크와 다른 화면 사이의 `content_val`. 검정과 유채색의 채널 평균 절대차는 임계를
#: 한참 넘는다. 적응형이 거리만 보면 블랭크가 항상 이기는 이유가 이 값이다.
BLANK_DISTANCE = 85.0


def _adaptive(
    *distances: float,
    cfg: FrameExtractionConfig | None = None,
    count: int = 5,
    blanks: frozenset[int] = frozenset(),
) -> tuple[int, ...]:
    """이웃한 자리 사이의 거리를 주고, 남은 자리 번호를 돌려받는다.

    `distances[i]` 는 자리 `i` 와 `i+1` 사이의 거리다. 떨어진 두 자리 사이는 그 사이
    구간들의 합으로 둔다 — 실제 영상에서 변화가 누적되는 모양이고, 표로 쓰기도 쉽다.
    프레임 번호를 자리 순번과 같게 둬서 "몇 번째 자리가 남았는가" 를 그대로 읽는다.

    `blanks` 에 든 자리는 `select` 가 블랭크로 표시한 자리다. 그 자리가 끼는 쌍의 거리는
    `distances` 와 무관하게 `BLANK_DISTANCE` 가 된다 — 실제 암전 프레임이 그렇다.
    """
    chosen = tuple(
        ChosenFrame(
            frame_number=index,
            timestamp_ms=index * 1000,
            score=1.0,
            blank=index in blanks,
        )
        for index in range(count)
    )
    frames = {
        index: ScoredFrame(
            frame_number=index,
            timestamp_ms=index * 1000,
            score=1.0,
            luma_std=0.0 if index in blanks else 40.0,
        )
        for index in range(count)
    }
    changes = {
        (left, right): (
            BLANK_DISTANCE
            if left in blanks or right in blanks
            else float(sum(distances[left:right]))
        )
        for left in range(count)
        for right in range(left + 1, count)
    }
    kept = prune_by_change(
        chosen,
        SceneMeasurement(frames=frames, changes=changes),
        cfg if cfg is not None else load_config(),
    )
    return tuple(frame.frame_number for frame in kept)


def test_a_static_scene_keeps_only_the_lower_bound() -> None:
    """다섯 자리가 전부 같은 화면이면 하한만 남는다. 같은 그림을 다섯 번 저장하지 않는다."""
    assert len(_adaptive(0.0, 0.0, 0.0, 0.0)) == load_config().min_keyframes_per_scene


def test_a_dynamic_scene_keeps_every_slot() -> None:
    """자리마다 화면이 다르면 전부 남는다. 상한까지 채우는 쪽이 동적 장면이다."""
    far = load_config().change_threshold * 2
    assert _adaptive(far, far, far, far) == (0, 1, 2, 3, 4)


def test_the_seed_is_the_middle_slot() -> None:
    """원 설계(`581f6e3`)의 "중앙(50%) 프레임을 시드로" 를 잇는다.

    정적 장면이라 시드 말고는 아무것도 임계를 넘지 못하므로, 남은 것에 가운데 자리가
    들어 있다는 사실이 곧 시드가 가운데였다는 뜻이다.
    """
    assert 2 in _adaptive(0.0, 0.0, 0.0, 0.0)


def test_the_cap_wins_over_the_change() -> None:
    """전부 달라도 상한을 넘지 않는다. 상한이 후속 VLM·OCR 의 비용 상한이다."""
    far = load_config().change_threshold * 2
    assert len(_adaptive(far, far, far, far, cfg=_cfg(max_keyframes_per_scene=3))) == 3


def test_the_cap_keeps_the_whole_scene_not_just_the_front() -> None:
    """상한이 자리 수보다 작을 때 장면 뒷부분이 통째로 사라지지 않는다.

    시드가 가운데라 시각 순으로 자르면 앞쪽 자리가 먼저 상한을 채운다. 전 구간이 동적인
    5 자리 장면에서 앞 세 자리만 남으면 뒤 40% 는 대표되지 않는다. 최원점으로 추리면
    남는 장이 장면 전체에 퍼진다.

    기본 설정은 `max == planned == 5` 라 지금은 걸리지 않지만, `max_keyframes_per_scene`
    은 후속 단계 비용 실측 후 다시 볼 값이다(`docs/frame-extraction.md` §10).
    """
    far = load_config().change_threshold * 2
    assert _adaptive(far, far, far, far, cfg=_cfg(max_keyframes_per_scene=3)) == (0, 2, 4)


def test_the_lower_bound_is_filled_with_the_least_similar_frame() -> None:
    """하한을 채울 때 아무거나 되돌리지 않는다. 떨어진 것 중 가장 덜 닮은 것이 온다.

    아무거나 채우면 되돌린 장이 남긴 장과 거의 같은 화면일 수 있고, 그러면 하한을
    숫자로만 맞추고 실제로는 같은 그림을 두 번 저장한다.

    임계(27.0)를 넘는 자리가 없으므로 시드(자리 2) 말고 넷이 다 떨어진다. 그중 시드에서
    가장 먼 것은 자리 0 이다 — d(0,2) = 1+20 = 21 이고, 나머지는 20·4·5 다.
    """
    kept = _adaptive(1.0, 20.0, 4.0, 1.0)
    assert kept == (0, 2)


def test_an_unmeasured_pair_leaves_every_slot_in_place() -> None:
    """재지 못한 것을 "안 달라졌다" 로 읽으면 측정 사고가 장 수 감소로 둔갑한다."""
    chosen = tuple(
        ChosenFrame(frame_number=index, timestamp_ms=index * 1000, score=1.0, blank=False)
        for index in range(5)
    )
    frames = {
        index: ScoredFrame(frame_number=index, timestamp_ms=index * 1000, score=1.0, luma_std=40.0)
        for index in range(5)
    }
    measurement = SceneMeasurement(frames=frames, changes={})
    assert len(prune_by_change(chosen, measurement, load_config())) == 5


# ── 적응형과 블랭크: 거리만 보면 암전이 항상 이긴다 ──────────────────────


def test_a_blank_slot_does_not_win_the_change_check() -> None:
    """블랭크는 다른 어떤 화면과도 멀어서, 거리만 보면 규칙 2 를 항상 통과한다.

    정적 장면의 자리 3 만 블랭크인 경우다. 거리만 보면 실제 화면 세 장을 버리고 검정
    JPEG 을 두 번째 keyframe 으로 확정한다 — `select` 가 "이 자리엔 쓸 만한 게 없었다"
    고 표시해 둔 장을 다음 단계가 오히려 선호하는 꼴이다.
    """
    kept = _adaptive(2.0, 2.0, 2.0, 2.0, blanks=frozenset({3}))

    assert 3 not in kept
    assert kept == (0, 2)


def test_the_seed_moves_off_a_blank_slot() -> None:
    """시드는 무조건 살아남는 자리다. 거기에 블랭크를 두면 검정 한 장이 확정된다."""
    assert _adaptive(0.0, 0.0, 0.0, 0.0, blanks=frozenset({2})) == (0, 1)


def test_a_blank_slot_comes_back_only_when_nothing_else_is_left() -> None:
    """장면 전체가 암전이면 블랭크라도 하한을 채운다.

    치명 단계이므로 keyframe 0 장으로 끝내는 것이 블랭크 한 장보다 나쁘다. 블랭크를
    빼는 것은 **선호**이지 금지가 아니다.
    """
    kept = _adaptive(0.0, 0.0, 0.0, 0.0, blanks=frozenset(range(5)))

    assert len(kept) == load_config().min_keyframes_per_scene


def test_change_is_looked_up_in_either_direction() -> None:
    """쌍의 키는 정렬된 순서쌍이다. 조회할 때 방향을 신경 쓰지 않는다."""
    measurement = SceneMeasurement(frames={}, changes={(3, 7): 12.5})
    assert measurement.change_between(3, 7) == 12.5
    assert measurement.change_between(7, 3) == 12.5
    assert measurement.change_between(1, 2) is None


# ── 변화 척도: scene 분할과 같은 자를 쓴다 ──────────────────────────────


def test_content_val_matches_the_scene_detection_scale() -> None:
    """`scenedetect` 의 `content_val` 과 같은 값이어야 한다. FRD F-03 의 척도 통일이다.

    기준점은 `ai/docs/scene-detection.md` §4 의 실측이다 — `KNI_02205` 프레임 720→721 이
    19.92 다. 눈금이 어긋나면 `change_threshold` 와 scene 분할의 `content.threshold` 를
    같은 자 위에서 말할 수 없다.

    여기서는 합성 프레임으로 **공식**을 잠근다. 실제 클립 대조는 문서의 실측이 맡는다 —
    19 MiB 짜리 샘플을 단위 테스트가 디코드하지 않는다.
    """
    black = np.zeros((8, 8, 3), dtype=np.float32)
    white = np.ones((8, 8, 3), dtype=np.float32)
    # 검정 대 흰색: H 는 둘 다 0, S 도 둘 다 0, V 만 0 대 255 다. 평균하면 255/3 이다.
    assert pyav_backend._content_val(
        pyav_backend._to_hsv_scaled(black), pyav_backend._to_hsv_scaled(white)
    ) == pytest.approx(255.0 / 3.0)


def test_hue_uses_the_opencv_eight_bit_range() -> None:
    """H 는 `0~179` 다. `cv2.cvtColor(..., COLOR_BGR2HSV)` 의 8bit 범위와 같아야 한다."""
    red = np.zeros((1, 3, 3), dtype=np.float32)
    red[:, :, 0] = 1.0
    hue, saturation, value = pyav_backend._to_hsv_scaled(red)
    assert float(hue.max()) == pytest.approx(0.0)
    assert float(saturation.max()) == pytest.approx(255.0)
    assert float(value.max()) == pytest.approx(255.0)

    cyan = np.zeros((1, 3, 3), dtype=np.float32)
    cyan[:, :, 1] = 1.0
    cyan[:, :, 2] = 1.0
    # 180도. OpenCV 눈금에서 절반인 90 이다.
    assert float(pyav_backend._to_hsv_scaled(cyan)[0].max()) == pytest.approx(90.0)


def test_a_static_scene_yields_fewer_keyframes_than_a_moving_one(
    make_video: MakeVideo, tmp_path: Path
) -> None:
    """실제 디코드·인코드 경로로 확인한다. 이것이 이 티켓이 바꾸려던 성질 자체다.

    두 장면의 **길이가 같다.** v1 은 길이로 장 수를 정했으므로 두 장면이 같은 장 수를
    받았다. v2 는 내용으로 정하므로 갈려야 한다.

    뒤 장면의 블록이 전부 **비블랭크**인 것이 중요하다. `white`·`gray` 는 휘도 표준편차가
    0 이라 `select` 가 블랭크로 표시하고, 블랭크는 장 수를 늘리는 근거가 되지 못한다.
    그런 블록으로 채우면 "동적이라 많이 뽑혔다" 가 아니라 "암전이라 많이 뽑혔다" 를
    확인하게 된다.
    """
    video = make_video(
        "static-vs-moving",
        [
            ("bars", BLOCK_FRAMES),
            *[(kind, 4) for kind in ("noise", "bars", "split", "noise", "bars")],
        ],
    )
    result = extract_keyframes(
        video, (_span(0, BLOCK_MS, 0), _span(BLOCK_MS, BLOCK_MS * 2, 1)), tmp_path / "out"
    )

    static, moving = result.scenes
    assert len(static.keyframes) == load_config().min_keyframes_per_scene
    assert len(moving.keyframes) > len(static.keyframes)


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
    with pytest.raises(ValueError, match="먼저 끝났다"):
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
    with pytest.raises(ValueError, match="먼저 끝났다"):
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
    ) -> Mapping[int, SceneMeasurement]:
        # `changes` 를 비워 둔다 — 변화량을 재지 않은 대역이므로 `prune_by_change` 가
        # 판정하지 않고 전부 남긴다. 여기서 보려는 것은 선정·배선이지 적응형이 아니다.
        return {
            request.scene_index: SceneMeasurement(
                frames={
                    number: ScoredFrame(
                        frame_number=number,
                        timestamp_ms=frames_to_ms(number, 10.0),
                        # 슬롯 순번이 클수록 선명하게 둬서 대표가 첫 슬롯이 아니게 만든다.
                        score=float(slot.slot_index + 1),
                        luma_std=40.0,
                    )
                    for slot in request.slots
                    for number in slot.frame_numbers
                },
                changes={},
            )
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
        ) -> Mapping[int, SceneMeasurement]:
            return {
                index: SceneMeasurement(
                    frames={
                        number: frame
                        for number, frame in measurement.frames.items()
                        if frames_to_ms(number, 10.0) < 500
                    },
                    changes={},
                )
                for index, measurement in super().measure(video_path, requests, cfg).items()
            }

    with pytest.raises(ValueError, match="먼저 끝났다"):
        extract_keyframes(
            tmp_path / "missing.mp4", _spans(1), tmp_path / "out", grabber=TruncatedGrabber()
        )


def test_an_unreached_outer_candidate_does_not_fail_the_clip(tmp_path: Path) -> None:
    """자리 안의 다른 후보로 채워지는 초과분은 실패가 아니다.

    자리를 2 개에서 5 개로 늘리면서 마지막 자리가 창의 75% 에서 90% 로 옮겨 갔다.
    컨테이너가 선언한 길이가 실제 디코드 가능 구간보다 긴 파일(TS, 잘린 꼬리, 추정
    duration)에서 바깥쪽 후보가 미디어 밖으로 나가는 일이 그만큼 흔해진다. 막으려는 것은
    자리가 사라져 장 수가 주는 일이므로, 자리가 남아 있으면 실패시키지 않는다.
    """

    class OuterCandidateMissingGrabber(FakeGrabber):
        def measure(
            self,
            video_path: Path,
            requests: Sequence[SceneRequest],
            cfg: FrameExtractionConfig,
        ) -> Mapping[int, SceneMeasurement]:
            unreached = {
                max(slot.frame_numbers)
                for request in requests
                for slot in request.slots
                if len(slot.frame_numbers) > 1
            }
            return {
                index: SceneMeasurement(
                    frames={
                        number: frame
                        for number, frame in measurement.frames.items()
                        if number not in unreached
                    },
                    changes={},
                )
                for index, measurement in super().measure(video_path, requests, cfg).items()
            }

    result = extract_keyframes(
        tmp_path / "missing.mp4",
        _spans(1),
        tmp_path / "out",
        grabber=OuterCandidateMissingGrabber(),
    )

    assert len(result.scenes[0].keyframes) >= load_config().min_keyframes_per_scene


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
    ) -> Mapping[int, SceneMeasurement]:
        self.requests.extend(requests)
        return {
            request.scene_index: SceneMeasurement(
                frames={
                    number: ScoredFrame(
                        frame_number=number,
                        timestamp_ms=frames_to_ms(number, self.frame_rate),
                        score=1.0 + number,
                        luma_std=40.0,
                    )
                    for slot in request.slots
                    for number in slot.frame_numbers
                },
                changes={},
            )
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
    assert len(scene.keyframes) >= load_config().min_keyframes_per_scene
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
def test_normal_scenes_keep_the_margin_and_the_planned_count(frame_rate: float) -> None:
    """상류 최소 길이(1000ms) 이상인 scene 의 계획은 프레임 상한에 걸리지 않는다.

    프레임 기준으로 옮긴 것이 정상 구간의 계획을 건드리지 않았다는 뜻이다. 옛 계획을
    스냅샷으로 떠 두는 대신 두 성질로 잠근다 — 여백이 유지되고, 자리 수가 설정값
    그대로다. 스냅샷은 옛 코드가 사라지면 자기 자신을 확인하는 셈이 된다.
    """
    config = load_config()
    for start in SWEEP_SCENE_STARTS:
        for duration in (1000, 1500, 2999, 3000, 9000, 60_000):
            slots = _plan(_span(start, start + duration), config, frame_rate)
            assert len(slots) == config.planned_slots_per_scene, (frame_rate, start, duration)
            for slot in slots:
                for candidate_ms in slot.candidates_ms:
                    assert (
                        start + config.edge_margin_ms
                        <= candidate_ms
                        < start + duration - config.edge_margin_ms
                    ), (frame_rate, start, duration)


# ── 열었지만 쓸 수 없는 미디어 ──────────────────────────────────────────


class _StubStream:
    """`average_rate` 는 주지 않으면 `guessed_rate` 와 같다.

    실측한 클립 110개 중 109개가 그랬다. 둘이 갈리는 것이 예외이므로 그쪽만 인자로
    적게 한다.
    """

    def __init__(
        self,
        guessed_rate: Fraction | float | None,
        width: int,
        height: int,
        *,
        average_rate: Fraction | float | None = None,
    ) -> None:
        self.guessed_rate = guessed_rate
        self.average_rate = guessed_rate if average_rate is None else average_rate
        self.codec_context = SimpleNamespace(width=width, height=height)


class _StubContainer:
    """`_profile` 이 읽는 것만 흉내낸 컨테이너.

    비디오 스트림·프레임레이트·해상도가 없는 파일을 픽스처로 만들기는 어렵지만, 그
    판정이 어떤 예외로 나가는지는 계약이 정한다. 스텁으로 세 갈래를 다 지난다.
    """

    def __init__(self, *streams: _StubStream) -> None:
        self.streams = SimpleNamespace(video=list(streams))


@pytest.mark.parametrize(
    ("container", "message"),
    [
        (_StubContainer(), "비디오 스트림이 없는"),
        (_StubContainer(_StubStream(None, 320, 240)), "프레임레이트를 읽을 수 없다"),
        (_StubContainer(_StubStream(0.0, 320, 240)), "프레임레이트를 읽을 수 없다"),
        (_StubContainer(_StubStream(30.0, 0, 240)), "해상도를 읽을 수 없다"),
    ],
)
def test_profile_reports_unreadable_media_not_a_validation_error(
    container: _StubContainer, message: str
) -> None:
    """영상을 열었지만 디코드에 필요한 것이 없으면 UNSUPPORTED_MEDIA 다(계약 §4.3.1).

    맨 `ValueError` 로 두면 `classify` 가 `VALIDATION_ERROR` 로 번역하고, 정본에는
    "상류 산출물·키가 잘못됐다" 는 다른 사실이 남는다.
    """
    with pytest.raises(MediaUnreadableError, match=message):
        pyav_backend._profile(cast("Any", container))


# ── 상류와 같은 프레임레이트를 읽는가 ──────────────────────────────────

#: 배포에서 `VALIDATION_ERROR` 로 죽은 클립(889783826175660755)의 실측값.
#: 안드로이드로 직접 찍은 26.8초·805프레임 영상이고, 간격 804개 중 777개가 정확히
#: 3000 ticks(=1/30초)다. 그런데 컨테이너 duration 이 마지막 프레임 길이만큼 짧게
#: 적혀 있어 `프레임수 / duration` 으로 유도되는 `average_rate` 만 위로 밀렸다.
PHONE_GUESSED_RATE = Fraction(30, 1)
PHONE_AVERAGE_RATE = Fraction(71875, 2393)  # = 30.0355…


def test_profile_reads_the_same_frame_rate_as_scene_detection() -> None:
    """상류가 쓰는 값은 `guessed_rate` 다. 이 단계도 그것을 읽어야 한다.

    기대값의 출처는 이 모듈이 아니라 상류다 — `scene_detection` 은 PySceneDetect 의
    `VideoStreamAv.frame_rate` 를 쓰고 그것이 `guessed_rate` 다(scenedetect 0.7.1,
    `backends/pyav.py`). 두 단계가 다른 값을 읽으면 `_check_frame_rate` 가 멈춰
    세우고, 검사가 없었다면 keyframe 의 `timestamp_ms` 가 조용히 어긋난다.
    """
    container = _StubContainer(
        _StubStream(PHONE_GUESSED_RATE, 1920, 1080, average_rate=PHONE_AVERAGE_RATE)
    )

    assert pyav_backend._profile(cast("Any", container)).frame_rate == float(PHONE_GUESSED_RATE)


def test_profile_agrees_with_pyscenedetect_on_a_real_file(make_video: MakeVideo) -> None:
    """위 테스트가 잠그는 "상류가 읽는 값" 을 상류에게 직접 물어본다.

    스텁만으로는 절반만 잠긴다 — 이 단계가 `guessed_rate` 를 읽는다는 것은 확인해도,
    상류가 **여전히** 그것을 읽는다는 것은 확인하지 못한다. scenedetect 가
    `VideoStreamAv.frame_rate` 의 출처를 바꾸면(0.7.1 은 `guessed_rate` 를
    `framerate_to_fraction` 에 통과시킨다) 아무 테스트도 빨개지지 않고 배포에서 같은
    자리가 다시 죽는다. 두 끝을 한 파일로 맞대어 그 쪽 끝을 고정한다.

    CFR 합성 영상이라 이 테스트만으로는 원래 버그를 못 잡는다. 위 스텁 테스트와 짝이다.
    """
    video = make_video("upstream-agreement", [("bars", BLOCK_FRAMES)])

    with av.open(str(video)) as container:
        ours = pyav_backend._profile(container).frame_rate

    assert ours == float(VideoStreamAv(str(video)).frame_rate)


# ── 상류와 같은 번호로 프레임을 세는가 ────────────────────────────────

#: 30fps·`time_base` 1/90000 에서 한 프레임이 0.6 프레임만큼 **늦게** 도착한 PTS 열.
#: 그 뒤 프레임은 전부 한 칸씩 뒤로 밀린다.
JITTERED_PTS = (0, 3000, 6000, 9000, 13800, 16800, 19800)

#: 위 PTS 를 상류 규칙으로 센 번호 — `round((pts - 첫 pts) * time_base * fps)`.
#: PySceneDetect 의 `VideoStreamAv.position` 이 쓰는 식이고(`backends/pyav.py`),
#: `SceneManager` 가 scene 경계에 적는 것이 이 번호다(`scene_manager.py`).
#: 4 번이 비어 있는 것이 핵심이다 — 그 시각에 프레임이 없다는 사실이고,
#: 디코드 순번으로 세면 그 사실이 지워진 채 뒤가 전부 한 칸 어긋난다.
JITTERED_FRAME_NUMBERS = (0, 1, 2, 3, 5, 6, 7)

#: 지터가 없는 같은 길이의 열. 정상 파일에서 번호가 촘촘하다는 것을 함께 잠근다.
STEADY_PTS = tuple(3000 * index for index in range(7))


class _StubFrame:
    def __init__(self, pts: int) -> None:
        self.pts = pts
        self.time_base = Fraction(1, 90000)


class _DecodeStubContainer:
    """`_decode_until` 이 읽는 것만 흉내낸 컨테이너.

    PTS 를 마음대로 놓은 파일은 픽스처로 만들 수 없다 — mp4 먹서는 단조 DTS 를 요구해
    거부하고, mkv 먹서는 PTS 를 프레임 간격 격자로 스냅해 지터를 지운다. 번호를 매기는
    규칙 자체는 프레임의 `pts` 와 `time_base` 만 보므로 그 둘만 준다.
    """

    def __init__(
        self,
        *pts: int,
        start_time: int = 0,
        stream_time_base: Fraction | None = Fraction(1, 90000),
    ) -> None:
        self._frames = [_StubFrame(value) for value in pts]
        self.streams = SimpleNamespace(
            video=[SimpleNamespace(start_time=start_time, time_base=stream_time_base)]
        )

    def decode(self, stream: object) -> Iterator[_StubFrame]:
        return iter(self._frames)


@pytest.mark.parametrize(
    ("pts", "expected"),
    [
        (JITTERED_PTS, JITTERED_FRAME_NUMBERS),
        (STEADY_PTS, tuple(range(7))),
    ],
)
def test_decode_numbers_frames_the_way_the_upstream_does(
    pts: tuple[int, ...], expected: tuple[int, ...]
) -> None:
    """프레임 번호는 디코드 순번이 아니라 PTS 에서 온다.

    두 단계가 같은 번호로 같은 프레임을 가리켜야 한다 — 상류가 scene 경계에 적은 번호를
    이 단계가 다른 프레임에 붙이면, 실패가 아니라 **조용히 틀린 `timestamp_ms`** 가
    나온다. `_check_frame_rate` 가 막는 것과 같은 종류의 어긋남이고 원인만 다르다.

    지터가 없는 열에서 번호가 `0..N-1` 로 촘촘한 것도 함께 본다. 정상 파일의 성질이
    바뀌면 후보를 못 재는 자리가 생긴다.
    """
    container = _DecodeStubContainer(*pts)

    numbered = [
        number
        for number, _ in pyav_backend._decode_until(
            cast("Any", container), expected[-1], frame_rate=30.0
        )
    ]

    assert tuple(numbered) == expected


@pytest.mark.parametrize(("pts", "expected"), [(0, 0), (3000, 1), (3400, 1), (13800, 5)])
def test_frame_number_from_pts_follows_the_upstream_rule(pts: int, expected: int) -> None:
    """번호 규칙은 `timecode` 하나에만 둔다. 상류 `VideoStreamAv.position` 의 식이다.

    같은 계산을 두 곳에서 따로 하면 갈린다 — 프레임레이트 출처가 그래서 갈렸다
    (S15P21A501-259). `frame_extraction` 과 `scene_detection` 이 이 함수를 함께 쓴다.
    """
    assert frame_number_from_pts(pts, Fraction(1, 90000), 30.0) == expected


def test_frame_number_from_pts_rescales_the_stream_start_time() -> None:
    """`start_time` 의 눈금이 프레임과 다르면 맞춘 뒤 뺀다.

    edit list 가 붙은 파일에서 스트림과 프레임의 `time_base` 가 갈린다. 상류가 여기서
    눈금을 맞추므로(`_normalized_pts`) 같이 맞춘다.

    스트림 눈금 1/1000 의 9 틱은 프레임 눈금 1/90000 에서 810 틱이다.
    """
    number = frame_number_from_pts(
        3810, Fraction(1, 90000), 30.0, start_time=9, stream_time_base=Fraction(1, 1000)
    )

    assert number == 1


def test_decode_yields_every_frame_carrying_the_last_number() -> None:
    """번호가 겹치면 그 번호를 가진 프레임을 **전부** 내보낸다.

    `measure` 와 `write` 는 번호를 키로 하는 dict 에 last-wins 로 쌓는다. 둘의 종료
    지점이 다르면 한쪽은 첫 번째를, 다른 쪽은 두 번째를 주인으로 삼는다 — 점수를 잰
    프레임과 저장된 JPEG 이 예외 없이 달라진다. 끝 번호를 양쪽이 똑같이 소진해야 한다.

    30fps·1/90000 에서 3000 과 3400 은 둘 다 번호 1 이다(`round(1)`·`round(1.13)`).
    """
    container = _DecodeStubContainer(0, 3000, 3400, 6000)

    numbered = [
        number
        for number, _ in pyav_backend._decode_until(cast("Any", container), 1, frame_rate=30.0)
    ]

    assert numbered == [0, 1, 1]


def test_measurement_gap_is_not_reported_as_a_truncated_media() -> None:
    """번호가 건너뛴 것과 미디어가 잘린 것은 다른 사실이다.

    프레임 번호가 PTS 에서 오므로 요청한 번호가 아예 없을 수 있다 — 그 시각에 프레임이
    없다는 뜻이다. 이 경우까지 "미디어가 먼저 끝났다" 로 신고하면 정본에 거짓 원인이
    남고, 영구 오류라 재시도로 걷히지도 않는다.
    """
    request = SceneRequest(
        scene_index=0,
        slots=(SlotCandidates(slot_index=0, frame_numbers=(4,)),),
    )
    # 4 번만 비어 있다. 뒤의 5 번을 쟀으므로 미디어가 끝난 것은 아니다.
    measured = {
        0: SceneMeasurement(
            frames={
                number: ScoredFrame(
                    frame_number=number,
                    timestamp_ms=frames_to_ms(number, 30.0),
                    score=1.0,
                    luma_std=1.0,
                )
                for number in (3, 5)
            },
            changes={},
        )
    }

    with pytest.raises(ValueError, match="그 시각에 프레임이 없다"):
        frame_extraction._check_measured([request], measured)


def test_decode_subtracts_the_stream_start_time() -> None:
    """상류는 첫 프레임의 PTS 가 아니라 `stream.start_time` 을 뺀다.

    edit list 가 붙은 파일은 스트림 시작 시각이 0 이 아니고, 그때 첫 프레임 PTS 를
    기준으로 세면 상류와 통째로 어긋난다. 배포 클립 110개 중 2개가 이 경우다.
    """
    container = _DecodeStubContainer(9000, 12000, 15000, start_time=9000)

    numbered = [
        number
        for number, _ in pyav_backend._decode_until(cast("Any", container), 2, frame_rate=30.0)
    ]

    assert numbered == [0, 1, 2]


def test_decode_tolerates_a_stream_without_a_time_base() -> None:
    """눈금 맞추기는 스트림 `time_base` 가 있을 때만 할 수 있다.

    없는데 `start_time` 이 있으면 곱셈이 `None` 을 만나 터진다. 프레임 쪽 눈금이 이미
    있으므로 그것을 그대로 쓴다 — 맞출 상대가 없는 것이지 읽을 수 없는 미디어가 아니다.
    """
    container = _DecodeStubContainer(3000, 6000, start_time=3000, stream_time_base=None)

    numbered = [
        number
        for number, _ in pyav_backend._decode_until(cast("Any", container), 1, frame_rate=30.0)
    ]

    assert numbered == [0, 1]


def test_decode_numbering_matches_pyscenedetect_on_a_real_file(make_video: MakeVideo) -> None:
    """식이 아니라 상류 구현과 직접 맞댄다.

    위 테스트는 우리가 **옮겨 적은 식**을 잠근다. 그 식이 상류의 것과 같다는 사실은
    상류에게 물어야 확인된다 — scenedetect 가 `position` 의 계산을 바꾸면 여기가 빨개진다.
    """
    video = make_video("numbering-agreement", [("bars", BLOCK_FRAMES)])

    upstream = VideoStreamAv(str(video))
    theirs = []
    while upstream.read() is not False:
        theirs.append(upstream.position.frame_num)

    with av.open(str(video)) as container:
        ours = [
            number
            for number, _ in pyav_backend._decode_until(
                container, len(theirs), frame_rate=float(upstream.frame_rate)
            )
        ]

    assert ours == theirs


class _DriftedAverageRateGrabber(PyAvFrameGrabber):
    """`average_rate` 만 어긋난 컨테이너를 흉내낸다. 디코드는 진짜 영상으로 한다.

    합성 영상으로는 이 상황을 만들 수 없다 — PyAV 의 mp4 먹서가 duration 을 깔끔하게
    다시 쓰므로 `average_rate` 가 언제나 `guessed_rate` 와 같아진다. 어긋난 파일은
    폰·카메라가 직접 쓴 원본에서 온다.
    """

    def profile(self, video_path: Path) -> MediaProfile:
        container = _StubContainer(
            _StubStream(
                Fraction(int(VIDEO_FPS), 1),
                VIDEO_WIDTH,
                VIDEO_HEIGHT,
                average_rate=Fraction(1005, 100),
            )
        )
        return pyav_backend._profile(cast("Any", container))


def test_extract_accepts_media_whose_average_rate_drifts(
    make_video: MakeVideo, tmp_path: Path
) -> None:
    """상류가 적어 보낸 프레임레이트로 keyframe 이 끝까지 나온다.

    배포에서 죽은 경로다(S15P21A501-259). `_profile` 단위 테스트와 갈라 두는 이유는
    두 단계를 잇는 것이 `extract_keyframes` 의 `expected_frame_rate` 이기 때문이다 —
    `_profile` 만 고치고 대조가 다른 값을 보면 여전히 같은 자리에서 죽는다.
    """
    video = make_video("drifted-rate", [("bars", BLOCK_FRAMES)])

    result = extract_keyframes(
        video,
        _spans(1),
        tmp_path / "out",
        grabber=_DriftedAverageRateGrabber(),
        expected_frame_rate=VIDEO_FPS,
    )

    assert result.scenes[0].keyframes
