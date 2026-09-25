"""질의 BM25 토큰의 같은 뜻 묶음 (`search_token_synonyms`, S15P21A501-320).

사용자는 「빨간 옷」(`빨갛/va`)이라 치고 VLM 캡션은 「빨간색 옷」(`빨간색/nng`)이라 쓴다.
묶음은 그 둘을 질의 쪽에서만 잇는다. 지키는 것 둘:

- `search_tokens` 에 **더하기만** 한다. 원 토큰은 색인 측과 같은 그대로다.
- `normalized_query`(지문)는 그대로다. 장면 제외 규칙이 지문에 exact 로 걸리므로, 여기에
  같은 뜻 토큰을 섞으면 규칙이 걸리는 질의가 넓어진다.
"""

from pathlib import Path

import pytest

from npick_worker import korean_tokens
from npick_worker.query_normalization import get_default_config, load_config, normalize
from npick_worker.query_normalization.config import DEFAULT_CONFIG_PATH

_V1 = DEFAULT_CONFIG_PATH.with_name("query_normalization.v1.toml")


@pytest.mark.parametrize(
    ("query", "added"),
    [
        ("빨간 옷 입은 사람", {"빨간색/nng", "붉/va"}),
        ("빨간색 옷", {"빨갛/va", "붉/va"}),
        ("파란 하늘", {"파란색/nng"}),
        ("노란 우산", {"노란색/nng"}),
        ("하얀 눈", {"흰색/nng", "하얀색/nng", "희/va"}),
        ("흰 눈", {"흰색/nng", "하얀색/nng", "하얗/va"}),
        ("검은 옷", {"검은색/nng", "검정/nng", "검정색/nng", "까맣/va"}),
        ("녹색 잎", {"초록색/nng", "초록/nng"}),
    ],
)
def test_color_adjective_and_noun_reach_each_other(query: str, added: set[str]) -> None:
    tokens = normalize(query).search_tokens
    original = korean_tokens.index_tokens(query)

    assert tokens[: len(original)] == original, "원 토큰이 그대로 앞에 있어야 한다"
    assert set(tokens[len(original) :]) == added
    assert len(tokens) == len(set(tokens)), "한 뜻을 두 번 세면 안 된다"


def test_fingerprint_is_the_same_with_or_without_synonyms() -> None:
    """묶음은 지문을 바꾸지 않는다 — 묶음이 없는 v1 설정과 `normalized_query` 가 같다."""
    v1 = load_config(_V1)
    for query in ["빨간 옷 입은 사람", "파란 하늘", "하얀 눈", "검은 옷과 검정색 모자"]:
        assert normalize(query).normalized_query == normalize(query, v1).normalized_query


def test_query_without_synonyms_is_untouched() -> None:
    query = "비 내리는 길"
    assert normalize(query).search_tokens == korean_tokens.index_tokens(query)


def test_synonyms_change_the_version_but_v1_keeps_its_old_hash() -> None:
    """묶음이 생기면 버전이 바뀐다. v1 파일은 키가 생기기 전의 해시를 그대로 낸다."""
    assert load_config(_V1).version_id == "query-norm/v1:b0d96c0c"
    assert get_default_config().version_id != load_config(_V1).version_id
    assert get_default_config().version_id.startswith("query-norm/v2:")


_BODY = """
schema = "query-norm/v2"
keep_pos = ["NNG", "VA"]
sort_tokens = true
stopwords = []
search_token_synonyms = {groups}

[aliases]
"""


def test_version_ignores_order_inside_a_group(tmp_path: Path) -> None:
    a = tmp_path / "a.toml"
    b = tmp_path / "b.toml"
    a.write_text(_BODY.format(groups='[["빨갛/VA", "빨간색/NNG"]]'), encoding="utf-8")
    b.write_text(_BODY.format(groups='[["빨간색/NNG", "빨갛/VA"]]'), encoding="utf-8")
    assert load_config(a).version_id == load_config(b).version_id


@pytest.mark.parametrize(
    ("groups", "message"),
    [
        ('[["빨갛/VA"]]', "둘 이상"),
        ('[["빨갛/VA", "빨갛/VA"]]', "둘 이상"),
        ('[["빨갛/VA", "빨간색"]]', "형태/품사태그"),
        ('[["빨갛/VA", "빨간색/XR"]]', "keep_pos 밖"),
        ('[["빨갛/VA", "빨간색/NNG"], ["빨간색/NNG", "붉/VA"]]', "두 묶음"),
    ],
)
def test_bad_groups_fail_at_load_time(tmp_path: Path, groups: str, message: str) -> None:
    path = tmp_path / "bad.toml"
    path.write_text(_BODY.format(groups=groups), encoding="utf-8")
    with pytest.raises(ValueError, match=message):
        load_config(path)
