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
#: VLM 장면 metadata 가 어느 어댑터로 나가는가. 자체 호스팅이 정본 경로이고(`02-container.md`
#: 요소 표) 외부 호출은 PRD §12.4 의 조건을 전부 만족할 때만 쓰는 대체 경로다.
VlmBackend = Literal["transformers", "external"]


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
    #: **origin 만 넣는다.** 예: `http://backend:8080`. httpx2 는 base path 를
    #: 덮어쓰지 않고 이어 붙이는데 client.py 의 모든 경로가 절대 경로
    #: `/api/v1/internal/jobs/...` 로 시작한다. `/api` 를 붙이면 `/api/api/v1/...`
    #: 이 되어 claim·heartbeat·complete 가 전부 404 다.
    #: 비어 있으면 폴링하지 않는다.
    job_api_base_url: str = ""
    #: 절대 /health 나 로그에 싣지 않는다.
    job_api_token: SecretStr = SecretStr("")
    #: 비어 있으면 기동 시 1회 만든다.
    worker_id: str = ""
    #: 워커가 속한 무리. BE 는 무리마다 다른 토큰을 발급해 개발 검증 산출물이
    #: 운영 정본에 섞이지 않게 한다(03-deployment.md 의 미결 항목에 대한 답).
    job_fleet: str = "local"

    # 아래 수치는 **전송 파라미터**다. 소켓이 얼마나 기다리는가일 뿐 어떤 단계의
    # 출력도 바꾸지 않으므로 실측 후 확정할 실행 설정(ai/AGENTS.md)이 아니다.
    # 품질 수치인 단계 재시도 횟수와 단계 타임아웃은 워커가 구현하지 않는다 —
    # infra/compose/profiles/pipeline.yml 에서 null 로 남아 있고 BE 가 소유한다.
    #: claim 요청에 싣는 서버 대기 상한.
    job_poll_wait_seconds: int = Field(default=25, ge=0, le=25)
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

    #: OCR 모델 가중치를 둘 곳. 비우면 rapidocr 기본값(site-packages 안)을 쓴다.
    #: 컨테이너에서는 반드시 준다 — 기본값은 이미지 레이어라 컨테이너를 다시 만들
    #: 때마다 모델을 새로 받는다. Dockerfile 이 /var/cache/npick/models 를 잡아 둔다.
    #: 품질을 바꾸는 값이 아니라 경로이므로 버전이 붙는 설정 파일이 아니라 여기 있다.
    ocr_model_dir: Path | None = None

    # ── VLM 장면 metadata (3단계) ────────────────────────────────────
    # 어느 어댑터로 장면을 설명하는가. 기본은 **자체 호스팅**이다 —
    # `docs/architecture/02-container.md` 요소 표가 VLM 을 워커의 자체 GPU 에 두고,
    # 외부 제공자는 PRD §12.4 의 조건을 전부 만족할 때만 쓸 수 있는 대체 경로다.
    vlm_backend: VlmBackend = "transformers"
    #: 가중치 식별자. **기본값을 두지 않는다.** 모델명은 결과를 바꾸는 값이고 실측 후
    #: 확정 대상이라(FRD §11) 코드가 임의로 고르면 그게 곧 근거 없는 동결이다 —
    #: `ollama_model` 과 같은 판단이다. 비어 있으면 이 단계는 `MODEL_UNAVAILABLE` 이다.
    vlm_model: str = ""
    #: 가중치 리비전(커밋 해시·태그). 같은 이름이라도 리비전이 바뀌면 출력이 달라지므로
    #: 재현 식별자에 들어간다. 비어 있으면 `main` 을 쓴 것으로 기록한다.
    vlm_model_revision: str = ""
    #: 가중치를 둘 곳. 비우면 라이브러리 기본 캐시를 쓴다. 컨테이너에서는 반드시 준다 —
    #: 파드 디스크가 휘발성이라 파드를 띄울 때마다 수 GB 를 다시 받는다
    #: (`03-deployment.md`: 모델 가중치는 네트워크 볼륨에 상주).
    vlm_model_dir: Path | None = None

    # ── 텍스트 임베딩 (9단계) ────────────────────────────────────────
    #: 가중치 식별자. **기본값을 두지 않는다.** `vlm_model` 과 같은 판단이다 —
    #: S15P21A501-175 의 선정(`dragonkue/snowflake-arctic-embed-l-v2.0-ko`)은 잠정이고
    #: Gate B 전까지 교체 가능해야 한다. 비어 있으면 이 단계는 `MODEL_UNAVAILABLE` 이다.
    embedding_model: str = ""
    #: 가중치 리비전(커밋 해시·태그). 같은 이름이라도 리비전이 바뀌면 다른 벡터가 나오고,
    #: 벡터는 사람이 보고 이상하다고 알아챌 수 있는 산출물이 아니다. 운영에는 SHA 를 고정한다.
    embedding_model_revision: str = ""
    #: 가중치를 둘 곳. 비우면 라이브러리 기본 캐시를 쓴다. 컨테이너에서는 반드시 준다 —
    #: `vlm_model_dir` 과 같은 이유다(`03-deployment.md`: 가중치는 네트워크 볼륨에 상주).
    embedding_model_dir: Path | None = None
    #: 어댑터가 한 번에 모델에 넣는 문장 수. **버전 붙는 설정 파일에 두지 않는다** —
    #: VRAM 사정으로 움직이는 값이고 결과를 바꾸지 않는데, 설정 파일에 있으면 16→8 로
    #: 내리는 것만으로 `config_version` 과 `stageVersion` 이 달라져 계약 §7 의 버전
    #: 불일치가 난다. `ocr_model_dir` 과 같은 판단이다.
    embedding_batch_size: int = Field(default=16, gt=0)

    # ── 외부 VLM 처리 (PRD §12.4) ────────────────────────────────────
    # 아래 값이 전부 맞아도 **그것만으로 승인이 성립하지 않는다.** clip 별 외부 처리
    # 권리 확인은 deployment 수준 허용으로 대신할 수 없고(PRD §12.4) 그 확인을 담는 DB
    # 경로를 만들지 않기로 했다(FRD §11). 그래서 기본값은 전부 닫힘이고, 조건 중 하나라도
    # 확인되지 않으면 전송 전에 fail-closed 한다.
    #: 활성 deployment policy 가 있는가.
    vlm_external_enabled: bool = False
    #: 활성 provider profile 의 식별자. 감사 기록에 남기는 값이다(원문 대신).
    vlm_external_provider_profile: str = ""
    #: 승인된 endpoint. TLS 가 아니면 게이트가 거절한다.
    vlm_external_endpoint: str = ""
    #: allowlist 에 오른 모델 이름. `vlm_model` 과 다르면 거절한다.
    vlm_external_model: str = ""
    #: provider 의 보관·학습·삭제 조건을 확인하고 승인했는가.
    vlm_external_provider_terms_confirmed: bool = False
    #: component 별 payload 크기 상한(바이트).
    vlm_external_max_payload_bytes: int = Field(default=0, ge=0)
    #: 승인된 주입 방식으로 들어온 secret. 로그·예외에 싣지 않는다.
    vlm_external_api_key: SecretStr = SecretStr("")

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
