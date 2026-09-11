# 인터페이스 계약 — 잡 API (서비스 서버 ↔ 파이프라인 워커)

> **문서 유형** 인터페이스 계약 · **상태** 초안 (S15P21A501-70)
> **소유** BE·AI 공동. 바꾸려면 양쪽 티켓이 함께 필요하다.
> **기준 문서** [docs/frd.md](../frd.md) (FRD v3.2, 정본) · [docs/prd.md](../prd.md) · [02-container.md](../architecture/02-container.md) · [03-deployment.md](../architecture/03-deployment.md)
> **단계 목록 정본** `ai/src/npick_worker/stages.py` — 이 문서는 단계 이름·순서·치명 여부를 다시 정의하지 않는다.
> **공용 규약** [README.md](README.md) — 버전 필드 형식과 오류 코드 접두.

## 1. 방향과 전제

**워커가 항상 발신자다.** 서비스 서버가 잡 API를 노출하고 워커가 long-poll한다. 워커는 DB에 직접 접속하지 않는다([02-container.md](../architecture/02-container.md)).

이 방향이 무엇을 사는지가 중요하다. GPU 파드에 인바운드 포트를 열 필요가 없고, 파드 IP가 바뀌어도 아무것도 고칠 필요가 없으며, 파드가 죽거나 요금 절약을 위해 내려가도 lease 만료로 회수되므로 잡이 유실되지 않는다([03-deployment.md](../architecture/03-deployment.md)). 워커의 HTTP 표면은 `/health` 뿐이고 **잡을 받는 인바운드 엔드포인트를 만들지 않는다.**

## 2. 잡의 단위는 스테이지다

run 전체가 아니라 단계 하나가 배정 단위다. 근거 셋:

- "스테이지 단위로 산출물이 반납되므로 재시도가 성공한 스테이지를 다시 실행하지 않는다"([03-deployment.md](../architecture/03-deployment.md)).
- `pipeline_run.stage_states_json`의 단위가 단계이고 단계별 `attempts`를 센다.
- `infra/compose/profiles/pipeline.yml`의 retry·timeout·concurrency 노브가 전부 단계별이고, CPU/GPU 배치 분리는 단계 단위 배정 없이 성립하지 않는다.

왕복 비용은 `complete` 응답의 `next`에 다음 배정을 실어 상쇄한다. 클립 하나에 왕복 11회가 아니라 claim 1회 + complete 10회다.

## 3. 공통 규약

**베이스 경로** `/api/v1/internal/jobs`. `internal` 세그먼트로 BE가 `SecurityConfig`의 필터 체인을 분리한다.

**응답 봉투** claim·heartbeat·complete는 `ApiResponse`를 쓴다.

```json
{ "isSuccess": true, "code": "COMM_200", "message": "OK",
  "timestamp": "2026-09-07T09:20:19Z", "path": "POST /api/v1/internal/jobs/claim",
  "data": { } }
```

`NON_NULL`이라 `data`가 없을 수 있다. 아래 스키마는 전부 `data`의 값만 적는다. artifacts의 성공 경로만 봉투를 면제한다 — 바이트 전송이라 봉투가 부적절하고, `backend/docs/ddd-package-architecture.md`가 이미 그 예외를 허용한다. **실패 응답은 artifacts를 포함해 전 경로가 봉투를 쓴다**(BE의 `GlobalExceptionHandler`가 만들기 때문에 자동으로 그렇게 된다).

**표기**

| 항목 | 규약 | 이유 |
| --- | --- | --- |
| 필드 이름 | camelCase | Jackson 기본값 |
| ID | **문자열** | TSID bigint가 JS 안전 정수를 넘는다 |
| 시각 | RFC3339 UTC | |
| 시간 길이 | `…Ms` 정수 | |
| 모르는 필드 | 무시한다 | BE가 필드를 늘려도 워커가 죽으면 안 된다 |

**인증** fleet별 정적 Bearer 토큰.

```
Authorization: Bearer <32바이트 이상 랜덤>
X-Worker-Id: runpod-a40-01
```

mTLS·JWT를 고르지 않은 이유는 사람이 아닌 클라이언트 한 종류뿐이기 때문이다. IP 허용 목록은 쓸 수 없다 — 파드 IP가 바뀌어도 고칠 필요가 없다는 게 이 구조의 이점인데 그걸 버리게 된다.

BE는 토큰을 쉼표로 구분해 여러 개 받는다. 회전 중 두 토큰이 동시에 유효해야 무중단 교체가 된다. 비교는 상수 시간으로 한다.

**fleet 격리** — "개발 검증 산출물이 운영 정본에 섞이지 않도록 격리 방법이 필요하다"([03-deployment.md](../architecture/03-deployment.md)의 미결)에 대한 이 계약의 답은 **fleet마다 토큰을 다르게 발급하고 운영 BE는 운영 토큰만 아는 것**이다. 스키마 추가가 필요 없다. claim의 `worker.fleet`는 진단용이며 토큰과 어긋나면 `JOB_403_002`.

## 4. 엔드포인트

```
POST /api/v1/internal/jobs/claim
POST /api/v1/internal/jobs/{runId}/stages/{stage}/heartbeat
POST /api/v1/internal/jobs/{runId}/stages/{stage}/complete
GET  /api/v1/internal/jobs/{runId}/artifacts?key={storageKey}
PUT  /api/v1/internal/jobs/{runId}/artifacts/{storageKey}
```

### 4.1 claim — 롱폴 배정

배정할 잡이 없으면 BE가 요청을 최대 **25초** 붙잡는다. nginx `proxy_read_timeout` 기본 60초와 RunPod egress NAT 유휴 타임아웃 아래로 확실히 들어가는 값이다. BE는 대기 중 DB 커넥션을 붙잡지 않는다.

**대기 만료는 204가 아니라 200 + `assigned: false`다.** 204는 본문을 금지하므로 `retryAfterMs`나 `revokedLeases`를 실을 수 없고, 워커가 상태 코드와 JSON 두 갈래로 분기해야 한다.

요청:

```json
{
  "worker": {
    "workerId": "runpod-a40-01",
    "instanceId": "0f3c9a7e6f2a4a4b9a6e7a1f5c2d8b10",
    "fleet": "prod",
    "workerVersion": "0.1.0",
    "maxConcurrentStages": 1,
    "sharedMediaVolume": false
  },
  "capabilities": [
    { "stage": "scene_detection", "stageVersion": "npick.stage.scene_detection/v1:3ab4bebe" }
  ],
  "device": {
    "kind": "cuda", "gpuModel": "NVIDIA A40", "gpuCount": 1, "vramMb": 46068,
    "cudaVersion": "12.4", "driverVersion": "550.90.07", "torchVersion": "2.5.1"
  },
  "waitSeconds": 25,
  "heldLeases": []
}
```

- `capabilities`는 **워커가 실행할 수 있는 단계와 그 단계의 실제 버전**이다. BE는 이 목록에 없는 단계를 배정하지 않는다. 이것이 `infra/compose/profiles/pipeline.yml`의 `placement.cpu_worker_stages` / `gpu_server_stages`를 채우는 방식이다 — 정적 목록 대신 워커가 선언한다. CPU 워커와 GPU 파드가 같은 이미지를 쓰므로 배치는 설정이 아니라 능력의 문제다.
- `device.gpuModel`은 **필수**다. 성능 수치에 GPU 모델을 기록하지 않으면 benchmark profile이 성립하지 않는다([03-deployment.md](../architecture/03-deployment.md)).
- `heldLeases`는 워커가 아직 살아 있다고 믿는 lease다. BE는 이미 회수한 것을 `revokedLeases`로 알려 준다 — 파드가 네트워크 단절에서 복귀했을 때 좀비 작업을 즉시 끊는다.

- `waitSeconds`는 0~25 정수다. BE와 워커 설정 모두 이 범위를 검사한다.
- BE가 모르는 capability와 정본 저장 어댑터가 지원하지 않는 단계는 배정 후보에서 제외한다.
  나머지 capability는 계속 사용할 수 있다. 빈 이름·버전과 중복 단계 선언은 잘못된 요청이다.
  저장 어댑터의 `StageOutputPort.supports`와 `validateAndStore`를 함께 구현해야 해당 단계가 배정된다.

응답 (배정 있음, 200):

```json
{
  "assigned": true,
  "revokedLeases": [],
  "lease": {
    "leaseId": "7d1f4e0c-3a55-4a10-8f60-2e1b9c0d4a77",
    "leaseUntil": "2026-09-07T09:21:19Z",
    "heartbeatIntervalMs": 10000
  },
  "job": {
    "pipelineRunId": "398021847361024",
    "clipId": "398021840012345",
    "processingNo": 2,
    "stage": "scene_detection",
    "attempt": 1,
    "maxAttempts": 1,
    "idempotencyKey": "398021847361024:scene_detection:1",
    "pipelineVersion": "npick-pipeline/v1:64960bae4565",
    "expectedStageVersion": "npick.stage.scene_detection/v1:3ab4bebe",
    "outputSchemaVersion": "npick.stage.scene_detection.output/v1",
    "outputKeyPrefix": "runs/398021847361024/scene_detection/a1/",
    "deadlineAt": null,
    "inputs": {
      "media": {
        "storageKey": "clips/398021840012345/source.mp4",
        "contentHash": "3f1e…",
        "sizeBytes": 734003200,
        "transport": "shared-volume",
        "url": null,
        "localPath": "clips/398021840012345/source.mp4"
      },
      "upstream": {},
      "config": {}
    }
  }
}
```

- `deadlineAt: null` = 단계 타임아웃 미동결(`pipeline.yml`의 `timeout_seconds: null`). 워커는 `null`이면 자체 상한을 쓰지 않는다.
- `maxAttempts: 1` — `retry_count`가 `null`인 동안의 고정 해석이다. "무한 재시도하지 않는다"([docs/frd.md](../frd.md) §6 F-14)와 "실측 없이 숫자를 만들지 않는다"를 동시에 지키는 값이 자동 재시도 0회다.
- `inputs.upstream`에는 상류 단계 산출물 중 1 MiB 이하의 구조화 데이터를 인라인한다. 워커는 DB에 접속하지 않으므로 BE가 되돌려 줘야 한다. 넘으면 artifact key로 대체한다.
- **`contentHash`는 소문자 hex, 접두 없음** (`3f1e…`, `sha256:3f1e…`가 아니다). 워커는 받을 때 대소문자·접두·공백을 정규화하지만 발신 형식은 이 하나로 고정한다. 인코딩을 못 박지 않으면 Java의 `String.format("%02X")` 하나로 **전 잡이 영구 실패한다** — 불일치는 `UNSUPPORTED_MEDIA`(영구)이고 §9.2가 영구를 재claim 없음으로 두므로, 치명 단계인 `scene_detection`이 이걸로 죽으면 run이 재시도 없이 `failed`가 된다.
- **`contentHash`·`sizeBytes` 중 최소 하나는 필수다.** 둘 다 비면 워커가 잘린 다운로드를 걸러낼 수단이 없다. 프록시 타임아웃이나 BE가 스트림 중간에 죽는 경우처럼 오류 없이 일찍 끝난 응답은 부분 파일을 남기고 정상 해석되며, 그 뒤 단계가 **틀린 결과를 `succeeded`로 정본에 넣는다.** 워커는 크기 불일치를 `MEDIA_UNAVAILABLE`(일시), 해시 불일치를 `UNSUPPORTED_MEDIA`(영구)로 보고한다.
- `inputs.media.url`은 **워커가 무시한다.** `transport: "http"`는 언제나 §4.4의 `GET …/artifacts?key=`로 간다. 별도 URL을 실어도 그 경로로 가지 않는다.

응답 (배정 없음, 200):

```json
{ "assigned": false, "revokedLeases": [], "retryAfterMs": 0 }
```

`retryAfterMs`는 BE가 과부하일 때만 0보다 크다. **평시 빈 응답에 워커는 쉬지 않고 즉시 다시 claim한다** — 대기는 이미 서버가 25초 했다.

### 4.2 heartbeat — lease 연장과 중단 지시

주기는 claim이 준 `heartbeatIntervalMs`(10초). lease TTL은 60초이고 성공한 heartbeat 하나가 `leaseUntil`을 `now + 60s`로 민다. **연장하는 것은 heartbeat뿐이다** — artifacts 업로드는 lease를 연장하지 않으므로, 업로드가 오래 걸리는 동안에도 워커는 별도로 heartbeat를 쳐야 한다.

요청:

```json
{ "leaseId": "7d1f4e0c-…", "elapsedMs": 31200,
  "progress": { "phase": "decoding", "percent": 42, "note": null },
  "metrics": { "gpuUtilPercent": 91, "vramUsedMb": 18240 } }
```

응답 (200):

```json
{ "command": "continue", "leaseUntil": "2026-09-07T09:22:19Z", "abortReason": null }
```

`command` ∈ `continue | abort`. `abortReason` ∈ `RUN_CANCELLED | SUPERSEDED | STAGE_ALREADY_COMPLETED | OPERATOR_REVOKED`.

**lease가 이미 회수돼 재배정된 경우는 200이 아니라 409 `JOB_409_002`다.** `leaseId`가 fencing token이므로 같은 lease로 오는 이후의 `complete`와 `artifacts`도 전부 같은 코드로 거절된다. 좀비 워커가 뒤늦게 결과를 던져 두 번째 `scene` 뭉치를 만드는 경로가 여기서 막힌다.

**협조적 취소의 한계 (알려진 제약).** `abort`를 받아도 워커는 실행 중인 단계를 중단시키지 못한다. 단계는 취소 콜백이 없는 순수 함수이고([ai/AGENTS.md](../../ai/AGENTS.md)), 콜백을 넣는 순간 단계 패키지 안에 pipeline 배선이 생긴다.

워커가 하는 일은 **결과를 버리는 것**이다. heartbeat를 멈추고, 단계가 끝나면 그 결과를 폐기하며, **`complete`를 보내지 않는다.** BE는 lease 만료로 회수한다. `abort`와 heartbeat의 `JOB_409_002`(회수) 양쪽 모두 같다. 반납해 버리면 BE가 이미 다른 워커에 재배정한 단계 위에 덮어쓰게 된다. 단계를 실제로 즉시 멈추는 협조적 취소는 별도 티켓이다.

### 4.3 complete — 스테이지 결과 반납

성공·실패·생략을 한 엔드포인트가 다 받는다. `stage_states_json`을 바꾸는 쓰기 경로가 하나여야 fencing과 멱등성 검사가 한 벌로 끝난다.

```
POST /api/v1/internal/jobs/{runId}/stages/{stage}/complete
Idempotency-Key: 398021847361024:scene_detection:1
```

요청 — 성공 (`scene_detection`의 실제 산출물):

```json
{
  "envelopeVersion": "stage-result/v1",
  "leaseId": "7d1f4e0c-…",
  "idempotencyKey": "398021847361024:scene_detection:1",
  "stage": "scene_detection",
  "attempt": 1,
  "status": "succeeded",
  "startedAt": "2026-09-07T09:20:19Z",
  "finishedAt": "2026-09-07T09:21:00.230Z",
  "durationMs": 41230,
  "versions": {
    "stageVersion": "npick.stage.scene_detection/v1:3ab4bebe",
    "outputSchemaVersion": "npick.stage.scene_detection.output/v1",
    "configVersion": "scene-detect/v1:20dfc0a6",
    "modelVersion": null,
    "promptVersion": null,
    "detail": { "detector": "content", "engine": "pyscenedetect", "engineVersion": "0.7.1" },
    "runtime": { "worker": "0.1.0", "python": "3.12.7", "torch": null, "cuda": null }
  },
  "metrics": { "scenes": 87 },
  "output": {
    "scenes": [
      { "sceneIndex": 0, "startTimeMs": 0, "endTimeMs": 4200 },
      { "sceneIndex": 1, "startTimeMs": 4200, "endTimeMs": 9970 }
    ],
    "mediaDurationMs": 812400,
    "frameRate": 29.97
  },
  "artifacts": [],
  "warnings": [],
  "error": null
}
```

**`durationMs`는 처리 시간이고 클립 길이는 `output.mediaDurationMs`다.** 워커 쪽 `SceneDetectionResult.duration_ms`는 클립 길이인데 요구사항의 "처리 시간"도 같은 이름을 원한다. 계약에서 이름을 갈라 두지 않으면 양쪽이 조용히 반대 숫자를 넣는다. 처리 시간은 단조 시계로 잰다 — 벽시계는 NTP 보정에 흔들린다.

**모델도 프롬프트도 없는 단계**(`scene_detection`이 그렇다)는 `modelVersion`·`promptVersion`의 **키를 넣고 값만 `null`**로 둔다. 생략도 `"n/a"`도 금지다. "이 단계엔 모델이 없다"와 "보고를 빠뜨렸다"는 다르고, 구분이 사라지면 나중에 어느 쪽인지 알 방법이 없다.

요청 — 실패·생략:

```json
{
  "envelopeVersion": "stage-result/v1",
  "leaseId": "…", "idempotencyKey": "398021847361024:asr:1",
  "stage": "asr", "attempt": 1, "status": "skipped",
  "startedAt": "…", "finishedAt": "…", "durationMs": 3,
  "versions": { "stageVersion": "unknown",
                "outputSchemaVersion": "npick.stage.asr.output/v1",
                "configVersion": null, "modelVersion": null, "promptVersion": null,
                "detail": {}, "runtime": null },
  "output": null,
  "error": { "code": "NO_ADAPTER", "retryable": false,
             "message": "이 워커에 구현이 없다: asr", "detail": {} }
}
```

단계를 돌리지 못했으면 `stageVersion`에 `"unknown"`을 넣는다. 그럴듯한 값을 채우면 "이 버전으로 돌렸는데 실패했다"는 잘못된 기록이 남는다.

`error.retryable`은 **워커의 신고이고 판정 권한은 BE에 있다.** 버그 있는 워커가 영구 오류를 영원히 재시도시키면 "무한 재시도하지 않는다"가 깨진다.

**BE의 거부 조건 (400 `JOB_400_001`)** — "AI 출력은 정해진 형식으로 검사하고, 형식이 잘못된 출력을 정상 데이터에 부분 적용하지 않는다"([docs/frd.md](../frd.md) §3 F-03)는 **스테이지 결과 전체를 거부하라**는 뜻이다. 반쯤 적용하지 않는다.

1. `envelopeVersion`이 아는 값이 아니다
2. `versions.stageVersion` 또는 `versions.outputSchemaVersion`이 없거나 빈 문자열이다
3. `output`이 선언한 `outputSchemaVersion`과 맞지 않는다
4. `status: succeeded`인데 `output`이 비었거나, `failed`/`skipped`인데 `error`가 없다

응답 (200):

```json
{
  "accepted": true, "duplicate": false, "runStatus": "running",
  "stageState": { "status": "succeeded", "attempts": 1 },
  "assignedIds": { "scenes": [ { "sceneIndex": 0, "sceneId": "398021849900001" } ] },
  "next": null
}
```

`next`가 있으면 워커는 **claim을 다시 하지 않고 이어서 실행한다.** 없으면 체인이 끝나고 다시 claim으로 돌아간다. 체인 중 한 단계가 실패해도 그 단계 결과는 정상적으로 반납하며, 그 응답에 `next`가 없으면 거기서 끝난다.

`assignedIds`가 필요한 이유는 `scene` 테이블에 `scene_index` 컬럼이 없기 때문이다. 워커는 순번으로 보내고 BE가 TSID를 발급하며, 그 대응을 돌려줘야 다음 단계가 진짜 `sceneId`로 작업한다. 컬럼을 추가하지 않고 해결된다.

### 4.3.1 `frame_extraction` — keyframe과 대표 이미지

`scene_detection`과 달리 이 단계는 **파일을 올린다.** 그래서 규약이 세 겹이다 — 입력(상류 산출물), 산출물 키, 그리고 순서.

**입력** — `inputs.upstream`에 상류 1단계 산출물을 인라인한다. 워커는 DB에 접속하지 않으므로 BE가 되돌려 줘야 한다. 키 이름은 스테이지 이름의 camelCase다.

```json
"inputs": {
  "media": { "storageKey": "clips/398021840012345/source.mp4", "transport": "shared-volume" },
  "upstream": {
    "sceneDetection": {
      "scenes": [ { "sceneIndex": 0, "startTimeMs": 0, "endTimeMs": 4200 } ],
      "mediaDurationMs": 812400,
      "frameRate": 29.97
    }
  },
  "config": {}
}
```

`sceneDetection`이 없으면 워커는 `VALIDATION_ERROR`(영구)로 실패를 신고한다. 이 단계는 상류 없이 빈 결과를 내지 않는다 — 그러면 치명 단계가 성공으로 기록되고 keyframe이 0장인 run이 생긴다. 인라인 상한(1 MiB)을 넘으면 §5의 artifact key로 대체한다. 장면 2,000개가 이 형식으로 약 200 KiB이므로 실제로 넘지 않는다.

**산출물 키** — `outputKeyPrefix` 아래 `s{sceneIndex:04d}/kf-{timestampMs:09d}.jpg`다. 업로드 `Content-Type`은 `image/jpeg` 고정이다(§4.4). 이 단계는 mjpeg으로만 인코딩하므로 확장자와 헤더가 갈리지 않는다 — BE가 검증을 붙일 때 이 값을 쓴다.

```
runs/398021847361024/frame_extraction/a1/s0000/kf-000004200.jpg
```

`timestampMs`는 **저장된 프레임의 정규 시각**이고 그대로 `keyframe.timestamp_ms`가 된다. 후보를 계획할 때 쓴 목표 시각이 아니다 — 두 값을 섞으면 `UNIQUE(scene_id, timestamp_ms)`가 서로 다른 ms를 가진 같은 프레임 두 장을 허용하고, OCR이 같은 화면을 두 번 읽는다. scene 번호를 디렉터리로 나누는 이유는 운영상의 것이다(장면 수백 개에 장 수를 곱한 파일이 한 디렉터리에 평평하게 쌓이면 눈으로 뒤질 수 없다).

**결과** — `artifacts[].kind`는 `keyframe`이다. `keyframe.storage_key`와 같은 어휘를 쓰고 새 식별자를 만들지 않는다.

```json
{
  "stage": "frame_extraction",
  "status": "succeeded",
  "versions": {
    "stageVersion": "npick.stage.frame_extraction/v1:595427d7",
    "outputSchemaVersion": "npick.stage.frame_extraction.output/v1",
    "configVersion": "frame-extract/v1:5b266b10",
    "modelVersion": null,
    "promptVersion": null,
    "detail": { "engine": "pyav", "engineVersion": "18.1.0+numpy2.5.2" },
    "runtime": { "worker": "0.1.0", "python": "3.12.7", "torch": null, "cuda": null }
  },
  "metrics": {
    "scenes": 87, "keyframes": 214, "blankKeyframes": 0,
    "bytes": 71340032, "imageWidth": 1920, "imageHeight": 1080
  },
  "output": {
    "scenes": [
      {
        "sceneIndex": 0,
        "representativeTimestampMs": 4200,
        "keyframes": [
          { "sceneIndex": 0, "timestampMs": 4200, "storageKey": "runs/…/a1/s0000/kf-000004200.jpg" },
          { "sceneIndex": 0, "timestampMs": 1100, "storageKey": "runs/…/a1/s0000/kf-000001100.jpg" },
          { "sceneIndex": 0, "timestampMs": 7300, "storageKey": "runs/…/a1/s0000/kf-000007300.jpg" }
        ]
      }
    ],
    "imageWidth": 1920,
    "imageHeight": 1080
  }
}
```

위 `metrics`의 수치는 형식을 보이기 위한 예시다. 실제 장당 크기는 기본 `jpeg_qscale = 2`에서 1080p 약 420 KiB이므로(`ai/docs/frame-extraction.md` §4 실측) 214장이면 88 MiB 급이다. 용량을 어림할 때는 예시 숫자가 아니라 그 값을 쓴다.

**대표 이미지는 목록의 첫 원소다. BE는 이 순서대로 INSERT한다.** `keyframe` 테이블에 대표를 표시할 컬럼이 없고(`keyframe_id`·`scene_id`·`timestamp_ms`·`storage_key`가 전부다) ERD 주석이 "결과 목록의 대표 이미지는 첫 장을 쓴다"로 두었기 때문에, 대표는 플래그가 아니라 **순서**로 전달된다. 이 순서대로 넣으면 대표가 그 scene의 최소 `keyframe_id`가 되고, 결과 카드는 `ORDER BY keyframe_id LIMIT 1`로 대표를 얻는다.

`timestamp_ms` 순으로 정렬해 저장하면 이 규약이 조용히 깨진다 — 대표는 선명도로 뽑히므로 시각이 가장 이르지 않다. 그래서 `representativeTimestampMs`를 함께 싣는다. BE는 저장 직전에 `keyframes[0].timestampMs`와 대조해 어긋나면 `JOB_400_001`로 거부한다. 워커도 보내기 전에 같은 검사를 한다.

**장 수** — `scenes[].keyframes`는 최소 1개다. 다만 **1개가 정상인 경우는 하나뿐이다**: 그 scene 구간에 정규 시각이 들어오는 프레임이 한 장뿐인 경우다. 그 밖의 부족은 성공으로 반납되지 않고 `VALIDATION_ERROR`(영구)로 실패한다. 구간이 미디어 끝을 넘으면 뒤쪽 슬롯의 후보가 디코드에 닿지 못해 앞쪽만 살아 한 장이 되는데, 그건 상류 scene 목록이 이 미디어의 것이 아니라는 신호이므로 적은 장 수로 통과시키지 않는다. **BE는 "장 수가 줄어든 성공"을 처리할 필요가 없다** — 그런 결과는 오지 않는다. 이 보증은 워커가 슬롯 수의 상한을 창의 ms가 아니라 **구간의 프레임 수**로 두는 데 기댄다. 그래서 프레임이 2장 이상인 구간은 항상 2장 이상을 낸다.

**남은 어긋남 — `stages.py`의 "thumbnail"과 축소본의 자리.** 단계 표는 2단계 필수 출력을 "복수 keyframe·thumbnail"로 적고 FRD §3 F-03은 "축소된 대표 이미지 대신 원본 해상도의 프레임"이라 쓰므로 축소본의 존재를 전제한다. 그런데 **축소본 경로를 담을 컬럼이 스키마에 없다.** 이번 구현은 축소본 파일을 만들지 않고 keyframe을 원본 해상도로만 저장한다. 근거는 둘이다 — 작은 글자 OCR이 요구하는 것이 원본 해상도 프레임이고(그것이 이 자산의 1차 소비자다), 결과 카드용 축소는 ID 기반 조회 응답에서 만들 수 있어 저장이 필요 없다. **컬럼을 새로 만들지 않았으므로 BE는 대표 keyframe을 축소해 카드에 제공한다.** 이 판단을 바꾸려면 스키마가 먼저 바뀌어야 하므로 여기 적어 둔다.

**순서** — 워커는 `complete` **전에** 모든 keyframe을 올린다. 반납 뒤로 미루면 BE가 keyframe 행을 만든 뒤에 파일이 올라가고, 그 사이 조회는 없는 파일을 가리킨다. 업로드는 lease를 연장하지 않으므로(§4.2) 워커는 올리는 동안에도 heartbeat를 계속 친다. 중단 지시(`abort`)를 받았으면 올리지 않고 결과를 버리며, **전송 도중에 받으면 남은 파일을 올리지 않는다** — 그래서 abort된 attempt의 접두 아래에는 파일이 일부만 남을 수 있다. `complete`가 오지 않았으므로 그 접두는 어느 keyframe 행도 가리키지 않는다.

**오류 코드** — 이 단계 전용 코드를 만들지 않는다. §9.2의 어휘로 충분하다: 상류 산출물·산출물 키가 잘못됐으면 `VALIDATION_ERROR`(영구), 영상을 열 수 없으면 `UNSUPPORTED_MEDIA`(영구), 업로드가 실패하면 `ARTIFACT_UPLOAD_FAILED`(일시), 나머지는 `STAGE_FAILED`(일시)다.

**재처리** — 재시도(attempt N+1)는 새 `outputKeyPrefix`를 받으므로 실패한 attempt N의 JPEG이 성공 결과와 섞이지 않는다. 같은 attempt의 중복 반납은 §8의 세 겹이 막는다. 워커 쪽 몫은 결정론이다 — 같은 입력과 같은 재현 튜플이면 같은 프레임을 고르고 같은 바이트를 쓴다.

### 4.3.2 `ocr` — 화면 글자 관측

`frame_extraction`과 달리 이 단계는 **파일을 읽는다.** 올리지 않고 받는다.

**입력** — `inputs.upstream`에 상류 2단계 산출물을 인라인한다. 키 이름은 스테이지 이름의 camelCase다.

```json
"inputs": {
  "media": { "storageKey": "clips/398021840012345/source.mp4", "transport": "shared-volume" },
  "upstream": {
    "frameExtraction": {
      "scenes": [
        { "sceneIndex": 0, "representativeTimestampMs": 4200,
          "keyframes": [ { "sceneIndex": 0, "timestampMs": 4200,
                           "storageKey": "runs/…/frame_extraction/a1/s0000/kf-000004200.jpg" } ] }
      ],
      "imageWidth": 1920, "imageHeight": 1080
    }
  },
  "config": {}
}
```

`frameExtraction`이 없으면 워커는 `VALIDATION_ERROR`(영구)로 실패를 신고한다. 이 단계는 상류 없이 빈 결과를 내지 않는다 — 그러면 "이 영상에는 화면 글자가 없다"는 거짓이 정본에 기록된다. keyframe 214장이 이 형식으로 약 40 KiB이므로 인라인 상한(1 MiB)에 걸리지 않는다.

**`representativeTimestampMs`를 검사하지 않는다.** 그것은 `frame_extraction`이 보낼 때의 자기 검사이고 BE의 저장 규약이다(§4.3.1). OCR은 모든 keyframe을 읽으므로 대표가 어느 장인지 알 필요가 없고, 여기서 같은 검사를 다시 하면 남의 규약이 이 단계의 실패 사유가 된다.

**바이트를 어떻게 받는가** — `inputs.media.transport`가 그대로 적용된다(§5). `shared-volume`이면 마운트에서 바로 열고 복사하지 않는다. `http`면 §4.4의 `GET …/artifacts?key=`로 keyframe마다 한 번씩 받는다. 워커는 **읽는 동안에도 heartbeat를 계속 친다** — 다운로드는 lease를 연장하지 않는다(§4.2).

**`inputs.media`의 원본 영상은 받지 않는다.** 이 단계가 여는 것은 상류 keyframe뿐이다. `storageKey`는 오류 메시지와 기록에만 쓴다. BE는 이 단계에도 `inputs.media`를 평소대로 실어 보내면 되고(§4.1의 모양은 단계마다 같다), 달라지는 것은 워커가 `http`에서 원본을 내려받지 않는다는 것뿐이다.

**빈 구멍** — `keyframes[]` 항목에는 `contentHash`도 `sizeBytes`도 없다(§4.3.1의 출력 모양). 그래서 이 단계는 §4.1의 미디어처럼 잘린 다운로드를 걸러내지 못한다. 깨진 JPEG은 이미지로 열리지 않아 `UNSUPPORTED_MEDIA`로 드러나는 데 그친다. BE가 상류 산출물에 해시를 실어 주면 막을 수 있고, 그때 이 문단을 지운다.

**결과** — 이 단계는 `artifacts`를 만들지 않는다. 관측은 전부 `output`으로 간다.

```json
{
  "stage": "ocr",
  "status": "succeeded",
  "versions": {
    "stageVersion": "npick.stage.ocr/v1:449d6928",
    "outputSchemaVersion": "npick.stage.ocr.output/v1",
    "configVersion": "ocr/v1:daaf4c83",
    "modelVersion": "rapidocr/rapidocr3.9.2+onnxruntime1.29.0",
    "promptVersion": null,
    "detail": {
      "engine": "rapidocr",
      "engineVersion": "rapidocr3.9.2+onnxruntime1.29.0",
      "tokenizer": "query-norm/v1:b0d96c0c:kiwi0.23.2:model0.23.0"
    },
    "runtime": { "worker": "0.1.0", "python": "3.12.14", "torch": null, "cuda": null }
  },
  "metrics": {
    "keyframes": 23, "observations": 44,
    "unverifiedObservations": 12, "textGroups": 35, "minConfidence": 0.7
  },
  "output": {
    "observations": [
      {
        "sceneIndex": 8,
        "timestampMs": 71833,
        "storageKey": "runs/…/frame_extraction/a1/s0008/kf-000071833.jpg",
        "rawText": "강원도 대표 볼거리관",
        "tokens": "강원도 대표 볼거리 관",
        "confidence": 0.9981,
        "unverified": false,
        "textKey": "3f1ea70c9b21",
        "boundingBox": {
          "points": [[262, 96], [531, 96], [531, 176], [262, 176]],
          "x": 262, "y": 96, "width": 269, "height": 80
        }
      }
    ],
    "keyframesRead": 23,
    "minConfidence": 0.7
  },
  "artifacts": []
}
```

**keyframe은 `(sceneIndex, timestampMs)`로 가리킨다.** `ocr_observation.keyframe_id`는 TSID이고 그것을 발급하는 쪽은 BE인데, `complete` 응답의 `assignedIds`는 scene만 돌려준다(§4.3). `keyframe`에 `UNIQUE(scene_id, timestamp_ms)`가 있으므로 이 쌍이 곧 그 행이다 — **스키마도 `assignedIds`도 늘리지 않고 닫힌다.** `storageKey`는 어느 파일을 읽었는지의 근거로 함께 싣는 값이지 참조 키가 아니다(`keyframe.storage_key`에 인덱스가 없다).

**`tokens`는 워커가 만든다.** BE에 Kiwi가 없고 [02-container.md](../architecture/02-container.md)가 "워커가 Kiwi로 토큰화한 결과를 별도 컬럼에 넣고 `pdb.whitespace` 토크나이저로 색인한다"로 정했다. 공백으로 이어진 문자열이 그대로 `ocr_observation.tokens`가 된다. **빈 문자열이 정상일 수 있다** — 기호만 읽은 관측에는 내용어가 없다. 색인과 질의가 같은 Kiwi 설정을 써야 하므로 `versions.detail.tokenizer`가 그 설정의 식별자를 싣는다.

**`confidence`는 넷째 자리까지다.** `ocr_observation.confidence`가 `numeric(5,4)`이고, 워커가 보내기 전에 반올림한다. 다섯째 자리를 보내면 DB가 반올림해 워커 기록과 저장된 값이 갈린다.

**`unverified`는 담을 컬럼이 없다.** `ocr_observation`에 검증 상태 칸이 없고 컬럼 주석이 "이 값(confidence)의 임계값으로 검증 상태를 판정한다"로 둔다. 그래서 이 필드는 저장 대상이 아니라 **BE가 `tag_evidence.verification_status`를 정할 때 쓰는 값**이고, `output.minConfidence`가 그 판정에 쓴 임계값을 함께 알려 준다. 임계값은 실측으로 정했다(`ai/docs/ocr.md` §5).

**미달 관측도 전부 온다.** FRD F-04가 "AI의 높은 신뢰도만으로 검증된 사실로 올리지 않는다"([docs/frd.md](../frd.md) §3)이고, 반대로 미달이라고 버리지도 않는다 — 검색 후보로는 쓸 수 있어야 한다. **BE는 `unverified: true`인 행을 거절하면 안 된다.**

**병합하지 않는다.** 프레임 사이의 같은 문구도 각자 관측으로 온다. `textKey`가 같으면 같은 문구이므로 소비자가 묶을 수 있고, 묶어도 원본 관측과 keyframe이 그대로 남는다("중복 글자를 묶어도 원본 관측과 프레임으로 돌아갈 수 있어야 한다", [docs/frd.md](../frd.md) §3 F-03). `ocr_observation`에 그룹 컬럼이 없으므로 **BE는 `textKey`를 저장하지 않는다.**

**`observations`가 빈 배열일 수 있다.** 화면에 글자가 없는 영상이 있고 그건 실패가 아니다. `keyframesRead`가 함께 오므로 "0장을 읽고 0건"과 "23장을 읽고 0건"이 구분된다. `status: succeeded`인데 `output`이 비었다고 거절하는 규칙(§4.3의 거부 조건 4)은 **`output` 객체 자체가 없는 경우**를 말하며, `observations: []`는 정상 payload다.

**오류 코드** — 이 단계 전용 코드를 만들지 않는다. §9.2의 어휘로 충분하다: 상류 산출물이 잘못됐으면 `VALIDATION_ERROR`(영구), keyframe JPEG을 열 수 없으면 `UNSUPPORTED_MEDIA`(영구), 모델 가중치를 준비하지 못하면 `MODEL_UNAVAILABLE`(일시), 나머지는 `OCR_FAILED`(일시)다.

**비치명이다.** `stages.py`가 이 단계를 `fatal=False`로 둔다. 실패해도 run은 계속 가고 그 사실이 `stage_states_json`에 남는다("VLM·OCR·음성인식 중 하나가 실패해도 나머지 결과를 사용할 수 있으면 처리를 계속한다", [docs/frd.md](../frd.md) §3 F-03).

### 4.4 artifacts — 입력 내려받기 / 산출물 올리기

```
GET /api/v1/internal/jobs/{runId}/artifacts?key={storageKey}
  X-Job-Lease-Id: <현재 배정의 leaseId>
  → 200 application/octet-stream (봉투 없음)
PUT /api/v1/internal/jobs/{runId}/artifacts/{storageKey}
  X-Job-Lease-Id: <현재 배정의 leaseId>
  Content-Type: <산출물 종류가 정한다 — keyframe 은 image/jpeg>
  X-Content-SHA256: <hex>
  → 201 (봉투 없음, 빈 본문)
```

**`Content-Type`은 산출물 종류가 정하고 워커가 종류마다 고정값으로 보낸다.** 지금 올리는 것은
`keyframe`과 자막 JSON이며 아래 표를 따른다. 종류가 늘면 표에 한 줄을 늘린다 — 워커가 형식을 고르는 것이
아니므로 BE는 종류별 고정값으로 검증할 수 있다.

`storageKey`는 미디어 루트 상대 경로다. `clip.storage_key`·`keyframe.storage_key`와 같은 어휘를 쓰고 새 식별자를 만들지 않는다.

GET·PUT은 `X-Worker-Id`와 `X-Job-Lease-Id`를 현재 run의 배정·만료 시각과 함께 검사한다.
워커 ID만 일치하는 이전 프로세스의 요청은 허용하지 않는다. GET은 배정에 보관된 미디어·상류·입력 준비
참조의 키만 읽을 수 있고, 같은 run의 임의 파일을 읽는 권한을 주지 않는다. PUT은 현재 attempt 접두만
허용하며 필수 `Content-Length`와 실제 바이트 수, SHA-256을 대조한다. 헤더 없는 chunked PUT은
`JOB_411_001`로 거절한다. 산출물 발신 해시는 접두 없는 소문자 64자리 hex다. 같은 키의 동일 바이트 재전송은
수용하되 다른 바이트로 기존 파일을 덮어쓰지 않는다. 업로드된 파일은 정본 저장 성공과 별개다.
업로드 바이트는 DB 잠금 밖에서 임시 파일로 수신·검증하고, 공개 직전에 lease를 다시 검사한다.
이 동안 heartbeat가 진행될 수 있으며 만료·회수된 lease의 임시 파일은 공개하지 않는다.

| artifact kind | Content-Type |
| --- | --- |
| `keyframe` | `image/jpeg` |
| `transcript_segments`, `transcript_decisions` | `application/json` |

### 4.5 자막 입력·산출물

`PrepareTranscriptInputUseCase.Prepared.transcript()`는 `inputs.upstream.transcript`로 전달한다.
`segmentsArtifact`는 `npick.transcript.segments/v1`의 `segments`를 가리킨다.
각 구간은 `segmentId`, 정수 ms `s/e`, 원문 `t`, `sourceDetail(uploaded/embedded/asr)`를 가진다.
`segmentId`는 해당 스냅샷 안에서 유일하며 이후 스냅샷에서도 기존 구간 ID를 보존한다.

`decisionsArtifact`는 `npick.transcript.decisions/v1`을 가리킨다. 파일 안의 `segmentsArtifact`는
대응 원본 ArtifactRef 전체와 같아야 한다. `decisions`는 모든 원본 ID에 정확히 하나씩 존재하며
`segmentId`, `selected`, `reasonCode`, `conflictsWith`를 가진다. 채택 사유는
`PREFERRED_SUBTITLE` 또는 `ASR_SUPPLEMENT`, 제외 사유는 `OVERLAPS_HIGHER_PRIORITY`다.
제외 근거는 같은 스냅샷의 상위 출처 원본을 참조한다. 겹친 하위 구간은 원문 전체를 보관하고
검색·기본 표시에서 구간 전체를 제외한다. 시간만 잘라 원문을 부분 발화로 만들지 않는다.

단계 결과는 `output.transcript.segmentsArtifact/decisionsArtifact`를 쓰고 두 참조를 `artifacts`에도
등록한다. 선택 단계는 `asrRequired`, `candidateRanges(s/e)`, `reasonCode`를 함께 반환한다.
사유는 `SUBTITLE_COVERED`, `UNCOVERED_RANGES`, `NO_VALID_SUBTITLE`이다. 원본·선택 정책은
워커 소유이며 BE 검증·저장이 선택 알고리즘을 대신하지 않는다.

워커는 참조 파일의 크기·해시·원본/채택 ID 관계를 검증한 JSON을
`StageContext.artifact_documents[storageKey]`로 실제 단계 함수에 제공한다. 원래 `upstream`도 유지한다.
설정을 처리하지 않는 단계에는 비어 있지 않은 `inputs.config`를 배정하지 않는다. 워커는 그 설정을
무시하지 않고 `VALIDATION_ERROR`로 거부하며, 실제 단계별 설정 지원은 해당 어댑터에서 연결한다.

ASR 정상 출력은 `segments` 배열이 있는 객체이며 빈 배열도 정상이다. 실제 발화 미감지 판정에만
`NO_SPEECH_DETECTED`를 기록한다. ASR 미배정과 실행 후 빈 결과, 실패 및 `NO_ADAPTER`는 구분한다.
최종 선택은 워커 `scene_transcript_mapping` 직전에 수행하며 기존 단계 목록·순서를 유지한다.
장면별 출력 봉투가 확정되기 전에는 해당 단계의 성공 정본 저장을 수락하지 않는다.

**PUT의 키는 경로 세그먼트로 들어가므로 워커가 퍼센트 인코딩한다** — 구분자 `/`는 남기고 `?`·`#`는 인코딩한다. 인코딩하지 않으면 `?`가 질의로 갈려 경로가 잘리고, BE의 접두 검사(`JOB_403_001`)가 의도한 경로에 대해 돌지 않는다. `..`나 절대 경로가 든 키는 인코딩으로 막히지 않으므로(구분자를 남기는 한 정규화된다) **워커가 보내기 전에 거절한다.** GET은 `?key=`로 실으므로 이 문제가 없다.

## 5. 입력·산출물 전송 — 두 배포를 한 계약으로

**계약이 교환하는 것은 언제나 storage key다.** 바이트가 어떻게 오는지는 `inputs.media.transport`가 정한다.

| transport | 언제 | 워커 구현 |
| --- | --- | --- |
| `http` | 언제나 성립 | **필수** |
| `shared-volume` | BE 설정과 워커의 `sharedMediaVolume: true`가 **둘 다** 참일 때만 | 선택 (최적화) |

compose에서는 backend와 ai-worker가 `media:/srv/npick/media`를 함께 마운트하므로 둘 다 참이고, 700MB 원본이 HTTP로 복사되지 않는다. RunPod에서는 BE가 `shared-volume`을 내보내지 않는다. **코드는 한 벌이고 바뀌는 것은 환경뿐이다.**

**경로 보호** — `storageKey`는 미디어 루트 기준으로 정규화한 뒤 검사하고, `..`와 절대 경로를 거절한다. "media는 사용자 경로가 아니라 검증된 ID로 접근한다"([docs/prd.md](../prd.md))가 코드에서 지켜지는 지점이다. 절대 경로 판정은 POSIX·Windows 양쪽 규칙으로 한다 — 같은 키가 개발 머신과 리눅스 컨테이너에서 다르게 판정되면 안 된다.

쓰기 키가 claim이 준 `outputKeyPrefix`(`runs/{runId}/{stage}/a{attempt}/`) 밖이면 `JOB_403_001`로 거절한다. attempt가 접두에 들어 있어 실패한 시도의 파일이 성공한 시도를 덮어쓸 수 없다.

## 6. `stage_states_json`

**단계 키는 `scene_detection`이다.** `pipeline_run.stage_states_json`의 컬럼 주석 예시는 `scene_detect`이지만 그건 스키마 제약이 아니라 주석 안의 문자열이고, 단계 목록의 정본은 `ai/src/npick_worker/stages.py`다. BE 티켓에 주석 수정을 포함한다.

```json
{
  "schemaVersion": "npick.stage_states/v1",
  "stages": {
    "scene_detection": {
      "status": "succeeded",
      "attempts": 1,
      "leaseId": null,
      "workerId": "runpod-a40-01",
      "startedAt": "2026-09-07T09:20:19Z",
      "finishedAt": "2026-09-07T09:21:00.230Z",
      "durationMs": 41230,
      "versions": { "…": "complete 의 versions 를 그대로" },
      "versionMismatch": null,
      "device": { "kind": "cuda", "gpuModel": "NVIDIA A40" },
      "metrics": { "scenes": 87 },
      "errorCode": null, "errorRetryable": null, "errorMessage": null,
      "lastIdempotencyKey": "398021847361024:scene_detection:1",
      "lastRequestSha256": "a71c…"
    },
    "asr": { "status": "skipped", "attempts": 0, "errorCode": "NO_ADAPTER" }
  }
}
```

`status` ∈ `pending | running | succeeded | failed | skipped`. BE는 run 생성 시 `stages.py`의 **10개 키를 전부 `pending`으로 초기화**한다 — "다음 단계가 뭔가"를 조인 없이 답할 수 있다.

## 7. 버전 규약과 `pipeline_version` 롤업

두 층으로 나눈다. 해시 규칙 자체는 [README.md](README.md)의 공용 규약이다.

### `stageVersion` — 워커가 계산한다

입력은 그 단계의 **재현 튜플 전체**다. `scene_detection`이면 `{configVersion, detector, engine, engineVersion}`, `frame_extraction`이면 `{configVersion, engine, engineVersion}`다 — 후자에 `detector` 같은 축이 없는 것은 고를 구현이 하나뿐이어서다. 항상 같은 값인 축을 넣으면 해시에 아무 정보도 들어가지 않는다.

`ocr`은 축이 넷이다 — `{configVersion, engine, engineVersion, tokenizer}`. `tokenizer`가 있는 이유는 `ocr_observation.tokens`가 그 단계의 산출물이기 때문이다. Kiwi 설정이 바뀌면 화면에서 읽은 글자가 같아도 색인이 달라지고, 그건 검색이 0건이 되는 종류의 변화다([02-container.md](../architecture/02-container.md)).

설정 해시만으로는 부족하다. 그 값은 설정 파일만 해시하므로 **라이브러리가 바뀌면 값이 그대로인데 경계는 달라질 수 있다**. 원본 튜플은 `versions.detail`에 그대로 남겨 조사할 수 있게 한다.

schema 접두를 `configVersion`과 다르게 둔 이유는 로그에 두 값이 나란히 찍히기 때문이다. 접두가 같으면 사람이 반드시 헷갈린다.

### `pipeline_version` — BE가 계산한다

근거 둘. `pipeline_run.pipeline_version`의 컬럼 주석이 "이 값 하나가 모델 조합을 결정한다. 버전별 모델 매핑은 설정 파일이 안다"로 설계 의도를 이미 BE + 설정 파일에 뒀고, 컬럼이 `NOT NULL`인데 run 행은 워커가 보기 전에 존재한다.

입력은 `단계 이름 → stageVersion` 맵이고, 결과는 단계 순서와 무관하다. 해시 길이만 12자다 — 이 값은 사람이 읽는 로그가 아니라 DB 키로 등가 비교되고 열 단계의 식별자를 한 문자열에 접는다.

BE는 run 생성 시 계산해 컬럼을 채우고 `claim`으로 워커에 내려준다. 맵의 출처는 `infra/compose/profiles/pipeline.yml`에 새로 두는 `stage_versions:` 키다(단계 *목록*이 아니라 각 단계의 기대 버전이므로 "단계 목록 정본은 stages.py"와 충돌하지 않는다).

### 고정 테스트 벡터

BE가 같은 값을 Java로 계산한다. 아래를 그대로 대조한다. **값이 바뀌면 `ai/tests/test_job_contract.py`의 벡터도 함께 고쳐야 한다.**

| 입력 | 결과 |
| --- | --- |
| `scene_detection.v1.toml` 기본 설정 | `configVersion` = `scene-detect/v1:20dfc0a6` |
| `{configVersion: scene-detect/v1:20dfc0a6, detector: content, engine: pyscenedetect, engineVersion: 0.7.1}` | `stageVersion` = `npick.stage.scene_detection/v1:3ab4bebe` |
| `frame_extraction.v1.toml` 기본 설정 | `configVersion` = `frame-extract/v1:5b266b10` |
| `{configVersion: frame-extract/v1:5b266b10, engine: pyav, engineVersion: 18.1.0+numpy2.5.2}` | `stageVersion` = `npick.stage.frame_extraction/v1:595427d7` |
| `ocr.v1.toml` 기본 설정 | `configVersion` = `ocr/v1:daaf4c83` |
| `{configVersion: ocr/v1:daaf4c83, engine: rapidocr, engineVersion: rapidocr3.9.2+onnxruntime1.29.0, tokenizer: query-norm/v1:b0d96c0c:kiwi0.23.2:model0.23.0}` | `stageVersion` = `npick.stage.ocr/v1:449d6928` |
| `{scene_detection: npick.stage.scene_detection/v1:aaaaaaaa, ocr: npick.stage.ocr/v1:bbbbbbbb}` | `pipelineVersion` = `npick-pipeline/v1:64960bae4565` |
| `{scene_detection: npick.stage.scene_detection/v1:aaaaaaaa, frame_extraction: npick.stage.frame_extraction/v1:cccccccc}` | `pipelineVersion` = `npick-pipeline/v1:32d2389f906a` |

### 버전 불일치

| 시점 | 처리 | 이유 |
| --- | --- | --- |
| claim | 워커의 `capabilities[].stageVersion`이 run의 기대 버전과 다르면 **배정하지 않는다** | GPU 분을 쓰기 전에 거른다 |
| complete | 결과는 **받아 저장하고** `versionMismatch`를 남긴다. `indexing` 성공 시 `clip.active_pipeline_run_id` 전환을 **거부**하고 run을 `failed`/`PIPELINE_VERSION_MISMATCH`로 끝낸다 | 중간에 하드 거절하면 이미 쓴 GPU 시간을 통째로 버리고, 조용히 받아들이면 `pipeline_version`이 거짓말이 된다 |

## 8. 멱등성

**키는 `{pipelineRunId}:{stage}:{attempt}`이고 BE가 발급한다.** 워커는 claim에서 받아 `complete`의 `Idempotency-Key` 헤더와 본문에 그대로 되돌린다. 만들지 않는다.

> 같은 `Idempotency-Key`로 도착한 `complete`는 **최초 1회만** 정본을 바꾼다. 두 번째부터는 최초에 저장한 결과를 그대로 돌려주며 `duplicate: true`를 붙인다(200, 오류가 아니다). 본문이 최초와 다르면 — 정규화 JSON의 sha256 비교 — 정본을 바꾸지 않고 `JOB_409_003`으로 거절한다.

별도 멱등성 테이블을 만들지 않는다. `stage_states_json`의 `lastIdempotencyKey` + `lastRequestSha256`에 넣고, `complete` 처리를 `SELECT … FOR UPDATE` 안의 조건부 UPDATE로 한다. 행 하나가 잠기므로 원자성이 공짜다.

"재시도가 성공 산출물을 중복 생성하지 않는다"([docs/frd.md](../frd.md) §3 F-03)가 성립하는 이유는 세 겹이다.

1. **fencing** — 회수된 lease로 오는 `complete`는 `JOB_409_002`. 좀비 워커가 두 번째 `scene` 뭉치를 넣을 수 없다.
2. **상태 전이 가드** — 이미 `succeeded`인 단계는 `JOB_409_001`.
3. **한 트랜잭션** — `scene`/`keyframe`/`ocr_observation` INSERT와 `stage_states_json` 갱신이 하나의 트랜잭션이다. 반쯤 들어간 산출물이 남지 않는다.

재시도(attempt N+1)는 새 키와 새 `outputKeyPrefix`를 받으므로 실패한 attempt N의 파일이 성공 결과와 섞이지 않는다.
lease 회수만으로 attempt가 증가하지는 않는다. 같은 attempt의 재배정은 기존 키에 동일 바이트만
재전송할 수 있으며, 다른 바이트로 충돌하면 덮어쓰지 않고 거절한다. 기대 `stageVersion` 일치는
재배정에도 필수다. 다른 결과를 저장할 새 attempt의 발급·예산은 재시도 정책의 책임이며 워커가 임의로 올리지 않는다.

## 9. 오류 코드

### 9.1 HTTP 계층 — `JOB_` 접두

BE의 실제 `ErrorType`(`BAD_REQUEST`, `LENGTH_REQUIRED`, `UNAUTHORIZED`, `FORBIDDEN`, `NOT_FOUND`, `CONFLICT`, `SERVICE_UNAVAILABLE`, `INTERNAL_SERVER_ERROR`)에 맞춘다.

| code | HTTP | 의미 | 워커의 정해진 반응 |
| --- | --- | --- | --- |
| `JOB_400` | 400 | 요청 형식 오류 | 버그. 재시도 금지 |
| `JOB_400_001` | 400 | 결과 봉투가 §4.3의 거부 조건에 걸림 | 재시도 금지 |
| `JOB_400_002` | 400 | 산출물 sha256 불일치 | PUT만 1회 재전송 후 `ARTIFACT_UPLOAD_FAILED`, `retryable=false`. 단계 자동 재실행과 구분 |
| `JOB_411_001` | 411 | PUT의 Content-Length 없음 | 버그. 재시도 금지 |
| `JOB_401` | 401 | 토큰 없음·불일치 | **루프 중단** |
| `JOB_403_001` | 403 | `outputKeyPrefix` 밖의 키 | **해당 단계만 실패로 보고. 워커 루프는 계속** |
| `JOB_403_002` | 403 | fleet 불일치 | **루프 중단** (프로세스는 살려 둔다) |
| `JOB_404_001` | 404 | run 없음 | 결과 폐기 |
| `JOB_404_002` | 404 | artifact key 없음 | `MEDIA_UNAVAILABLE`로 단계 실패 보고 |
| `JOB_409_001` | 409 | 이미 `succeeded`인 단계 | 폐기 (정상) |
| `JOB_409_002` | 409 | **lease 만료·회수 (fencing)** | 즉시 중단, 산출물 폐기 |
| `JOB_409_003` | 409 | 같은 키·다른 본문 | 버그. 중단 |
| `JOB_409_004` | 409 | `stageVersion` 불일치 | 중단. 워커 재배포 필요 |
| `JOB_503_001` | 503 | 정본 DB 사용 불가 | 지수 백오프 |

`401`을 재시도하지 않는 이유: 토큰이 거절되는데 백오프로 계속 두드려도 열리지 않고 BE 로그만 더럽힌다.

### 9.2 스테이지 어휘 — `stage_states_json`의 `errorCode`

`varchar(64)` 이내. 구분 축은 하나다 — "권한·외부 전송 거부 같은 영구 오류와 일시 오류를 구분한다"([docs/frd.md](../frd.md) §6 F-14).

| code | 분류 | 해당 단계 |
| --- | --- | --- |
| `SCENE_DETECTION_FAILED` | 일시 | scene_detection |
| `VLM_SCHEMA_INVALID` | **영구** | vlm_metadata |
| `OCR_FAILED` | 일시 | ocr |
| `ASR_FAILED` | 일시 | asr |
| `INDEX_FAILED` | 일시 | indexing |
| `VALIDATION_ERROR` | **영구** | 전 단계 |
| `UNSUPPORTED_MEDIA` | **영구** | 전 단계 — 코덱·컨테이너가 범위 밖이거나 파일이 깨졌다 |
| `INVALID_TRANSCRIPT` | **영구** | transcript_selection |
| `EXTERNAL_PROCESSING_NOT_ALLOWED` | **영구** | 어댑터 사용 단계 |
| `NO_ADAPTER` | 영구 → `skipped` | 전 단계 — 구현이 없다 |
| `MODEL_UNAVAILABLE` | 일시 | 전 단계 — 가중치 볼륨 미마운트 |
| `OUT_OF_MEMORY` | 일시 | GPU 단계 — 다른 파드에서 성공할 수 있다 |
| `STAGE_TIMEOUT` | 일시 | 전 단계 |
| `MEDIA_UNAVAILABLE` | 상황에 따름 | 전 단계 — 없는 파일은 영구, I/O 실패는 일시 |
| `ARTIFACT_UPLOAD_FAILED` | 일시 | 전 단계 |
| `WORKER_ABORTED` | 일시 | 전 단계 — heartbeat `abort` 수신 |
| `UNSUPPORTED_STAGE` | **영구** | FRD 표에 없는 이름을 배정받음 |
| `STAGE_FAILED` | 일시 | 위 어느 것도 아닌 실패. 분류를 미룰 뿐 숨기지 않는다 |
| `PIPELINE_VERSION_MISMATCH` | **영구** | run 레벨 (`pipeline_run.error_code`). BE만 발신 |

`JOB_403_002`에서 프로세스를 죽이지 않는 이유 — 잘못 발급된 토큰 하나로 컨테이너가 재시작 루프에 빠지는 것이 보이면서 노는 컨테이너보다 나쁘다. 워커는 루프를 멈추고 `/health`의 `polling.running`을 `false`로 뒤집어 그 상태를 밖에 알린다. 상태 코드는 200을 유지하므로 compose 헬스체크가 컨테이너를 재기동하지 않는다.

v2.2의 `ROLE_FORBIDDEN`은 **승계하지 않는다.** 워커에 역할 개념이 없고 `JOB_401`/`JOB_403_002`가 같은 사실을 더 정확히 말한다.

**재시도 정책** — 영구는 `attempts`를 동결하고 재claim하지 않는다. 일시는 `attempts < maxAttempts`일 때만 재claim한다. 치명 단계(`stages.py`의 `fatal=True`: `scene_detection`·`frame_extraction`·`indexing`)의 최종 실패는 run을 `failed`로 만들고, 비치명 단계 실패는 run을 계속 진행시킨다([docs/frd.md](../frd.md) §3 F-03).

## 10. 시간 수치

실측 후 확정할 품질 임계값이 아니라 **프로토콜 타임아웃**이므로 실측 없이 고정해도 "실행 환경 수치를 만들지 않는다"를 위반하지 않는다. 품질 수치(`retry_count`·`timeout_seconds`·`concurrency`)는 `pipeline.yml`에 `null`로 남는다.

| 항목 | 값 | 근거 |
| --- | --- | --- |
| claim 롱폴 보류 | 25 s | nginx `proxy_read_timeout` 60 s, RunPod NAT 유휴 30~60 s 아래 |
| 워커 claim read timeout | 35 s | 보류 + 여유. 서버의 정상 대기와 클라이언트 타임아웃을 갈라야 한다 |
| lease TTL | 60 s | heartbeat 6회 분 |
| heartbeat 주기 | 10 s | |
| 회수 유예 | 만료 + 15 s | 일시 네트워크 흔들림 흡수 |
| 리퍼 주기 | 10 s | |
| `inputs.upstream` 인라인 상한 | 1 MiB | 넘으면 artifact key |
| `maxAttempts` 기본 | 1 | `retry_count: null` 동안 |

## 11. 서비스 서버가 구현해야 하는 것

1. **스키마 추가** — `pipeline_run`에 `lease_id`(uuid) / `lease_stage`(varchar 48) / `lease_worker_id`(varchar 64) / `lease_expires_at`(timestamptz) / `lease_heartbeat_at`(timestamptz), **전부 nullable** + 부분 인덱스 `ix_pipeline_run_lease_expiry ON pipeline_run (lease_expires_at) WHERE lease_expires_at IS NOT NULL`.
   별도 lease 테이블을 만들지 않는다 — baseline 마이그레이션이 "별도 이력·잠금·outbox 테이블, 트리거"를 금지한다. attempts·타이밍·버전은 `stage_states_json`에 자리가 있으므로 컬럼을 늘리지 않는다.
   **이건 선택이 아니다.** [03-deployment.md](../architecture/03-deployment.md)가 "lease 만료로 회수되므로 잡이 유실되지 않는다"를 아키텍처 보장으로 적어 뒀는데, 현재 `pipeline_run`에는 lease 칸이 하나도 없어 그 보장이 구현 불가능하다.
2. **주석 수정** — `stage_states_json` 컬럼 주석의 `scene_detect` → `scene_detection`, 예시를 §6의 래퍼 형태로.
3. **엔드포인트 4종** — claim은 `DeferredResult` 롱폴이며 대기 중 DB 커넥션을 붙잡지 않는다.
4. **배정 쿼리** — `FOR UPDATE SKIP LOCKED`, 기존 `ix_pipeline_run_queue (status, created_at)` 사용, 워커 `capabilities`로 단계 필터.
   ```sql
   SELECT … FROM pipeline_run
    WHERE status IN ('queued','running')
      AND (lease_expires_at IS NULL OR lease_expires_at < now())
    ORDER BY created_at
    FOR UPDATE SKIP LOCKED LIMIT 1;
   ```
5. **lease 리퍼** — 10초 주기, 만료+15초 초과 lease 해제, 단계 `running → pending`, **`attempts` 미증가**. 파드를 요금 절약으로 내린 것이 재시도 예산을 먹으면 안 된다.
6. **`complete` 트랜잭션** — 행 `FOR UPDATE` → fencing(`leaseId`) → 멱등성(`lastIdempotencyKey`/`lastRequestSha256`) → 정본 INSERT → `stage_states_json` 갱신, 한 트랜잭션.
7. **run 생성 시** `stage_states_json`을 10개 키 `pending`으로 초기화하고 `pipeline_version`을 계산해 채운다.
8. **`JOB_` ErrorCode enum** — `pipeline` 도메인 모듈의 `application/error`에 두고 `ErrorCode`를 구현한다.
9. **보안 체인** — `@Order(1)` + `securityMatcher("/api/v1/internal/**")` + Bearer 필터 + 다중 토큰.
   현재 `SecurityConfig`는 `anyRequest().permitAll()`이고 nginx가 `/api/`를 공개로 프록시한다. **인증 없이 잡 API를 배포하면 claim/complete가 인터넷에 열린다.** 이 항목은 엔드포인트와 같은 MR에 들어가야 한다.
10. **artifacts 저장소 어댑터** — 미디어 루트 정규화·경로 이탈 차단·sha256 검증.
11. **`pipeline.yml`에 `stage_versions:` 키 신설**, 기동 시 롤업 계산·로그.

## 12. 워커 쪽 구현

`ai/src/npick_worker/jobs/` — 파이프라인 워커 전용. `client.py`(4개 엔드포인트), `runner.py`(claim→실행→heartbeat→complete), `registry.py`(단계 디스패치·워밍업), `media.py`(입력 해석·경로 보호), `versions.py`·`errors.py`(이 문서의 §7·§9).

단계가 상류 산출물의 **바이트**를 필요로 하면(`ocr`의 keyframe JPEG) `StageHandler.required_inputs`로 키를 선언하고 러너가 heartbeat가 도는 동안 받아 넘긴다. 단계 구현은 잡 API를 모르므로 직접 받지 않는다 — 업로드를 러너가 하는 것과 같은 이유다(§4.2).

잡 루프는 라우트가 아니라 FastAPI lifespan 태스크로 돈다. **워커에 인바운드 잡 엔드포인트가 생기지 않는다.**

`NPICK_AI_JOB_POLL_ENABLED`가 배포 단위 둘을 가른다 — 같은 이미지가 이 값 하나로 "잡을 도는 파이프라인 워커"와 "폴링하지 않는 질의 리졸버"가 된다. 컨테이너를 실제로 쪼갤 때 바뀌는 것은 이 값과 `NPICK_AI_JOB_API_BASE_URL`뿐이다.

## 13. 미결

| 항목 | 어디서 정하는가 |
| --- | --- |
| 토큰 발급·회전 절차 | 인프라 티켓 |
| 단계 재시도 횟수·타임아웃 | 실측 후 `infra/compose/profiles/pipeline.yml` |
| 미구현 7단계 | 각 단계 티켓. capabilities에 없는 단계는 미배정이며, 배정 후 어댑터가 없으면 `NO_ADAPTER`로 보고한다 |
| 협조적 취소 | 별도 티켓 (§4.2의 한계) |
| 리졸버/워커 컨테이너 분리 | `docs/architecture/04-implementation-gap.md` (G-3, 아직 없는 파일) |

## 14. 오프라인 산출물 반입

오프라인 결과도 같은 `JobAssignment`, `StageResult`, artifact 전송과 `complete`를 사용한다.
`ai/src/npick_worker/jobs/offline.py`의 `import_result`는 배정·lease·stage·attempt·멱등성 키·출력 스키마와
모든 파일의 경로·크기·해시를 대조하고 반입 중 heartbeat를 유지한다. bundle 디렉터리 안에서
`storageKey`와 같은 상대 경로로 파일을 찾는다. 별도 DB 쓰기·새 성공 봉투를 만들지 않는다.

오프라인 실행 중에도 배정 lease는 유효하게 유지되어야 한다. 만료된 bundle의 lease나 attempt를
새 값으로 바꿔 반입하지 않는다. lease가 이미 회수됐다면 실행기의 재배정·재실행 절차를 따른다.
완료된 동일 결과의 응답 확인은 원래 봉투를 `complete`에 재전송한다. 완료된 lease는 artifact 재업로드나
heartbeat 권한을 주지 않으므로 완료 후 `import_result` 전체를 다시 실행하는 방식과 구분한다.
오프라인 GPU 패키지 실행 자체와 모델 구현은 각 AI 단계 담당 범위다.
