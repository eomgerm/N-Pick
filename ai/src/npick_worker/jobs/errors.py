"""워커 예외와 오류 어휘.

오류 코드 표의 정본은 `docs/contracts/job-api.md` 다. 여기 있는 것은 그 표 중
**워커가 실제로 발신하는 값**뿐이다. BE 만 쓰는 코드(`PIPELINE_VERSION_MISMATCH` 등)와
HTTP 계층의 `JOB_*` 코드는 워커가 만들지 않으므로 넣지 않는다.

구분은 하나뿐이다 — 일시 오류와 영구 오류(FRD v3.1 §6 F-14, `docs/frd.md:359`
"무한 재시도하지 않는다. 권한·외부 전송 거부 같은 영구 오류와 일시 오류를 구분한다").
더 잘게 나누고 싶은 유혹이 있지만 정본이 요구하는 축은 이 하나이고,
`retryable` 은 어차피 **BE 가 판정한다**. 워커의 값은 신고일 뿐이다.
"""

from typing import ClassVar, Final, Literal, cast, get_args

from npick_worker.media_errors import MediaUnreadableError

#: 워커가 `complete` 에 실을 수 있는 오류 코드. `stage_states_json` 의 varchar(64) 에 들어간다.
StageErrorCode = Literal[
    # ── 단계별 실패 ──
    "SCENE_DETECTION_FAILED",
    "VLM_SCHEMA_INVALID",
    "ENTITY_SCHEMA_INVALID",
    "OCR_FAILED",
    "ASR_FAILED",
    "INDEX_FAILED",
    # ── 입력이 잘못됨 (영구) ──
    "VALIDATION_ERROR",
    "UNSUPPORTED_MEDIA",
    "INVALID_TRANSCRIPT",
    "EXTERNAL_PROCESSING_NOT_ALLOWED",
    # ── 실행 환경 ──
    "NO_ADAPTER",
    "MODEL_UNAVAILABLE",
    "OUT_OF_MEMORY",
    "STAGE_TIMEOUT",
    "MEDIA_UNAVAILABLE",
    "ARTIFACT_UPLOAD_FAILED",
    "WORKER_ABORTED",
    "UNSUPPORTED_STAGE",
    #: 위 어느 것에도 해당하지 않는 실패. 분류를 미룰 뿐 숨기지 않는다.
    "STAGE_FAILED",
]

#: `StageErrorCode` 의 런타임 사본. 방어 검사와 테스트가 쓴다.
STAGE_ERROR_CODES: Final[frozenset[str]] = frozenset(get_args(StageErrorCode))


class WorkerError(Exception):
    """워커 예외의 뿌리. 자기 코드와 재시도 가능 여부를 스스로 안다.

    예외마다 클래스를 만들지 않는다. 코드가 데이터이므로 계층은 재시도 가능 여부라는
    한 축만 표현한다.
    """

    error_code: ClassVar[str] = "STAGE_FAILED"
    retryable: ClassVar[bool] = True


class TransientStageError(WorkerError):
    """다시 시도하면 성공할 수 있는 실패."""

    error_code: ClassVar[str] = "STAGE_FAILED"
    retryable: ClassVar[bool] = True


class PermanentStageError(WorkerError):
    """같은 입력으로 다시 시도해도 같은 결과인 실패."""

    error_code: ClassVar[str] = "VALIDATION_ERROR"
    retryable: ClassVar[bool] = False


class InputUnavailableError(PermanentStageError):
    """입력 미디어가 없거나 경로가 허용 범위를 벗어났다."""

    error_code: ClassVar[str] = "MEDIA_UNAVAILABLE"


class InputDownloadError(TransientStageError):
    """입력 미디어를 받아오지 못했다. 네트워크·서버 사정일 수 있다."""

    error_code: ClassVar[str] = "MEDIA_UNAVAILABLE"


class UnsupportedMediaError(PermanentStageError):
    """미디어를 열 수 없다. 코덱·컨테이너가 지원 범위 밖이거나 파일이 깨졌다."""

    error_code: ClassVar[str] = "UNSUPPORTED_MEDIA"


class ContentHashMismatchError(PermanentStageError):
    """받은 바이트가 배정이 말한 해시와 다르다. 다시 받아도 같은 파일이 온다."""

    error_code: ClassVar[str] = "UNSUPPORTED_MEDIA"


class StageConfigUnsupportedError(PermanentStageError):
    """배정이 단계 설정을 실어 보냈지만 이 워커가 그것을 쓰지 않는다.

    조용히 무시하면 보고되는 `stageVersion`·`configVersion` 이 "이 설정으로 만든
    결과" 라는 거짓 기록이 된다. 계약 §7 이 막으려는 상황이라 거절이 정직하다.
    """


class UpstreamOutputInvalidError(PermanentStageError):
    """상류 단계 산출물(`inputs.upstream`)이 계약과 다르다.

    영구인 이유는 BE 가 다시 보내도 같은 것을 보내기 때문이다. 일시로 신고하면
    `maxAttempts` 만큼 GPU 분을 태우고 같은 자리에서 죽는다.
    """


class InvalidTranscriptError(PermanentStageError):
    """BE 가 준비한 자막 snapshot 이 4단계가 받을 수 있는 것이 아니다.

    **용도가 하나다.** "snapshot 이 계약과 다르다" 는 판정 대부분은 러너가 핸들러를
    부르기 **전에** 이미 낸다 — `jobs/artifacts.py` 가 내려받은 문서를
    `TranscriptSegments`(`extra="forbid"`)로 검증하고 실패를 `VALIDATION_ERROR` 로
    보고한다. 그 공용 검증기가 통과시키지만 이 단계만 아는 사실은 하나뿐이다:
    `sourceDetail == "asr"` 가 섞여 온 것. 4단계 시점에 ASR 은 아직 돌지 않았고 BE 의
    준비 어댑터는 `uploaded`·`embedded` 만 만든다. 그것이 왔다면 배정이나 준비가
    어긋난 것이고, 그대로 예비 판정을 내면 `ASR_SUPPLEMENT` 채택이 실제 인식 없이
    최종 정본으로 되살아날 수 있다.

    계약 §9.2 가 이 자리에 준 코드는 `INVALID_TRANSCRIPT`(영구)다.
    """

    error_code: ClassVar[str] = "INVALID_TRANSCRIPT"


class StageUnavailableError(PermanentStageError):
    """FRD 단계 표에는 있으나 이 워커에 구현이 없다."""

    error_code: ClassVar[str] = "NO_ADAPTER"


class ModelUnavailableError(TransientStageError):
    """가중치를 준비하지 못했다. 구현은 있는데 모델이 없는 상태다.

    `NO_ADAPTER`(영구)와 갈라야 한다. 구현이 없는 것은 이 이미지의 성질이라 재시도가
    고칠 수 없지만, 가중치는 캐시 볼륨이 안 붙었거나 내려받기가 실패한 것이라 다른
    파드나 다음 시도에서 성공할 수 있다(계약 §9.2).
    """

    error_code: ClassVar[str] = "MODEL_UNAVAILABLE"


class AsrFailedError(TransientStageError):
    """음성 인식 실행이 실패했다(계약 §9.2 `ASR_FAILED`, **일시**).

    `TransientStageError` 를 그대로 던지면 `STAGE_FAILED` 로 적힌다. 그 값의 뜻은
    "분류를 미룬다" 이고(위 `StageErrorCode` 주석), 아래 `_STAGE_DEFAULT_CODE` 가
    `asr` 에 `ASR_FAILED` 를 준 것은 **정체 모를** 예외에만 걸리기 때문에 명시적으로
    던진 예외는 그 표를 지나가지 않는다. 인식이 실패한 것은 미룰 것이 없는 분류된
    사실이고 계약이 그 자리에 코드를 하나 주고 있으므로, 그 코드로 신고한다.

    빈 결과와 갈라야 한다 — 발화가 없어 구간이 0 개인 것은 정상 종료
    (`NO_SPEECH_DETECTED`)다. 둘이 같은 기록으로 남으면 "무음이었나 실패했나" 를
    나중에 구분할 수 없다.
    """

    error_code: ClassVar[str] = "ASR_FAILED"


class VlmOutputInvalidError(PermanentStageError):
    """VLM 출력이 계약과 다르다(계약 §9.2 `VLM_SCHEMA_INVALID`, **영구**).

    `UpstreamOutputInvalidError` 와 갈라야 한다. 상류 산출물이 잘못된 것과 모델이 형식을
    지키지 않은 것은 다른 사실이고, 고칠 곳도 다르다 — 앞엣것은 상류 단계, 뒤엣것은
    프롬프트나 모델이다. 둘 다 영구이지만 정본에 남는 원인이 달라야 한다.

    영구인 이유: temperature 0 으로 부르므로 같은 입력에 같은 출력이 다시 온다
    (`config/vlm_metadata.v1.toml` 의 `[call]`). 재시도는 GPU 분만 태운다.
    """

    error_code: ClassVar[str] = "VLM_SCHEMA_INVALID"


class EntityOutputInvalidError(PermanentStageError):
    """NER 출력이 계약과 다르다(계약 §9.2 `ENTITY_SCHEMA_INVALID`, **영구**).

    `VlmOutputInvalidError` 와 같은 자리의 코드이고 가르는 이유도 같다. 상류 산출물이
    잘못된 것(`UpstreamOutputInvalidError`)과 모델이 형식을 지키지 않은 것은 고칠 곳이
    다르다 — 앞엣것은 상류 단계, 뒤엣것은 가중치나 라벨표다.

    영구인 이유: 이 단계는 같은 가중치로 같은 텍스트를 다시 읽는다. 표본 추출도
    temperature 도 없으므로 재시도는 GPU 분만 태운다. 계약 §4.3.6 이 요구하는 원자적
    폐기 — "단계 전체 출력의 어떤 필드도 쓰지 않는다" — 가 이 코드로 보고된다.
    """

    error_code: ClassVar[str] = "ENTITY_SCHEMA_INVALID"


class ExternalProcessingRefusedError(PermanentStageError):
    """외부 전송 조건이 확인되지 않아 **보내지 않았다**(PRD §12.4 fail-closed).

    영구인 이유는 이 판정이 요청 내용이 아니라 정책·권리 확인 상태에서 오기 때문이다.
    같은 클립으로 다시 시도하면 같은 판정이 나온다 — 바뀌어야 하는 것은 배포 설정이나
    권리 확인이고, 그건 재시도가 아니라 사람이 하는 일이다.
    """

    error_code: ClassVar[str] = "EXTERNAL_PROCESSING_NOT_ALLOWED"


class UnknownStageError(PermanentStageError):
    """FRD 단계 표에 없는 이름을 배정받았다. 재시도가 고칠 수 없다."""

    error_code: ClassVar[str] = "UNSUPPORTED_STAGE"


class ArtifactUploadError(TransientStageError):
    """산출물 업로드가 실패했다."""

    error_code: ClassVar[str] = "ARTIFACT_UPLOAD_FAILED"


class ArtifactKeyRejectedError(PermanentStageError):
    """산출물 키가 배정이 준 접두 밖이다(JOB_403_001).

    워커 버그이므로 다시 시도해도 같다. 다만 **단계 하나의 문제이지 권한 문제가
    아니다** — 이걸 인증 거절과 같이 다루면 워커 루프 전체가 멈춘다.
    """

    error_code: ClassVar[str] = "VALIDATION_ERROR"


class ArtifactHashMismatchError(PermanentStageError):
    """The server rejected artifact integrity, including after one bounded upload retry."""

    error_code: ClassVar[str] = "ARTIFACT_UPLOAD_FAILED"


class JobApiError(WorkerError):
    """잡 API 호출 자체의 실패. 단계 실행과 무관하므로 `complete` 에 실리지 않는다."""

    error_code: ClassVar[str] = "JOB_API_ERROR"


class JobApiUnavailableError(JobApiError):
    """BE 에 닿지 못했거나 5xx 를 받았다. 백오프 후 계속 폴링한다."""

    error_code: ClassVar[str] = "JOB_API_UNAVAILABLE"
    retryable: ClassVar[bool] = True


class JobApiUnauthorizedError(JobApiError):
    """토큰이 거절됐다. 벽을 두드려도 열리지 않으므로 루프를 멈춘다."""

    error_code: ClassVar[str] = "JOB_API_UNAUTHORIZED"
    retryable: ClassVar[bool] = False


class StageAlreadyCompletedError(JobApiError):
    """이 단계는 이미 성공으로 기록돼 있다(JOB_409_001).

    오류가 아니다. 재시도나 중복 반납이 정상적으로 걸린 것이므로 결과를 버리고
    다음 잡으로 간다.
    """

    error_code: ClassVar[str] = "STAGE_ALREADY_COMPLETED"
    retryable: ClassVar[bool] = False


class JobApiConflictError(JobApiError):
    """정본 상태와 어긋나 반납이 거절됐다(JOB_409_003·409_004·404_001).

    lease 문제와 구분해야 한다. 같은 키에 다른 본문(003)이나 버전 불일치(004)는
    워커를 고쳐야 하는 상황이고, 재시도로 풀리지 않는다.
    """

    error_code: ClassVar[str] = "JOB_API_CONFLICT"
    retryable: ClassVar[bool] = False


class JobApiInvalidRequestError(JobApiConflictError):
    """Invalid request/output contract (JOB_400_001/411_001); sending it again cannot help."""


class LeaseLostError(JobApiError):
    """lease 가 회수됐다(fencing). 산출물을 버리고 `complete` 를 보내지 않는다.

    이걸 무시하고 결과를 반납하면 이미 다른 워커가 만든 정본 위에 두 번째 산출물이
    올라간다. `docs/frd.md:137` "재시도가 성공 산출물을 중복 생성하지 않는다" 가 깨지는
    지점이 정확히 여기다.
    """

    error_code: ClassVar[str] = "LEASE_LOST"
    retryable: ClassVar[bool] = False


#: 단계가 정체 모를 예외를 던졌을 때 쓸 단계별 기본 코드.
#: 없는 단계는 `STAGE_FAILED` 로 떨어진다 — 구현이 생길 때 여기 한 줄을 늘린다.
_STAGE_DEFAULT_CODE: Final[dict[str, StageErrorCode]] = {
    "scene_detection": "SCENE_DETECTION_FAILED",
    # `vlm_metadata` 가 여기 없다. 계약 §9.2 가 이 단계에 준 코드는 `VLM_SCHEMA_INVALID`
    # 하나인데 그것은 **영구**이고, 이 표의 값은 "정체 모를 예외" 에 붙는 기본값이라
    # 재시도 가능으로 보고된다. 정체 모를 실패를 영구 코드로 적으면 두 가지가 동시에
    # 거짓이 된다 — 원인(형식 오류가 아니었다)과 분류(일시인데 영구로 적힌다).
    # 형식 오류는 `VlmOutputInvalidError` 로 명시적으로 발신하고, 나머지는 아래
    # `STAGE_FAILED`(일시) 로 떨어진다 — "분류를 미룰 뿐 숨기지 않는다".
    "ocr": "OCR_FAILED",
    "asr": "ASR_FAILED",
    # `entity_extraction` 도 여기 없다. 이유는 위 `vlm_metadata` 와 같다 —
    # `ENTITY_SCHEMA_INVALID` 는 영구이고 이 표의 값은 정체 모를 예외에 붙는다.
    "indexing": "INDEX_FAILED",
}

_CUDA_OOM_MARKER: Final[str] = "out of memory"


def classify(exc: BaseException, stage: str) -> tuple[StageErrorCode, bool]:
    """단계가 던진 예외를 `(코드, 재시도 가능)` 으로 번역한다.

    단계 구현은 순수 함수라 잡 레이어를 import 할 수 없고(`ai/AGENTS.md:15`), 그래서
    맨 `ValueError` 를 던진다(`scene_detection/__init__.py`,
    `scene_detection/pyscenedetect_backend.py`). 번역은 경계인 여기서 한다.

    `ValueError` 계열을 영구로 보는 이유: 단계가 그것을 던지는 경우는 상류 산출물·설정·
    미디어에 대한 판정, 즉 같은 바이트에 대해 항상 같은 결과인 판정뿐이다. 다시 돌려도 같다.

    그중 **미디어를 쓸 수 없다** 는 판정만 `MediaUnreadableError` 로 갈라 `UNSUPPORTED_MEDIA`
    로 번역한다. 둘 다 영구지만 정본에 남는 원인이 달라야 한다 — 계약 §4.3.1 은 영상을 열
    수 없는 것을 `UNSUPPORTED_MEDIA` 로 두고, `VALIDATION_ERROR` 는 "상류 산출물·산출물
    키가 잘못됐다" 는 다른 사실이다.
    """
    if isinstance(exc, WorkerError):
        # 잡 API 예외는 단계 어휘가 아니다. 호출부가 걸러야 하지만 방어적으로 막는다.
        if exc.error_code not in STAGE_ERROR_CODES:
            return "STAGE_FAILED", exc.retryable
        return cast(StageErrorCode, exc.error_code), exc.retryable
    if isinstance(exc, MemoryError):
        return "OUT_OF_MEMORY", True
    if isinstance(exc, RuntimeError) and _CUDA_OOM_MARKER in str(exc).lower():
        return "OUT_OF_MEMORY", True
    if isinstance(exc, FileNotFoundError):
        return "MEDIA_UNAVAILABLE", False
    if isinstance(exc, TimeoutError):
        return "STAGE_TIMEOUT", True
    if isinstance(exc, OSError):
        return "MEDIA_UNAVAILABLE", True
    if isinstance(exc, MediaUnreadableError):
        # ValueError 하위이므로 아래 분기보다 앞에 있어야 한다.
        return "UNSUPPORTED_MEDIA", False
    if isinstance(exc, ValueError | TypeError | KeyError):
        return "VALIDATION_ERROR", False
    return _STAGE_DEFAULT_CODE.get(stage, "STAGE_FAILED"), True
