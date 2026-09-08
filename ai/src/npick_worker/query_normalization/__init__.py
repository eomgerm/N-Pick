"""질의 정규화기 (S15P21A501-43).

파이프라인 단계가 아니라 리졸버 쪽 코드다. `stages.py` 의 `STAGES` 에 넣지 않는다.
순수 함수로만 두고 HTTP 표면·BE 호출 배선은 하지 않는다 — 그 인터페이스는
`ai/AGENTS.md` 에 따라 S15P21A501-70 에서 합의한다.
"""

from npick_worker.query_normalization.config import (
    QueryNormalizationConfig,
    get_default_config,
    load_config,
)
from npick_worker.query_normalization.normalizer import NormalizedQuery, normalize

__all__ = [
    "NormalizedQuery",
    "QueryNormalizationConfig",
    "get_default_config",
    "load_config",
    "normalize",
]
