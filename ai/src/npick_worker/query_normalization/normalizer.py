"""질의 정규화 (FR-QRY-001, FR-QRY-004, FR-QRY-006).

규칙 기반이며 LLM 이 아니다. 같은 입력은 언제나 같은 출력이어야 한다 —
이 성질이 깨지면 `search_execution.query_fingerprint` 가 흔들리고 장면 제외
규칙이 자기가 만들어진 질의에도 안 걸리게 된다.

출력이 둘인 이유:

- `normalized_query` : 지문용. 정렬·불용어 제거까지 공격적으로 적용한다.
- `search_tokens`    : BM25 용. 색인 측과 **같은 규칙만** 적용한다. 여기까지
  뭉개면 색인된 토큰과 어긋나 검색이 0건이 된다
  (`docs/architecture/02-container.md` 의 Kiwi 설정 일치 요구).
"""

from dataclasses import dataclass

from npick_worker.korean_tokens import analyze, prepare, tokenizer_version
from npick_worker.query_normalization.config import (
    QueryNormalizationConfig,
    get_default_config,
)


@dataclass(frozen=True, slots=True)
class NormalizedQuery:
    """정규화 결과. BE 가 이 값으로 지문을 만들고 snapshot 에 기록한다."""

    #: 지문 재료. 정렬·불용어 제거 적용.
    normalized_query: str
    #: BM25 질의 토큰. 원 순서 유지, 불용어 유지.
    search_tokens: tuple[str, ...]
    #: `search_execution.normalization_version` 에 그대로 들어간다.
    normalization_version: str


def normalize(raw_query: str, config: QueryNormalizationConfig | None = None) -> NormalizedQuery:
    """원문 질의를 canonical 형태로 만든다.

    Raises:
        ValueError: 질의가 비었거나 내용어가 하나도 남지 않은 경우.
    """
    settings = config if config is not None else get_default_config()

    # 1. 유니코드 통일. 전각으로 친 'COVID' 와 반각 'COVID' 가 다른 지문을 만들면 안 된다.
    if not prepare(raw_query):
        msg = "질의가 비어 있다"
        raise ValueError(msg)

    # 2. 형태소 분석 후 품사로 거른다. 조사·어미·기호가 여기서 사라진다.
    #    이 두 단계(1·2)는 색인 측과 **같은 코드**여야 한다. 규칙은
    #    `npick_worker.korean_tokens` 하나에 있고 ocr 단계가 같은 것을 쓴다.
    kept = list(analyze(raw_query, settings))

    # 3. 여기서 갈라진다. search_tokens 는 색인 측과 같은 상태로 둔다 — 별칭도
    #    불용어도 적용하지 않는다. 색인 측이 안 하는 변형을 질의에만 걸면 매칭이
    #    어긋난다.
    #
    #    별칭은 **토큰 단위 정확 일치** 다. 문자열 치환으로 하면 '부산시' 규칙이
    #    '부산시청' 의 부분 문자열에 걸려 '부산청' 을 만든다(실측). Kiwi 는
    #    '부산시청' 을 NNP 한 토큰으로 잡으므로 토큰끼리 대조하면 안전하다.
    #    별칭이 불용어 필터보다 **먼저** 온다. 반대면 '영상물'(별칭 -> '영상')은
    #    필터를 통과해 매체어가 지문에 남고 '영상'은 거부된다. 같은 뜻인데 갈린다.
    aliases = settings.aliases
    stopwords = settings.stopword_pairs
    aliased = [(aliases.get(form, form), tag) for form, tag in kept]
    content = [form for form, tag in aliased if (form, tag) not in stopwords]
    # 불용어까지 걷어내고 아무것도 안 남으면 지문이 빈 문자열이 된다. 그런 질의는
    # 어떤 규칙에도 걸리지 않으면서 서로 전부 같은 검색으로 취급되므로 여기서 끊는다.
    if not content:
        msg = "내용어가 없다"
        raise ValueError(msg)
    if settings.sort_tokens:
        content.sort()

    return NormalizedQuery(
        normalized_query=" ".join(content),
        search_tokens=tuple(form for form, _ in kept),
        normalization_version=tokenizer_version(settings),
    )
