"""scene detection 산출물. FRD §10.3 `scene` 테이블 컬럼명을 그대로 쓴다."""

from dataclasses import dataclass


@dataclass(frozen=True, slots=True)
class Scene:
    """clip 을 나눈 반열린 구간 하나. `[start_time_ms, end_time_ms)` (FRD §15.6).

    `scene_id` 와 `processing_version` 은 여기서 만들지 않는다. 안정 ID 부여와
    pipeline run 배선은 BE 스키마 확정 뒤 S15P21A501-70 에서 한다(FRD F-14 의 새 장면 ID).
    """

    #: clip 내 0-base 순번. FRD 의 `UNIQUE(pipeline_run_id, scene_index)` 에 대응한다.
    scene_index: int
    #: 포함
    start_time_ms: int
    #: 미포함
    end_time_ms: int

    @property
    def duration_ms(self) -> int:
        return self.end_time_ms - self.start_time_ms


@dataclass(frozen=True, slots=True)
class SceneDetectionResult:
    """단계 산출물 전체.

    재현성 식별자는 `(config_version, engine, engine_version)` 튜플이다.
    `config_version` 은 설정 파일만 해시하므로 라이브러리가 바뀌면 값이 그대로인데
    경계는 달라질 수 있다. 세 필드를 모두 실어야 `docs/frd.md:137` 이 성립한다.

    이 튜플을 묶어 FRD `pipeline_run.pipeline_version` 을 만드는 일은 파이프라인 전체의
    몫이므로 S15P21A501-70 에서 한다.
    """

    scenes: tuple[Scene, ...]
    #: 이 결과를 만든 설정의 버전(SceneDetectionConfig.version_id). `docs/frd.md:415`.
    config_version: str
    #: 사용한 detector 이름. config 에 이미 들어 있지만 로그·리포트에서 자주 쓴다.
    detector: str
    #: 경계를 실제로 계산한 구현 이름 (SceneDetector.name). 예: `pyscenedetect`
    engine: str
    #: 그 구현의 버전 (SceneDetector.version). 예: `0.7.1`
    engine_version: str
    #: clip 전체 길이. 마지막 scene 의 end_time_ms 와 같다.
    duration_ms: int
    #: 디코드에 사용한 프레임레이트. VFR 소스 판별과 재현 확인용 기록이다.
    frame_rate: float
