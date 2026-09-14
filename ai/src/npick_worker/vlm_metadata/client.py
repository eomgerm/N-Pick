"""VLM 호출의 경계.

`ai/AGENTS.md` 가 "외부 모델 호출은 모듈 Protocol 어댑터 경계 뒤에 두고 호출부에
provider SDK 를 직접 노출하지 않는다" 로 정한다. `ocr/engine.py` 의 `OcrEngine`,
`frame_extraction` 의 `FrameGrabber` 와 같은 자리다.

이 경계가 실제로 사는 것 둘.

- **모델 선정이 아직 열려 있다.** 후보 비교가 이 티켓의 일이고, 결과에 따라 바뀌는 것은
  backend 파일 하나와 설정이지 `describer.py` 도 `validator.py` 도 `jobs/` 도 아니다.
- **자체 호스팅과 외부 호출이 같은 자리에 꽂힌다.** `02-container.md` 의 요소 표는 VLM 을
  워커의 자체 GPU 에 두고, 외부 제공자는 PRD §12.4 의 조건을 전부 만족할 때만 쓸 수 있는
  대체 경로다. 조건 판정은 어댑터를 **고르는 쪽**의 일이고 이 Protocol 은 그것을 모른다.
"""

from collections.abc import Sequence
from dataclasses import dataclass
from pathlib import Path
from typing import Protocol, runtime_checkable

from npick_worker.vlm_metadata.config import CallParams


@dataclass(frozen=True, slots=True)
class LabeledImage:
    """모델에 넣는 이미지 한 장과 그 근거 라벨.

    라벨을 이미지와 함께 넘기는 이유는 어댑터마다 이미지를 싣는 방식이 달라서다. 어떤
    런타임은 대화 메시지에 텍스트와 이미지를 번갈아 넣고, 어떤 것은 이미지 배열과 본문을
    나눠 받는다. 라벨이 이미지에 붙어 있어야 어느 쪽에서도 "이 그림이 kf_2 다" 를 말할 수
    있고, 그 말이 없으면 근거 라벨 전체가 뜻을 잃는다.
    """

    label: str
    #: 로컬 파일 경로. **이 모듈은 파일을 내려받지 않는다** — 바이트를 가져오는 일은 잡
    #: 레이어의 몫이다(`ai/AGENTS.md`: 단계는 순수 함수).
    path: Path


class VlmCallError(Exception):
    """모델을 부르지 못했거나 응답을 받지 못했다.

    **출력 내용의 문제가 아니다.** 형식이 틀린 출력은 `validator.py` 의
    `VlmSchemaInvalidError` 이고 그건 영구 실패다(계약 §9.2 `VLM_SCHEMA_INVALID`). 이쪽은
    타임아웃·연결 실패·런타임 오류라 다음 시도에서 성공할 수 있다. 두 실패가 같은 코드로
    기록되면 "재시도가 고칠 수 있는가" 를 나중에 구분할 수 없다.
    """


class VlmModelUnavailableError(Exception):
    """가중치를 준비하지 못했다. 구현은 있는데 모델이 없는 상태다.

    `NO_ADAPTER`(영구)와 갈라야 한다 — 계약 §9.2 의 `MODEL_UNAVAILABLE` 은 일시다.
    캐시 볼륨이 안 붙었거나 내려받기가 실패한 것이라 다른 파드에서 성공할 수 있다.
    """


@runtime_checkable
class VlmClient(Protocol):
    """이미지 여러 장과 프롬프트로 텍스트 하나를 받는다."""

    @property
    def name(self) -> str:
        """재현 튜플에 들어가는 어댑터 이름. 예: `transformers`"""
        ...

    @property
    def version(self) -> str:
        """어댑터와 런타임의 버전. 가중치 버전은 `model_version` 이 따로 말한다."""
        ...

    @property
    def model_version(self) -> str:
        """가중치의 식별자. 예: `Qwen/Qwen3-VL-8B-Instruct@<revision>`

        런타임 버전만으로는 부족하다. 같은 런타임이라도 가중치가 바뀌면 같은 프레임에서
        다른 문장이 나온다 — `OcrEngine.version` 이 모델 버전을 함께 싣는 것과 같은 이유다.
        """
        ...

    def describe(
        self,
        images: Sequence[LabeledImage],
        system_prompt: str,
        user_prompt: str,
        params: CallParams,
    ) -> str:
        """모델이 낸 텍스트를 **그대로** 돌려준다.

        JSON 으로 파싱하지 않는다. 파싱과 검증은 `validator.py` 한 곳이고, 어댑터마다
        따로 하면 어느 어댑터에서 무엇이 거부됐는지가 갈린다. 원문을 그대로 넘기는 것은
        실패 원인을 추적할 수 있어야 한다는 티켓 요구이기도 하다 — 거부된 출력의 원문이
        남지 않으면 프롬프트를 고칠 근거가 없다.

        Raises:
            VlmCallError: 호출 자체가 실패했다(일시).
            VlmModelUnavailableError: 가중치를 준비하지 못했다(일시).
        """
        ...
