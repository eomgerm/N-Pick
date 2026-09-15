"""질의 임베딩 (S15P21A501-164).

검색어를 dense 검색용 벡터로 만든다. BM25 와 RRF 로 결합할 보조 채널의 **질의 측**
재료이고, 색인 측(`text_embedding/`, S15P21A501-100)이 만든 `scene.embedding` 과 같은
공간에 놓여야 한다.

파이프라인 단계가 아니라 리졸버 쪽 코드다. `stages.py` 의 `STAGES` 에 넣지 않는다 —
`query_normalization/` 과 같은 자리다.

## 색인 측과 공유하는 것

`ai/AGENTS.md` 는 리졸버와 워커가 공유하는 것을 `versioning.py` 와 `korean_tokens.py`
둘로 못박고 새 공유를 늘리지 말라고 한다. 이 모듈은 거기에 셋째를 더한다 —
`text_embedding.encoder` 의 `TextEncoder`·`finalize_vector` 와
`sentence_transformers_backend.shared_encoder` 다.

**그 규칙이 `korean_tokens` 를 공유로 둔 근거와 같은 근거다.** 색인과 질의가 같은 Kiwi
설정을 써야 하고 어긋나면 검색이 0 건이 되듯, 색인과 질의는 같은 모델·차원·정규화를
써야 하고 어긋나면 코사인 유사도가 조용히 무의미해진다. 규칙의 목록이 자기 근거보다
좁은 자리라 어긋난 지점으로 보고했다.

공유하지 **않는** 것은 장면 전용 조립이다 — `compose_text`·`SceneText`·`embed_scenes`
는 "장면 N 개 → 벡터 N 개 + skipped" 모양이고 질의는 "문자열 하나 → 벡터 하나" 다.

## 접두가 다르다

arctic-ko 는 질의에만 `query: ` 를 요구한다(문서측은 접두 없음). 그래서 설정 파일이
따로 있고, 그 대가로 `dimension`·`normalize` 가 두 곳에 적힌다. 두 값이 갈리는 것은
`tests/test_query_embedding.py` 가 막는다.
"""

from npick_worker.query_embedding.config import (
    DEFAULT_CONFIG_PATH,
    QueryEmbeddingConfig,
    get_default_config,
    load_config,
)
from npick_worker.query_embedding.embedder import QueryEmbedding, embed_query

__all__ = [
    "DEFAULT_CONFIG_PATH",
    "QueryEmbedding",
    "QueryEmbeddingConfig",
    "embed_query",
    "get_default_config",
    "load_config",
]
