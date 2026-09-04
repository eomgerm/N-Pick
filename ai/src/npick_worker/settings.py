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

    # ── Query Resolver LLM (FRD §2.1 "local 또는 승인된 GMS" 중 local) ──
    # Ollama 가 없어도 워커는 그대로 기동한다. 실패는 resolver 를 실제로 호출할 때만
    # 난다(ai/AGENTS.md — GPU 없이도 기동하는 성질을 깨지 않는다).
    ollama_url: str = "http://127.0.0.1:11434"
    # 기본값을 두지 않는다. 모델명은 결과를 바꾸는 값이고 Gate B 미동결이라,
    # 코드가 임의로 고르면 그게 곧 근거 없는 동결이다(PRD §15.3).
    ollama_model: str = ""


@lru_cache(maxsize=1)
def get_settings() -> Settings:
    return Settings()
