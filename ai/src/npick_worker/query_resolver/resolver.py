"""LLM 호출을 감싸는 모듈 어댑터 경계.

QueryResolver Protocol 뒤에 provider 구현을 두어 상위 코드에 SDK를 노출하지 않는다.
FRD F-05의 질의 해석과 §6.2의 실패 처리를 지원하는 구현 계약이다.
"""

from typing import Protocol


class ResolverCallError(RuntimeError):
    """LLM 호출 자체가 실패했다 — timeout·network·rate limit.

    provider 예외를 이 타입으로 바꿔서 내보낸다. httpx·SDK 예외가 호출부로 새면
    adapter 경계가 없는 것과 같다. 호출부는 이걸 받아 raw query BM25 fallback 으로
    내려간다(`FRD §6.2`).
    """

    def __init__(self, message: str, *, category: str) -> None:
        super().__init__(message)
        #: 모듈 오류 코드. `RESOLVER_TIMEOUT` | `RESOLVER_RATE_LIMITED` 등.
        self.category = category


class QueryResolver(Protocol):
    """프롬프트를 받아 모델의 원시 텍스트를 돌려준다.

    JSON 파싱과 검증은 여기서 하지 않는다. 구현마다 파싱 규칙이 달라지면
    `RESOLVER_SCHEMA_INVALID` 판정이 구현에 따라 흔들린다.
    """

    @property
    def name(self) -> str: ...

    @property
    def version(self) -> str:
        """반환 메타데이터 model_version에 실릴 값.

        모델을 바꾸면 같은 프롬프트에서도 해석이 달라진다. `prompt_version` 만으로는
        재현이 보장되지 않아 이 값이 따로 필요하다.
        """
        ...

    def complete(self, system_prompt: str, user_prompt: str) -> str: ...
