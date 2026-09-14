"""VLM 단계가 **모델에게 요구하는** 출력 계약 (FRD F-03 영상 설명 생성, F-04 태그와 근거).

`stages.py` 3단계 `vlm_metadata` 의 필수 출력은 "schema-valid metadata·confidence·frame
evidence" 다. 이 파일이 그 schema 의 정본이고, 검증을 통과한 값의 어휘는 `models.py` 에 있다.

두 층을 나누는 이유는 `query_resolver` 와 같다 — 검증되지 않은 값이 정본으로 새는 경로를
타입으로 막는다.

- `RawSceneMetadata` — 모델이 낸 JSON 그대로. 아직 못 믿는다.
- `models.SceneMetadata` — `validator.py` 를 통과한 것. 잡 레이어가 반납할 수 있다.

**어휘를 어디에 두는지가 갈린다.**

| 어휘 | 자리 | 이유 |
| --- | --- | --- |
| `shot_type` 4값 | **이 파일**(`Literal`) | FRD 가 확정했다(`docs/frd.md:159`) |
| `tag_candidate` 유형 | **이 파일**(`Literal`) | `tag.tag_type` 11종에서 날짜 2종을 뺀 것 |
| `scene_type` 값 | **설정**(`vlm_metadata.v*.toml`) | 닫힌 어휘지만 초안이라 실측 후 확정한다 |

`scene_type` 만 설정에 있는 것은 타협이 아니라 그 값의 성질이다. 지금 어휘를 코드에 박으면
실측으로 고칠 때마다 출력 schema 버전이 올라가는데, 바뀐 것은 계약이 아니라 어휘다.
"""

from typing import Annotated, Final, Literal

from pydantic import BaseModel, ConfigDict, Field, StringConstraints

#: 출력 schema 의 버전. **해시가 아니라 손으로 붙인다.**
#:
#: `query_resolver/schema.py` 와 같은 판단이다. 해시로 만들면 주석 한 줄만 고쳐도 값이
#: 달라져 "호환된다" 를 표현할 방법이 없다. 필드가 늘거나 의미가 바뀔 때만 올린다.
#: FRD F-14 의 출력 형식 비호환 처리를 지원한다.
SCHEMA_VERSION: Final[str] = "vlm-metadata/v1"

#: `scene.shot_type` 의 값. **태그가 아니라 컬럼이다** (`docs/frd.md:159`).
#: 분류값 중 유일하게 칸으로 남은 것이라(baseline 의 컬럼 주석) 어휘가 FRD 에 확정돼 있다.
ShotType = Literal["anchor", "interview", "b_roll", "unknown"]

#: 시각 근거로 만들 수 있는 태그 후보의 유형.
#:
#: `tag.tag_type` 11종에서 **날짜 2종(`filmed_date`·`broadcast_date`)을 뺐다.** 화면에
#: 날짜가 보인다는 사실과 그 날짜가 방송일·촬영일이라는 판단은 다르고, FRD 는 후자를
#: "OCR 신뢰도와 원본 문맥을 확인하지 않고" 하지 말라고 요구한다(`docs/frd.md:157`).
#: 화면에 실제로 표시된 문자열은 OCR 단계의 것이므로(같은 문서 F-03) VLM 이 날짜 후보를
#: 만들 자리가 없다. 유형 자체를 빼면 모델이 날짜 태그를 지어내는 경로가 schema 에서 닫힌다.
#:
#: 인물·기관·장소·사건은 남긴다. 티켓 제약이 "VLM 에서 생성한 추정 정보는 confidence 가
#: 높더라도 자동으로 verified 처리하지 않는다" 이지 만들지 말라는 것이 아니고, 실제로
#: `tag_evidence.source` 에 `vlm` 이 있다(baseline 컬럼 주석).
TagCandidateType = Literal[
    "person",
    "organization",
    "location",
    "facility",
    "keyword",
    "event",
    "season",
    "weather",
    "scene_type",
]

#: 근거 keyframe 을 가리키는 이름. 프롬프트가 이미지마다 이 형식의 라벨을 붙여 준다.
#:
#: **모델에게 `timestamp_ms` 를 말하게 하지 않는다.** 그 값은 화면에 없으므로 물으면
#: 지어낸다. 라벨은 우리가 준 것이라 대조가 가능하고, 실제 keyframe 으로 되돌리는 일은
#: `validator.py` 가 한다.
EVIDENCE_LABEL_PATTERN: Final[str] = r"^kf_[0-9]+$"

#: 근거 라벨의 타입. 모양이 다른 문자열은 pydantic 이 먼저 거부한다 — 모양이 맞는데 입력에
#: 없는 라벨(`kf_9` 을 3장만 준 장면에서)을 거부하는 일은 `validator.py` 가 한다.
EvidenceLabel = Annotated[str, StringConstraints(pattern=EVIDENCE_LABEL_PATTERN)]


class _Raw(BaseModel):
    # extra="forbid": 모델이 schema 에 없는 키를 얹으면 조용히 버리지 않고 거부한다.
    # 모르는 필드를 무시하면 "프롬프트를 고쳤는데 출력이 그대로" 인 상황을 못 잡는다.
    model_config = ConfigDict(frozen=True, extra="forbid")


class RawJudgement(_Raw):
    """판단 하나의 공통 부분 — 신뢰도와 근거 프레임.

    둘 다 필수인 이유가 다르다. `confidence` 는 `tag_evidence.confidence` 가 될 값이고,
    `evidence` 는 FRD F-04 의 "값만 저장하지 않고 출처와 확인 가능한 근거 위치를 연결한다"
    를 이 단계에서 지키는 자리다.
    """

    confidence: float = Field(ge=0.0, le=1.0)
    #: 이 판단의 근거가 된 keyframe 라벨. **비어 있을 수 있는 곳은 한 군데뿐이다** —
    #: `RawSceneMetadata.shot_type` 이 `unknown` 일 때다(그 필드 주석 참고).
    #: 강제는 `validator.py` 가 한다. schema 로는 "라벨의 모양" 까지만 말할 수 있다.
    evidence: tuple[EvidenceLabel, ...] = Field(default=(), max_length=32)


class RawCaption(RawJudgement):
    """`scene.caption` 이 될 장면 설명."""

    value: str = Field(min_length=1)


class RawShotType(RawJudgement):
    """`scene.shot_type` 이 될 샷 유형."""

    value: ShotType


class RawSceneType(RawJudgement):
    """장면 유형. **`scene` 의 칸이 아니라 태그 후보다** (`docs/frd.md:148`).

    `validator.py` 가 `type="scene_type"` 인 태그 후보로 옮긴다. 여기서 필드를 따로 두는
    이유는 프롬프트 때문이다 — 닫힌 어휘 하나를 고르는 일과 자유로운 태그 후보를 여러 개
    만드는 일은 모델에게 다른 작업이고, 한 배열에 섞으면 어휘 준수율이 떨어진다.
    """

    #: 허용 어휘는 설정이 정한다(`config/vlm_metadata.v*.toml` 의 `scene_type_vocabulary`).
    #: 실측 후 확정 대상이라 `Literal` 로 닫지 않는다 — FRD §11.
    value: str = Field(min_length=1)


class RawTagCandidate(RawJudgement):
    """시각 근거로 만든 태그 후보 하나.

    `tag` 행이 되는 것은 이 단계가 아니다. `stages.py` 8단계 `entity_extraction` 이
    "typed tag 후보·source·confidence·evidence" 의 주인이고, 여기서 나온 것은 그 입력이다.
    `tag.match_value` 정규화(NFKC + 공백 제거, `docs/frd.md:149`)도 저장 규약이라 하지 않는다.
    """

    type: TagCandidateType
    #: 표시 이름 후보. 정규화하지 않은 원래 표기다.
    value: str = Field(min_length=1)


class RawSceneMetadata(_Raw):
    """모델이 scene 하나에 대해 내야 하는 전부.

    **`scene_index` 가 없다.** 어느 장면을 보고 있는지는 우리가 아는 값이고 화면에는 없다.
    물으면 지어내므로 묻지 않고 `validator.py` 가 붙인다. `schema_version` 이 없는 것도
    같은 이유다 — 모델이 버전 문자열을 지어내면 그 자체가 거짓 기록이다.
    """

    #: 시각 근거가 없으면 `null` 이다. 지어낸 문장으로 채우지 않는다(티켓 제약).
    caption: RawCaption | None = None
    #: **`null` 을 허용하지 않는다.** `scene.shot_type` 이 `NOT NULL` 이고, 근거가 없을 때
    #: 쓸 값이 어휘 안에 이미 있다 — `unknown` 이다. 그 값일 때만 `evidence` 가 비어도 된다.
    shot_type: RawShotType
    #: 시각 근거가 없으면 `null` 이다. 태그는 없으면 안 붙는 것이라 `unknown` 값을 두지
    #: 않는다 — 컬럼인 `shot_type` 과 반대인 지점이고, 그 차이는 저장 자리에서 온다.
    scene_type: RawSceneType | None = None
    tag_candidates: tuple[RawTagCandidate, ...] = Field(default=(), max_length=32)
