"""워커 설정. 모든 값은 NPICK_AI_ 접두사 환경 변수로 주입한다."""

from functools import lru_cache
from pathlib import Path
from typing import Literal

from pydantic import Field, SecretStr
from pydantic_settings import BaseSettings, SettingsConfigDict

DeviceChoice = Literal["auto", "cuda", "cpu"]
LogLevel = Literal["DEBUG", "INFO", "WARNING", "ERROR"]


class Settings(BaseSettings):
    model_config = SettingsConfigDict(
        env_prefix="NPICK_AI_",
        env_file=".env",
        env_file_encoding="utf-8",
        extra="ignore",
    )

    # 로컬 개발 기본값. 컨테이너 배포에서는 NPICK_AI_HOST=0.0.0.0 으로 뒤집는다.
    host: str = "127.0.0.1"
    # backend 는 8080 을 쓴다(backend/README.md). 충돌을 피해 8000 을 기본으로 둔다.
    port: int = 8000
    log_level: LogLevel = "INFO"
    device: DeviceChoice = "auto"

    # ── 잡 수신 (파이프라인 워커 전용) ──────────────────────────────
    # `ai/` 는 배포 단위 둘을 담는다. 아래 job_* 는 파이프라인 워커의 것이고
    # 질의 리졸버는 쓰지 않는다(docs/architecture/02-container.md 의 *요소* 표).
    #
    # job_poll_enabled 는 그 둘을 가르는 이음매다. 같은 이미지가 이 값 하나로
    # "잡을 도는 워커" 와 "폴링하지 않는 리졸버" 가 된다. 기본값이 거짓인 이유도
    # 둘이다 — 리졸버 쪽이 기본이어야 하고, 테스트가 lifespan 을 돌 때 네트워크로
    # 나가면 안 된다.
    job_poll_enabled: bool = False
    #: 예: https://j15a501.p.ssafy.io/api. 비어 있으면 폴링하지 않는다.
    job_api_base_url: str = ""
    #: 절대 /health 나 로그에 싣지 않는다.
    job_api_token: SecretStr = SecretStr("")
    #: 비어 있으면 기동 시 1회 만든다.
    worker_id: str = ""
    #: 워커가 속한 무리. BE 는 무리마다 다른 토큰을 발급해 개발 검증 산출물이
    #: 운영 정본에 섞이지 않게 한다(03-deployment.md 의 미결 항목에 대한 답).
    job_fleet: str = "local"

    # 아래 수치는 **전송 파라미터**다. 소켓이 얼마나 기다리는가일 뿐 어떤 단계의
    # 출력도 바꾸지 않으므로 Gate B 미동결 수치(ai/AGENTS.md)가 아니다.
    # 품질 수치인 단계 재시도 횟수와 단계 타임아웃은 워커가 구현하지 않는다 —
    # infra/compose/profiles/pipeline.yml 에서 null 로 남아 있고 BE 가 소유한다.
    #: claim 요청에 싣는 서버 대기 상한.
    job_poll_wait_seconds: int = Field(default=25, ge=0)
    job_connect_timeout_seconds: float = Field(default=5.0, gt=0)
    #: claim 이외 요청의 read timeout. claim 은 대기 시간만큼 따로 늘린다.
    job_read_timeout_seconds: float = Field(default=30.0, gt=0)
    #: 상한일 뿐이다. claim 이 heartbeatIntervalMs 를 주면 그 값을 쓴다.
    job_heartbeat_seconds: float = Field(default=10.0, gt=0)
    job_max_backoff_seconds: float = Field(default=60.0, gt=0)
    #: GPU 한 장을 전제한다.
    job_concurrency: int = Field(default=1, ge=1)

    #: backend 와 공유하는 미디어 마운트. 없으면 입력을 HTTP 로 받는다.
    #: compose 는 /srv/npick/media 를 준다. RunPod 파드에는 공유 볼륨이 없다.
    media_root: Path | None = None


@lru_cache(maxsize=1)
def get_settings() -> Settings:
    return Settings()
