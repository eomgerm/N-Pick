"""프레임 번호와 ms 사이의 변환 규칙. 단계 여럿이 함께 쓴다.

**변환은 이 모듈에서만 한다.** 부동소수 초를 여기저기서 반올림하면 재실행 간 1ms 가
흔들린다. `scene_detection` 이 경계를 ms 로 만들고 `frame_extraction` 이 그 ms 를 다시
프레임 번호로 되돌리므로, 두 단계가 같은 규칙을 쓰지 않으면 keyframe 이 자기 scene 밖의
프레임을 가리킬 수 있다.

같은 이유로 **구간 안의 프레임을 세는 규칙도 여기 있다**(`frames_in_range`). "이 구간에서
몇 장을 뽑을 수 있는가" 를 두 곳에서 따로 세면 한쪽이 ms 로, 다른 쪽이 프레임으로 세는
어긋남이 생긴다.

한계도 공유한다: 여기 있는 변환은 **명목 프레임레이트** 기반이라, 프레임 간격이 고르지
않은 소스에서는 `frames_to_ms` 가 준 ms 와 그 프레임의 실제 PTS 가 최대 반 프레임까지
어긋난다. 결정론은 유지되지만 정확도가 떨어진다.

번호 자체는 두 단계 사이에서 어긋나지 않는다 — 둘 다 아래 `frame_number_from_pts` 로
세므로 **같은 번호가 두 단계에서 같은 자리를 가리킨다.** 번호와 프레임이 1:1 이라는
뜻은 아니다. 간격이 고르지 않으면 한 번호에 두 프레임이 겹칠 수 있고, 그것 역시 상류가
보는 것과 같다. 남는 것은 그 번호를 ms 로 옮길 때의 오차뿐이다.

측정 샘플이 CFR 인 것은 `ai/docs/scene-detection.md` §4.1 에 적혀 있으나, 그 문서가
VFR 소스를 어떻게 다룰지까지 정하고 있지는 않다.
"""

from fractions import Fraction


def frame_number_from_pts(
    pts: int,
    time_base: "Fraction",
    frame_rate: float,
    start_time: int = 0,
    stream_time_base: "Fraction | None" = None,
) -> int:
    """디코드한 프레임의 표시 시각(PTS)을 프레임 번호로 바꾼다.

    **상류 PySceneDetect 의 `VideoStreamAv.position` 과 같은 식이다.** 그쪽이 scene 경계에
    적는 번호이므로 여기서 다른 값을 내면 두 단계가 다른 프레임을 같은 번호로 부른다 —
    실패가 아니라 조용히 어긋난 `keyframe.timestamp_ms` 가 된다.

    디코드 순번으로 세면 안 되는 이유가 그것이다. CFR 에서만 우연히 같고, 프레임 하나가
    반 프레임 넘게 일찍·늦게 도착하면 그 뒤로 번호가 통째로 밀린다.

    `start_time` 을 빼는 것은 edit list 가 붙어 스트림 시작이 0 이 아닌 파일 때문이다.
    그 눈금(`stream_time_base`)이 프레임 눈금과 다르면 맞춘 뒤 뺀다. 상류가
    `_normalized_pts` 에서 하는 것과 같다.

    번호가 촘촘하다는 보장은 없다. 간격이 고르지 않으면 건너뛰거나 겹치는데, 그것은 그
    시각에 프레임이 없거나 둘이 겹쳐 있다는 **사실**이고 상류도 같은 것을 본다.
    """
    offset = start_time
    if start_time and stream_time_base and stream_time_base != time_base:
        offset = int(start_time * stream_time_base / time_base)
    return round(float((pts - offset) * time_base) * frame_rate)


def frames_to_ms(frame_num: int, frame_rate: float) -> int:
    """프레임 번호를 가장 가까운 정수 ms 로 반올림한다.

    같은 프레임 번호 + 같은 fps = 항상 같은 ms 다. `keyframe.timestamp_ms` 가 이 값이고,
    `UNIQUE(scene_id, timestamp_ms)` 가 "서로 다른 프레임" 을 뜻하게 되는 근거다.
    """
    return round(frame_num * 1000 / frame_rate)


def ms_to_frame(timestamp_ms: int, frame_rate: float) -> int:
    """`frames_to_ms` 의 역함수. 정규 시각이 `timestamp_ms` 에 가장 가까운 프레임이다.

    **반올림이다.** `frames_to_ms` 가 반올림이므로 그래야 왕복이 성립한다 —
    `ms_to_frame(frames_to_ms(f)) == f` 가 모든 `f` 에서 참이어야, `scene_detection` 이
    보고한 경계 ms 를 다시 프레임으로 되돌릴 수 있다. 내림으로 두면 30fps 에서
    `frames_to_ms(1) = 33` 인데 `ms_to_frame(33) = 0` 이 되어, scene 시작 시각이
    **직전 scene 의 마지막 프레임**을 가리킨다.

    가장 가까운 프레임이므로 결과의 정규 시각은 목표보다 최대 반 프레임 앞이나 뒤일
    수 있다. 반열린 구간의 양 끝에서 이 값이 옆 구간으로 새지 않게 보정하는 일은
    `frames_in_range` 가 한다.
    """
    return round(timestamp_ms * frame_rate / 1000)


def frames_in_range(start_ms: int, end_ms: int, frame_rate: float) -> tuple[int, int] | None:
    """반열린 구간 `[start_ms, end_ms)` 안에 정규 시각이 들어오는 프레임 번호의 폐구간.

    보정이 필요한 이유는 `ms_to_frame` 이 **가장 가까운** 프레임을 주기 때문이다. 목표
    시각이 구간 양 끝에 붙어 있으면 그 프레임의 정규 시각(`frames_to_ms`)이 반 프레임만큼
    구간 밖으로 넘어갈 수 있다. 30fps 에서 반 프레임은 17ms 다. 그 상태로 저장하면
    `keyframe.timestamp_ms` 가 자기 scene 구간 밖을 가리키고, 검수자가 근거 프레임을
    눌렀을 때 다른 장면이 열린다.

    반올림 오차는 한 프레임을 넘지 않으므로 보정 반복은 각 방향 1회 이하다.

    구간이 한 프레임 간격보다 짧아 정규 시각이 들어오는 프레임이 없으면 `None` 이다.
    그것이 실패인지는 구간의 뜻을 아는 호출부가 정한다 — scene 이면 실패이고
    (`frame_extraction.frames_in_span`), 후보를 놓을 창이면 창을 넓히면 되는 일이다
    (`frame_extraction.selector` 의 `_window`).
    """
    first = max(ms_to_frame(start_ms, frame_rate), 0)
    while frames_to_ms(first, frame_rate) < start_ms:
        first += 1
    last = ms_to_frame(end_ms, frame_rate)
    while last >= 0 and frames_to_ms(last, frame_rate) >= end_ms:
        last -= 1
    if last < first:
        return None
    return first, last


def frame_count_in_range(start_ms: int, end_ms: int, frame_rate: float) -> int:
    """`frames_in_range` 가 담는 프레임 수. 빈 구간은 0 이다.

    개수만 필요한 곳이 `+1` 산술을 반복하지 않게 한다. 그 오프바이원이 갈리면 "이 구간에서
    몇 장을 뽑을 수 있는가" 의 답이 호출부마다 달라진다.
    """
    span = frames_in_range(start_ms, end_ms, frame_rate)
    if span is None:
        return 0
    first, last = span
    return last - first + 1
