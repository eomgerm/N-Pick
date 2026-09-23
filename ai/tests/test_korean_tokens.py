"""검색 토큰 규칙 (`korean_tokens.index_tokens`).

색인·질의가 공유하는 토큰을 검증한다. 여기서 갈라지면 증상이 "검색이 잘못
매칭" 이라 원인을 찾기 특히 어렵다 — 그래서 규칙을 회귀로 고정한다.
"""

import pytest

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


@pytest.mark.xfail(
    strict=True,
    reason="S15P21A501-293 ③ — 사전 밖 사람 이름의 품사가 문맥에 따라 뒤집힌다. "
    "고치려면 인코딩 판을 올려 저장 토큰 전체를 재색인해야 해서 분리 티켓이 가져간다. "
    "근거와 실측은 ai/docs/proper-noun-search.md §3.",
)
def test_person_name_query_shares_a_token_with_the_same_name_in_a_caption() -> None:
    """사람 이름 검색어가 그 이름이 나온 자막과 토큰을 하나도 공유하지 못한다.

    Kiwi 는 사전에 없는 이름의 **형태는 같게, 품사는 문맥마다 다르게** 준다.

        "김예은"                       -> ('김예은', 'NNG')   사용자가 치는 검색어
        "오늘 김예은 씨가 인터뷰를 했다"  -> ('김예은', 'NNP')   자막에 색인되는 형태

    토큰이 형태뿐이던 시절에는 둘이 같았다. `encode_token` 이 품사를 실으면서
    `김예은/nng` 와 `김예은/nnp` 가 되었고 `term_set` 은 정확 일치라 영영 안 맞는다.
    같은 흔한 성 30 곱하기 흔한 이름 20 스윕에서 매칭이 88.2% -> 34.8% 로 떨어졌다.

    바로 위 `test_rain_query_does_not_share_token_with_empty_classroom_caption` 이
    함께 서 있어야 한다. 품사를 통째로 떼어 이 테스트만 통과시키면 그쪽이 깨진다 —
    고칠 방향은 명사류(NNG·NNP)끼리만 합치는 것이다.
    """
    query = set(korean_tokens.index_tokens("김예은"))
    caption = set(korean_tokens.index_tokens("오늘 김예은 씨가 인터뷰를 했다"))

    overlap = query & caption
    assert overlap, (
        f"이름 검색어와 자막이 토큰을 공유하지 않는다: {sorted(query)} vs {sorted(caption)}"
    )


@pytest.mark.xfail(
    strict=True,
    reason="S15P21A501-293 ③ — 경계 분절로 음절이 사라지는 쪽. 위 테스트와 같은 티켓이지만 "
    "명사류 통합만으로는 해결되지 않는다. ai/docs/proper-noun-search.md §3.",
)
def test_person_name_query_does_not_drop_a_syllable() -> None:
    """`배현진` 검색어에서 가운데 음절이 사라지고 흔한 낱말 둘만 남는다.

        "배현진" 단독      -> 배/NNP + 현/MM + 진/NNG
        "오늘 배현진 씨를"  -> 배현진/NNP

    MM(관형사)은 `keep_pos` 에 없어 '현' 이 걸러진다. 남는 토큰 `배/nng`·`진/nng` 는
    배(과일)·진(陣)이라, 0 건이 되는 데서 그치지 않고 **엉뚱한 장면을 맞다고 한다.**
    """
    tokens = korean_tokens.index_tokens("배현진")

    forms = {token.rsplit("/", 1)[0] for token in tokens}
    assert "배현진" in forms, f"이름이 쪼개져 음절을 잃었다: {tokens}"
