"""프레임 번호와 ms 사이의 변환 규칙. 단계 여럿이 함께 쓴다.

**변환은 이 모듈에서만 한다.** 부동소수 초를 여기저기서 반올림하면 재실행 간 1ms 가
흔들린다. `scene_detection` 이 경계를 ms 로 만들고 `frame_extraction` 이 그 ms 를 다시
프레임 번호로 되돌리므로, 두 단계가 같은 규칙을 쓰지 않으면 keyframe 이 자기 scene 밖의
프레임을 가리킬 수 있다.

한계도 공유한다: 프레임 번호 기반이라 VFR(가변 프레임레이트) 소스에서는 실제 PTS 와
어긋날 수 있다. 결정론은 유지되지만 정확도가 떨어지므로 샘플 클립은 CFR 을 쓴다
(`ai/docs/scene-detection.md`).
"""


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
    수 있다. 반열린 구간의 양 끝에서 이 값이 옆 scene 으로 새지 않게 하는 일은
    호출부가 한다 — 구간을 아는 쪽이 거기이기 때문이다
    (`frame_extraction` 의 `frames_in_span`).
    """
    return round(timestamp_ms * frame_rate / 1000)
