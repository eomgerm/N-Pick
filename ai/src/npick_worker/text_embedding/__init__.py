"""FRD §3 F-03 검색 준비 — 텍스트 임베딩 (`stages.py` 9단계 `text_embedding`, 비치명).

장면의 **캡션과 대사를 합쳐** dense 벡터 하나를 만든다. 그 벡터가
`scene.embedding vector(1024)` 한 칸에 들어가 pgvector 로 색인되고 BM25 순위와 RRF 로
결합된다(FRD §11.4, baseline 마이그레이션의 컬럼 주석).

네 가지를 이 모듈이 지킨다.

- **장면 하나에 벡터 하나다.** 캡션용·대사용으로 나누지 않는다. `scene.embedding` 이
  칸 하나이고, pgvector 는 행당 벡터 하나다(S15P21A501-175 가 KURE-v2 를 배제한 이유).
- **텍스트가 없으면 벡터를 만들지 않는다.** 빈 문자열을 임베딩하면 모든 빈 장면이 서로
  최근접이 되어 보조 채널이 오염된다. `scene.embedding` 이 nullable 인 이유이고, 그
  장면은 BM25 채널로만 검색된다.
- **모델을 고르지 않는다.** 가중치 식별자는 `NPICK_AI_EMBEDDING_MODEL` 이 정하고 런타임
  교체는 `TextEncoder` Protocol 이 받는다. S15P21A501-175 의 선정은 잠정이고 Gate B 전까지
  교체 가능해야 한다(일감 제약).
- **경로를 만들지 않는다.** 상류 산출물을 가져오는 일도, 결과를 올리는 일도 하지 않는다.
  이 모듈은 `pipeline_run_id` 도 미디어 루트도 모른다.

**OCR 은 입력이 아니다.** 일감 본문은 "caption·OCR·transcript" 로 적었으나 정본은 FRD
§11.4 의 "캡션과 대사" 다. 화면 글자는 `ocr_observation.tokens` 로 BM25 채널에 이미
들어가 있다(`models.py` 의 표).

**임베딩은 보조 채널이다.** exact term 매칭을 대체하지 못한다(FR-SRH-002). S15P21A501-175
가 그 전제의 측정 증거를 냈다 — 방송일로만 갈리는 사건 질의에서 전 후보가 ndcg@10
0.115~0.125 에 머문다.

이 모듈은 순수 함수만 제공한다. 상류 입력 조립과 pipeline run 배선은 `jobs/` 의 몫이다.
"""

from npick_worker.text_embedding.config import (
    DEFAULT_CONFIG_PATH,
    TextEmbeddingConfig,
    get_default_config,
    load_config,
)
from npick_worker.text_embedding.embedder import compose_text, embed_scenes
from npick_worker.text_embedding.encoder import (
    EmbeddingCallError,
    EmbeddingModelUnavailableError,
    TextEncoder,
)
from npick_worker.text_embedding.models import (
    SceneEmbedding,
    SceneText,
    TextEmbeddingResult,
)
from npick_worker.text_embedding.sentence_transformers_backend import (
    SentenceTransformerEncoder,
    shared_encoder,
)

__all__ = [
    "DEFAULT_CONFIG_PATH",
    "EmbeddingCallError",
    "EmbeddingModelUnavailableError",
    "SceneEmbedding",
    "SceneText",
    "SentenceTransformerEncoder",
    "TextEmbeddingConfig",
    "TextEmbeddingResult",
    "TextEncoder",
    "compose_text",
    "embed_scenes",
    "get_default_config",
    "load_config",
    "shared_encoder",
]
