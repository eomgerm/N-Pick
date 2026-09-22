"""검색 토큰 규칙 (`korean_tokens.index_tokens`).

색인·질의가 공유하는 토큰을 검증한다. 여기서 갈라지면 증상이 "검색이 잘못
매칭" 이라 원인을 찾기 특히 어렵다 — 그래서 규칙을 회귀로 고정한다.
"""

from npick_worker import korean_tokens
from npick_worker.query_normalization.normalizer import normalize


def test_query_search_tokens_use_same_encoding_as_index_tokens() -> None:
    """질의측 `search_tokens` 는 색인측 `index_tokens` 와 글자까지 같아야 한다.

    BM25 `term_set` 은 정확 일치라, 두 경로가 조금이라도 다르게 인코딩하면 매칭이
    0 건이 된다. 품사를 색인에만 붙이고 질의에 안 붙이면 바로 그 사고가 난다.
    """
    query = "비 내리는 길"
    assert normalize(query).search_tokens == korean_tokens.index_tokens(query)


def test_rain_query_does_not_share_token_with_empty_classroom_caption() -> None:
    """`비`(rain, NNG) 검색이 "빈 교실"(비다, VV) 캡션에 걸리면 안 된다.

    형태 `비` 가 명사(비=rain)와 동사 어간(빈=비다)에서 똑같이 나오므로, 토큰이
    형태만 담으면 두 장면이 같은 토큰으로 충돌해 BM25 가 빈 교실 장면을 비 검색
    결과로 잘못 매칭한다. 품사를 함께 담아야 갈린다.
    """
    rain_query = set(korean_tokens.index_tokens("비"))
    empty_caption = set(korean_tokens.index_tokens("빈 교실과 시험실"))

    overlap = rain_query & empty_caption
    assert not overlap, f"품사 다른 동형이의가 같은 토큰으로 충돌한다: {overlap}"
