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


def test_index_tokens_are_lowercase_because_the_bm25_index_lowercases_terms() -> None:
    """토큰에 대문자가 있으면 안 된다 — BM25 인덱스가 텀을 소문자로 색인하기 때문이다.

    `ix_scene_bm25` 의 `pdb.whitespace` 토크나이저는 색인할 때 텀을 소문자로 내린다.
    반면 질의는 `paradedb.term_set` 으로 텀을 **그대로** 대조한다. 그래서 토큰이
    `비/NNG` 면 색인에는 `비/nng` 로 들어가고 질의는 `비/NNG` 로 나가 영영 안 맞는다.

    운영 실측 (2026-09-22, 백필 직후):
        term_set('caption_tokens', ['비/NNG','내리/VV','길/NNG'])  ->   0 건
        term_set('caption_tokens', ['비/nng','내리/vv','길/nng'])  ->  40 건

    형태는 `prepare` 가 이미 소문자로 내려 준다(`KBS` -> `kbs/SL`). 남은 것은 품사
    태그뿐이지만, 토큰 전체를 내려 두면 형태 쪽 규칙이 바뀌어도 이 불변식이 깨지지 않는다.
    """
    tokens = korean_tokens.index_tokens("KBS 뉴스에서 비 내리는 길을 보도했다")

    assert tokens
    uppercase = [token for token in tokens if token != token.lower()]
    assert not uppercase, f"BM25 색인은 소문자인데 토큰에 대문자가 있다: {uppercase}"


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
