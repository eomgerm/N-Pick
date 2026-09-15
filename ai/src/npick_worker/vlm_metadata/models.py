"""VLM 단계의 입력·산출물. 검증을 통과한 값의 어휘다.

모델이 내는 모양은 `schema.py` 가, 그것을 여기 값으로 옮기는 일은 `validator.py` 가 한다.

저장 자리는 표 셋이다. 이 단계의 산출물이 그 셋으로 정확히 갈라지는지가 설계의 전부다.

| 이 파일의 값 | 저장 자리 | 근거 |
| --- | --- | --- |
| `Caption.value`·`tokens` | `scene.caption`·`scene.caption_tokens` | `docs/frd.md:121` |
| `ShotTypeJudgement.value` | `scene.shot_type` (`NOT NULL`) | `docs/frd.md:159` |
| `TagCandidate` | `tag`·`tagging`·`tag_evidence` 후보 | `docs/frd.md:148` |
| 근거 keyframe | `tag_evidence.source_ref_type='keyframe'` | baseline 컬럼 주석 |

**`scene_type` 은 `scene` 의 칸이 아니다.** 여기서는 `type="scene_type"` 인 `TagCandidate`
하나로만 존재한다 — 칸으로 착각할 수 있는 필드를 아예 두지 않는다.
"""

from dataclasses import dataclass
from typing import Final

from npick_worker.vlm_metadata.grounding import OcrRef, OcrText, TranscriptRef, TranscriptText
from npick_worker.vlm_metadata.schema import ShotType, TagCandidateType

#: `tag_evidence.confidence` 가 `numeric(5,4)` 다. 다섯째 자리를 보내면 DB 가 반올림해
#: 워커 기록과 저장된 값이 갈린다. `ocr/models.py` 와 같은 이유로 보내기 전에 맞춘다.
CONFIDENCE_DECIMALS: Final[int] = 4


@dataclass(frozen=True, slots=True)
class KeyframeRef:
    """입력 이미지 한 장. 상류 `frame_extraction` 산출물의 한 원소다.

    `ocr.KeyframeRef` 와 필드가 같지만 **그것을 임포트하지 않는다.** 두 단계는 서로의
    상류가 아니고, 둘 다 BE 가 `inputs.upstream` 으로 되돌려 주는 같은 JSON 을 읽는
    형제다(`docs/contracts/job-api.md` §4.1). `frame_extraction` 이 `scene_detection.Scene`
    대신 자기 `SceneSpan` 을 두는 것과 같은 판단이다 — 한쪽 단계의 타입에 의존을 걸면
    VLM 만 도는 파드가 `ocr` 을 통해 rapidocr·onnxruntime 을 끌어오게 된다.

    `keyframe_id` 를 받지 않는다. TSID 는 BE 가 발급하고 `complete` 응답의 `assignedIds`
    도 scene 만 돌려준다(계약 §4.3). `keyframe` 의 `UNIQUE(scene_id, timestamp_ms)` 가
    있으므로 이 쌍이 곧 그 행이다 — OCR 이 이미 같은 방식으로 닫았다(계약 §4.3.2).
    """

    scene_index: int
    #: 저장된 프레임의 정규 시각. `keyframe.timestamp_ms` 와 같은 값이다.
    timestamp_ms: int
    #: `keyframe.storage_key`. 어느 파일을 보았는지의 근거로 결과에 다시 싣는다.
    storage_key: str


@dataclass(frozen=True, slots=True)
class SceneKeyframes:
    """이 단계의 입력 — scene 하나와 그 장면에서 뽑힌 keyframe 들.

    상류 `frame_extraction` 이 준 순서를 그대로 받는다. **첫 원소가 대표 이미지다**
    (계약 §4.3.1). 이 단계는 대표를 특별히 쓰지 않지만 순서를 바꾸지 않고 받는다 —
    무엇을 모델에 넣을지 고르는 일(`describer.select_keyframes`)이 그 순서를 알고 있어야
    한다.
    """

    scene_index: int
    keyframes: tuple[KeyframeRef, ...]
    ocr: tuple[OcrText, ...] = ()
    transcripts: tuple[TranscriptText, ...] = ()

    def __post_init__(self) -> None:
        if not self.keyframes:
            # `frame_extraction` 은 치명 단계이고 장면마다 복수 keyframe 을 보장한다
            # (FRD F-03). 빈 묶음이 왔다는 것은 상류 산출물이 그 보장을 깼다는 뜻이라
            # 이 단계가 빈 결과를 내며 넘어갈 일이 아니다.
            msg = f"keyframe 이 없는 scene 이다: scene_index={self.scene_index}"
            raise ValueError(msg)
        if any(keyframe.scene_index != self.scene_index for keyframe in self.keyframes):
            msg = f"다른 scene 의 keyframe 이 섞였다: scene_index={self.scene_index}"
            raise ValueError(msg)


@dataclass(frozen=True, slots=True)
class Judgement:
    """판단 하나의 공통 부분.

    `evidence` 가 튜플인 이유는 한 판단의 근거가 여러 장일 수 있기 때문이다 — 복수
    keyframe 을 함께 넣는 것이 이 단계의 입력 규약이고(티켓), "앵커석에서 시작해 자료
    화면으로 넘어간다" 같은 판단은 한 장으로 성립하지 않는다.
    """

    #: 0~1. 넷째 자리까지 반올림돼 있다.
    confidence: float
    #: 이 판단의 근거가 된 keyframe. `shot_type` 이 `unknown` 일 때만 비어 있을 수 있다.
    evidence: tuple[KeyframeRef | OcrRef | TranscriptRef, ...]


@dataclass(frozen=True, slots=True)
class Caption(Judgement):
    """`scene.caption` 이 될 장면 설명과 그 색인 토큰."""

    value: str
    #: Kiwi 색인 토큰. `scene.caption_tokens` 에 공백으로 이어 넣는다. BE 에 Kiwi 가 없어
    #: 워커가 만든다 — `ocr_observation.tokens` 와 같은 규약이고 같은 설정을 쓴다
    #: (`korean_tokens.py`). 빈 튜플이 정상일 수 있다(내용어가 없는 설명).
    tokens: tuple[str, ...]

    @property
    def tokens_text(self) -> str:
        """`scene.caption_tokens` 에 들어가는 문자열. 색인이 `pdb.whitespace` 라 공백으로 잇는다."""
        return " ".join(self.tokens)


@dataclass(frozen=True, slots=True)
class ShotTypeJudgement(Judgement):
    """`scene.shot_type` 이 될 값. 어휘는 FRD 가 확정했다."""

    value: ShotType


@dataclass(frozen=True, slots=True)
class TagCandidate(Judgement):
    """태그 후보 하나. **아직 태그가 아니다.**

    `tag` 행으로 만드는 일은 8단계 `entity_extraction` 과 BE 의 몫이다. 이 값이 저장될 때
    `tag_evidence.source` 는 `vlm`, `verification_status` 는 `unverified` 다 — AI 의 높은
    신뢰도만으로 verified 가 되지 않는다(`docs/frd.md:156`, `docs/frd.md:158`).
    """

    type: TagCandidateType
    value: str


@dataclass(frozen=True, slots=True)
class SceneMetadata:
    """scene 하나의 검증된 metadata.

    `caption` 이 `None` 일 수 있고 `tag_candidates` 가 빌 수 있다. 둘 다 정상이다 —
    시각 근거가 없는 값을 지어내지 않는 것이 이 단계의 계약이다. 반면 `shot_type` 은
    항상 있다. 값이 없는 상태를 `unknown` 이라는 어휘로 표현하기 때문이고, 그 자리가
    `NOT NULL` 컬럼이기 때문이다.
    """

    scene_index: int
    shot_type: ShotTypeJudgement
    caption: Caption | None = None
    tag_candidates: tuple[TagCandidate, ...] = ()

    @property
    def scene_type(self) -> TagCandidate | None:
        """장면 유형 후보. 조회 편의일 뿐 **별도의 저장 자리가 아니다**."""
        return next((tag for tag in self.tag_candidates if tag.type == "scene_type"), None)


@dataclass(frozen=True, slots=True)
class VlmResult:
    """단계 산출물 전체.

    재현성 식별자가 다섯 축이다 — `(config_version, engine, engine_version, model_version,
    tokenizer)`. 앞 단계들보다 많은 이유가 셋이다.

    - `model_version` — 가중치가 바뀌면 같은 프레임에서 다른 문장이 나온다.
    - `tokenizer` — `scene.caption_tokens` 가 이 단계의 산출물이다. Kiwi 설정이 바뀌면
      설명이 같아도 색인이 달라지고, 그건 검색이 0 건이 되는 종류의 변화다.
    - `prompt_version` 은 축이 아니라 `config_version` 안에 있다. 프롬프트가 설정 파일의
      한 절이기 때문이다(`config.py` 참고). 그런데도 따로 노출하는 이유는 §7.2 기록에서
      "프롬프트만 바뀌었는가" 를 물을 수 있어야 하기 때문이다.
    """

    scenes: tuple[SceneMetadata, ...]
    #: 모델 출력 계약의 버전(`schema.SCHEMA_VERSION`). 코드가 붙인다 — 모델이 아니라.
    schema_version: str
    #: 설정 파일 전체의 해시. 프롬프트·호출 파라미터·어휘가 모두 들어간다.
    config_version: str
    #: `[prompt]` 절만의 해시.
    prompt_version: str
    #: 호출 어댑터의 이름 (`VlmClient.name`). 예: `transformers`
    engine: str
    #: 그 어댑터와 런타임의 버전 (`VlmClient.version`).
    engine_version: str
    #: 가중치의 식별자 (`VlmClient.model_version`). 예: `Qwen/Qwen3-VL-8B-Instruct@<revision>`
    model_version: str
    #: 색인 토큰을 만든 규칙의 식별자 (`korean_tokens.tokenizer_version`).
    tokenizer: str

    @property
    def scene_count(self) -> int:
        return len(self.scenes)

    @property
    def caption_count(self) -> int:
        """설명이 생성된 장면 수. `scene_count` 보다 작으면 근거가 없어 비운 장면이 있다."""
        return sum(1 for scene in self.scenes if scene.caption is not None)

    @property
    def tag_candidate_count(self) -> int:
        return sum(len(scene.tag_candidates) for scene in self.scenes)

    @property
    def unknown_shot_type_count(self) -> int:
        """`unknown` 으로 남은 장면 수. 이 비율이 튀면 프롬프트나 모델을 사람이 봐야 한다."""
        return sum(1 for scene in self.scenes if scene.shot_type.value == "unknown")
