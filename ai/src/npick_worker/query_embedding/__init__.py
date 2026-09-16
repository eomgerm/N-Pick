"""질의 임베딩 (S15P21A501-164).

검색어를 dense 검색용 벡터로 만든다. BM25 와 RRF 로 결합할 보조 채널의 **질의 측**
재료이고, 색인 측(`text_embedding/`, S15P21A501-100)이 만든 `scene.embedding` 과 같은
공간에 놓여야 한다.

파이프라인 단계가 아니라 리졸버 쪽 코드다. `stages.py` 의 `STAGES` 에 넣지 않는다 —
`query_normalization/` 과 같은 자리다.

**이 모듈은 자기 설정 파일을 갖지 않는다.** 차원과 정규화는 색인 정본
(`config/text_embedding.v1.toml`)이 정하고 양쪽이 그것을 읽는다. 질의측 접두만 다르고
그것은 `NPICK_AI_EMBEDDING_QUERY_PREFIX` 다.

근거와 경계는 [../../../docs/query-embedding.md](../../../docs/query-embedding.md) 가 정본이다.
"""

from npick_worker.query_embedding.embedder import QueryEmbedding, embed_query

__all__ = ["QueryEmbedding", "embed_query"]
