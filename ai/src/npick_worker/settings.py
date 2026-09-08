"""워커 설정. 모든 값은 NPICK_AI_ 접두사 환경 변수로 주입한다."""

from functools import lru_cache
from pathlib import Path
from typing import Literal

from pydantic import Field, SecretStr
from pydantic_settings import BaseSettings, SettingsConfigDict

DeviceChoice = Literal["auto", "cuda", "cpu"]
LogLevel = Literal["DEBUG", "INFO", "WARNING", "ERROR"]
#: Query Resolver 가 어느 adapter 로 나가는가. local 또는 승인된 GMS 중 하나다.
#: 배포 경계와 교체 가능성은 `docs/architecture/02-container.md` 요소 표가 정본이다.
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
    #: heartbeat 주기의 상한. claim 이 준 heartbeatIntervalMs 가 이 값보다 작으면
    #: 그 값을 쓰고, 크면 이 값으로 자른다. BE 가 lease TTL 보다 긴 주기를 줘서
    #: lease 가 만료되는 사고를 여기서 막는다.
    job_heartbeat_seconds: float = Field(default=10.0, gt=0)
    job_max_backoff_seconds: float = Field(default=60.0, gt=0)
    #: GPU 한 장을 전제한다.
    job_concurrency: int = Field(default=1, ge=1)

    #: backend 와 공유하는 미디어 마운트. 없으면 입력을 HTTP 로 받는다.
    #: compose 는 /srv/npick/media 를 준다. RunPod 파드에는 공유 볼륨이 없다.
    media_root: Path | None = None

    # ── Query Resolver LLM (local 또는 승인된 GMS) ──
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
