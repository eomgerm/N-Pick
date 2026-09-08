"""Query Resolver 출력 계약 (FRD F-04~06, §11.1).

이 파일이 출력 schema 의 **정본**이다. `resolution_schema_version` 이 가리키는 대상이
여기이다. 반환 메타데이터는 FRD §7.2의 해석·버전 기록을 지원한다.

두 가지를 구분한다.

- `RawResolution` — LLM 이 낸 것을 그대로 담는 모델. 아직 못 믿는다.
- `ValidatedResolution` — `validator.py` 를 통과한 것. 검색이 쓸 수 있다.

둘의 필드는 같다. 타입을 나눈 이유는 검증되지 않은 값이 검색으로 새는 경로를
타입 검사에서 막기 위해서다.
"""

from typing import Final, Literal

from pydantic import BaseModel, ConfigDict, Field

#: 출력 schema 의 버전. **해시가 아니라 손으로 붙인다.**
#:
#: FRD F-14의 출력 형식 비호환 처리를 지원하는 모듈 버전이다.
#: 해시로 만들면 주석 한 줄만 고쳐도 값이 달라져 "호환된다"를 표현할 방법이 없다.
#: 필드가 늘거나 의미가 바뀔 때만 올린다.
SCHEMA_VERSION: Final[str] = "query-resolver/v2"

#: 날짜 필드 이름. **여기 한 곳에서만 정의한다.**
#:
#: FRD v3.1 F-04의 날짜 태그와 동일한 이름을 출력 계약으로 확정한다.
DateField = Literal["broadcast_date", "filmed_date"]

#: `explicit_filter` 는 사용자가 UI 에서 직접 고른 필터다. schema 에는 존재하지만
#: **resolver 는 이 값을 만들 수 없다** — F-05의 사용자 명시 필터 보호를 위한
#: 모듈 계약이다. 강제는 `validator.py` 가 한다.
Origin = Literal["explicit_filter", "explicit_query", "inferred"]

#: resolver 출력에서 허용되는 origin. 위 Origin 의 부분집합이다.
RESOLVER_ORIGINS: Final[frozenset[str]] = frozenset({"explicit_query", "inferred"})

Intent = Literal["scene_search", "recent_scene", "unknown"]
EntityType = Literal["person", "organization"]
LocationType = Literal["location", "facility"]

#: 분류 축. 값(어휘)은 아직 닫지 않는다 — 아래 `Classification.value` 주석 참조.
ClassificationType = Literal["season", "weather", "scene_type"]


class _Frozen(BaseModel):
    # extra="forbid": LLM 이 schema 에 없는 키를 얹으면 조용히 버리지 않고 거부한다.
    # 모르는 필드를 무시하면 "프롬프트를 고쳤는데 출력이 그대로"인 상황을 못 잡는다.
    model_config = ConfigDict(frozen=True, extra="forbid")


class QuerySpan(_Frozen):
    """원문 query 기준 `[start, end)` 반열린 구간.

    `query[start:end] == value` 가 성립해야 한다. 이 대조는 schema 로 표현할 수 없어
    `validator.py` 가 한다(`FRD F-05`).
    """

    start: int = Field(ge=0)
    end: int = Field(ge=0)


class _Anchor(_Frozen):
    """origin·span·confidence 를 갖는 항목의 공통 부분."""

    origin: Origin
    #: `explicit_query` 면 필수, `inferred` 면 `None`. 강제는 validator 가 한다.
    query_span: QuerySpan | None = None
    confidence: float = Field(ge=0.0, le=1.0)


class DateWindow(_Anchor):
    """`[start, end_exclusive)` 반열린 날짜 구간.

    모듈 날짜 해석 계약 (FRD F-04~06): 설명 없는 날짜는 `broadcast_date`,
    촬영 의미가 원문에 명시된 날짜만 `filmed_date` 다.
    """

    field: DateField
    #: `YYYY-MM-DD`. 날짜 파싱과 `start < end_exclusive` 검증은 validator 가 한다.
    start: str
    end_exclusive: str


class ValuedAnchor(_Anchor):
    """`value` 문자열을 갖는 anchor.

    `DateWindow` 와 갈리는 지점이다. 여기 속한 항목은 `query[span] == value` 대조가
    가능하지만(`FRD F-05`), 날짜 구간은 대조할 문자열이 없어 span 범위만 본다.
    validator 가 이 구분에 기대어 동작한다.
    """

    value: str = Field(min_length=1)


class IncidentName(ValuedAnchor):
    """사건명. 사건 목록·배정 기능은 P0 에 없다(FRD §1.2)."""


class Entity(ValuedAnchor):
    """사람·기관. 장소·시설은 여기 넣지 않는다(`FRD F-04~05`)."""

    type: EntityType


class Location(ValuedAnchor):
    """장소·시설. 사람·기관은 여기 넣지 않는다(`FRD F-04~05`)."""

    type: LocationType


class Classification(ValuedAnchor):
    """계절·날씨·장면 유형.

    이 축의 근거는 F-04 태그 유형표의 `season`·`weather`·`scene_type` 이다. F-05 #3 의
    열거에는 없으므로, 해석기 출력에 이 축을 두는 근거는 F-04 쪽에서 온다.

    `value` 를 enum 으로 닫지 않는다. 허용 어휘는 개발셋을 보고 정할 항목이고
    (FRD §11 실측 후 확정) FRD 가 enum 을 요구하지도 않는다. 어휘가 확정되면 그때
    `Literal` 로 좁힌다.
    """

    type: ClassificationType


class _ResolutionBase(_Frozen):
    """F-04~05를 표현하는 모듈 출력 계약. `RawResolution` 과 `ValidatedResolution` 의 공통 정의."""

    intent: Intent
    date_windows: tuple[DateWindow, ...] = ()
    incident_names: tuple[IncidentName, ...] = ()
    entities: tuple[Entity, ...] = ()
    locations: tuple[Location, ...] = ()
    classifications: tuple[Classification, ...] = ()
    #: 동의어·관련어. `FRD F-05` — 확장은 여기 한 곳에서만 하고 별도 rewrite 단계를 두지 않는다.
    expanded_terms: tuple[str, ...] = ()
    confidence: float = Field(ge=0.0, le=1.0)


class RawResolution(_ResolutionBase):
    """LLM 이 낸 그대로. 아직 검증되지 않았다.

    `schema_version` 이 없다. LLM 이 버전 문자열을 지어내면 그 자체가 거짓 기록이라
    코드가 붙인다(`ValidatedResolution.schema_version`).
    """


class ValidatedResolution(_ResolutionBase):
    """`validator.py` 를 통과한 결과. 검색이 쓸 수 있다."""

    schema_version: str = SCHEMA_VERSION


def empty_resolution() -> ValidatedResolution:
    """fallback 이 쓰는 빈 resolution.

    `모듈 fallback 계약`: fallback 은 **비어 있는 validated resolution** 과 raw query 를 쓴다.
    빈 결과도 같은 출력 계약의 schema_version을 반환한다.
    """
    return ValidatedResolution(intent="unknown", confidence=0.0)
