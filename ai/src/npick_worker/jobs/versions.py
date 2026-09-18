"""단계 버전과 파이프라인 버전 롤업.

`scene_detection/config.py` 가 미뤄 둔 일 — "여러 단계의 version_id 를 묶는 일" — 을
두 층으로 나눠 닫는다.

- **`stage_version()`** 은 워커가 계산한다. 입력은 그 단계의 **재현 튜플 전체**다.
  `config_version` 만으로는 부족하다. 그 값은 설정 파일만 해시하므로 라이브러리가 바뀌면
  값이 그대로인데 경계는 달라질 수 있다(`scene_detection/models.py` 의 같은 지적).
- **`pipeline_version()`** 은 참조 구현이다. `pipeline_run.pipeline_version` 을 실제로
  채우는 쪽은 BE 다 — 그 컬럼은 `NOT NULL` 인데 run 행은 워커가 보기 전에 존재한다.
  여기 함수는 BE 의 Java 포팅을 대조할 고정 벡터를 만들기 위한 것이다
  (`docs/contracts/job-api.md` §8).

해시 규칙 자체는 `npick_worker.versioning` 한 곳에만 있다.
"""

from collections.abc import Mapping
from typing import Any, Final

from pydantic import BaseModel, ConfigDict, Field
from pydantic.alias_generators import to_camel

from npick_worker.versioning import version_id

#: 단계 재현 식별자의 schema. `configVersion` 과 접두가 겹치지 않게 한다 —
#: 로그에 두 값이 나란히 찍히는데 접두가 같으면 사람이 반드시 헷갈린다.
STAGE_VERSION_SCHEMA: Final[str] = "npick.stage.{stage}/v1"

#: 단계 산출물 payload 의 schema.
OUTPUT_SCHEMA: Final[str] = "npick.stage.{stage}.output/v1"

PIPELINE_VERSION_SCHEMA: Final[str] = "npick-pipeline/v1"

#: pipeline_version 의 해시 길이. 단계별 8자보다 넓게 잡는다. 이 값은 사람이 읽는 로그가
#: 아니라 DB 키로 등가 비교되고, 열 단계의 식별자를 한 문자열에 접기 때문이다.
#: `varchar(128)` 안에서 8자를 더 쓰는 값은 충분히 싸다.
PIPELINE_HASH_LENGTH: Final[int] = 12


class WireModel(BaseModel):
    """잡 API 와 주고받는 모델의 공통 설정.

    BE 는 Jackson 기본값인 camelCase 를 쓴다. 파이썬 쪽 필드 이름은 snake_case 로 두고
    직렬화에서만 바꾼다. `extra="forbid"` 는 계약에 없는 키가 조용히 흘러드는 것을 막는다.
    """

    model_config = ConfigDict(
        alias_generator=to_camel,
        populate_by_name=True,
        extra="forbid",
        frozen=True,
        # 'model_version' 은 계약이 정한 와이어 이름이다. pydantic 의 'model_' 보호
        # 경고를 끄되 이름은 바꾸지 않는다.
        protected_namespaces=(),
    )


class StageRuntime(WireModel):
    """무엇이 이 결과를 만들었는지. 성능 수치를 나중에 해석하려면 있어야 한다."""

    worker: str
    python: str
    torch: str | None = None
    cuda: str | None = None


class StageVersion(WireModel):
    """단계 결과에 붙는 버전 묶음.

    `model_version` 과 `prompt_version` 은 **키가 항상 있고 값만 `None`** 이다. 생략하거나
    `"n/a"` 를 넣지 않는다. "이 단계엔 모델이 없다" 와 "보고를 빠뜨렸다" 는 다르고,
    구분이 사라지면 나중에 어느 쪽인지 알 방법이 없다. scene detection 이 정확히
    전자에 해당한다 — 가중치도 프롬프트도 쓰지 않는다.
    """

    #: 재현 식별자. 없으면 BE 가 결과를 거부한다.
    stage_version: str = Field(min_length=1)
    #: `output` payload 의 형식 버전. 없으면 BE 가 결과를 거부한다.
    output_schema_version: str = Field(min_length=1)
    #: Gate B 설정 해시. scene detection 은 `SceneDetectionConfig.version_id`.
    config_version: str | None = None
    model_version: str | None = None
    prompt_version: str | None = None
    #: `stage_version` 해시에 들어간 원본 값들. 값이 왜 바뀌었는지 추적하려면 필요하다.
    detail: Mapping[str, str] = Field(default_factory=dict)
    runtime: StageRuntime | None = None


def stage_version(stage: str, reproducibility: Mapping[str, Any]) -> str:
    """단계 하나의 재현 식별자를 만든다.

    `reproducibility` 에는 결과를 바꿀 수 있는 것을 **전부** 넣는다. 설정 해시뿐 아니라
    경계를 실제로 계산한 구현과 그 버전까지 넣어야 한다.
    """
    return version_id(STAGE_VERSION_SCHEMA.format(stage=stage), reproducibility)


def output_schema_version(stage: str) -> str:
    """단계 산출물 payload 의 schema 문자열.

    `ocr`·`vlm_metadata` 만 v2 다. 나머지는 단계 이름에서 균일하게 유도한다.

    BE 도 같은 예외 표를 든다 — `PipelineStages.outputSchema` 가 `ocr`·`vlm_metadata` 에만
    v2 를 돌려주고 배정 payload·`complete` 검사·저장 어댑터가 모두 그 표 하나를 읽는다
    (S15P21A501-184). 문자열을 어느 한쪽에서 따로 조립하면 배정과 검사가 갈려 성공 결과가
    저장 분기에 닿기도 전에 거부된다 (`docs/contracts/job-api.md` §11 항목 12).
    """
    if stage == "ocr":
        return "npick.stage.ocr.output/v2"
    if stage == "vlm_metadata":
        return "npick.stage.vlm_metadata.output/v2"
    return OUTPUT_SCHEMA.format(stage=stage)


def pipeline_version(stage_versions: Mapping[str, str]) -> str:
    """단계별 `stage_version` 들을 파이프라인 하나의 값으로 접는다.

    입력이 `단계 이름 → stage_version` 맵이므로 결과는 단계 순서와 무관하다. 정렬은
    `canonical_json` 이 하고, 여기서 다시 하지 않는다.
    """
    return version_id(
        PIPELINE_VERSION_SCHEMA,
        dict(stage_versions),
        length=PIPELINE_HASH_LENGTH,
    )
