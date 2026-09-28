"""text embedding 단계의 입력·산출물. FRD §7.1 `scene.embedding` 컬럼 어휘를 쓴다.

```sql
"embedding" vector(1024),
-- 캡션+대사를 합친 dense 벡터. pgvector 로 색인하고 BM25 순위와 RRF 결합.
```

이 컬럼이 설계 여지를 대부분 좁혀 놓았다.

| 요구 | 스키마의 자리 | 결과 |
| --- | --- | --- |
| 장면 하나당 벡터 하나 | `scene.embedding` | 캡션과 대사를 **합쳐** 하나로 만든다 |
| 차원 | `vector(1024)` | 설정의 `dimension` 과 어긋나면 INSERT 가 실패한다 |
| 텍스트가 없는 장면 | `NULL` 허용 | 벡터를 지어내지 않고 건너뛴다 |
| 어느 장면인가 | `scene_id` | 워커는 `scene_index` 로 말한다 — DB 에 접속하지 않는다 |

**OCR 은 입력이 아니다.** 일감 본문은 "caption·OCR·transcript" 로 적었지만 정본은
FRD §11 결정 표(`docs/frd.md:609`)와 위 컬럼 주석의 **"캡션+대사"** 다. 화면 글자는
`ocr_observation.tokens` 로 BM25 채널에 이미 들어가 있고, 같은 문자열을 dense 채널에도
넣으면 RRF 결합에서 한 신호가 두 번 세어진다.
"""

from dataclasses import dataclass


@dataclass(frozen=True, slots=True)
class SceneText:
    """벡터 하나를 만들 재료. 상류 단계 산출물에서 모은 장면 하나의 텍스트다.

    **누가 만들었는지를 이 단계는 모른다.** 캡션은 `vlm_metadata`(7단계),
    대사는 `scene_transcript_mapping`(6단계)에서 오지만 둘 다 비치명 단계라
    없을 수 있다(`stages.py`). 둘 다 비는 것도 정상 입력이다.
    """

    scene_index: int
    #: VLM 장면 설명. 없으면 빈 문자열이다.
    caption: str
    #: 장면에 겹치는 대사 줄. 시간 순서를 유지한다. 없으면 빈 튜플이다.
    dialogue: tuple[str, ...] = ()


@dataclass(frozen=True, slots=True)
class SceneEmbedding:
    """`scene.embedding` 한 칸에 들어갈 벡터 하나."""

    scene_index: int
    #: 설정의 `dimension` 과 길이가 같다. `normalize` 가 참이면 L2 norm 이 1 이다.
    vector: tuple[float, ...]
    #: 이 벡터를 만든 원문. FRD §7.2 — 나중에 "그때 무엇을 임베딩했나" 를 물을 수 있어야
    #: 한다. 벡터만 남기면 캡션이 교정된 뒤 무엇으로 만든 값인지 알 방법이 없다.
    source_text: str


@dataclass(frozen=True, slots=True)
class TextEmbeddingResult:
    """단계 산출물 전체.

    재현성 식별자는 `(config_version, engine, engine_version, model_version)` 튜플이다.
    `config_version` 은 설정 파일만 해시하므로 가중치가 바뀌면 값이 그대로인데 벡터는
    달라진다 — `model_version` 이 따로 있는 이유다(`ocr/models.py` 의 같은 지적).

    **어휘는 계약 §7 의 재현 튜플과 같다.** `ocr/models.py`·`vlm_metadata/models.py` 도
    같은 자리를 `engine`/`engine_version` 으로 부른다. 여기만 다른 말을 쓰면 배선할 때
    이름을 갈아야 하고, 그때 두 어휘가 로그와 DB 에 섞인다.
    """

    scenes: tuple[SceneEmbedding, ...]
    #: 텍스트가 없어 벡터를 만들지 않은 `scene_index`. **실패가 아니다** —
    #: `scene.embedding` 은 nullable 이고 그 장면은 BM25 채널로만 검색된다.
    #: 이 수가 튀면 상류 VLM·자막이 얼마나 비었는지를 사람이 볼 신호가 된다.
    skipped: tuple[int, ...]
    config_version: str
    #: 벡터를 실제로 만든 구현 이름 (`TextEncoder.name`). 예: `sentence-transformers`
    engine: str
    #: 그 구현과 런타임의 버전 (`TextEncoder.version`).
    engine_version: str
    #: 가중치의 식별자 (`TextEncoder.model_version`). 예: `dragonkue/...-ko@<revision>`
    model_version: str
    #: 만든 벡터의 차원. `scene.embedding vector(N)` 과 같아야 한다.
    dimension: int

    @property
    def embedded_count(self) -> int:
        return len(self.scenes)

    @property
    def skipped_count(self) -> int:
        return len(self.skipped)
