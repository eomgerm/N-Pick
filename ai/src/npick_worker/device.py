"""장치 탐지. torch 는 선택 의존성(gpu 그룹)이므로 함수 안에서만 임포트한다."""

import logging
from dataclasses import dataclass, replace
from functools import cache
from typing import Literal

from npick_worker.settings import DeviceChoice

logger = logging.getLogger(__name__)


@dataclass(frozen=True, slots=True)
class DeviceInfo:
    requested: DeviceChoice
    resolved: Literal["cuda", "cpu"]
    torch_available: bool
    torch_version: str | None
    cuda_available: bool
    #: torch.version.cuda. CPU 전용 휠이 설치되면 None 이 된다 — 잘못된 휠의 신호다.
    cuda_version: str | None
    device_name: str | None
    total_memory_mb: int | None


_TORCH_ABSENT = DeviceInfo(
    requested="auto",
    resolved="cpu",
    torch_available=False,
    torch_version=None,
    cuda_available=False,
    cuda_version=None,
    device_name=None,
    total_memory_mb=None,
)


@cache
def detect_device(requested: DeviceChoice = "auto") -> DeviceInfo:
    """요청 장치를 실제 사용 가능한 장치로 해석한다.

    어떤 경우에도 예외를 던지지 않는다. GPU 부재는 정상 상태이며 워커는 기동해야 한다.
    프로세스 수명 동안 캐시되므로 드라이버를 교체했으면 워커를 재기동한다.
    """
    try:
        import torch
    except ImportError:
        logger.info("torch 미설치: CPU 로 동작한다. GPU 를 쓰려면: uv sync --group gpu")
        return replace(_TORCH_ABSENT, requested=requested)

    torch_version: str = str(torch.__version__)
    cuda_version: str | None = torch.version.cuda
    cuda_available = bool(torch.cuda.is_available())

    if requested == "cpu" or not cuda_available:
        if requested == "cuda":
            logger.warning(
                "device=cuda 를 요청했으나 CUDA 를 쓸 수 없어 cpu 로 내려간다 "
                "(torch=%s, torch.version.cuda=%s)",
                torch_version,
                cuda_version,
            )
        return DeviceInfo(
            requested=requested,
            resolved="cpu",
            torch_available=True,
            torch_version=torch_version,
            cuda_available=cuda_available,
            cuda_version=cuda_version,
            device_name=None,
            total_memory_mb=None,
        )

    props = torch.cuda.get_device_properties(0)
    return DeviceInfo(
        requested=requested,
        resolved="cuda",
        torch_available=True,
        torch_version=torch_version,
        cuda_available=True,
        cuda_version=cuda_version,
        device_name=str(props.name),
        total_memory_mb=int(props.total_memory) // (1024 * 1024),
    )
