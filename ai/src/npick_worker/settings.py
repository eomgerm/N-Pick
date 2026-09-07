"""워커 설정. 모든 값은 NPICK_AI_ 접두사 환경 변수로 주입한다."""

from functools import lru_cache
from typing import Literal

from pydantic import SecretStr
from pydantic_settings import BaseSettings, SettingsConfigDict

DeviceChoice = Literal["auto", "cuda", "cpu"]
LogLevel = Literal["DEBUG", "INFO", "WARNING", "ERROR"]
#: Query Resolver 가 어느 adapter 로 나가는가. FRD §2.1 "local 또는 승인된 GMS".
ResolverBackend = Literal["ollama", "gms"]


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

    # ── Query Resolver LLM (FRD §2.1 "local 또는 승인된 GMS") ──
    # 모델이 없어도 워커는 그대로 기동한다. 실패는 resolver 를 실제로 호출할 때만
    # 난다(ai/AGENTS.md — GPU 없이도 기동하는 성질을 깨지 않는다).
    #
    # 기본이 local 인 이유: FRD §6.4 가 query 외부 전송을 별도 승인 대상으로 둔다.
    # 승인된 배포에서만 명시적으로 gms 로 뒤집는다.
    resolver_backend: ResolverBackend = "ollama"

    ollama_url: str = "http://127.0.0.1:11434"
    # 기본값을 두지 않는다. 모델명은 결과를 바꾸는 값이고 실측 후 확정 대상이라,
    # 코드가 임의로 고르면 그게 곧 근거 없는 동결이다(FRD §11).
    ollama_model: str = ""

    # 승인된 GMS(OpenAI 호환 게이트웨이). 세 값 모두 기본값이 없다 —
    # 어느 엔드포인트로 나가는지는 추정해서는 안 되는 값이다.
    gms_base_url: str = ""
    # SecretStr: 로그·repr·예외 메시지로 토큰이 새는 경로를 타입으로 막는다.
    # 값을 쓰려면 .get_secret_value() 를 명시적으로 불러야 한다.
    gms_api_key: SecretStr = SecretStr("")
    gms_model: str = ""
    # 게이트웨이가 response_format 을 받지 않으면 false 로 끈다. 그러면 JSON 강제가
    # 프롬프트 지시뿐이라 RESOLVER_SCHEMA_INVALID 가 늘어난다.
    gms_json_mode: bool = True


@lru_cache(maxsize=1)
def get_settings() -> Settings:
    return Settings()
