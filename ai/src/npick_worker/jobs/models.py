"""잡 API 와 주고받는 모델. 정본은 `docs/contracts/job-api.md` 다.

요청 모델은 `extra="forbid"`, 응답 모델은 `extra="ignore"` 다. 보내는 쪽은 계약에 없는
키를 만들면 안 되고, 받는 쪽은 BE 가 필드를 늘려도 죽지 않아야 한다. 전방 호환이
한쪽 방향으로만 필요하다는 뜻이다.
"""

from collections.abc import Mapping, Sequence
from datetime import datetime
from typing import TYPE_CHECKING, Any, Final, Literal

from pydantic import ConfigDict, Field, model_validator

from npick_worker.jobs.errors import StageErrorCode
from npick_worker.jobs.versions import StageVersion, WireModel

if TYPE_CHECKING:  # 런타임에 단계 구현을 끌어오지 않는다(scene_detection 은 cv2 가 딸려 온다).
    from npick_worker.frame_extraction.models import FrameExtractionResult
    from npick_worker.ocr.models import OcrResult
    from npick_worker.scene_detection.models import SceneDetectionResult
    from npick_worker.vlm_metadata.models import KeyframeRef as VlmKeyframeRef
    from npick_worker.vlm_metadata.models import VlmResult

#: 결과 봉투의 형식 버전. BE 는 모르는 값을 받으면 400 으로 거절한다.
StageResultEnvelope = Literal["stage-result/v1"]
STAGE_RESULT_ENVELOPE: Final[StageResultEnvelope] = "stage-result/v1"

StageStatus = Literal["succeeded", "failed", "skipped"]
MediaTransport = Literal["http", "shared-volume"]
HeartbeatCommand = Literal["continue", "abort"]


class WireResponse(WireModel):
    """BE 가 보내는 것. 모르는 필드는 무시한다."""

    model_config = ConfigDict(extra="ignore")


# ── claim ────────────────────────────────────────────────────────────


class StageCapability(WireModel):
    """이 워커가 실행할 수 있는 단계와 그 단계의 실제 버전.

    BE 는 이 목록에 없는 단계를 배정하지 않는다. `infra/compose/profiles/pipeline.yml` 의
    `placement.*_stages` 를 정적 목록으로 채우는 대신 워커가 선언하는 방식이다 —
    CPU 워커와 GPU 파드가 같은 이미지를 쓰므로 배치는 설정이 아니라 능력의 문제다.
    """

    stage: str
    stage_version: str


class WorkerIdentity(WireModel):
    worker_id: str
    instance_id: str
    fleet: str
    worker_version: str
    max_concurrent_stages: int = Field(ge=1)
    #: 참이면 BE 가 입력을 `transport="shared-volume"` 으로 내려줄 수 있다.
    shared_media_volume: bool


class WorkerDevice(WireModel):
    """GPU 모델은 필수다. 없으면 성능 수치를 나중에 해석할 근거가 사라진다
    (`docs/architecture/03-deployment.md` 의 benchmark profile 요구)."""

    kind: str
    gpu_model: str | None = None
    gpu_count: int = 0
    vram_mb: int | None = None
    cuda_version: str | None = None
    driver_version: str | None = None
    torch_version: str | None = None


class HeldLease(WireModel):
    """워커가 아직 살아 있다고 믿는 lease. BE 가 이미 회수한 것을 알려 준다."""

    pipeline_run_id: str
    stage: str
    lease_id: str


class ClaimRequest(WireModel):
    worker: WorkerIdentity
    capabilities: Sequence[StageCapability]
    device: WorkerDevice
    wait_seconds: int = Field(ge=0, le=25)
    held_leases: Sequence[HeldLease] = ()


class LeaseGrant(WireResponse):
    lease_id: str
    lease_until: datetime
    heartbeat_interval_ms: int = Field(gt=0)


class MediaRef(WireResponse):
    storage_key: str
    content_hash: str | None = None
    size_bytes: int | None = None
    transport: MediaTransport = "http"
    #: `transport="http"` 일 때 받아올 경로.
    url: str | None = None
    #: `transport="shared-volume"` 일 때 공유 마운트 기준 경로.
    local_path: str | None = None


class JobInputs(WireResponse):
    media: MediaRef
    #: 상류 단계 산출물. 워커가 DB 에 접속하지 않으므로 BE 가 되돌려 준다.
    upstream: Mapping[str, Any] = Field(default_factory=dict)
    config: Mapping[str, Any] = Field(default_factory=dict)


class JobAssignment(WireResponse):
    pipeline_run_id: str
    clip_id: str
    processing_no: int
    stage: str
    attempt: int = Field(ge=1)
    max_attempts: int = Field(ge=1)
    #: BE 가 발급한다. 워커는 `complete` 에 그대로 되돌려 줄 뿐 만들지 않는다.
    idempotency_key: str
    pipeline_version: str
    #: 배정 시점에 BE 가 기대하는 단계 버전. 워커의 실제 값과 다르면 기록된다.
    expected_stage_version: str | None = None
    output_schema_version: str | None = None
    deadline_at: datetime | None = None
    #: 산출물을 올릴 수 있는 키 접두. 밖으로 나가면 BE 가 403 으로 거절한다.
    output_key_prefix: str
    inputs: JobInputs


class ClaimResponse(WireResponse):
    assigned: bool
    lease: LeaseGrant | None = None
    job: JobAssignment | None = None
    revoked_leases: Sequence[str] = ()
    #: BE 가 과부하일 때만 0보다 크다. 평시 빈 응답에는 쉬지 않는다.
    retry_after_ms: int = 0


# ── heartbeat ────────────────────────────────────────────────────────


class HeartbeatProgress(WireModel):
    phase: str
    percent: int | None = None
    note: str | None = None


class HeartbeatRequest(WireModel):
    lease_id: str
    elapsed_ms: int = Field(ge=0)
    progress: HeartbeatProgress | None = None
    metrics: Mapping[str, Any] = Field(default_factory=dict)


class HeartbeatAck(WireResponse):
    command: HeartbeatCommand = "continue"
    lease_until: datetime | None = None
    abort_reason: str | None = None


# ── complete ─────────────────────────────────────────────────────────


class StageError(WireModel):
    code: StageErrorCode
    #: 워커의 신고일 뿐이다. 재시도 판정 권한은 BE 에 있다.
    retryable: bool
    #: 절대 경로·비밀값이 들어가면 안 된다. runner 가 마스킹한다.
    message: str
    detail: Mapping[str, Any] = Field(default_factory=dict)


class ArtifactRef(WireModel):
    kind: str
    storage_key: str
    byte_size: int = Field(ge=0)
    #: 계약 §4.4 의 `X-Content-SHA256` 값이다. 올리는 쪽이 반드시 아는 값이므로 필수다 —
    #: 선택으로 두면 "해시를 모르는 산출물" 이라는, 계약에 없는 상태가 표현 가능해진다.
    content_hash: str


class StageResult(WireModel):
    """`complete` 본문. 성공·실패·생략이 모두 이 모양이다.

    실패 전용 엔드포인트를 두지 않는 이유는 `stage_states_json` 을 바꾸는 쓰기 경로가
    하나여야 fencing 과 멱등성 검사가 한 벌로 끝나기 때문이다.
    """

    envelope_version: StageResultEnvelope = STAGE_RESULT_ENVELOPE
    lease_id: str
    idempotency_key: str
    stage: str
    attempt: int = Field(ge=1)
    status: StageStatus
    started_at: datetime
    finished_at: datetime
    #: 처리 시간. 단조 시계로 잰다 — 벽시계는 NTP 보정에 흔들린다.
    #: 클립 길이인 `output.mediaDurationMs` 와 이름이 겹치지 않게 둔다.
    duration_ms: int = Field(ge=0)
    #: 없으면 BE 가 거부한다.
    versions: StageVersion
    metrics: Mapping[str, Any] = Field(default_factory=dict)
    output: Mapping[str, Any] | None = None
    artifacts: Sequence[ArtifactRef] = ()
    warnings: Sequence[str] = ()
    error: StageError | None = None

    @model_validator(mode="after")
    def _status_matches_payload(self) -> "StageResult":
        """상태와 내용이 어긋난 봉투를 만들지 못하게 한다.

        BE 도 같은 검사를 하지만, 보내기 전에 걸리는 편이 낫다. 여기서 막지 않으면
        "성공했다는데 산출물이 없다" 같은 결과가 정본에 들어가려다 400 으로 튕기고,
        그때는 이미 GPU 시간을 다 쓴 뒤다.
        """
        if self.status == "succeeded":
            if not self.output:
                msg = "status=succeeded 인데 output 이 비어 있다"
                raise ValueError(msg)
            if self.error is not None:
                msg = "status=succeeded 인데 error 가 있다"
                raise ValueError(msg)
        elif self.error is None:
            msg = f"status={self.status} 이면 error 가 있어야 한다"
            raise ValueError(msg)
        if self.finished_at < self.started_at:
            msg = "finished_at 이 started_at 보다 이르다"
            raise ValueError(msg)
        return self


class AssignedSceneId(WireResponse):
    """`scene` 테이블에 `scene_index` 컬럼이 없어서 필요하다. 워커는 순번으로 보내고
    BE 가 TSID 를 발급한 뒤 그 대응을 돌려준다."""

    scene_index: int
    scene_id: str


class AssignedIds(WireResponse):
    scenes: Sequence[AssignedSceneId] = ()


class CompleteAck(WireResponse):
    accepted: bool
    #: 같은 멱등성 키의 재전송이면 참이다. 오류가 아니다.
    duplicate: bool = False
    run_status: str | None = None
    stage_state: Mapping[str, Any] = Field(default_factory=dict)
    assigned_ids: AssignedIds | None = None
    #: 같은 워커가 이어서 할 수 있는 다음 배정. 왕복을 줄인다.
    next: ClaimResponse | None = None


# ── 단계 산출물 ──────────────────────────────────────────────────────


class SceneOut(WireModel):
    """`[start_time_ms, end_time_ms)` 반열린 구간."""

    scene_index: int = Field(ge=0)
    start_time_ms: int = Field(ge=0)
    end_time_ms: int = Field(gt=0)


class SceneDetectionOutput(WireModel):
    """`scene_detection` 단계의 payload."""

    scenes: Sequence[SceneOut]
    #: 클립 전체 길이. 봉투의 `duration_ms`(처리 시간)와 다른 값이다.
    media_duration_ms: int = Field(gt=0)
    frame_rate: float = Field(gt=0)

    @classmethod
    def from_result(cls, result: "SceneDetectionResult") -> "SceneDetectionOutput":
        """단계의 순수 산출물을 와이어 모양으로 옮긴다.

        `Scene.duration_ms` 는 파생값이므로 보내지 않는다. `config_version`·`engine`·
        `engine_version` 은 payload 가 아니라 `versions` 쪽으로 간다.
        """
        return cls(
            scenes=[
                SceneOut(
                    scene_index=scene.scene_index,
                    start_time_ms=scene.start_time_ms,
                    end_time_ms=scene.end_time_ms,
                )
                for scene in result.scenes
            ],
            media_duration_ms=result.duration_ms,
            frame_rate=result.frame_rate,
        )


class UpstreamSceneOut(SceneOut):
    """수신용 `SceneOut`. 값의 뜻은 같고 미지의 키 정책만 반대다.

    계약 §3 은 보내는 모델에 `extra="forbid"`, 받는 모델에 `extra="ignore"` 를 둔다.
    같은 payload 가 방향에 따라 두 정책을 다 필요로 하는 곳이 여기다 — 워커가 만든
    `scene_detection` 산출물을 BE 가 `inputs.upstream` 으로 되돌려 주기 때문이다.
    상속으로 정책만 뒤집어 필드가 갈라질 여지를 없앤다.
    """

    model_config = ConfigDict(extra="ignore")


class UpstreamSceneDetection(SceneDetectionOutput):
    """`inputs.upstream["sceneDetection"]`. 상류 1단계 산출물이 그대로 돌아온 것이다."""

    model_config = ConfigDict(extra="ignore")

    scenes: Sequence[UpstreamSceneOut] = Field(min_length=1)


class FrameExtractionUpstream(WireResponse):
    """`inputs.upstream` 중 `frame_extraction` 이 쓰는 부분.

    워커는 DB 에 접속하지 않으므로 상류 산출물은 BE 가 되돌려 준다(계약 §4.1). 키가
    없으면 이 단계는 할 일을 모르는 것이지 빈 결과를 내는 것이 아니다 — 그래서 필수다.
    """

    scene_detection: UpstreamSceneDetection


class KeyframeOut(WireModel):
    """`keyframe` 행 하나가 될 값. 이 세 필드가 그 테이블의 전부다."""

    scene_index: int = Field(ge=0)
    #: 저장된 프레임의 정규 시각. `UNIQUE(scene_id, timestamp_ms)` 의 그 값이다.
    timestamp_ms: int = Field(ge=0)
    #: 미디어 루트 상대 경로. `artifacts` 의 같은 키로 바이트가 올라가 있다.
    storage_key: str = Field(min_length=1)


class SceneKeyframesOut(WireModel):
    """scene 하나의 keyframe 묶음.

    **`keyframes[0]` 이 대표 이미지다.** `keyframe` 테이블에 대표를 표시할 컬럼이 없고
    ERD 주석이 "결과 목록의 대표 이미지는 첫 장을 쓴다" 로 두었기 때문에, 대표는 별도
    플래그가 아니라 **목록 순서**로 전달한다. BE 는 이 순서대로 INSERT 해야 하고 그래야
    대표가 그 scene 의 최소 `keyframe_id` 가 된다.

    `representative_timestamp_ms` 를 함께 싣는 이유는 그 규약이 순서 하나에 매달려 있기
    때문이다. 목록을 정렬해 저장하는 구현 변경이 생기면 대표가 조용히 바뀌는데, 이 필드가
    있으면 BE 가 저장 직전에 대조해 어긋남을 잡을 수 있다.
    """

    scene_index: int = Field(ge=0)
    representative_timestamp_ms: int = Field(ge=0)
    keyframes: Sequence[KeyframeOut] = Field(min_length=1)

    @model_validator(mode="after")
    def _representative_is_first(self) -> "SceneKeyframesOut":
        head = self.keyframes[0]
        if head.timestamp_ms != self.representative_timestamp_ms:
            msg = (
                "대표 이미지가 목록의 첫 장이 아니다: "
                f"scene_index={self.scene_index} "
                f"(첫 장 {head.timestamp_ms}ms, 대표 {self.representative_timestamp_ms}ms)"
            )
            raise ValueError(msg)
        stamps = [keyframe.timestamp_ms for keyframe in self.keyframes]
        if len(set(stamps)) != len(stamps):
            # BE 에서 UNIQUE 제약으로 터지면 트랜잭션 하나가 통째로 롤백되고 이미 쓴
            # 처리 시간이 사라진다. 보내기 전에 걸리는 편이 낫다.
            msg = f"같은 timestamp_ms 가 두 번 있다: scene_index={self.scene_index}"
            raise ValueError(msg)
        if any(keyframe.scene_index != self.scene_index for keyframe in self.keyframes):
            msg = f"다른 scene 의 keyframe 이 섞였다: scene_index={self.scene_index}"
            raise ValueError(msg)
        return self


class FrameExtractionOutput(WireModel):
    """`frame_extraction` 단계의 payload."""

    scenes: Sequence[SceneKeyframesOut] = Field(min_length=1)
    #: 저장한 이미지의 해상도. **원본 그대로다** — 이 단계는 다운스케일하지 않는다.
    #: 후속 OCR 의 bounding box 좌표계이므로 payload 에 싣는다.
    image_width: int = Field(gt=0)
    image_height: int = Field(gt=0)

    @classmethod
    def from_result(
        cls, result: "FrameExtractionResult", storage_keys: Mapping[tuple[int, int], str]
    ) -> "FrameExtractionOutput":
        """단계의 순수 산출물을 와이어 모양으로 옮긴다.

        `storage_keys` 는 `(scene_index, timestamp_ms) → storage_key` 다. 단계 구현은
        파일명만 정하고 접두는 잡 레이어가 붙이므로(계약 §5 의 `outputKeyPrefix`) 키를
        여기서 받는다. `score`·`frame_number` 는 DB 에 자리가 없어 보내지 않는다 —
        집계값은 `metrics` 로 간다.
        """
        return cls(
            scenes=[
                SceneKeyframesOut(
                    scene_index=scene.scene_index,
                    representative_timestamp_ms=scene.representative.timestamp_ms,
                    keyframes=[
                        KeyframeOut(
                            scene_index=keyframe.scene_index,
                            timestamp_ms=keyframe.timestamp_ms,
                            storage_key=storage_keys[(keyframe.scene_index, keyframe.timestamp_ms)],
                        )
                        for keyframe in scene.keyframes
                    ],
                )
                for scene in result.scenes
            ],
            image_width=result.image_width,
            image_height=result.image_height,
        )


class UpstreamKeyframeOut(KeyframeOut):
    """수신용 `KeyframeOut`. 값의 뜻은 같고 미지의 키 정책만 반대다.

    `UpstreamSceneOut` 과 같은 이유다 — 워커가 만든 `frame_extraction` 산출물을 BE 가
    `inputs.upstream` 으로 되돌려 주므로 같은 payload 가 방향에 따라 두 정책을 쓴다.
    """

    model_config = ConfigDict(extra="ignore")


class UpstreamSceneKeyframes(WireResponse):
    """상류가 돌려준 scene 하나의 keyframe 묶음.

    `SceneKeyframesOut` 을 상속하지 않는다. 그쪽의 `_representative_is_first` 는
    **보내기 전 자기 검사**이고, 여기서 같은 검사를 다시 하면 BE 가 순서를 바꿔 보낸
    경우에 이 단계가 `VALIDATION_ERROR` 로 죽는다. OCR 은 대표가 어느 장인지 알 필요가
    없다 — 모든 keyframe 을 읽기 때문이다. 남의 규약을 이 단계의 실패 사유로 삼지 않는다.
    """

    scene_index: int = Field(ge=0)
    keyframes: Sequence[UpstreamKeyframeOut] = Field(min_length=1)


class UpstreamFrameExtraction(WireResponse):
    """`inputs.upstream["frameExtraction"]`. 상류 2단계 산출물이 그대로 돌아온 것이다."""

    scenes: Sequence[UpstreamSceneKeyframes] = Field(min_length=1)
    image_width: int = Field(gt=0)
    image_height: int = Field(gt=0)


class OcrUpstream(WireResponse):
    """`inputs.upstream` 중 `ocr` 이 쓰는 부분.

    `frameExtraction` 이 없으면 이 단계는 무엇을 읽을지 모른다. 빈 결과를 성공으로
    반납하면 "이 영상에는 화면 글자가 없다" 는 거짓이 정본에 남으므로 필수로 둔다
    (`frame_extraction` 이 `sceneDetection` 을 필수로 두는 것과 같은 판단).
    """

    frame_extraction: UpstreamFrameExtraction


class BoundingBoxOut(WireModel):
    """`ocr_observation.bounding_box_json` 에 그대로 들어가는 값.

    **원본 해상도 픽셀 좌표**다. 정규화 좌표(0~1)로 보내지 않는 이유는 이 값의 용도가
    근거 이미지 위에 상자를 그리는 것이고(컬럼 주석), 그 이미지가 곧 `keyframe` 의
    원본 해상도 JPEG 이기 때문이다. 좌표계를 바꾸면 소비자마다 되돌리는 코드를 갖게 된다.
    """

    #: 네 점 다각형. 검출기가 준 순서를 유지한다 — 기울어진 현판·배너에서 축에 나란한
    #: 사각형으로 펴면 실제보다 넓은 영역을 가리킨다.
    points: Sequence[Sequence[float]] = Field(min_length=3)
    x: float = Field(ge=0)
    y: float = Field(ge=0)
    width: float = Field(ge=0)
    height: float = Field(ge=0)


class OcrObservationOut(WireModel):
    """`ocr_observation` 행 하나가 될 값."""

    #: 어느 프레임에서 읽었나. `keyframe_id` 가 아니라 이 쌍으로 말한다 — BE 가
    #: `UNIQUE(scene_id, timestamp_ms)` 로 행을 찾는다(계약 §4.3.2).
    scene_index: int = Field(ge=0)
    timestamp_ms: int = Field(ge=0)
    #: 어느 파일을 읽었는지의 근거. 상류가 준 `keyframe.storage_key` 그대로다.
    storage_key: str = Field(min_length=1)
    #: 읽은 그대로. 교정하거나 정규화한 문자열을 넣지 않는다.
    raw_text: str = Field(min_length=1)
    #: Kiwi 색인 토큰을 공백으로 이은 것. 빈 문자열이 정상일 수 있다(기호만 읽은 경우).
    tokens: str
    #: `numeric(5,4)` 에 맞춰 넷째 자리까지다.
    confidence: float = Field(ge=0, le=1)
    #: `confidence < minConfidence`. 담을 컬럼이 없으므로 BE 는 이 값을 저장하지 않고
    #: `tag_evidence.verification_status` 를 정할 때 쓴다(계약 §4.3.2).
    unverified: bool
    #: 검색 토큰 해시. 원문 일치의 충분조건이나 병합 그룹 ID가 아니다.
    text_key: str = Field(min_length=1)
    bounding_box: BoundingBoxOut


class OcrTextGroupOut(WireModel):
    """관측 배열 안에서만 유효한 참조. DB ID나 합성 confidence를 만들지 않는다."""

    scene_index: int = Field(ge=0)
    observation_indices: Sequence[int] = Field(min_length=1)
    representative_index: int = Field(ge=0)


def _ocr_envelope_fields(result: "OcrResult") -> dict[str, Any]:
    """봉투(v1)와 `ocr_result` 산출물(v2)이 함께 쓰는 부분.

    두 벌로 적어 두면 언젠가 갈라지고, 그때 산출물이 "complete 로 보낸 것" 이 아니게
    된다 — 재현용 문서가 재현하지 못하는 상태다.
    """
    return {
        "observations": [
            OcrObservationOut(
                scene_index=observation.keyframe.scene_index,
                timestamp_ms=observation.keyframe.timestamp_ms,
                storage_key=observation.keyframe.storage_key,
                raw_text=observation.raw_text,
                tokens=observation.tokens_text,
                confidence=observation.confidence,
                unverified=observation.unverified,
                text_key=observation.text_key,
                # 상자 모양을 여기서 다시 조립하지 않는다. `to_json()` 이
                # `ocr_observation.bounding_box_json` 의 컬럼 모양 정본이고
                # `ocr/report.py` 도 그것을 쓴다. 두 벌이면 언젠가 갈라지고,
                # 그때 report 출력과 와이어 payload 가 조용히 달라진다.
                bounding_box=BoundingBoxOut(**observation.box.to_json()),
            )
            for keyframe in result.keyframes
            for observation in keyframe.observations
        ],
        "keyframes_read": len(result.keyframes),
        "min_confidence": result.min_confidence,
    }


class OcrOutput(WireModel):
    """`ocr` 단계 봉투의 payload. `npick.stage.ocr.output/v1` 이다.

    **병합 그룹은 여기 없다.** 계약 §4.3 의 거부 조건 3 이 "`output` 이 선언한
    `outputSchemaVersion` 과 맞지 않으면 거부한다" 이므로, v1 이라 선언하면서 필드를 더
    실을 수 없다. 그룹은 `ocr_result` 산출물(`OcrResultOutput`)이 나른다.

    어느 병합 설정으로 돌렸는지는 `versions.detail.mergeConfigVersion` 과 `stageVersion`
    에 그대로 있으므로, 봉투만 보는 소비자도 재현 조건은 안다 — 모르는 것은 그룹의 내용
    뿐이고 그것은 산출물 참조를 따라가면 있다.
    """

    observations: Sequence[OcrObservationOut]
    #: 읽은 keyframe 수. `observations` 가 비어도 "몇 장을 읽었는지" 는 남아야 한다 —
    #: 0 장을 읽고 0 건을 낸 것과 23 장을 읽고 0 건을 낸 것은 다른 사실이다.
    keyframes_read: int = Field(ge=0)
    #: 판정에 쓴 임계값. 이 값이 없으면 나중에 `unverified` 를 재현할 수 없다.
    min_confidence: float = Field(ge=0, le=1)

    @classmethod
    def from_result(cls, result: "OcrResult") -> "OcrOutput":
        """단계의 순수 산출물을 봉투 모양으로 옮긴다."""
        return OcrOutput(**_ocr_envelope_fields(result))


#: `ocr-result.json` 이 자기 안에 선언하는 스키마. 봉투의 `outputSchemaVersion` 과 다른
#: 값이라 `versions.output_schema_version()` 으로 만들지 않는다.
OCR_RESULT_SCHEMA_VERSION: Final[str] = "npick.stage.ocr.output/v2"


class OcrResultOutput(OcrOutput):
    """`ocr-result.json` 의 `output`. `npick.stage.ocr.output/v2` 다.

    봉투 payload 에 병합 결과를 더한 상위 집합이다. 개별 관측을 평평하게 보존하고
    `text_groups` 가 그 배열의 0-based 인덱스를 참조한다. 대표 문구·confidence·bbox·
    미검증 표시는 대표 관측에서 읽는다. 그룹은 검증 상태를 승격하지 않으며, 전체 배열과
    그룹을 함께 저장해야 참조가 유지된다.
    """

    text_groups: Sequence[OcrTextGroupOut]
    merge_config_version: str = Field(min_length=1)

    @model_validator(mode="after")
    def validate_groups(self) -> "OcrResultOutput":
        seen: set[int] = set()
        for group in self.text_groups:
            members = group.observation_indices
            if group.representative_index not in members:
                raise ValueError("대표 관측이 그룹에 없다")
            timestamps: set[int] = set()
            for index in members:
                if index < 0 or index >= len(self.observations) or index in seen:
                    raise ValueError("관측 참조가 범위를 벗어나거나 중복된다")
                observation = self.observations[index]
                if observation.scene_index != group.scene_index:
                    raise ValueError("다른 scene의 관측을 병합할 수 없다")
                if observation.timestamp_ms in timestamps:
                    raise ValueError("같은 frame의 관측을 병합할 수 없다")
                seen.add(index)
                timestamps.add(observation.timestamp_ms)
            if self.observations[group.representative_index].confidence != max(
                self.observations[index].confidence for index in members
            ):
                raise ValueError("대표 관측은 최대 confidence 관측이어야 한다")
        if seen != set(range(len(self.observations))):
            raise ValueError("병합 결과에서 원본 관측이 누락됐다")
        return self

    @classmethod
    def from_result(cls, result: "OcrResult") -> "OcrResultOutput":
        """봉투 payload 에 병합 결과를 더해 보존 문서 모양으로 옮긴다."""
        return OcrResultOutput(
            **_ocr_envelope_fields(result),
            text_groups=[
                OcrTextGroupOut(
                    scene_index=group.scene_index,
                    observation_indices=group.observation_indices,
                    representative_index=group.representative_index,
                )
                for group in result.text_groups
            ],
            merge_config_version=result.merge_config.version_id,
        )


class VlmMetadataUpstream(WireResponse):
    """`inputs.upstream` 중 `vlm_metadata` 가 쓰는 부분.

    `ocr` 과 같은 상류를 쓴다. 없으면 이 단계는 무엇을 볼지 모르고, 빈 결과를 성공으로
    반납하면 "이 영상에는 설명할 장면이 없다" 는 거짓이 정본에 남으므로 필수로 둔다.
    """

    frame_extraction: UpstreamFrameExtraction


class EvidenceKeyframeOut(WireModel):
    """어느 프레임이 근거인가.

    `keyframe_id` 가 아니라 이 쌍으로 말한다 — TSID 는 BE 가 발급하고 `assignedIds` 는
    scene 만 돌려준다(계약 §4.3). `keyframe` 의 `UNIQUE(scene_id, timestamp_ms)` 가 곧
    이 쌍이다. BE 는 이 값으로 `tag_evidence.source_ref_id` 를 채운다
    (`source_ref_type='keyframe'`).
    """

    scene_index: int = Field(ge=0)
    timestamp_ms: int = Field(ge=0)
    #: 어느 파일을 보았는지의 근거. 참조 키가 아니다(`keyframe.storage_key` 에 인덱스가 없다).
    storage_key: str = Field(min_length=1)


class CaptionOut(WireModel):
    """`scene.caption`·`scene.caption_tokens` 가 될 값."""

    value: str = Field(min_length=1)
    #: Kiwi 색인 토큰을 공백으로 이은 것. 빈 문자열이 정상일 수 있다(내용어 없는 설명).
    #: 색인과 질의가 같은 Kiwi 설정을 써야 하므로 `versions.detail.tokenizer` 가 그
    #: 설정의 식별자를 함께 싣는다(`ocr` 과 같은 규약).
    tokens: str
    confidence: float = Field(ge=0, le=1)
    evidence: Sequence[EvidenceKeyframeOut] = Field(min_length=1)


class ShotTypeOut(WireModel):
    """`scene.shot_type` 이 될 값.

    `evidence` 가 빈 배열일 수 있는 유일한 판단이다 — `unknown` 일 때다. "판단할 근거가
    부족하다" 는 판단에 근거 프레임을 요구할 수는 없다.
    """

    value: Literal["anchor", "interview", "b_roll", "unknown"]
    confidence: float = Field(ge=0, le=1)
    evidence: Sequence[EvidenceKeyframeOut] = ()


class TagCandidateOut(WireModel):
    """태그 후보 하나. **`tag` 행이 아니다.**

    `tag.match_value` 정규화(NFKC + 공백 제거)와 행 생성은 BE 의 몫이고, 저장될 때
    `tag_evidence.source` 는 `vlm`, `verification_status` 는 `unverified` 다
    ([docs/frd.md](../frd.md):158). 날짜 유형(`filmed_date`·`broadcast_date`)은 이
    payload 에 **올 수 없다** — 워커 쪽 schema 에 그 유형이 없다.
    """

    type: Literal[
        "person",
        "organization",
        "location",
        "facility",
        "keyword",
        "event",
        "season",
        "weather",
        "scene_type",
    ]
    value: str = Field(min_length=1)
    confidence: float = Field(ge=0, le=1)
    evidence: Sequence[EvidenceKeyframeOut] = Field(min_length=1)


class SceneMetadataOut(WireModel):
    """scene 하나의 metadata.

    `caption` 이 `null` 이고 `tagCandidates` 가 빈 배열일 수 있다. 둘 다 정상이다 —
    시각 근거가 없는 값을 지어내지 않는 것이 이 단계의 계약이다.
    """

    scene_index: int = Field(ge=0)
    shot_type: ShotTypeOut
    caption: CaptionOut | None = None
    tag_candidates: Sequence[TagCandidateOut] = ()


class VlmMetadataOutput(WireModel):
    """`vlm_metadata` 단계의 payload.

    `ocr` 과 달리 scene 으로 묶어 보낸다. 이 결과의 저장 자리가 `scene` 행의 컬럼
    (`caption`·`shot_type`)이라 BE 가 scene 단위로 쓰기 때문이다 — 평평하게 보내면
    받는 쪽이 다시 묶어야 한다.
    """

    scenes: Sequence[SceneMetadataOut] = Field(min_length=1)
    #: 모델 출력 계약의 버전. `versions.outputSchemaVersion`(payload 형식)과 다른 값이다 —
    #: 이쪽은 **모델에게 요구한 JSON** 의 버전이고, 프롬프트를 고치지 않고도 바뀔 수 있다.
    metadata_schema_version: str = Field(min_length=1)

    @classmethod
    def from_result(cls, result: "VlmResult") -> "VlmMetadataOutput":
        """단계의 순수 산출물을 와이어 모양으로 옮긴다."""
        return cls(
            scenes=[
                SceneMetadataOut(
                    scene_index=scene.scene_index,
                    shot_type=ShotTypeOut(
                        value=scene.shot_type.value,
                        confidence=scene.shot_type.confidence,
                        evidence=_evidence(scene.shot_type.evidence),
                    ),
                    caption=(
                        None
                        if scene.caption is None
                        else CaptionOut(
                            value=scene.caption.value,
                            tokens=scene.caption.tokens_text,
                            confidence=scene.caption.confidence,
                            evidence=_evidence(scene.caption.evidence),
                        )
                    ),
                    tag_candidates=[
                        TagCandidateOut(
                            type=tag.type,
                            value=tag.value,
                            confidence=tag.confidence,
                            evidence=_evidence(tag.evidence),
                        )
                        for tag in scene.tag_candidates
                    ],
                )
                for scene in result.scenes
            ],
            metadata_schema_version=result.schema_version,
        )


def _evidence(keyframes: Sequence["VlmKeyframeRef"]) -> list[EvidenceKeyframeOut]:
    """근거 keyframe 을 와이어 모양으로. 조립을 한 곳에 둔다."""
    return [
        EvidenceKeyframeOut(
            scene_index=keyframe.scene_index,
            timestamp_ms=keyframe.timestamp_ms,
            storage_key=keyframe.storage_key,
        )
        for keyframe in keyframes
    ]
