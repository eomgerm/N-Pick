"""질의 임베딩 (S15P21A501-164).

색인 측(`test_text_embedding.py`)과 같은 방침이다 — 실제 가중치는 `smoke` 에서만 돌리고
여기서는 가짜 인코더를 쓴다. 이 모듈에서 규약인 것은 "어떤 벡터가 나오는가" 가 아니라
**무엇을 인코더에 넣고 무엇을 기록하는가**다.

가장 중요한 테스트 둘.

- `test_encoder_receives_prefix_and_raw_query` — 접두 `query: ` 와 **원문**이 들어간다.
  정규화 질의를 넣으면 색인 측(캡션+대사라는 자연어 문장)과 입력 분포가 어긋나고,
  S15P21A501-175 의 비교표가 `"query: " + 원질의` 로 측정된 값이라 그 수치도 보장되지 않는다.
- `test_query_and_scene_configs_agree_on_vector_space` — 두 설정 파일이 같은 벡터 공간을
  말하는지. 차원이나 정규화가 갈리면 코사인 유사도가 조용히 무의미해진다. 색인 측과 질의
  측이 설정 파일을 따로 갖는 대가를 이 테스트 하나로 치른다.
"""

import hashlib
import math
from collections.abc import Sequence
from pathlib import Path

import pytest

from npick_worker.query_embedding import (
    QueryEmbeddingConfig,
    embed_query,
    get_default_config,
    load_config,
)
from npick_worker.query_embedding.config import DEFAULT_CONFIG_PATH
from npick_worker.query_normalization import normalize
from npick_worker.text_embedding import get_default_config as get_scene_config
from npick_worker.text_embedding.encoder import (
    EmbeddingCallError,
    EmbeddingModelUnavailableError,
)

RAW_QUERY = "작년 여름에 부산 침수됐던 장면 좀 찾아줘"


class _FakeEncoder:
    """`TextEncoder` 구현. 텍스트 내용에 따라 달라지되 결정적인 벡터를 만든다.

    **L2 norm 이 1 이 아닌 값을 돌려준다.** 정규화를 호출부가 하는지 확인하려면 인코더가
    이미 정규화된 값을 주면 안 된다.
    """

    name = "fake"
    version = "0"

    def __init__(
        self,
        *,
        dimension: int = 4,
        model_version: str = "fake/model@0",
        vector: Sequence[float] | None = None,
        failure: Exception | None = None,
    ) -> None:
        self._dimension = dimension
        self.model_version = model_version
        self._fixed = tuple(vector) if vector is not None else None
        self._failure = failure
        self.calls: list[tuple[str, ...]] = []

    def encode(self, texts: Sequence[str]) -> tuple[tuple[float, ...], ...]:
        self.calls.append(tuple(texts))
        if self._failure is not None:
            raise self._failure
        return tuple(self._vector_for(text) for text in texts)

    def _vector_for(self, text: str) -> tuple[float, ...]:
        if self._fixed is not None:
            return self._fixed
        digest = hashlib.sha256(text.encode("utf-8")).digest()
        return tuple(float(digest[index % len(digest)] + 1) for index in range(self._dimension))


def _config(**overrides: object) -> QueryEmbeddingConfig:
    """테스트용 설정. 차원을 작게 줄여 조립 규칙만 본다."""
    values: dict[str, object] = {
        "schema": "query-embedding/v1",
        "dimension": 4,
        "normalize": True,
        "query_prefix": "query: ",
    }
    values.update(overrides)
    return QueryEmbeddingConfig.model_validate(values)


# ── 무엇을 인코더에 넣는가 ──────────────────────────────────────────────


def test_encoder_receives_prefix_and_raw_query() -> None:
    """접두 + 원문 하나. 문장 한 개만 인코더에 간다."""
    encoder = _FakeEncoder()

    result = embed_query(RAW_QUERY, encoder=encoder, config=_config())

    assert encoder.calls == [("query: " + RAW_QUERY,)]
    assert result.source_text == "query: " + RAW_QUERY


def test_embedded_text_is_not_the_normalized_query() -> None:
    """정규화 질의는 정렬·불용어 제거된 토큰 나열이라 문장이 아니다.

    색인 측은 캡션+대사라는 자연어 문장을 넣는다(`text_embedding.compose_text`).
    질의만 토큰 나열로 넣으면 두 분포가 어긋난다. `search_tokens` 는 BM25 전용이다.
    """
    encoder = _FakeEncoder()

    result = embed_query(RAW_QUERY, encoder=encoder, config=_config())

    normalized = normalize(RAW_QUERY)
    assert normalized.normalized_query not in result.source_text
    assert RAW_QUERY in result.source_text


def test_surrounding_whitespace_does_not_change_the_vector() -> None:
    """`" 부산 "` 과 `"부산"` 이 다른 벡터가 되면 같은 질의가 다른 검색이 된다."""
    config = _config()

    padded = embed_query(f"  {RAW_QUERY}  ", encoder=_FakeEncoder(), config=config)
    bare = embed_query(RAW_QUERY, encoder=_FakeEncoder(), config=config)

    assert padded.vector == bare.vector


def test_empty_prefix_sends_the_query_unchanged() -> None:
    """접두는 모델이 요구할 때만 붙는다. 코드에 박혀 있지 않다."""
    encoder = _FakeEncoder()

    embed_query(RAW_QUERY, encoder=encoder, config=_config(query_prefix=""))

    assert encoder.calls == [(RAW_QUERY,)]


@pytest.mark.parametrize("raw", ["", "   ", "\t\n"])
def test_blank_query_is_rejected_before_the_model(raw: str) -> None:
    """공백을 임베딩하면 의미 없는 벡터가 검색에 쓰인다. 모델을 부르지도 않는다."""
    encoder = _FakeEncoder()

    with pytest.raises(ValueError, match="질의"):
        embed_query(raw, encoder=encoder, config=_config())

    assert encoder.calls == []


# ── 무엇을 기록하는가 ───────────────────────────────────────────────────


def test_model_version_comes_from_the_encoder() -> None:
    """가중치가 바뀌면 벡터가 달라진다. 호출부가 그 사실을 알 수 있어야 한다."""
    encoder = _FakeEncoder(model_version="dragonkue/arctic@abc123")

    result = embed_query(RAW_QUERY, encoder=encoder, config=_config())

    assert result.model_version == "dragonkue/arctic@abc123"


# ── 벡터 위생 (색인 측과 같은 규칙) ─────────────────────────────────────


def test_vector_is_l2_normalized() -> None:
    """pgvector 코사인 검색의 전제. 색인 측과 같은 크기여야 한다."""
    result = embed_query(RAW_QUERY, encoder=_FakeEncoder(), config=_config())

    assert math.isclose(math.sqrt(sum(v * v for v in result.vector)), 1.0, rel_tol=1e-9)


def test_normalize_off_keeps_the_raw_magnitude() -> None:
    result = embed_query(
        RAW_QUERY,
        encoder=_FakeEncoder(vector=[3.0, 4.0, 0.0, 0.0]),
        config=_config(normalize=False),
    )

    assert result.vector == (3.0, 4.0, 0.0, 0.0)


def test_dimension_mismatch_is_rejected() -> None:
    """설정과 다른 차원이 나오면 색인 측 벡터와 비교 자체가 불가능하다."""
    encoder = _FakeEncoder(dimension=8)

    with pytest.raises(ValueError, match="차원"):
        embed_query(RAW_QUERY, encoder=encoder, config=_config(dimension=4))


def test_zero_vector_is_rejected() -> None:
    """코사인 거리가 정의되지 않는다. 검색이 조용히 빈 결과를 낸다."""
    encoder = _FakeEncoder(vector=[0.0, 0.0, 0.0, 0.0])

    with pytest.raises(ValueError, match="0 벡터"):
        embed_query(RAW_QUERY, encoder=encoder, config=_config())


@pytest.mark.parametrize("bad", [math.nan, math.inf])
def test_non_finite_component_is_rejected(bad: float) -> None:
    """fp16 경로에서 나온다. norm 도 NaN 이 되어 0 벡터 검사만으로는 빠져나간다."""
    encoder = _FakeEncoder(vector=[bad, 1.0, 1.0, 1.0])

    with pytest.raises(ValueError, match="유한"):
        embed_query(RAW_QUERY, encoder=encoder, config=_config())


# ── 실패는 감싸지 않는다 ────────────────────────────────────────────────


@pytest.mark.parametrize(
    "failure",
    [EmbeddingCallError("호출 실패"), EmbeddingModelUnavailableError("가중치 없음")],
)
def test_encoder_failures_propagate_unchanged(failure: Exception) -> None:
    """분류는 호출부(`query_api`)가 한다. 여기서 ValueError 로 바꾸면 설정 오류와 섞인다."""
    encoder = _FakeEncoder(failure=failure)

    with pytest.raises(type(failure)):
        embed_query(RAW_QUERY, encoder=encoder, config=_config())


# ── 설정 ────────────────────────────────────────────────────────────────


def test_query_and_scene_configs_agree_on_vector_space() -> None:
    """**설정 파일을 둘로 나눈 대가를 치르는 자리다.**

    질의측 접두는 색인 결과를 바꾸지 않으므로 `text_embedding.v1.toml` 에 넣을 수 없다 —
    넣으면 `config_version` 이 움직여 `stageVersion` 이 달라지고 계약 §7 의 버전 불일치로
    전 클립이 재처리 대상이 된다. 그래서 파일이 둘인데, 그러면 `dimension` 이 두 곳에
    적힌다. 그 둘이 갈리는 것을 막는 것이 이 테스트다.
    """
    query = get_default_config()
    scene = get_scene_config()

    assert query.dimension == scene.dimension
    assert query.normalize == scene.normalize


def test_default_config_is_the_packaged_file() -> None:
    assert get_default_config() == load_config(DEFAULT_CONFIG_PATH)


def test_version_id_moves_with_the_values() -> None:
    """접두가 바뀌면 다른 벡터가 나온다. 버전이 그대로면 기록이 거짓이 된다."""
    assert _config().version_id != _config(query_prefix="passage: ").version_id


def test_unknown_key_is_rejected() -> None:
    """오타가 조용히 무시되면 version_id 는 바뀌는데 동작은 그대로다."""
    with pytest.raises(ValueError, match=r"query_prefx|[Ee]xtra"):
        QueryEmbeddingConfig.model_validate(
            {
                "schema": "query-embedding/v1",
                "dimension": 4,
                "normalize": True,
                "query_prefix": "query: ",
                "query_prefx": "오타",
            }
        )


def test_schema_must_match_the_file_version(tmp_path: Path) -> None:
    """`v2.toml` 에 `v1` schema 가 든 파일은 버전 기록을 거짓으로 만든다."""
    target = tmp_path / "query_embedding.v2.toml"
    target.write_text(
        "\n".join(
            [
                'schema = "query-embedding/v1"',
                "dimension = 4",
                "normalize = true",
                'query_prefix = "query: "',
            ]
        ),
        encoding="utf-8",
    )

    with pytest.raises(ValueError, match="query-embedding/v2"):
        load_config(target)
