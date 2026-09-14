"""임베딩 모델 호출의 경계. **모델을 갈아 끼우는 자리다.**

`ai/AGENTS.md` 가 "외부 모델 호출은 모듈 Protocol 어댑터 경계 뒤에 두고 호출부에
provider SDK 를 직접 노출하지 않는다" 로 정한다. `ocr/engine.py` 의 `OcrEngine`,
`vlm_metadata/client.py` 의 `VlmClient` 와 같은 자리다.

이 경계가 실제로 사는 것 둘.

- **선정이 동결되지 않았다.** S15P21A501-175 는 `dragonkue/snowflake-arctic-embed-l-v2.0-ko`
  를 **잠정** 선정했고 리뷰어 승인 전이다. 그 티켓의 재평가 조건(장면 단위 골드셋에서
  PIXIE 가 유의하게 앞서면 교체)이 걸리면 바뀌는 것은 환경 변수 하나이거나 backend 파일
  하나이지 `embedder.py` 도 `jobs/` 도 아니다.
- **교체 층이 둘이다.** 같은 sentence-transformers 런타임에서 가중치만 바꾸는 것은
  `NPICK_AI_EMBEDDING_MODEL` 이고(arctic↔PIXIE↔KURE 가 전부 여기), 런타임 자체를 바꾸는
  것은 이 Protocol 의 다른 구현이다. 앞엣것이 흔하고 뒤엣것이 드물다.
"""

from collections.abc import Sequence
from typing import Protocol, runtime_checkable


class EmbeddingCallError(Exception):
    """모델을 부르지 못했거나 응답을 받지 못했다.

    **출력 내용의 문제가 아니다.** 차원이 어긋난 것은 `embedder.py` 의 `ValueError` 이고
    그건 설정과 가중치가 안 맞는 영구 상태다. 이쪽은 런타임 오류라 다음 시도에서
    성공할 수 있다 — 계약 §9.2 에서 일시로 분류된다.
    """


class EmbeddingModelUnavailableError(Exception):
    """가중치를 준비하지 못했다. 구현은 있는데 모델이 없는 상태다.

    `NO_ADAPTER`(영구)와 갈라야 한다 — 계약 §9.2 의 `MODEL_UNAVAILABLE` 은 일시다.
    캐시 볼륨이 안 붙었거나 내려받기가 실패한 것이라 다른 파드에서 성공할 수 있다.
    `NPICK_AI_EMBEDDING_MODEL` 이 비어 있는 경우도 여기다(`vlm_model` 과 같은 판단).

    **그 번역은 아직 배선되지 않았다.** `jobs/registry.py` 에 이 단계의 핸들러가 없어
    계약 §9.2 의 `MODEL_UNAVAILABLE` 로 옮겨 주는 자리가 없다(ocr 은 `_run_ocr`, vlm 은
    `_run_vlm_metadata` 가 그 일을 한다). 배선 티켓이 그 번역을 함께 넣는다.
    """


@runtime_checkable
class TextEncoder(Protocol):
    """문장 여러 개를 벡터 여러 개로 바꾼다."""

    @property
    def name(self) -> str:
        """재현 튜플에 들어가는 어댑터 이름. 예: `sentence-transformers`"""
        ...

    @property
    def version(self) -> str:
        """어댑터와 런타임의 버전. 가중치 버전은 `model_version` 이 따로 말한다."""
        ...

    @property
    def model_version(self) -> str:
        """가중치의 식별자. 예: `dragonkue/snowflake-arctic-embed-l-v2.0-ko@<revision>`

        런타임 버전만으로는 부족하다. 같은 sentence-transformers 라도 가중치가 바뀌면
        같은 문장에서 다른 벡터가 나온다 — `VlmClient.model_version` 과 같은 이유다.
        """
        ...

    def encode(self, texts: Sequence[str]) -> tuple[tuple[float, ...], ...]:
        """입력 순서대로 벡터를 돌려준다. 길이는 입력과 같다.

        **정규화하지 않는다.** L2 정규화는 `embedder.py` 한 곳에서 한다 — 어댑터마다
        기본값이 달라(sentence-transformers 는 꺼져 있고 어떤 런타임은 켜져 있다)
        여기서 각자 하면 같은 설정에서 다른 크기의 벡터가 나온다.

        **numpy 배열을 돌려주지 않는다.** 경계 밖으로 배열 타입이 새면 호출부가
        어댑터의 런타임을 알게 된다.

        **차원을 선언하지 않는다.** 호출부는 실제로 나온 벡터의 길이를 설정과 대조한다
        (`embedder._finalize`). 모델이 선언한 값은 실제와 다를 수 있고, 그 값을 읽으려고
        가중치를 올리게 만들면 Protocol 이 부작용을 갖는다.

        Raises:
            EmbeddingCallError: 호출 자체가 실패했다(일시).
            EmbeddingModelUnavailableError: 가중치를 준비하지 못했다(일시).
        """
        ...
