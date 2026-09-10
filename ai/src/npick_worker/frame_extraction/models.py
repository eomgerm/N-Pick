"""frame extraction 의 입력·산출물. FRD §7.1 `keyframe` 테이블 어휘를 그대로 쓴다."""

from dataclasses import dataclass
from typing import Final

#: `keyframe.storage_key` 파일명 규약. 잡 레이어가 앞에 `outputKeyPrefix` 를 붙인다.
#: scene 번호를 디렉터리로 나누는 이유는 장면 수백 개에 장 수를 곱한 수가 한 디렉터리에 평평하게
#: 쌓이면 운영자가 눈으로 뒤질 수 없기 때문이다.
FILE_NAME_TEMPLATE: Final[str] = "s{scene_index:04d}/kf-{timestamp_ms:09d}.jpg"


@dataclass(frozen=True, slots=True)
class SceneSpan:
    """이 단계의 입력. `[start_time_ms, end_time_ms)` 반열린 구간이다.

    `scene_detection.Scene` 을 그대로 받지 않는다. 이 단계에 scene 이 도착하는 실제
    경로는 BE 가 `inputs.upstream` 으로 되돌려 주는 JSON 이지 상류 단계의 파이썬
    객체가 아니다(`docs/contracts/job-api.md` §4.1 — 워커는 DB 에 접속하지 않는다).
    같은 워커 프로세스가 두 단계를 다 돌릴 수도 있지만 그건 배치의 우연이고, 그
    우연에 타입 의존을 걸면 CPU 전용 fleet 이 frame extraction 만 돌릴 때
    `scenedetect`→`cv2` 를 끌어오게 된다.
    """

    #: clip 내 0-base 순번. `scene` 행의 안정 ID(`scene_id`)는 BE 가 발급한다.
    scene_index: int
    #: 포함
    start_time_ms: int
    #: 미포함
    end_time_ms: int

    @property
    def duration_ms(self) -> int:
        return self.end_time_ms - self.start_time_ms


@dataclass(frozen=True, slots=True)
class Keyframe:
    """저장된 이미지 한 장. `keyframe` 행 하나가 된다."""

    scene_index: int
    #: **저장된 프레임의 정규 시각**이다. 후보를 계획할 때 쓴 목표 시각이 아니라
    #: `frames_to_ms(frame_number, frame_rate)` 다. 두 값을 섞으면 `keyframe` 의
    #: `UNIQUE(scene_id, timestamp_ms)` 가 서로 다른 ms 를 가진 같은 프레임 두 장을
    #: 허용하게 되고, OCR 이 같은 화면을 두 번 읽는다.
    timestamp_ms: int
    #: 디코드 순서상 프레임 번호. 중복 제거의 실제 기준이다.
    frame_number: int
    #: `out_dir` 기준 상대 경로. `storage_key` 는 잡 레이어가 접두를 붙여 만든다 —
    #: 이 모듈은 `outputKeyPrefix` 도 `pipeline_run_id` 도 모른다.
    file_name: str
    byte_size: int
    #: 업로드 시 `X-Content-SHA256` 에 싣는 값. 계약 §4.4.
    content_sha256: str
    #: 선정 점수(선명도). 왜 이 프레임이 뽑혔는지 나중에 조사할 근거다.
    score: float
    #: 참이면 `min_luma_std` 미달인데도 대안이 없어 쓴 프레임이다. 치명 단계라
    #: 0 장으로 끝내지 않지만, 그 사실을 지우지도 않는다.
    blank: bool


@dataclass(frozen=True, slots=True)
class SceneKeyframes:
    """scene 하나의 keyframe 묶음.

    **`keyframes[0]` 이 대표 이미지다.** `keyframe` 테이블에 대표를 표시할 컬럼이
    없고 ERD 주석이 "결과 목록의 대표 이미지는 첫 장을 쓴다" 로 두었기 때문에, 대표는
    별도 플래그가 아니라 **목록 순서**로 전달한다. BE 는 이 순서대로 INSERT 하므로
    대표가 그 scene 의 최소 `keyframe_id` 가 된다(`docs/contracts/job-api.md` §4.3).
    나머지는 `timestamp_ms` 오름차순이다.
    """

    scene_index: int
    keyframes: tuple[Keyframe, ...]

    def __post_init__(self) -> None:
        if not self.keyframes:
            # 이 단계는 치명 단계다. scene 하나라도 빈 묶음이면 후속 OCR·VLM 이
            # 근거 프레임 없이 돌아야 하고, FRD §3 은 대표 이미지 없이 검색 가능으로
            # 표시하지 않도록 요구한다(docs/frd.md:135).
            msg = f"keyframe 이 없는 scene 이다: scene_index={self.scene_index}"
            raise ValueError(msg)
        if any(kf.scene_index != self.scene_index for kf in self.keyframes):
            msg = f"다른 scene 의 keyframe 이 섞였다: scene_index={self.scene_index}"
            raise ValueError(msg)
        stamps = [kf.timestamp_ms for kf in self.keyframes]
        if len(set(stamps)) != len(stamps):
            # UNIQUE(scene_id, timestamp_ms) 를 여기서 미리 지킨다. BE 에서 터지면
            # 트랜잭션 하나가 통째로 롤백되고 이미 쓴 처리 시간이 사라진다.
            msg = f"같은 timestamp_ms 가 두 번 있다: scene_index={self.scene_index}"
            raise ValueError(msg)

    @property
    def representative(self) -> Keyframe:
        """결과 카드에 쓸 대표 이미지. 목록의 첫 장이다."""
        return self.keyframes[0]


@dataclass(frozen=True, slots=True)
class FrameExtractionResult:
    """단계 산출물 전체.

    재현성 식별자는 `(config_version, engine, engine_version)` 튜플이다.
    `config_version` 은 설정 파일만 해시하므로 디코더·인코더가 바뀌면 값이 그대로인데
    고른 프레임이나 저장된 바이트는 달라질 수 있다.
    """

    scenes: tuple[SceneKeyframes, ...]
    #: 이 결과를 만든 설정의 버전(FrameExtractionConfig.version_id).
    config_version: str
    #: 프레임을 실제로 뽑은 구현 이름 (FrameGrabber.name). 예: `pyav`
    engine: str
    #: 그 구현의 버전 (FrameGrabber.version). 예: `15.1.0`
    engine_version: str
    #: 디코드에 사용한 프레임레이트. ms↔프레임 변환의 기준이므로 반드시 남긴다.
    frame_rate: float
    #: 저장한 이미지의 해상도. **원본 그대로다** — 이 단계는 다운스케일하지 않는다
    #: (docs/frd.md:131 작은 글자 OCR). OCR 의 bounding box 좌표계이기도 하다.
    image_width: int
    image_height: int

    @property
    def keyframe_count(self) -> int:
        return sum(len(scene.keyframes) for scene in self.scenes)

    @property
    def blank_count(self) -> int:
        """블랭크 판정에 걸렸는데도 대안이 없어 쓴 장 수. 품질 경고의 근거다."""
        return sum(1 for scene in self.scenes for kf in scene.keyframes if kf.blank)
