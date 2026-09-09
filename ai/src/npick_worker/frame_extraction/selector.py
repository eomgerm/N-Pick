"""후보 계획과 대표 선정. 영상을 열지 않는 순수 함수만 둔다.

디코드에서 분리한 이유는 이 부분이 결과의 의미를 정하는 곳이기 때문이다 — "몇 장을
어디서 뽑고 그중 무엇을 대표로 하는가" 는 백엔드 라이브러리와 무관하게 검증할 수
있어야 한다. 영상 픽스처 없이 표로 확인되는 성질이 여기 다 모여 있다.

프레임레이트는 인자로 받는다. 그 값을 **알아내는** 일(영상 열기)이 백엔드의 몫이고, 그
값으로 몇 장을 어디서 뽑을지 정하는 것이 여기의 몫이다. 그래서 fps 는 표의 한 열이 되고
이 모듈은 여전히 파일을 열지 않는다.
"""

from collections.abc import Mapping, Sequence
from dataclasses import dataclass

from npick_worker.frame_extraction.config import FrameExtractionConfig
from npick_worker.frame_extraction.models import SceneSpan
from npick_worker.timecode import frame_count_in_range


@dataclass(frozen=True, slots=True)
class SlotPlan:
    """scene 안의 한 자리. 이 자리에서 후보 여럿을 보고 한 장을 고른다."""

    slot_index: int
    center_ms: int
    #: 볼 후보의 목표 시각. **중심에 가까운 순서**다. 같은 점수면 중심에 가까운
    #: 쪽이 이기도록 이 순서를 그대로 선호 순위로 쓴다.
    candidates_ms: tuple[int, ...]


@dataclass(frozen=True, slots=True)
class SlotCandidates:
    """`SlotPlan` 의 목표 시각을 실제 프레임 번호로 옮긴 것.

    옮기면서 후보를 scene 의 프레임 구간 안으로 당기므로(clamp) 그 구간을 아는
    `extract_keyframes` 가 이 변환을 한다. 고르는 규칙은 다시 이 모듈로 돌아온다.
    """

    slot_index: int
    #: 선호 순위대로. 중복은 제거되어 있다.
    frame_numbers: tuple[int, ...]


@dataclass(frozen=True, slots=True)
class ScoredFrame:
    """후보 한 장의 측정값."""

    frame_number: int
    timestamp_ms: int
    #: 선명도. 클수록 좋다.
    score: float
    #: 휘도 표준편차. 블랭크 판정에 쓴다.
    luma_std: float


@dataclass(frozen=True, slots=True)
class ChosenFrame:
    frame_number: int
    timestamp_ms: int
    score: float
    #: 참이면 블랭크 판정에 걸렸는데도 대안이 없어 쓴 프레임이다.
    blank: bool


@dataclass(frozen=True, slots=True)
class _Window:
    """후보를 놓을 구간과 그 안의 프레임 수."""

    start_ms: int
    end_ms: int
    #: 이 창 안에 정규 시각이 들어오는 프레임의 수. 여백 유지 판단과 슬롯 상한이 **같은 값**을
    #: 써야 하므로 한 번 세서 들고 다닌다. 두 번 세면 두 값이 갈릴 여지가 생긴다.
    frame_count: int


def plan_slots(
    scene: SceneSpan, cfg: FrameExtractionConfig, frame_rate: float
) -> tuple[SlotPlan, ...]:
    """scene 하나의 후보 계획을 만든다.

    정수 산술만 쓴다. 부동소수로 중심을 계산하면 같은 입력에서 실행마다 1ms 가
    흔들릴 수 있고, 그러면 `timestamp_ms` 가 바뀌어 재처리 결과를 비교할 수 없다.

    프레임레이트가 필요한 이유는 장 수의 상한이 창의 **ms** 가 아니라 창 안의 **프레임
    수** 여야 하기 때문이다. `_check_keyframe_count` 의 기대치가 프레임 수이므로 여기서
    ms 로 세면 두 곳이 다른 단위로 같은 것을 말한다 — 그 상태에서는 30fps 501ms scene 이
    슬롯 1개를 받는데 검사는 2장을 요구했고, 치명 단계라 그 clip 은 영구 실패했다.
    """
    duration_ms = scene.duration_ms
    if duration_ms <= 0:
        msg = (
            f"길이가 0 이하인 scene 이다: scene_index={scene.scene_index} "
            f"({scene.start_time_ms}~{scene.end_time_ms})"
        )
        raise ValueError(msg)

    window = _window(scene, cfg, frame_rate)
    if window.frame_count == 0:
        # 구간 전체에 정규 시각이 들어오는 프레임이 없다. 슬롯 0개를 돌려주면 그 scene 의
        # keyframe 이 조용히 0장이 되고, 1개로 올려 주면 없는 프레임을 향해 계획하는 것이다.
        # 실제 경로에서는 `frames_in_span` 이 먼저 걸러내므로 같은 어휘로 거절한다.
        msg = (
            f"scene 구간 안에 프레임이 없다: scene_index={scene.scene_index} "
            f"({scene.start_time_ms}~{scene.end_time_ms}ms, {frame_rate:g}fps)"
        )
        raise ValueError(msg)

    window_len = window.end_ms - window.start_ms
    slot_count = min(
        max(duration_ms // cfg.interval_ms, cfg.min_keyframes_per_scene),
        cfg.max_keyframes_per_scene,
        # 창에 프레임이 n 장이면 서로 다른 프레임을 받을 수 있는 슬롯도 최대 n 개다.
        # 없는 프레임을 만들지 않는다.
        window.frame_count,
    )

    return tuple(
        SlotPlan(
            slot_index=index,
            center_ms=center,
            candidates_ms=_candidates(center, window.start_ms, window.end_ms, cfg),
        )
        for index, center in enumerate(_centers(window.start_ms, window_len, slot_count))
    )


def _window(scene: SceneSpan, cfg: FrameExtractionConfig, frame_rate: float) -> _Window:
    """후보를 놓을 구간. 경계 여백을 뺀 안쪽이다.

    여백을 빼면 서로 다른 프레임이 `min_keyframes_per_scene` 개도 남지 않는 scene 에서는
    여백을 포기한다. 여백을 양쪽에서 뺄 수 없을 만큼 짧은 scene 에서 이미 하던 판단과
    같은 것이고 기준만 ms 에서 프레임으로 옮겼다 — "창이 34ms 다" 는 "두 장을 담는다" 를
    뜻하지 않는다.

    여기서 좁은 창을 돌려주면 그 scene 의 keyframe 이 하한보다 적어지는데, 이 단계는
    치명 단계다. 여백은 품질을 위한 선호이고 장 수 확보가 요구사항이다.
    """
    if scene.duration_ms > 2 * cfg.edge_margin_ms:
        start_ms = scene.start_time_ms + cfg.edge_margin_ms
        end_ms = scene.end_time_ms - cfg.edge_margin_ms
        frame_count = frame_count_in_range(start_ms, end_ms, frame_rate)
        if frame_count >= cfg.min_keyframes_per_scene:
            return _Window(start_ms=start_ms, end_ms=end_ms, frame_count=frame_count)
    return _Window(
        start_ms=scene.start_time_ms,
        end_ms=scene.end_time_ms,
        frame_count=frame_count_in_range(scene.start_time_ms, scene.end_time_ms, frame_rate),
    )


def _centers(window_start: int, window_len: int, slot_count: int) -> tuple[int, ...]:
    """창을 `slot_count` 등분한 각 칸의 중앙.

    양 끝이 아니라 칸의 중앙을 쓴다. 끝을 쓰면 첫 슬롯이 창 시작에 붙어 경계 여백을
    둔 의미가 없어진다.
    """
    return tuple(
        window_start + ((2 * index + 1) * window_len) // (2 * slot_count)
        for index in range(slot_count)
    )


def _candidates(
    center_ms: int, window_start: int, window_end: int, cfg: FrameExtractionConfig
) -> tuple[int, ...]:
    """중심에서 좌우로 번갈아 벌린 목표 시각. 중심에 가까운 순서다.

    창 밖으로 나간 후보는 창 안으로 당긴다. 그 결과 다른 후보와 같은 ms 가 되면
    중복을 없애므로, 창이 좁으면 후보가 `candidates_per_slot` 보다 적을 수 있다.
    같은 자리를 두 번 재는 것은 비용만 들고 아무것도 바꾸지 않는다.
    """
    last_ms = window_end - 1  # 구간이 반열린이므로 끝 ms 는 이 scene 이 아니다
    seen: dict[int, None] = {}
    for index in range(cfg.candidates_per_slot):
        # 0, +1, -1, +2, -2, ... 순서. 중심을 먼저 보고 점점 멀리 본다.
        step = (index + 1) // 2
        offset = step if index % 2 == 1 else -step
        seen.setdefault(min(max(center_ms + offset * cfg.candidate_step_ms, window_start), last_ms))
    return tuple(seen)


def select(
    slots: Sequence[SlotCandidates],
    scored: Mapping[int, ScoredFrame],
    cfg: FrameExtractionConfig,
) -> tuple[ChosenFrame, ...]:
    """슬롯마다 한 장을 고른다. 결과는 `timestamp_ms` 오름차순이다.

    규칙 셋이다.
    1. 블랭크(휘도 표준편차 < `min_luma_std`)가 아닌 후보 중 선명도 최고를 고른다.
    2. 후보가 전부 블랭크면 그중 선명도 최고를 `blank=True` 로 쓴다. 치명 단계이므로
       keyframe 0 장으로 끝내는 것이 블랭크 한 장보다 나쁘다.
    3. 이미 다른 슬롯이 고른 프레임은 다시 고르지 않는다. 남은 후보가 없으면 그
       슬롯을 버린다 — 같은 프레임을 두 번 저장하면 OCR 이 같은 화면을 두 번 읽고
       `UNIQUE(scene_id, timestamp_ms)` 도 걸린다.

    규칙 3 은 슬롯 순서·점수 순 그리디다. 앞 슬롯이 뒤 슬롯의 유일한 후보를 먼저 집는
    배치가 원리적으로 가능하므로, "슬롯 수만큼 고른다" 는 성질은 **슬롯의 후보 집합이
    충분히 벌어져 있다** 는 전제에 기댄다. 그 전제를 세우는 곳이 `plan_slots` 다.

    `scored` 에 없는 후보(디코드가 그 프레임에 닿지 못한 경우)는 조용히 건너뛴다.
    """
    taken: set[int] = set()
    chosen: list[ChosenFrame] = []
    for slot in slots:
        available = [
            scored[frame_number]
            for frame_number in slot.frame_numbers
            if frame_number in scored and frame_number not in taken
        ]
        if not available:
            continue
        best = _best(available, cfg.min_luma_std)
        taken.add(best.frame_number)
        chosen.append(
            ChosenFrame(
                frame_number=best.frame_number,
                timestamp_ms=best.timestamp_ms,
                score=best.score,
                blank=best.luma_std < cfg.min_luma_std,
            )
        )
    return tuple(sorted(chosen, key=lambda frame: frame.timestamp_ms))


def _best(candidates: Sequence[ScoredFrame], min_luma_std: float) -> ScoredFrame:
    """블랭크가 아닌 것 중 선명도 최고. 전부 블랭크면 그중 선명도 최고.

    동점이면 `candidates` 의 순서가 이긴다 — 그게 중심에 가까운 순서다. `max` 는
    첫 최대값을 돌려주므로 정렬을 한 번 더 하지 않아도 결정적이다.
    """
    usable = [frame for frame in candidates if frame.luma_std >= min_luma_std]
    return max(usable or candidates, key=lambda frame: frame.score)


def order_for_output(chosen: Sequence[ChosenFrame]) -> tuple[ChosenFrame, ...]:
    """대표를 맨 앞으로 옮긴다. 나머지는 `timestamp_ms` 오름차순이다.

    대표는 선명도 최고이고, 동점이면 시각이 이른 쪽이다. 동점 규칙이 없으면 같은
    입력에서 대표가 흔들려 결과 카드의 이미지가 재처리마다 바뀐다.

    `keyframe` 테이블에 대표 표시 컬럼이 없어서 **순서가 곧 표시**다. BE 는 이 순서대로
    INSERT 하고 대표는 그 scene 의 최소 `keyframe_id` 가 된다. ERD 주석의 "결과 목록의
    대표 이미지는 첫 장을 쓴다" 가 이 규약으로 성립한다.
    """
    if not chosen:
        return ()
    representative = min(chosen, key=lambda frame: (-frame.score, frame.timestamp_ms))
    rest = sorted(
        (frame for frame in chosen if frame is not representative),
        key=lambda frame: frame.timestamp_ms,
    )
    return (representative, *rest)
