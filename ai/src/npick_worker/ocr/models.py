"""OCR 단계의 입력·산출물. FRD §7.1 `ocr_observation` 테이블 어휘를 그대로 쓴다.

```sql
CREATE TABLE "ocr_observation" (
    "ocr_observation_id" bigint  NOT NULL,
    "keyframe_id"        bigint  NOT NULL,
    "raw_text"           text    NOT NULL,
    "tokens"             text    NOT NULL,
    "confidence"      numeric(5,4) NOT NULL,
    "bounding_box_json"  jsonb   NOT NULL
);
```

이 표가 설계 여지를 대부분 좁혀 놓았다.

| 요구 | 스키마의 자리 | 결과 |
| --- | --- | --- |
| 어느 프레임에서 읽었나 | `keyframe_id` | 워커는 `(sceneIndex, timestampMs)` 로 말한다 |
| OCR 원문 | `raw_text` | 엔진이 읽은 그대로. 정규화한 문자열을 넣지 않는다 |
| 검색 토큰 | `tokens` | Kiwi. 색인과 질의가 같은 설정을 써야 한다 |
| 위치 | `bounding_box_json` | 원본 해상도 픽셀 좌표 |
| 신뢰도 | `confidence` | `numeric(5,4)` — 소수점 넷째 자리까지다 |
| 검증 상태 | **없음** | 컬럼 주석이 "이 값의 임계값으로 판정한다" 로 둔다 |
| 병합 그룹 | 전용 컬럼 없음 | OCR v2 출력·ocr_result JSON 산출물에 원본과 함께 보존 |
"""

from dataclasses import dataclass, field
from typing import Any, Final

from npick_worker.ocr.merge import (
    OcrMergeConfig,
    OcrTextGroup,
    get_merge_config,
    merge_observations,
)

#: `ocr_observation.confidence` 가 `numeric(5,4)` 다. 다섯째 자리를 보내면 BE 나 DB 가
#: 반올림하고, 그러면 워커 로그의 값과 저장된 값이 갈린다. 보내기 전에 여기서 맞춘다.
CONFIDENCE_DECIMALS: Final[int] = 4


@dataclass(frozen=True, slots=True)
class KeyframeRef:
    """읽을 이미지 한 장. 상류 `frame_extraction` 산출물의 한 원소다.

    `keyframe_id` 를 받지 않는다. 워커는 DB 에 접속하지 않고, `complete` 응답의
    `assignedIds` 도 scene 만 돌려준다(`docs/contracts/job-api.md` §4.3). 대신
    `keyframe` 에 `UNIQUE(scene_id, timestamp_ms)` 가 있으므로 이 쌍이 곧 그 행을
    가리킨다 — 스키마도 `assignedIds` 도 늘리지 않고 닫힌다.
    """

    scene_index: int
    #: 저장된 프레임의 정규 시각. `keyframe.timestamp_ms` 와 같은 값이다.
    timestamp_ms: int
    #: `keyframe.storage_key`. 어느 파일을 읽었는지의 근거로 결과에 다시 싣는다.
    storage_key: str


@dataclass(frozen=True, slots=True)
class BoundingBox:
    """화면 위치. **원본 해상도 픽셀 좌표**다.

    네 점 다각형으로 둔다. 검출기가 돌려주는 것이 축에 나란한 사각형이 아니라
    기울어질 수 있는 사변형이고, 축에 맞춰 펴면 기울어진 현판·배너에서 실제보다
    넓은 영역을 가리키게 된다. 근거 이미지 위에 상자를 그려 보여 주는 것이 이 값의
    용도이므로(컬럼 주석) 그 어긋남이 그대로 보인다.

    `x`·`y`·`width`·`height` 를 함께 담는 이유는 소비자가 사변형을 쓰지 않을 때
    다시 계산하지 않게 하기 위해서다. 값은 `points` 에서 유도한다.
    """

    #: `[[x, y], ...]` 네 점. 검출기가 준 순서(좌상단부터 시계 방향)를 유지한다.
    points: tuple[tuple[float, float], ...]

    def __post_init__(self) -> None:
        if len(self.points) < 3:
            msg = f"상자는 최소 세 점이어야 한다: {len(self.points)}점"
            raise ValueError(msg)

    @property
    def x(self) -> float:
        return min(x for x, _ in self.points)

    @property
    def y(self) -> float:
        return min(y for _, y in self.points)

    @property
    def width(self) -> float:
        return max(x for x, _ in self.points) - self.x

    @property
    def height(self) -> float:
        return max(y for _, y in self.points) - self.y

    def to_json(self) -> dict[str, Any]:
        """`ocr_observation.bounding_box_json` 에 그대로 들어가는 모양."""
        return {
            "points": [[x, y] for x, y in self.points],
            "x": self.x,
            "y": self.y,
            "width": self.width,
            "height": self.height,
        }


@dataclass(frozen=True, slots=True)
class OcrObservation:
    """`ocr_observation` 행 하나. 검출된 글줄 하나에 대응한다.

    **글줄을 문단으로 합치지 않는다.** 합치면 `bounding_box_json` 이 여러 줄을 덮는
    사각형이 되어 "어디서 읽었는지" 를 정확히 가리키지 못하고, confidence 도 하나로
    뭉개져 줄마다 다른 품질이 사라진다.
    """

    keyframe: KeyframeRef
    #: 엔진이 읽은 그대로. **덮어쓰지 않는다**(컬럼 주석).
    raw_text: str
    #: Kiwi 색인 토큰. 공백으로 이어 `tokens` 컬럼에 넣는다.
    tokens: tuple[str, ...]
    #: 0~1. 넷째 자리까지 반올림해 둔다.
    confidence: float
    box: BoundingBox
    #: 참이면 `confidence` 가 `min_confidence` 미만이다. 검색 후보로는 쓰되 검증된
    #: 사실이나 hard conflict 의 근거로 쓰지 않는다(티켓 제약).
    unverified: bool
    #: 같은 문구를 가리키는 관측들의 공통 키. 색인 토큰이 같으면 같은 값이다.
    #: 검색 토큰의 해시. 병합 그룹 ID가 아니며 원문 일치를 보장하지 않는다.
    text_key: str

    @property
    def tokens_text(self) -> str:
        """`ocr_observation.tokens` 에 들어가는 문자열.

        공백으로 잇는다. 색인이 `tokens::pdb.whitespace` 로 걸려 있어(baseline
        마이그레이션 `ix_ocr_bm25`) 구분자가 공백이어야 한다.
        """
        return " ".join(self.tokens)


@dataclass(frozen=True, slots=True)
class KeyframeObservations:
    """keyframe 한 장에서 읽은 것 전부. 0 건이 정상이다."""

    keyframe: KeyframeRef
    observations: tuple[OcrObservation, ...]

    def __post_init__(self) -> None:
        if any(obs.keyframe != self.keyframe for obs in self.observations):
            msg = f"다른 keyframe 의 관측이 섞였다: {self.keyframe.storage_key}"
            raise ValueError(msg)


@dataclass(frozen=True, slots=True)
class OcrResult:
    """단계 산출물 전체.

    재현성 식별자는 `(config_version, engine, engine_version, tokenizer,
    merge_config.version_id)` 튜플이다.
    `config_version` 은 설정 파일만 해시하므로 모델 가중치나 onnxruntime 이 바뀌면
    값이 그대로인데 읽는 글자는 달라질 수 있다. `tokenizer` 가 따로 있는 이유는
    `tokens` 컬럼이 이 단계의 산출물이기 때문이다 — Kiwi 설정이 바뀌면 원문이 같아도
    색인이 달라진다.
    """

    keyframes: tuple[KeyframeObservations, ...]
    config_version: str
    #: 글자를 실제로 읽은 구현 이름 (OcrEngine.name). 예: `rapidocr`
    engine: str
    #: 그 구현과 모델의 버전 (OcrEngine.version).
    engine_version: str
    #: 색인 토큰을 만든 규칙의 식별자 (`korean_tokens.tokenizer_version`).
    tokenizer: str
    #: 판정에 쓴 임계값. 결과에 실어야 나중에 "그때 무엇이 unverified 였나" 를 안다.
    min_confidence: float
    merge_config: OcrMergeConfig = field(default_factory=get_merge_config)

    @property
    def observations(self) -> tuple[OcrObservation, ...]:
        return tuple(obs for keyframe in self.keyframes for obs in keyframe.observations)

    @property
    def text_groups(self) -> tuple[OcrTextGroup, ...]:
        return merge_observations(self.observations, self.merge_config)

    @property
    def observation_count(self) -> int:
        return sum(len(kf.observations) for kf in self.keyframes)

    @property
    def unverified_count(self) -> int:
        return sum(1 for kf in self.keyframes for obs in kf.observations if obs.unverified)

    @property
    def text_group_count(self) -> int:
        """독립 관측을 포함한 scene별 병합 그룹 수."""
        return len(self.text_groups)
