"""검색어를 dense 벡터로 바꾼다. 이 모듈의 본체다."""

from dataclasses import dataclass

from npick_worker.settings import get_settings
from npick_worker.text_embedding import TextEmbeddingConfig
from npick_worker.text_embedding import get_default_config as vector_space
from npick_worker.text_embedding.encoder import TextEncoder, finalize_vector


@dataclass(frozen=True, slots=True)
class QueryEmbedding:
    """질의 하나의 벡터와 그 재현 정보."""

    #: 벡터 공간 설정의 `dimension` 과 길이가 같다. `normalize` 가 참이면 L2 norm 이 1 이다.
    vector: tuple[float, ...]
    #: 실제로 인코더에 넣은 문자열. **접두를 포함한다.** 벡터만 남기면 나중에 "그때
    #: 무엇을 임베딩했나" 를 물을 수 없다 — `SceneEmbedding.source_text` 와 같은 판단이다.
    source_text: str
    #: 가중치 식별자 (`TextEncoder.model_version`). 색인 측 값과 같아야 유사도가 성립한다.
    model_version: str


def embed_query(
    raw_query: str,
    *,
    encoder: TextEncoder,
    prefix: str | None = None,
    config: TextEmbeddingConfig | None = None,
) -> QueryEmbedding:
    """검색어 하나를 벡터로 만든다.

    **원문을 임베딩한다. 정규화 질의가 아니다.** 근거 셋.

    1. 색인 측은 캡션과 대사라는 **자연어 문장**을 넣는다(`text_embedding.compose_text`).
       `normalized_query` 는 형태소만 남기고 불용어를 걷어낸 뒤 **정렬**까지 한 토큰
       나열이라 문장이 아니다. 질의만 그 모양으로 넣으면 두 입력의 분포가 어긋난다.
    2. S15P21A501-175 의 비교표가 `"query: " + 원질의` 로 측정한 값이다
       (`eval/text_embedding/measure.py`). 운영에서 다른 것을 넣으면 그 수치가 이
       파이프라인에서 재현된다고 말할 수 없다.
    3. `search_tokens` 는 BM25 전용이다 — 색인 토큰과 같은 규칙만 적용한 값이고
       dense 채널과는 쓰임이 다르다.

    같은 이유로 `query_api` 가 해석에도 원문을 넘긴다.

    Args:
        raw_query: 사용자가 친 원문. 앞뒤 공백은 벗긴다.
        encoder: 벡터를 만들 인코더. **색인 측과 같은 인스턴스여야 한다** —
            호출부가 `shared_encoder()` 로 받는다.
        prefix: 질의측 접두. 없으면 `NPICK_AI_EMBEDDING_QUERY_PREFIX`.
        config: 벡터 공간(`dimension`·`normalize`). 없으면 **색인 측 정본**이다.
            질의가 자기 값을 갖지 않는 이유가 이것이다 — 두 곳에 적히면 갈릴 수 있고,
            갈리면 코사인 유사도가 조용히 무의미해진다.

    Returns:
        벡터와 재현 정보.

    Raises:
        ValueError: 질의가 비었거나, 차원이 어긋났거나, 못 쓰는 벡터(NaN·inf·0)가 나왔다.
            앞엣것은 입력 문제고 뒤엣것은 설정·모델이 잘못됐다는 신호다.
        EmbeddingCallError: 모델 호출이 실패했다(일시).
        EmbeddingModelUnavailableError: 가중치를 준비하지 못했다(일시).
    """
    space = config if config is not None else vector_space()
    marker = prefix if prefix is not None else get_settings().embedding_query_prefix

    # 공백만 있는 질의를 임베딩하면 의미 없는 벡터가 검색에 쓰인다. 색인 측이 빈 장면을
    # `skipped` 로 두는 것과 같은 판단인데, 질의에는 "건너뛴다" 가 없어 오류가 된다.
    stripped = raw_query.strip()
    if not stripped:
        msg = "질의가 비어 있다"
        raise ValueError(msg)

    text = marker + stripped
    vectors = encoder.encode([text])
    if len(vectors) != 1:
        msg = f"인코더가 질의 하나에 벡터 {len(vectors)} 개를 돌려줬다"
        raise ValueError(msg)

    return QueryEmbedding(
        vector=finalize_vector(vectors[0], dimension=space.dimension, normalize=space.normalize),
        source_text=text,
        model_version=encoder.model_version,
    )
