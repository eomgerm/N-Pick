"""외부 처리 정책 게이트 — 보내기 전에 판정한다 (PRD §12.4, FRD §6.4).

`02-container.md` 요소 표는 VLM 을 워커의 **자체 GPU** 에 둔다. 외부 제공자는 조건을 전부
만족할 때만 쓰는 대체 경로이고, 이 파일은 그 조건을 코드로 옮긴 것이다.

PRD §12.4 가 요구하는 것은 일곱 가지다. 하나라도 확인되지 않으면 **전송 전에**
fail-closed 하고 local adapter 또는 명시된 fallback 을 쓴다.

| 조건 | 어디서 오나 | 이 파일의 검사 |
| --- | --- | --- |
| clip 의 권리·clip 별 허용 | 등록 절차와 운영 기록 | 요청의 `clip_rights_confirmed` |
| provider 의 보관·학습·삭제 조건 승인 | 운영 | 설정 `..._provider_terms_confirmed` |
| 활성 deployment policy·provider profile | 설정 | `..._enabled`·`..._provider_profile` |
| component·model·endpoint·payload category allowlist | 설정 | profile 과 요청 대조 |
| payload size 가 component 별 상한 이내 | 설정 | `..._max_payload_bytes` |
| TLS | endpoint | `https` 가 아니면 거절 |
| 승인된 secret 주입 | 환경 변수(`SecretStr`) | 비어 있으면 거절 |

**deployment 수준 허용이 clip 별 권리를 대신하지 못한다.** PRD 가 "media clip 별 승인과
query·filter 의 deployment-level 승인은 서로 대신할 수 없다" 로 못 박았기 때문에
`clip_rights_confirmed` 를 설정에서 읽지 않는다. 전역 플래그로 읽으면 운영자가 한 번
켜는 것이 모든 클립의 권리 확인을 대신하게 되고, 그게 정확히 금지된 것이다.

**그래서 지금 이 경로는 닫혀 있다.** 잡 계약(`docs/contracts/job-api.md`)에 clip 별 권리
확인을 실어 보내는 자리가 없고, FRD §11 은 그 확인을 담는 DB 컬럼·정책 테이블을 만들지
않기로 했다. 계약에 그 자리가 생기기 전까지 `clip_rights_confirmed` 는 항상 거짓이고
게이트는 항상 거절한다 — 그것이 이 단계의 정상 동작이다. 나머지 여섯 검사를 지금 구현해
두는 이유는, 그 자리가 생겼을 때 통과 조건을 새로 발명하지 않기 위해서다.

감사 기록은 **원문을 남기지 않는다.** provider profile·payload category·크기·판정·결과만
남긴다(PRD §12.4 마지막 문단).
"""

from dataclasses import dataclass
from typing import Final

from npick_worker.settings import Settings

#: 이 단계의 component 이름. PRD §12.4 표의 행 이름과 같아야 한다.
COMPONENT: Final[str] = "vlm"

#: VLM 이 보낼 수 있는 유일한 payload 종류. PRD §12.4 표의 "필요한 selected keyframe".
PAYLOAD_CATEGORY_SELECTED_KEYFRAMES: Final[str] = "selected_keyframes"

#: P0 에서 외부 전송이 금지된 종류. 정책이 켜져 있어도 예외가 없다.
FORBIDDEN_PAYLOAD_CATEGORIES: Final[frozenset[str]] = frozenset(
    {"full_video", "full_transcript", "full_ocr"}
)

#: 모듈 오류 코드. 계약 §9.2 의 같은 이름으로 번역된다(영구).
EXTERNAL_PROCESSING_NOT_ALLOWED: Final[str] = "EXTERNAL_PROCESSING_NOT_ALLOWED"

_TLS_SCHEME: Final[str] = "https://"


class ExternalProcessingNotAllowedError(Exception):
    """조건이 충족되지 않았다. **아직 아무것도 보내지 않았다.**

    이 예외가 나는 시점은 항상 전송 **전**이다. 그래서 이 실패는 "보냈는데 거절당했다"
    가 아니라 "보내지 않았다" 는 사실을 뜻한다.
    """

    code = EXTERNAL_PROCESSING_NOT_ALLOWED

    def __init__(self, message: str, record: "AuthorizationRecord") -> None:
        super().__init__(message)
        self.record = record


@dataclass(frozen=True, slots=True)
class ProviderProfile:
    """활성 provider profile. 설정에서 만든다.

    프로파일이 없으면 `None` 이고, 그 자체가 거절 사유다 — "활성 deployment policy 와
    provider profile 이 존재함" 이 조건이기 때문이다.
    """

    name: str
    endpoint: str
    model: str
    max_payload_bytes: int
    #: 승인된 secret 이 주입됐는가. **값을 담지 않는다** — 존재 여부만 판정에 쓴다.
    secret_present: bool

    @classmethod
    def from_settings(cls, settings: Settings) -> "ProviderProfile | None":
        """설정이 프로파일을 완성했을 때만 만든다.

        일부만 채워진 프로파일을 만들지 않는 이유는 그것이 "활성" 의 뜻이기 때문이다.
        endpoint 만 있고 모델이 없는 프로파일로 통과 판정을 할 수는 없다.
        """
        if not settings.vlm_external_enabled:
            return None
        required = (
            settings.vlm_external_provider_profile,
            settings.vlm_external_endpoint,
            settings.vlm_external_model,
        )
        if not all(required) or settings.vlm_external_max_payload_bytes <= 0:
            return None
        return cls(
            name=settings.vlm_external_provider_profile,
            endpoint=settings.vlm_external_endpoint,
            model=settings.vlm_external_model,
            max_payload_bytes=settings.vlm_external_max_payload_bytes,
            secret_present=bool(settings.vlm_external_api_key.get_secret_value()),
        )


@dataclass(frozen=True, slots=True)
class ExternalCallRequest:
    """보내려는 것. 판정의 입력이다."""

    model: str
    endpoint: str
    payload_category: str
    payload_bytes: int
    #: 이 clip 의 외부 처리 권리와 clip 별 허용이 **명시적으로** 확인됐는가.
    #: 설정에서 오지 않는다(모듈 docstring). 계약에 자리가 없으므로 현재는 항상 거짓이다.
    clip_rights_confirmed: bool
    component: str = COMPONENT


@dataclass(frozen=True, slots=True)
class AuthorizationRecord:
    """감사 기록에 남길 것. **원문은 없다**(PRD §12.4).

    거절된 판정도 기록한다. "보내지 않았다" 는 사실이 남지 않으면 나중에 fail-closed 가
    실제로 걸렸는지 확인할 방법이 없다.
    """

    component: str
    provider_profile: str
    model: str
    payload_category: str
    payload_bytes: int
    allowed: bool
    #: 거절 사유. 통과면 `None`.
    reason: str | None = None

    def as_log_fields(self) -> dict[str, object]:
        """로그에 그대로 실을 수 있는 모양. 값에 원문·secret 이 없다."""
        return {
            "component": self.component,
            "providerProfile": self.provider_profile,
            "model": self.model,
            "payloadCategory": self.payload_category,
            "payloadBytes": self.payload_bytes,
            "allowed": self.allowed,
            "reason": self.reason,
        }


def authorize(
    request: ExternalCallRequest,
    settings: Settings,
    profile: ProviderProfile | None = None,
) -> AuthorizationRecord:
    """보내도 되는지 판정한다. 거절이면 예외이고, 그때도 기록은 남는다.

    Args:
        request: 보내려는 것.
        settings: deployment 수준 조건.
        profile: 활성 provider profile. 주지 않으면 설정에서 만든다.

    Returns:
        통과 기록. 호출부가 감사 로그에 남긴다.

    Raises:
        ExternalProcessingNotAllowedError: 조건 중 하나라도 확인되지 않았다. **전송 전이다.**
    """
    active = profile if profile is not None else ProviderProfile.from_settings(settings)
    reason = _rejection_reason(request, settings, active)
    record = AuthorizationRecord(
        component=request.component,
        provider_profile=active.name if active is not None else "",
        model=request.model,
        payload_category=request.payload_category,
        payload_bytes=request.payload_bytes,
        allowed=reason is None,
        reason=reason,
    )
    if reason is not None:
        raise ExternalProcessingNotAllowedError(f"외부 전송을 하지 않았다: {reason}", record)
    return record


def _rejection_reason(
    request: ExternalCallRequest, settings: Settings, profile: ProviderProfile | None
) -> str | None:
    """거절 사유 하나를 돌려준다. 통과면 `None`.

    순서가 있다. 먼저 **절대 금지**를 본다 — 정책이 켜져 있는지와 무관하게 안 되는 것이
    있기 때문이다(P0 의 full video·전체 transcript·전체 OCR). 그다음 정책·프로파일,
    allowlist, 크기, 전송 보호 순이다. 사유를 하나만 돌려주는 이유는 이 값이 감사 기록의
    한 칸이기 때문이다 — 전부 모으면 첫 번째 위반이 묻힌다.
    """
    if request.payload_category in FORBIDDEN_PAYLOAD_CATEGORIES:
        return f"P0 에서 금지된 payload category 다: {request.payload_category}"
    if request.component != COMPONENT:
        return f"이 게이트는 {COMPONENT} 전용이다: {request.component}"
    if request.payload_category != PAYLOAD_CATEGORY_SELECTED_KEYFRAMES:
        # PRD §12.4 표가 VLM 에 허용한 것은 "필요한 selected keyframe" 하나다.
        return f"allowlist 에 없는 payload category 다: {request.payload_category}"
    if not request.clip_rights_confirmed:
        # deployment 수준 허용으로 대신할 수 없다(모듈 docstring).
        return "clip 의 외부 처리 권리와 clip 별 허용이 확인되지 않았다"
    if not settings.vlm_external_provider_terms_confirmed:
        return "provider 의 보관·학습·삭제 조건이 확인·승인되지 않았다"
    if profile is None:
        return "활성 deployment policy 또는 provider profile 이 없다"
    if request.model != profile.model:
        return f"allowlist 에 없는 모델이다: {request.model}"
    if request.endpoint != profile.endpoint:
        return f"allowlist 에 없는 endpoint 다: {request.endpoint}"
    if not request.endpoint.startswith(_TLS_SCHEME):
        # 프로파일과 같더라도 평문이면 보내지 않는다. 프로파일이 잘못 채워진 경우다.
        return "TLS 가 아닌 endpoint 다"
    if request.payload_bytes > profile.max_payload_bytes:
        return (
            f"payload 가 상한을 넘는다: {request.payload_bytes}B "
            f"(상한 {profile.max_payload_bytes}B)"
        )
    if not profile.secret_present:
        return "승인된 주입 방식으로 들어온 secret 이 없다"
    return None
