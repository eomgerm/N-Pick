"""색인 토큰 규칙. 질의 쪽과 문서 쪽이 함께 쓴다.

`docs/architecture/02-container.md:110` 이 "워커가 Kiwi 로 토큰화한 결과를 별도
컬럼에 넣고 `pdb.whitespace` 토크나이저로 색인한다" 로 정했고, 같은 문장이
**색인과 질의가 동일한 Kiwi 설정을 써야 하며 다르면 검색이 0 건이 된다** 고
못 박는다. 그래서 규칙을 여기 하나만 둔다.

토큰을 만드는 곳이 둘이다.

- **질의 쪽** `query_normalization.normalize()` 의 `search_tokens`
  (`search_execution` 이 기록한다)
- **문서 쪽** `ocr` 단계의 `ocr_observation.tokens`

`timecode.py` 를 `scene_detection` 과 `frame_extraction` 이 나눠 쓰는 것과 같은
이유다 — 같은 변환을 두 곳에서 따로 구현하면 언젠가 조용히 갈라지는데, 여기서
갈라지면 증상이 "검색이 0 건" 이라 원인을 찾기가 특히 어렵다.

**설정 파일도 하나여야 한다.** 그래서 이 모듈이 `query_normalization` 의 설정을
읽는다. 파일을 따로 두면 `keep_pos` 하나만 어긋나도 색인과 질의의 토큰 경계가
달라진다. `ai/AGENTS.md` 가 "리졸버와 워커가 공유하는 것은 `versioning.py` 뿐" 으로
적어 두었던 경계는 이 요구 때문에 한 칸 넓어졌고, 그 문서도 함께 고쳤다.

**별칭·불용어·정렬은 여기 없다.** 그것들은 질의 지문(`normalized_query`) 전용이고,
색인 측이 하지 않는 변형을 질의에만 걸면 매칭이 어긋난다.
"""

import unicodedata
from functools import lru_cache
from importlib.metadata import version
from typing import TYPE_CHECKING

from kiwipiepy import Kiwi

if TYPE_CHECKING:  # 순환 임포트를 피한다 — 아래 _config() 주석 참고
    from npick_worker.query_normalization.config import QueryNormalizationConfig

__all__ = [
    "analyze",
    "encode_token",
    "engine_version",
    "index_tokens",
    "prepare",
    "tokenizer_version",
]


# 색인 토큰 인코딩 판. `encode_token` 이 만드는 문자열 형식이 바뀌면 이 값을 올린다.
# 형식은 코드가 정하므로 config 해시로는 안 잡힌다 — 여기에 실어 `tokenizer_version`
# 이 함께 바뀌게 해야 구 색인(옛 형식)과 신 질의가 다른 규칙으로 인식된다.
#   pos1: `형태/품사` (S15P21A501, 품사 다른 동형이의 충돌 해소). 그전 판은 `형태` 만.
_ENCODING_VERSION = "pos1"


@lru_cache(maxsize=1)
def engine_version() -> str:
    """토큰 경계를 정하는 것은 `kiwipiepy` 가 아니라 `kiwipiepy-model` 이다.

    모델만 올라가도 canonical 이 바뀌는데 `kiwipiepy.__version__` 은 그대로다. 둘 다
    담아야 "왜 토큰이 달라졌나" 를 나중에 설명할 수 있다. 토큰 문자열 형식(`encode_token`)
    도 경계의 일부라 `_ENCODING_VERSION` 을 함께 싣는다.
    """
    return f"kiwi{version('kiwipiepy')}:model{version('kiwipiepy_model')}:enc{_ENCODING_VERSION}"


def _config(config: "QueryNormalizationConfig | None") -> "QueryNormalizationConfig":
    """기본 설정을 늦게 읽는다.

    모듈 최상단에서 `query_normalization.config` 를 임포트하면 순환이 된다 —
    그 패키지의 `__init__` 이 `normalizer` 를 끌어오고 `normalizer` 가 이 모듈을
    쓴다. 함수 안에서 임포트하면 그 시점에는 양쪽이 이미 초기화돼 있다.
    """
    if config is not None:
        return config
    from npick_worker.query_normalization.config import get_default_config

    return get_default_config()


def tokenizer_version(config: "QueryNormalizationConfig | None" = None) -> str:
    """토큰 경계를 결정하는 모든 것의 식별자 — 설정 해시와 엔진 버전.

    질의 쪽은 이 값을 `search_execution.normalization_version` 으로 저장하고, 문서
    쪽은 단계 결과의 재현 튜플에 싣는다. 두 값이 다르면 그 색인과 그 질의는 서로
    다른 규칙으로 만들어진 것이다.
    """
    settings = _config(config)
    return f"{settings.version_id}:{engine_version()}"


@lru_cache(maxsize=4)
def _kiwi(user_words: tuple[str, ...], score: float) -> Kiwi:
    """Kiwi 인스턴스. 초기화가 무거워 사용자 사전 조합마다 하나만 만든다.

    사용자 사전이 다르면 토큰 경계가 달라지므로 인스턴스를 공유할 수 없다.
    """
    kiwi = Kiwi()
    for word in user_words:
        kiwi.add_user_word(word, "NNP", score)
    return kiwi


def prepare(text: str) -> str:
    """분석 전 문자열 정리.

    NFKC 로 통일하는 이유는 전각으로 친 `COVID` 와 반각 `COVID` 가 다른 토큰이 되면
    안 되기 때문이다. OCR 원문에는 이 문제가 질의보다 더 자주 나온다 — 화면 글자에는
    전각 영숫자와 기호가 섞인다.

    **원문 자체를 바꾸지 않는다.** 반환값은 토큰을 만드는 데만 쓰고,
    `ocr_observation.raw_text` 에는 엔진이 읽은 그대로가 들어간다
    (baseline 마이그레이션의 컬럼 주석: "읽은 그대로. 절대 덮어쓰지 않는다").
    """
    return unicodedata.normalize("NFKC", text).casefold().strip()


def analyze(
    text: str, config: "QueryNormalizationConfig | None" = None
) -> tuple[tuple[str, str], ...]:
    """형태소 분석 후 `keep_pos` 로 거른 `(형태, 품사태그)` 목록.

    태그를 함께 돌려주는 이유는 질의 쪽이 그것을 쓰기 때문이다 — 별칭·불용어가
    `("찾", "VV")` 처럼 태그까지 보고 걸린다. 색인 토큰(`index_tokens`)도 태그를
    형태에 붙여 쓴다 — 형태만 담으면 품사가 다른 동형이의(`비`=rain NNG 와
    `비`=비다 VV 어간)가 한 토큰으로 뭉개져 검색이 잘못 매칭한다 (S15P21A501).
    """
    settings = _config(config)
    keep_pos = frozenset(settings.keep_pos)
    tokens = _kiwi(settings.user_words, settings.user_word_score).tokenize(prepare(text))
    return tuple((token.form, token.tag) for token in tokens if token.tag in keep_pos)


def encode_token(form: str, tag: str) -> str:
    """`(형태, 품사)` 를 색인·질의가 공유하는 한 토큰 문자열로 만든다.

    색인 측(`index_tokens`)과 질의 측(`query_normalization.normalizer`)이 각자
    문자열을 만들면 언젠가 한쪽만 바뀌어 매칭이 0 건이 된다. 그 인코딩을 여기 하나로
    둔다 — `keep_pos` 를 한 곳에 두는 것과 같은 이유다.
    """
    return f"{form}/{tag}"


def index_tokens(text: str, config: "QueryNormalizationConfig | None" = None) -> tuple[str, ...]:
    """BM25 색인·질의에 쓰는 토큰. 원 순서를 유지하고 불용어도 남긴다.

    각 토큰은 `형태/품사` 다 (예: `비/NNG`). 품사를 붙이는 것은 형태가 같고 품사가
    다른 동형이의를 가르기 위해서다 — `비`(rain, NNG) 와 `빈 교실`의 `비`(비다, VV
    어간) 는 형태가 같아 형태만 담으면 한 토큰으로 충돌한다. `pdb.whitespace` 색인은
    공백만 자르므로 `/` 는 토큰 안에 남고, `keep_pos` 가 기호(SW·SO)를 이미 걸러
    형태에 `/` 가 들어오지 않는다.

    빈 튜플이 정상적인 결과다. 기호만 있는 OCR 원문(`...`, `▶`)은 내용어가 하나도
    없고, 그건 오류가 아니라 "검색에 걸릴 것이 없는 관측" 이다. 질의 쪽이 같은
    상황을 `ValueError` 로 끊는 것과 다른데, 질의는 사용자가 다시 칠 수 있지만
    관측은 그 프레임의 사실이라 버리거나 거부할 대상이 아니다.
    """
    return tuple(encode_token(form, tag) for form, tag in analyze(text, config))
