"""LLM 호출 경계.

`ai/AGENTS.md`: "외부 모델 호출은 FRD §2.1 의 adapter 경계 뒤에 둔다. 호출부에
provider SDK 를 직접 노출하지 않는다."

`scene_detection/detector.py` 의 `SceneDetector` 와 같은 형태다. 이 Protocol 만
만족시키면 Ollama 든 승인된 GMS 든 갈아끼울 수 있고, 상위 코드는 바뀌지 않는다
(FRD §2.1 — "자체 호스팅 구현으로 교체 가능해야 한다").
"""

from typing import Protocol


class ResolverCallError(RuntimeError):
    """LLM 호출 자체가 실패했다 — timeout·network·rate limit.

    provider 예외를 이 타입으로 바꿔서 내보낸다. httpx·SDK 예외가 호출부로 새면
    adapter 경계가 없는 것과 같다. 호출부는 이걸 받아 raw query BM25 fallback 으로
    내려간다(`FR-QRY-022`).
    """

    def __init__(self, message: str, *, category: str) -> None:
        super().__init__(message)
        #: FRD §12 의 오류 코드. `RESOLVER_TIMEOUT` | `RESOLVER_RATE_LIMITED` 등.
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
        """`query_resolution_snapshot.model_version` 에 실릴 값.

        모델을 바꾸면 같은 프롬프트에서도 해석이 달라진다. `prompt_version` 만으로는
        재현이 보장되지 않아 이 값이 따로 필요하다.
        """
        ...

    def complete(self, system_prompt: str, user_prompt: str) -> str: ...
