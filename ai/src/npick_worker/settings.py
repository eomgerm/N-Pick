"""워커 설정. 모든 값은 NPICK_AI_ 접두사 환경 변수로 주입한다."""

from functools import lru_cache
from typing import Literal

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


@lru_cache(maxsize=1)
def get_settings() -> Settings:
    return Settings()
