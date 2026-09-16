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
    "stageVersion": "npick.stage.frame_extraction/v1:5fa70a50",
    "outputSchemaVersion": "npick.stage.frame_extraction.output/v1",
    "configVersion": "frame-extract/v2:a0684794",
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

**장 수는 장면 안의 변화량으로 정해진다.** 고정 개수도, 장면 길이에 비례하는 값도 아니다 — 정적 장면은 적게, 동적 장면은 많게 나온다(FRD v3.2 F-03, `docs/frd.md:131`). **BE는 장면마다 장 수가 다른 것을 정상으로 받는다.** 길이가 같은 두 장면이 다른 장 수를 내는 것도 정상이다.

변화량 척도는 `scene_detection`이 컷을 판정할 때 쓰는 `content_val`과 같다(FRD의 척도 통일 권고). 기본 임계도 그 단계의 컷 임계와 같은 값이라, 규칙이 한 문장으로 선다 — **장면 안의 두 장을 따로 남기려면 scene 분할이 컷으로 봤을 만큼 달라야 한다.** 판정에 쓴 임계값은 `configVersion`에 들어가므로 어떤 설정으로 뽑은 결과인지는 그 값으로 되짚는다. 근거는 `ai/docs/frame-extraction.md` §3.1이다.

**하한과 상한** — `scenes[].keyframes`는 최소 1개다. 다만 **1개가 정상인 경우는 하나뿐이다**: 그 scene 구간에 정규 시각이 들어오는 프레임이 한 장뿐인 경우다. 그 밖에는 변화량이 아무리 작아도 `min_keyframes_per_scene`(기본 2)을 보장한다 — FRD F-03의 "장면의 복수 키프레임"이 변화량 판정으로 깨지지 않아야 하기 때문이다. 상한은 `max_keyframes_per_scene`(기본 5)이고 이것이 곧 후속 VLM·OCR의 비용 상한이다.

그 밖의 부족은 성공으로 반납되지 않고 `VALIDATION_ERROR`(영구)로 실패한다. scene 구간이 미디어 끝을 넘으면 워커가 **재 달라고 한 프레임에 디코드가 닿지 못한 것을 직접 검출해** 실패시킨다(장 수로 추론하지 않는다). 그건 상류 scene 목록이 이 미디어의 것이 아니라는 신호다. **BE는 "장 수가 줄어든 성공"을 처리할 필요가 없다** — 그런 결과는 오지 않는다.

**남은 어긋남 — `stages.py`의 "thumbnail"과 축소본의 자리.** 단계 표는 2단계 필수 출력을 "복수 keyframe·thumbnail"로 적고 FRD §3 F-03은 "축소된 대표 이미지 대신 원본 해상도의 프레임"이라 쓰므로 축소본의 존재를 전제한다. 그런데 **축소본 경로를 담을 컬럼이 스키마에 없다.** 이번 구현은 축소본 파일을 만들지 않고 keyframe을 원본 해상도로만 저장한다. 근거는 둘이다 — 작은 글자 OCR이 요구하는 것이 원본 해상도 프레임이고(그것이 이 자산의 1차 소비자다), 결과 카드용 축소는 ID 기반 조회 응답에서 만들 수 있어 저장이 필요 없다. **컬럼을 새로 만들지 않았으므로 BE는 대표 keyframe을 축소해 카드에 제공한다.** 이 판단을 바꾸려면 스키마가 먼저 바뀌어야 하므로 여기 적어 둔다.

**순서** — 워커는 `complete` **전에** 모든 keyframe을 올린다. 반납 뒤로 미루면 BE가 keyframe 행을 만든 뒤에 파일이 올라가고, 그 사이 조회는 없는 파일을 가리킨다. 업로드는 lease를 연장하지 않으므로(§4.2) 워커는 올리는 동안에도 heartbeat를 계속 친다. 중단 지시(`abort`)를 받았으면 올리지 않고 결과를 버리며, **전송 도중에 받으면 남은 파일을 올리지 않는다** — 그래서 abort된 attempt의 접두 아래에는 파일이 일부만 남을 수 있다. `complete`가 오지 않았으므로 그 접두는 어느 keyframe 행도 가리키지 않는다.

**오류 코드** — 이 단계 전용 코드를 만들지 않는다. §9.2의 어휘로 충분하다: 상류 산출물·산출물 키가 잘못됐으면 `VALIDATION_ERROR`(영구), 영상을 열 수 없으면 `UNSUPPORTED_MEDIA`(영구), 업로드가 실패하면 `ARTIFACT_UPLOAD_FAILED`(일시), 나머지는 `STAGE_FAILED`(일시)다.

**재처리** — 재시도(attempt N+1)는 새 `outputKeyPrefix`를 받으므로 실패한 attempt N의 JPEG이 성공 결과와 섞이지 않는다. 같은 attempt의 중복 반납은 §8의 세 겹이 막는다. 워커 쪽 몫은 결정론이다 — 같은 입력과 같은 재현 튜플이면 같은 프레임을 고르고 같은 바이트를 쓴다.

### 4.3.2 `ocr` — 화면 글자 관측

`frame_extraction`과 달리 이 단계는 **파일을 읽는다.** keyframe을 받고, OCR v2에서는 원본 관측·병합 그룹을 보존한 JSON 산출물을 올린다.

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

**결과** — 관측은 전부 `output`으로 간다. OCR v2는 같은 출력과 재현 튜플을 `ocr_result` JSON 산출물로 함께 보존한다. 아래 관측·그룹은 설명을 위한 발췌다.

```json
{
  "stage": "ocr",
  "status": "succeeded",
  "versions": {
    "stageVersion": "npick.stage.ocr/v1:bc75979d",
    "outputSchemaVersion": "npick.stage.ocr.output/v2",
    "configVersion": "ocr/v1:daaf4c83",
    "modelVersion": "rapidocr/rapidocr3.9.2+onnxruntime1.29.0",
    "promptVersion": null,
    "detail": {
      "engine": "rapidocr",
      "engineVersion": "rapidocr3.9.2+onnxruntime1.29.0",
      "tokenizer": "query-norm/v1:b0d96c0c:kiwi0.23.2:model0.23.0",
      "mergeConfigVersion": "ocr-merge/v1:28d42216"
    },
    "runtime": { "worker": "0.1.0", "python": "3.12.14", "torch": null, "cuda": null }
  },
  "metrics": {
    "keyframes": 23, "observations": 44,
    "unverifiedObservations": 12, "textGroups": 40, "minConfidence": 0.7
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
    "textGroups": [
      { "sceneIndex": 8, "observationIndices": [0], "representativeIndex": 0 }
    ],
    "mergeConfigVersion": "ocr-merge/v1:28d42216",
    "keyframesRead": 23,
    "minConfidence": 0.7
  },
  "artifacts": [
    { "kind": "ocr_result", "storageKey": "runs/398021847361024/ocr/a1/ocr-result.json",
      "byteSize": 12345,
      "contentHash": "0000000000000000000000000000000000000000000000000000000000000000" }
  ]
}
```

**keyframe은 `(sceneIndex, timestampMs)`로 가리킨다.** `ocr_observation.keyframe_id`는 TSID이고 그것을 발급하는 쪽은 BE인데, `complete` 응답의 `assignedIds`는 scene만 돌려준다(§4.3). `keyframe`에 `UNIQUE(scene_id, timestamp_ms)`가 있으므로 이 쌍이 곧 그 행이다 — **스키마도 `assignedIds`도 늘리지 않고 닫힌다.** `storageKey`는 어느 파일을 읽었는지의 근거로 함께 싣는 값이지 참조 키가 아니다(`keyframe.storage_key`에 인덱스가 없다).

**`tokens`는 워커가 만든다.** BE에 Kiwi가 없고 [02-container.md](../architecture/02-container.md)가 "워커가 Kiwi로 토큰화한 결과를 별도 컬럼에 넣고 `pdb.whitespace` 토크나이저로 색인한다"로 정했다. 공백으로 이어진 문자열이 그대로 `ocr_observation.tokens`가 된다. **빈 문자열이 정상일 수 있다** — 기호만 읽은 관측에는 내용어가 없다. 색인과 질의가 같은 Kiwi 설정을 써야 하므로 `versions.detail.tokenizer`가 그 설정의 식별자를 싣는다.

**`confidence`는 넷째 자리까지다.** `ocr_observation.confidence`가 `numeric(5,4)`이고, 워커가 보내기 전에 반올림한다. 다섯째 자리를 보내면 DB가 반올림해 워커 기록과 저장된 값이 갈린다.

**`unverified`는 담을 컬럼이 없다.** `ocr_observation`에 검증 상태 칸이 없고 컬럼 주석이 "이 값(confidence)의 임계값으로 검증 상태를 판정한다"로 둔다. 그래서 이 필드는 저장 대상이 아니라 **BE가 `tag_evidence.verification_status`를 정할 때 쓰는 값**이고, `output.minConfidence`가 그 판정에 쓴 임계값을 함께 알려 준다. 임계값은 실측으로 정했다(`ai/docs/ocr.md` §5).

**미달 관측도 전부 온다.** FRD F-04가 "AI의 높은 신뢰도만으로 검증된 사실로 올리지 않는다"([docs/frd.md](../frd.md) §3)이고, 반대로 미달이라고 버리지도 않는다 — 검색 후보로는 쓸 수 있어야 한다. **BE는 `unverified: true`인 행을 거절하면 안 된다.**

**원본을 보존하면서 병합한다 (S15P21A501-95).** `textGroups`는 scene별 문구 그룹이다. `observationIndices`와 `representativeIndex`는 같은 `output.observations` 배열의 0-based 인덱스다. DB ID나 keyframe 인덱스가 아니다. 독립 관측도 1원소 그룹으로 보내며, 모든 관측은 정확히 한 그룹에 속한다. 그룹은 비어 있지 않고 같은 scene의 서로 다른 timestamp만 포함한다. 대표 인덱스는 구성원 중 confidence가 최대인 관측을 가리킨다. 범위 밖·중복·누락·다른 scene·같은 frame·그룹 밖 대표를 거부한다.

대표 문구·bbox·confidence·미검증 상태는 `observations[representativeIndex]`에서 읽는다. 그룹 전체 confidence나 검증 상태를 합성하지 않는다. 모든 구성원의 상태와 원문은 그대로 남는다. `textKey`는 검색 토큰 해시여서 원문이 다른 관측도 같을 수 있으며 병합 그룹 ID로 쓰지 않는다. `ocr_observation`에 그룹 컬럼이 없으므로 BE는 이 키를 관측 컬럼에 추가 저장하지 않는다.

**JSON 보존 산출물.** v2의 `artifacts`에는 `kind: "ocr_result"`, `storageKey: "runs/{runId}/ocr/a{attempt}/ocr-result.json"`, 실제 `byteSize`·`contentHash`(SHA256)를 가진 참조 한 개가 온다. 위 예제의 크기·해시는 자리 표시용이며 실제 파일 바이트에서 계산한다. 파일은 `{outputSchemaVersion, identity, output}` 구조다. `identity`는 §7의 OCR 재현 튜플이고 `output`은 complete의 출력과 동일하다. UTF-8·키 정렬·공백 없는 JSON으로 만들며 실행 시각은 넣지 않아 같은 결과가 같은 바이트가 된다. 러너가 성공 업로드 후 complete에 참조를 싣는다.

**보존·소비 조건.** BE는 v2 출력 검증, 산출물 참조·크기·해시 확인, complete 출력과 JSON 내용 일치 확인, 원본 관측 저장, 성공 단계의 산출물 참조 보존을 함께 구현한다(`S15P21A501-184`). 후속 소비자는 이 문서 전체를 읽어 대표 문구와 근거를 조회한다. 배열을 단독 정렬·필터링하거나 관측 인덱스를 DB ID로 변환 없이 사용하지 않는다. 별도 그룹 테이블을 임의로 추가하지 않는다.

기본 규칙은 NFKC·casefold·공백 제거 후 일치이며, 원문은 수정하지 않는다. 유사도 병합과 모호성 보류 규칙·실측은 `ai/docs/ocr.md` §4가 설명한다. `mergeConfigVersion`은 출력과 `versions.detail` 모두에 넣고 `stageVersion`에도 반영한다. 기본 `textGroups` metric은 이전의 전역 textKey 개수가 아니라 독립 관측을 포함한 scene별 그룹 수다.

**`observations`가 빈 배열일 수 있다.** 화면에 글자가 없는 영상이 있고 그건 실패가 아니다. `keyframesRead`가 함께 오므로 "0장을 읽고 0건"과 "23장을 읽고 0건"이 구분된다. `status: succeeded`인데 `output`이 비었다고 거절하는 규칙(§4.3의 거부 조건 4)은 **`output` 객체 자체가 없는 경우**를 말하며, `observations: []`는 정상 payload다.

**오류 코드** — 이 단계 전용 코드를 만들지 않는다. §9.2의 어휘로 충분하다: 상류 산출물이 잘못됐으면 `VALIDATION_ERROR`(영구), keyframe JPEG을 열 수 없으면 `UNSUPPORTED_MEDIA`(영구), 모델 가중치를 준비하지 못하면 `MODEL_UNAVAILABLE`(일시), 나머지는 `OCR_FAILED`(일시)다.

**비치명이다.** `stages.py`가 이 단계를 `fatal=False`로 둔다. 실패해도 run은 계속 가고 그 사실이 `stage_states_json`에 남는다("VLM·OCR·음성인식 중 하나가 실패해도 나머지 결과를 사용할 수 있으면 처리를 계속한다", [docs/frd.md](../frd.md) §3 F-03).

### 4.3.3 `vlm_metadata` — 장면 설명과 샷 유형

VLM은 키프레임과 기존 OCR·최종 채택 대사를 종합해 장면을 설명한다. OCR·대사 추출 및 선택·매핑은 상류 책임이며 이 단계에서 반복하지 않는다(FRD F-03).

**입력** — `inputs.upstream.frameExtraction`이 필수다. 모양은 §4.3.2와 같다. 없으면 워커는 `VALIDATION_ERROR`(영구)로 실패를 신고한다. 빈 결과를 성공으로 반납하면 "이 영상에는 설명할 장면이 없다"는 거짓이 정본에 남는다.

이미지 전용 v1 등 `{grounding}`이 없는 사용자 프롬프트는 텍스트를 전달하지 않으며 OCR·대사 라벨도 근거로 허용하지 않는다.

**Grounding 입력** — 기존 `inputs.upstream.ocr.observations`를 `sceneIndex`로 나눠 사용한다. **이 상류 payload에는 `textGroups`가 없다** — BE가 `ocr_observation` 행에서 조립하는데 그 표에 그룹 컬럼이 없기 때문이다(§4.3.2). 그룹이 필요하면 `ocr_result` 산출물을 읽는다. `rawText`·`textKey`·`confidence`·프레임 참조를 재사용하며 검색 토큰은 프롬프트에 넣지 않는다. 동일 `textKey`와 동일 원문만 묶고, 원본 배열 위치와 프레임 참조를 모두 보존한다. `max_ocr_chars`·`max_transcript_chars`는 원문을 자르지 않는 항목 단위 제한이며 0은 제한 없음이다. 운영 상한은 개발 샘플 실측으로 정한다.

대사는 §4.5의 `inputs.upstream.scene_transcript_mapping`을 소비한다. 기존 artifact 로더로 최종 snapshot의 원본·채택 결과를 읽고, `scenes[].segments[].segmentId`가 가리키는 채택 구간의 원문·시간·출처를 VLM에 전달한다. 상위 `upstream.transcript` 별칭이 이전 snapshot을 가리켜도 최종 매핑 안의 참조를 사용한다. VLM은 선택·매핑을 다시 계산하지 않는다. 이 매핑 검증은 VLM 소비에만 적용하며 다른 단계의 공용 artifact 로딩에서 강제하지 않는다. 매핑 단계 결과 자체가 없으면 OCR·이미지로 진행하며, 존재하는 결과가 잘못됐으면 `VALIDATION_ERROR`로 거부한다. 원본 대사를 임의로 장면에 배정하지 않는다.

**실행 순서** — `scene_detection → frame_extraction → ocr → transcript_selection → asr → scene_transcript_mapping → vlm_metadata → entity_extraction → text_embedding → indexing`. 각 단계의 기존 fatal 분류와 재시도 정책은 유지한다.

**장면당 keyframe을 여러 장 넣는다.** 한 장씩 따로 보면 "앵커에서 자료 화면으로 넘어간다" 같은 판단이 불가능하고, 그 판단이 이 단계가 존재하는 이유다. 장면당 장 수는 상류가 내용으로 정하므로(F-03) 고정이 아니고, 워커가 설정 상한(`max_keyframes_per_scene`)까지만 넣는다.

**러너는 고른 것만 받아 온다.** `StageHandler.required_inputs`가 돌려주는 목록이 상류 keyframe 전부가 아니라 **모델에 실제로 넣을 것**이다. 상한을 넘는 장면에서는 시간순으로 양 끝을 포함해 고르게 고른다 — 앞에서 자르면 장면 뒷부분이 통째로 빠진다. 고르는 규칙은 결정론이다(§8).

**결과** — 이 단계는 `artifacts`를 만들지 않는다. 전부 `output`으로 간다.

```json
{
  "stage": "vlm_metadata",
  "status": "succeeded",
  "versions": {
    "stageVersion": "npick.stage.vlm_metadata/v1:2f0d224e",
    "outputSchemaVersion": "npick.stage.vlm_metadata.output/v2",
    "configVersion": "vlm-metadata-config/v2:c3b7d840",
    "modelVersion": "example/vlm@main",
    "promptVersion": "vlm-metadata-prompt/v2:2c686602",
    "detail": {
      "engine": "transformers",
      "engineVersion": "transformers5.0.0+torch2.13.0",
      "modelVersion": "example/vlm@main",
      "tokenizer": "query-norm/v1:b0d96c0c:kiwi0.23.2:model0.23.0"
    },
    "runtime": { "worker": "0.1.0", "python": "3.12.14", "torch": "2.13.0+cu130", "cuda": "13.0" }
  },
  "metrics": {
    "scenes": 10, "captionedScenes": 9, "tagCandidates": 24,
    "unknownShotTypes": 1, "keyframesSent": 23
  },
  "output": {
    "metadataSchemaVersion": "vlm-metadata/v2",
    "scenes": [
      {
        "sceneIndex": 8,
        "shotType": {
          "value": "b_roll",
          "confidence": 0.82,
          "evidence": [{ "sceneIndex": 8, "timestampMs": 71833,
                         "storageKey": "runs/…/frame_extraction/a1/s0008/kf-000071833.jpg" }]
        },
        "caption": {
          "value": "취재진이 모인 현장에서 관계자가 무언가를 가리키고 있다",
          "tokens": "취재진 모이다 현장 관계자 가리키다",
          "confidence": 0.77,
          "evidence": [{ "sceneIndex": 8, "timestampMs": 71833,
                         "storageKey": "runs/…/frame_extraction/a1/s0008/kf-000071833.jpg" }]
        },
        "tagCandidates": [
          { "type": "scene_type", "value": "사고 현장", "confidence": 0.61,
            "evidence": [{ "sceneIndex": 8, "timestampMs": 71833,
                           "storageKey": "runs/…/frame_extraction/a1/s0008/kf-000071833.jpg" }] }
        ]
      }
    ]
  }
}
```

**`promptVersion`이 처음으로 채워지는 단계다.** 앞의 세 단계는 가중치도 프롬프트도 쓰지 않아 두 키가 `null`이었다(§7). 여기서는 둘 다 값이 있고, `configVersion`과 `promptVersion`은 **다른 값**이다 — 앞엣것은 설정 파일 전체의 해시이고 뒤엣것은 어휘까지 채워 **렌더링된** 프롬프트의 해시다. 어휘를 바꾸면 템플릿이 그대로여도 `promptVersion`이 움직인다.

**`metadataSchemaVersion`은 또 다른 값이다.** `outputSchemaVersion`이 이 payload의 형식이라면, 이쪽은 **모델에게 요구한 JSON**의 버전이다(워커의 `vlm_metadata/schema.py`가 정본). 프롬프트를 고치지 않고도 바뀔 수 있고, 반대도 된다.

**keyframe은 `(sceneIndex, timestampMs)`로 가리킨다.** §4.3.2와 같은 이유다 — `keyframe_id`는 BE가 발급하고 `assignedIds`는 scene만 돌려준다. BE는 이 쌍으로 행을 찾아 `tag_evidence.source_ref_type='keyframe'`·`source_ref_id`를 채운다.

**텍스트 근거(v2)** — 모델은 제공된 `ocr_N`·`tr_N` 라벨을 기존 `evidence` 배열에서 인용한다. 라벨은 모델 출력용이며 DB ID가 아니다. 입력에 없는 라벨은 기존 근거 해석 경로에서 거부한다. `shotType`은 이미지 라벨만 사용한다. 기존 이미지 근거 객체는 유지하며 caption·tagCandidates의 근거에 다음 객체가 추가된다.

- OCR: `{sourceRefType: "ocr_observation", sceneIndex, timestampMs, storageKey, observationIndex}`. `observationIndex`는 현재 run의 원본 `ocr.observations` 배열 위치이며 DB ID가 아니다. OCR을 묶은 라벨은 해당 원본 관측 전부로 되돌린다.
- 대사: `{sourceRefType: "scene", sceneIndex, storageKey, segmentId, s, e, sourceDetail}`. `storageKey`는 원본 segments snapshot이며 `segmentId`는 그 안에서만 유일하다. 저장 시 scene 근거를 사용하되 구간 참조를 손실시키지 않는다.

출력 payload는 `npick.stage.vlm_metadata.output/v2`, 모델 schema는 `vlm-metadata/v2`다. 기존 이미지 전용 v1 소비자가 이 출력을 정상 데이터로 받아서는 안 된다. BE 저장·부분 저장 방지 및 대사 매핑 결과 생성은 각 담당 연동 구현에서 이 계약과 맞춰야 한다. 이 확장만으로 해당 저장 경로가 완성됐음을 의미하지 않는다.

**`shotType`은 항상 있고 `caption`은 없을 수 있다.** `scene.shot_type`이 `NOT NULL`이고 근거가 없을 때 쓸 값이 어휘 안에 있기 때문이다(`unknown`). 반대로 캡션과 태그는 없으면 없는 것이라 `null`·빈 배열이 정상 payload다. **`evidence`가 빈 배열일 수 있는 곳은 `shotType`의 `unknown` 하나뿐이다.**

**단계가 실패해도 `scene.shot_type`은 채워져야 한다.** 그 컬럼이 `NOT NULL`인데 이 단계는 비치명이므로, VLM이 실패한 run에서도 scene 행은 만들어진다. 그때 BE가 쓰는 값은 `unknown`이다 — 워커가 그 사실을 보고할 자리가 없으므로 여기 적어 둔다.

**`caption.tokens`는 워커가 만든다.** BE에 Kiwi가 없고([02-container.md](../architecture/02-container.md)) 색인과 질의가 같은 설정을 써야 한다. `ocr_observation.tokens`와 같은 규약이고 `versions.detail.tokenizer`가 그 설정의 식별자를 싣는다. 공백으로 이어진 문자열이 그대로 `scene.caption_tokens`가 된다.

**`tagCandidates`는 태그가 아니다.** `tag`·`tagging` 행을 만드는 일과 `tag.match_value` 정규화(NFKC + 공백 제거)는 BE의 몫이고, 이 값은 8단계 `entity_extraction`이 모으는 후보와 같은 성격이다. **날짜 유형(`filmed_date`·`broadcast_date`)은 이 payload에 올 수 없다** — 워커 쪽 schema에 그 유형이 없다. 화면에 날짜가 보인다는 사실과 그것이 방송일·촬영일이라는 판단은 다르고, 후자는 OCR 신뢰도와 원본 문맥을 확인한 뒤의 일이다([docs/frd.md](../frd.md) §3 F-04).

**전부 미검증이다.** 이 payload에 검증 상태 필드가 없는 것은 빠뜨려서가 아니라 값이 하나이기 때문이다. 저장할 때 `tag_evidence.source`는 `vlm`, `verification_status`는 `unverified`다. **BE는 confidence가 높다는 이유로 `verified`로 올리지 않는다**([docs/frd.md](../frd.md) §3 F-04: "ASR·VLM·일반 추론 규칙은 기본 미검증"). 사람의 승인 판단은 `reviewer_feedback`으로 따로 남는다.

**형식이 틀린 출력은 통째로 버린다.** 워커가 JSON·schema·어휘·근거를 검사하고, 하나라도 어긋나면 그 출력의 **어떤 필드도** 쓰지 않는다([docs/frd.md](../frd.md) §3 F-03: "형식이 잘못된 출력을 정상 데이터에 부분 적용하지 않는다"). 거부는 `VLM_SCHEMA_INVALID`이고 §9.2대로 **영구**다 — 워커가 `temperature: 0`으로 부르므로 다시 물어도 같은 답이 온다.

**장면 하나가 깨지면 그 단계 전체가 실패한다.** 깨진 장면만 빼고 나머지를 반납하면 그 장면은 "설명이 없는 장면"으로 저장되어, 실패가 정상 데이터로 보인다. 비치명 단계이므로 run은 계속 가고 OCR·ASR 결과는 그대로 쓰인다.

**오류 코드** — 이 단계 전용 코드는 §9.2의 `VLM_SCHEMA_INVALID` 하나다. 나머지는 공통 어휘를 쓴다: 상류 산출물이 잘못됐으면 `VALIDATION_ERROR`(영구), keyframe JPEG을 열 수 없으면 `UNSUPPORTED_MEDIA`(영구), 가중치를 준비하지 못했으면 `MODEL_UNAVAILABLE`(일시), VRAM이 모자라면 `OUT_OF_MEMORY`(일시), 외부 전송 조건이 확인되지 않으면 `EXTERNAL_PROCESSING_NOT_ALLOWED`(영구), 그 밖의 실패는 `STAGE_FAILED`(일시)다.

**외부 제공자는 기본 경로가 아니다.** [02-container.md](../architecture/02-container.md)의 *요소* 표가 이 단계를 워커의 자체 GPU에 두고, 외부 호출은 PRD §12.4의 조건을 **전부** 만족할 때만 쓰는 대체 경로다. 그 조건 중 하나인 clip별 외부 처리 권리 확인을 실어 보내는 자리가 이 계약에 없으므로, 지금 워커의 외부 경로는 항상 전송 전에 fail-closed한다. 자리를 만들려면 이 문서와 §4.1을 함께 고쳐야 한다.

**배정 조건** — 워커는 가중치 이름이 설정돼 있을 때만 이 단계를 `capabilities`에 싣는다. 모델이 없는 워커가 배정받아 매번 `MODEL_UNAVAILABLE`로 죽는 것보다 배정받지 않는 편이 낫다. CPU 전용 워커가 scene 분할·keyframe 추출·OCR만 도는 구성이 그래서 성립한다.

### 4.3.4 `text_embedding` — 장면 dense 벡터

캡션과 최종 채택 대사를 **합쳐** 장면마다 벡터 하나를 만든다. 그 벡터가 `scene.embedding vector(1024)` 한 칸에 들어가고 pgvector 로 색인돼 BM25 순위와 RRF 로 결합된다([docs/frd.md](../frd.md) §11 결정 표).

**입력** — `inputs.upstream.vlmMetadata`가 필수다. 없으면 워커는 `VALIDATION_ERROR`(영구)로 실패를 신고한다. 빈 결과를 성공으로 반납하면 "이 클립에는 임베딩할 텍스트가 없다"는 거짓이 정본에 남는다. 대사는 `scene_transcript_mapping`에서 오고 **선택**이다 — 비치명 상류라 없을 수 있다. 다만 그 값이 **있는데 모양이 틀리면** 이 단계는 `vlm_metadata`와 같이 거절한다(§4.5). 대사가 빠진 벡터를 정본에 넣으면 되돌리는 값이 전체 재색인이다.

**OCR 은 입력이 아니다.** 화면 글자는 `ocr_observation.tokens`로 BM25 채널에 이미 들어가 있고, 같은 문자열을 dense 채널에도 넣으면 RRF 결합에서 한 신호가 두 번 세어진다.

요청 — 성공:

```json
{
  "stage": "text_embedding",
  "status": "succeeded",
  "versions": {
    "stageVersion": "npick.stage.text_embedding/v1:1f2e3d4c",
    "outputSchemaVersion": "npick.stage.text_embedding.output/v1",
    "configVersion": "text-embedding/v1:9a8b7c6d",
    "modelVersion": "dragonkue/snowflake-arctic-embed-l-v2.0-ko@55ec6e93…",
    "promptVersion": null,
    "detail": { "engine": "sentence-transformers", "engineVersion": "sentence-transformers/5.1.2",
                "modelVersion": "dragonkue/snowflake-arctic-embed-l-v2.0-ko@55ec6e93…" }
  },
  "metrics": { "scenes": 87, "embedded": 83, "skipped": 4 },
  "output": {
    "embeddingsArtifact": { "kind": "scene_embeddings",
                            "storageKey": "runs/398021847361024/text_embedding/a1/embeddings.json",
                            "byteSize": 1712640, "contentHash": "<sha256>" },
    "dimension": 1024,
    "embeddedCount": 83,
    "skippedSceneIndexes": [7, 13, 40, 71]
  },
  "artifacts": [ { "kind": "scene_embeddings", "storageKey": "runs/…/embeddings.json",
                   "byteSize": 1712640, "contentHash": "<sha256>" } ]
}
```

artifact 본문 (`npick.scene.embeddings/v1`):

```json
{
  "schemaVersion": "npick.scene.embeddings/v1",
  "dimension": 1024,
  "scenes": [ { "sceneIndex": 0, "vector": [0.0123, -0.0456, "…1024개"], "sourceText": "광안대교 야경\n오늘 축제가 열렸습니다" } ]
}
```

**벡터를 `output`에 인라인하지 않는다.** 1024 차원 float 하나가 JSON 으로 20KB 급이라 장면 수십 개면 `stage_states_json` 한 행이 MB 단위가 된다. 그 컬럼은 run 을 읽을 때마다 통째로 오간다. `transcript_selection`이 세그먼트를 artifact 로 빼는 것과 같은 판단이다(§4.5).

**`sourceText`는 저장되지 않는다.** 담을 컬럼이 없다. 그래도 artifact 에는 남아야 한다 — 캡션이 교정된 뒤 "그때 무엇을 임베딩했나"를 물을 수 있어야 하고([docs/frd.md](../frd.md) §7.2), 벡터만으로는 그 답이 나오지 않는다.

**텍스트가 없는 장면은 벡터가 없다.** 캡션도 대사도 비면 `skippedSceneIndexes`에 들어가고 `scene.embedding`은 `NULL`로 남는다. **실패가 아니다** — 그 컬럼이 nullable 인 이유이고 그 장면은 BM25 채널로만 검색된다. 빈 문자열을 임베딩하면 모든 빈 장면이 서로 최근접이 되어 보조 채널이 오염된다.

**BE 의 거부 조건** — `embeddingsArtifact`가 `artifacts`에 등록되지 않았거나, **`versions.detail.modelVersion`이 `<모델>@<40자리 hex>` 형식이 아니거나**, artifact 의 `schemaVersion`·`dimension`이 payload 와 다르거나, **`dimension`이 `scene.embedding` 컬럼의 폭과 다르거나**, 벡터 길이가 `dimension`과 다르거나, 성분에 유한하지 않은 값(NaN·inf)이 있거나, `sourceText`가 없거나 공백뿐이거나, `sceneIndex`가 범위 밖·중복이거나, `skippedSceneIndexes`가 범위 밖·중복이거나 `sceneIndex`와 겹치거나, **임베딩과 생략을 합쳐 그 run 의 장면 전체를 덮지 않거나**, `embeddedCount`가 artifact 의 장면 수와 다르면 **결과 전체를 거절한다**.

**`modelVersion`이 고정 리비전이어야 하는 이유는 무증상이기 때문이다.** 이 형식은 dense 리더가 SQL 로 강제하는 것과 같아야 한다(`DenseSceneCandidateAdapter`) — 어긋난 벡터는 저장까지 되고 `indexing`의 `embeddedScenes` 대조(`embedding IS NOT NULL`)도 통과하는데, 검색에서는 `missing_model`로 후보에서 전량 제외된다. 정본·요약·채널 상태가 전부 정상이라고 말하는데 dense 채널만 조용히 죽는 조합이라, `dimension`처럼 시끄럽게 실패하지 않는다. 워커도 `_declared_version()`에서 같은 가드를 걸지만 그것은 검증 대상이 스스로 만드는 보장이므로, 아래 컬럼 폭과 같은 이유로 BE 가 따로 본다.

**컬럼 폭 대조는 다른 셋과 축이 다르다.** `output.dimension`·artifact 의 `dimension`·실제 벡터 길이는 셋 다 워커가 만드는 값이라 서로 맞는 것만으로는 아무것도 보장하지 않는다 — 워커 설정만 768 로 바꾸고 마이그레이션을 두면 셋이 사이좋게 통과한다. BE 는 `pg_attribute`에서 실제 컬럼 폭을 읽어 대조한다(상수로 박으면 어긋날 수 있는 자리가 하나 더 생긴다). 이 대조가 없으면 `vector(1024)` 컬럼이 트랜잭션 전체를 SQL 오류로 끊고, 워커가 받는 것은 `JOB_400_001`이 아니라 **500** 이다 — 그 응답은 재시도 가능으로 분류돼 단계가 실패로 기록조차 되지 않은 채 lease 만료 → 재배정을 반복한다.

**`dimension`을 바꾸면 전체 재색인이다**([docs/frd.md](../frd.md) §11). 워커의 `config/text_embedding.v1.toml`과 `scene.embedding vector(N)`이 같은 값이어야 하고, 둘을 함께 고치지 않으면 저장이 통째로 실패한다.

**오류 코드** — 전용 코드를 만들지 않는다. 상류 산출물이 잘못됐으면 `VALIDATION_ERROR`(영구), 가중치를 준비하지 못했으면 `MODEL_UNAVAILABLE`(일시), VRAM 이 모자라면 `OUT_OF_MEMORY`(일시), 나머지는 `STAGE_FAILED`(일시)다.

**비치명이다.** 실패해도 run 은 계속 가고 그 클립은 BM25 채널로만 검색된다.

**배정 조건** — 워커는 임베딩 런타임과 모델 이름이 둘 다 있을 때만 이 단계를 `capabilities`에 싣는다. 런타임이 없는 워커가 `engineVersion`을 `unknown`으로 선언하면, 나중에 설치됐을 때 같은 단계의 `stageVersion`이 조용히 바뀌어 §7 의 버전 불일치가 난다.

### 4.3.5 `indexing` — 색인 재료 요약

**색인을 만들지 않는다.** 외부·비동기 색인을 쓰지 않고([docs/frd.md](../frd.md) §11) BM25·pgvector 인덱스는 baseline 마이그레이션이 정본이다. 색인 재료도 상류가 이미 채웠다 — `scene.caption_tokens`는 `vlm_metadata`, `ocr_observation.tokens`는 `ocr`, `scene.embedding`은 `text_embedding`이 완료될 때 BE 가 넣는다.

그래서 이 단계에 남는 일은 **무엇이 들어왔는지를 세어 남기는 것**뿐이다. 그럼에도 단계가 있어야 하는 이유는 하나다 — 이 단계의 성공이 곧 게시 신호다. `indexing`이 `succeeded`로 반납될 때만 BE 가 게시 가능 판정을 돌리고 `clip.active_pipeline_run_id`를 전환한다.

요청 — 성공:

```json
{
  "stage": "indexing",
  "status": "succeeded",
  "versions": {
    "stageVersion": "npick.stage.indexing/v1:44136fa3",
    "outputSchemaVersion": "npick.stage.indexing.output/v1",
    "configVersion": null, "modelVersion": null, "promptVersion": null, "detail": {}
  },
  "metrics": { "scenes": 87 },
  "output": {
    "sceneCount": 87, "captionedScenes": 80, "dialogueScenes": 61,
    "ocrScenes": 44, "embeddedScenes": 83
  },
  "artifacts": []
}
```

**`stageVersion`의 재현 튜플이 비어 있다.** 설정도 모델도 토크나이저도 쓰지 않는 단계라 결과를 바꿀 수 있는 입력이 상류 산출물뿐이고, 그것은 버전이 아니라 데이터다.

**채널별 수는 전부 "장면 수"다.** `ocrScenes`가 관측 수가 아닌 이유가 그것이다 — 한 장면에서 여러 프레임을 읽으므로 관측 수는 다른 칸과 축이 다르고, 나란히 놓으면 사람이 반드시 잘못 읽는다.

**`captionedScenes`·`ocrScenes`는 산문이 아니라 색인 토큰을 센다.** 게시 판정이 보는 것이 `scene.caption_tokens`·`ocr_observation.tokens`이고, 조사·기호뿐인 설명은 `caption.value`가 있어도 그 컬럼이 빈다. 산문을 세면 "캡션 채널은 80으로 차 있는데 왜 게시가 안 되지"가 된다. **`dialogueScenes`만 예외다** — 대사의 색인 토큰은 BE 가 만들고 워커에게는 그 값이 없어서, 연결된 세그먼트가 있는 장면 수를 센다.

**게시 가능 판정을 하지 않는다.** 그 정본은 BE 의 `JdbcClipPublicationAdapter`이고, 거기에는 워커가 볼 수 없는 것(keyframe 파일이 디스크에 실제로 있는가)이 들어간다. 같은 판정을 두 곳에서 하면 둘이 갈라지는 날 원인을 찾을 수 없다. **채널이 전부 0 이어도 이 단계는 성공한다** — 게시를 막는 것은 BE 의 일이고, 이 요약은 게시가 안 됐을 때 어느 채널이 비었는지를 사람이 보는 값이다.

**BE 의 거부 조건** — `sceneCount`가 그 run 의 저장된 `scene` 행 수와 다르거나, 채널 수 중 하나라도 `sceneCount`를 넘거나, `embeddedScenes`가 `embedding IS NOT NULL` 인 행 수와 다르면 결과 전체를 거절한다. 네 채널 중 `embeddedScenes`만 대조하는 이유는 나머지 셋의 재료를 **아직 어느 어댑터도 쓰지 않기 때문이다**(§11-12) — 대조할 행이 없다. 저장할 자리가 없으므로 BE 는 이 단계에서 **아무 행도 쓰지 않고** `assignedIds`도 비운다.

**배정 조건** — 모델도 설정도 쓰지 않으므로 이 단계는 어느 워커에서나 `capabilities`에 실린다.

**치명이다.** `stages.py`가 이 단계를 `fatal=True`로 둔다. 최종 실패는 run 을 `failed`로 만들고 그 클립은 게시되지 않는다.

**오류 코드** — 상류 산출물이 잘못됐으면 `VALIDATION_ERROR`(영구), 그 밖의 정체 모를 실패는 `INDEX_FAILED`(일시)다.

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
| `transcript_segments`, `transcript_decisions`, `ocr_result` | `application/json` |

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
최종 선택은 워커 `scene_transcript_mapping` 직전에 수행한다. VLM은 그 단계 이후 실행한다.
`scene_transcript_mapping`의 output은 다음 구조다. 정본 타입은 워커 `jobs/transcripts.py`의 `SceneTranscriptMappingOutput`이며 단계 출력 버전은 `npick.stage.scene_transcript_mapping.output/v1`이다.

```json
{
  "transcript": {
    "segmentsArtifact": {"kind": "transcript_segments", "storageKey": "runs/…/segments.json", "byteSize": 1234, "contentHash": "<sha256>"},
    "decisionsArtifact": {"kind": "transcript_decisions", "storageKey": "runs/…/decisions.json", "byteSize": 567, "contentHash": "<sha256>"}
  },
  "scenes": [
    {"sceneIndex": 0, "segments": [{"segmentId": "s1", "overlapMs": 1500}]},
    {"sceneIndex": 1, "segments": []}
  ]
}
```

- `transcript`는 최종 선택 snapshot의 두 ArtifactRef다. 기존 원본·선택 JSON 형식과 무결성 검증을 재사용하고, 두 참조를 단계 결과의 `artifacts`에도 등록한다.
- `scenes`에는 해당 실행의 모든 장면이 정확히 한 번씩 존재한다. 대사가 없으면 `segments: []`다. 같은 장면 안의 구간 ID 중복은 금지한다.
- 각 연결은 해당 snapshot에서 `selected=true`인 구간만 가리킨다. `overlapMs`는 양의 정수이고 원본 구간 길이를 넘을 수 없다. 생산 단계가 장면과 실제로 겹치는 구간만 연결하고 정확한 겹침 시간을 계산한다. 여러 장면과 겹치면 같은 ID를 각각 연결하며, 겹치지 않는 구간은 연결하지 않는다.
- 원문 `t`·정수 ms `s/e`·`sourceDetail`은 원본 snapshot에서 구간 ID로 읽는다. 장면 연결 목록에는 중복 복사하지 않는다. ID의 범위는 segments artifact 하나다.
- 소비자는 snapshot 관계·채택 여부·장면 및 구간 ID를 검사한다. 알 수 없는 구간·보관 전용 구간·누락/중복 장면·잘못된 artifact는 빈 결과로 처리하지 않는다.
- BE의 `scene.transcript_json` 저장 시 연결 ID를 원본과 결합해 기존 `s/e/t/overlap_ms` 형식으로 변환한다. 출력의 `overlapMs`와 저장 JSON의 `overlap_ms`를 구분한다. 토큰 생성·저장 및 실제 매핑 알고리즘은 해당 단계/저장 어댑터 책임이며 VLM 소비자가 대신 수행하지 않는다.

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

`vlm_metadata`는 축이 다섯이다 — `{configVersion, engine, engineVersion, modelVersion, tokenizer}`. `modelVersion`이 있는 이유는 가중치가 바뀌면 같은 프레임에서 다른 문장이 나오기 때문이고, `tokenizer`는 `scene.caption_tokens`가 이 단계의 산출물이기 때문이다. `promptVersion`은 축이 아니다 — 프롬프트가 설정 파일의 한 절이라 `configVersion`이 이미 그것을 덮는다. 위 벡터의 `engineVersion`·`modelVersion`은 **예시 값**이다. 실제 값은 설치된 런타임과 설정에서 오므로, 이 벡터가 고정하는 것은 해시 함수와 키 이름이다.

`ocr`은 축이 다섯이다 — `{configVersion, engine, engineVersion, tokenizer, mergeConfigVersion}`. `tokenizer`는 색인 규칙, `mergeConfigVersion`은 병합 규칙·설정의 식별자다. 어느 쪽이 바뀌어도 `stageVersion`이 바뀐다. 출력 스키마는 `npick.stage.ocr.output/v2`이며 다른 단계의 v1에는 영향이 없다.

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
| `frame_extraction.v2.toml` 기본 설정 | `configVersion` = `frame-extract/v2:a0684794` |
| `{configVersion: frame-extract/v2:a0684794, engine: pyav, engineVersion: 18.1.0+numpy2.5.2}` | `stageVersion` = `npick.stage.frame_extraction/v1:5fa70a50` |
| 보존된 `vlm_metadata.v1.toml` 이미지 전용 설정 | `configVersion` = `vlm-metadata-config/v1:13d50f07` |
| 보존된 v1 설정의 **렌더링된** 프롬프트 | `promptVersion` = `vlm-metadata-prompt/v1:78a02dbd` |
| `{configVersion: vlm-metadata-config/v1:fcd15e10, engine: transformers, engineVersion: transformers5.0.0+torch2.13.0, modelVersion: example/vlm@main, tokenizer: query-norm/v1:b0d96c0c:kiwi0.23.2:model0.23.0}` | `stageVersion` = `npick.stage.vlm_metadata/v1:325198af` |
| `vlm_metadata.v2.toml` 기본 설정 | `configVersion` = `vlm-metadata-config/v2:c3b7d840` |
| v2 기본 설정의 렌더링된 프롬프트 | `promptVersion` = `vlm-metadata-prompt/v2:2c686602` |
| 위 v2 configVersion과 기존 예시 engine·engineVersion·modelVersion·tokenizer | `stageVersion` = `npick.stage.vlm_metadata/v1:2f0d224e` |
| `ocr.v1.toml` 기본 설정 | `configVersion` = `ocr/v1:daaf4c83` |
| `ocr-merge.v1.toml` 기본 설정 | `mergeConfigVersion` = `ocr-merge/v1:28d42216` |
| `{configVersion: ocr/v1:daaf4c83, engine: rapidocr, engineVersion: rapidocr3.9.2+onnxruntime1.29.0, tokenizer: query-norm/v1:b0d96c0c:kiwi0.23.2:model0.23.0, mergeConfigVersion: ocr-merge/v1:28d42216}` | `stageVersion` = `npick.stage.ocr/v1:bc75979d` (출력 v2) |
| 같은 벡터에서 `mergeConfigVersion` 을 뺀 것 (S15P21A501-95 이전·출력 v1) | `stageVersion` = `npick.stage.ocr/v1:449d6928`. **해시 함수 회귀용이며 지금 워커가 내는 값이 아니다** |
| `{scene_detection: npick.stage.scene_detection/v1:aaaaaaaa, ocr: npick.stage.ocr/v1:bbbbbbbb}` | `pipelineVersion` = `npick-pipeline/v1:64960bae4565` |
| `{scene_detection: npick.stage.scene_detection/v1:aaaaaaaa, frame_extraction: npick.stage.frame_extraction/v1:cccccccc}` | `pipelineVersion` = `npick-pipeline/v1:32d2389f906a` |

v1의 기존 해시 재현을 위해 `version_number="1"`인 설정은 값이 0인 `max_ocr_chars`·`max_transcript_chars` 키를 config 해시 payload에서 각각 제외한다. 0이 아닌 값은 포함하며 v2는 두 키를 항상 포함한다. Java에서 config 해시를 재현할 때도 같은 규칙을 적용한다. v1 stageVersion 행은 명시된 예시 configVersion을 입력으로 사용하는 기존 해시 함수 벡터이며, 현재 보존된 v1 설정 파일의 해시와 구분한다.

### 버전 불일치

| 시점 | 처리 | 이유 |
| --- | --- | --- |
| claim | 워커의 `capabilities[].stageVersion`이 run의 기대 버전과 다르면 **배정하지 않는다** | GPU 분을 쓰기 전에 거른다 |
| complete | 결과는 **받아 저장하고** `versionMismatch`를 남긴다. `indexing` 성공 시 `clip.active_pipeline_run_id` 전환을 **거부**하고 run을 `failed`/`PIPELINE_VERSION_MISMATCH`로 끝낸다 | 중간에 하드 거절하면 이미 쓴 GPU 시간을 통째로 버리고, 조용히 받아들이면 `pipeline_version`이 거짓말이 된다 |

## 8. 멱등성

**키는 `{pipelineRunId}:{stage}:{attempt}`이고 BE가 발급한다.** 워커는 claim에서 받아 `complete`의 `Idempotency-Key` 헤더와 본문에 그대로 되돌린다. 만들지 않는다.

> 같은 `Idempotency-Key`로 도착한 `complete`는 **최초 1회만** 정본을 바꾼다. 두 번째부터는 최초에 저장한 결과를 그대로 돌려주며 `duplicate: true`를 붙인다(200, 오류가 아니다). 본문이 최초와 다르면 — 정규화 JSON의 sha256 비교 — 정본을 바꾸지 않고 `JOB_409_003`으로 거절한다.

별도 멱등성 테이블을 만들지 않는다. 단계별 `completions`에 멱등성 키를 인덱스로 하여
수락된 요청의 `requestSha256`·`leaseId`·`workerId`·`response`를 보존한다.
`lastIdempotencyKey`·`lastRequestSha256`·`completedLeaseId`·`completedWorkerId`·`completion`은
마지막 완료 정보로 유지한다. 구 기록은 다음 배정에서 worker를 덮기 전에 보존한다.
`complete` 처리는 `SELECT … FOR UPDATE` 안에서 정본 저장과 같은 트랜잭션으로 수행한다.
다음 attempt의 배정·완료나 프로세스 재기동 이후에도 이미 수락한 원래 lease·worker의 동일 요청은
원래 응답을 반환한다. 다른 worker·lease는 `JOB_409_002`, 같은 키의 다른 본문은 `JOB_409_003`이다.
아직 수락하지 않은 회수된 lease 결과는 완료 이력이 없으므로 fencing으로 거절한다.

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

BE는 `pipeline.yml`의 `defaults.retry_count`와 단계별 `stage_overrides.<stage>.retry_count`를 읽는다.
`null`은 추가 시도 0회, 정수 N은 최초 시도를 포함한 `maxAttempts=N+1`이다.
`transient_errors`는 횟수와 별도의 허용 목록이며, 위 오류 계약상 일시 오류이면서
워커가 `retryable=true`로 신고한 `failed` 결과만 예산 안에서 재시도한다.
영구·미등록 코드와 `skipped`는 설정으로 재시도할 수 없다. `MEDIA_UNAVAILABLE`의 파일 부재와
`ARTIFACT_UPLOAD_FAILED`의 해시 불일치처럼 `retryable=false`인 상세 분류도 재시도하지 않는다.

재시도 수락 시 해당 단계만 `pending`이 되고 `retryScheduled=true`를 기록한다.
`attempts`는 완료한 시도 번호를 유지하며 다음 claim에서 한 번 증가한다. 이때 멱등성 키와
`outputKeyPrefix`도 새 attempt 값으로 바뀐다. lease 회수는 같은 attempt를 재배정한다.
이전 실패는 같은 단계의 `failedAttempts`에 attempt·오류·종료 시각·멱등성 키로 보존한다.
최종 실패·누락은 기존 단계 `status`·`error`·`errorCode`에 남고 성공 결과만 upstream으로 전달된다.
`retryScheduled`는 BE 판단이며 기존 `errorRetryable`은 워커 신고 의미를 유지한다.

각 단계의 최초 배정에서 `retryPolicy`에 `maxAttempts`와 `transientErrors`를 저장한다.
배정 응답·완료 판정·lease 재배정은 이 정책을 사용한다. 프로파일 변경은 아직 배정하지 않은
단계부터 적용되며, 이미 시작한 단계의 예산이나 일시 오류 목록을 재기동 시 바꾸지 않는다.
정책 기록 전에 시작한 구 단계는 기존 시도(이미 예약된 다음 시도가 있으면 그 1회)까지만
보존하고 추가 자동 재시도를 새로 허용하지 않는다.

BE 자막 입력 준비 실패도 같은 오류 계약을 사용한다. 저장 오류로 감싼 내부 원인까지 확인하여
잘못된 자막은 `INVALID_TRANSCRIPT`·`retryable=false`, 영구 입력·권한 오류와 파일 부재도
`retryable=false`로 기록한다. 서비스 일시 불가·시간 초과는 설정된 예산 안에서 재시도할 수 있다.
일시 여부가 확인되지 않은 오류는 재시도하지 않는다. 원본 예외 메시지 대신 기존 오류 코드만
`error.detail.sourceErrorCode`에 남기며 준비 실패라는 사실은 `phase=input_preparation`으로 구분한다.

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
12. **stage output 저장 어댑터가 지원하는 단계를 늘린다.** BE는 `StageOutputPort.supports(stage)`가 거짓인 단계를 워커 `capabilities`에서 **제거한다**(`WorkerExecutionBinding`). 그래서 워커가 구현하고 버전을 선언해도 그 단계는 배정되지 않고, 강제로 결과를 보내도 `validateAndStore`의 `default` 분기에서 거절된다.
    현재 어댑터가 지원하는 단계는 `scene_detection`·`frame_extraction`·`ocr`·`transcript_selection`·`asr`·`vlm_metadata`·`text_embedding`·`indexing` 여덟이다(`text_embedding`·`indexing`은 `S15P21A501-183`, `ocr`·`vlm_metadata`는 `S15P21A501-184`). **남은 것은 `scene_transcript_mapping`과 `entity_extraction` 둘이며 양쪽 다 없다.** `scene_transcript_mapping`은 워커 쪽이 `S15P21A501-98`, BE 저장 쪽이 `S15P21A501-191`이다. **후자를 그대로 집으면 막힌다** — `scene.transcript_text`·`transcript_tokens`·`transcript_json`·`transcript_source`가 받을 자리인데, 그 단계 output에는 `segmentId`·`overlapMs`만 있고 segments artifact의 구간도 `t`(원문)까지라 **Kiwi 토큰을 싣는 자리가 없다.** `ocr_observation.tokens`·`scene.caption_tokens`와 같은 규약(BE는 토큰을 만들지 않는다)을 지키려면 워커 output에 장면별 토큰을 더하고 §4.5를 함께 고쳐야 한다. `-98`에 그 요청을 남겼다.
    **출력 스키마가 v2인 단계가 둘이다** — `ocr`(`npick.stage.ocr.output/v2`, §4.3.2, S15P21A501-95)과 `vlm_metadata`(`npick.stage.vlm_metadata.output/v2`, S15P21A501-92). `PipelineStages.outputSchema`가 그 예외 목록을 들고 있고 배정 payload·`complete` 검사·저장 어댑터가 모두 그 표 하나를 읽는다. 문자열을 따로 조립하는 자리를 다시 만들면 배정과 검사가 갈려 성공 결과가 저장 분기에 닿기도 전에 거절된다.
    `ocr` 저장 어댑터는 관측 행을 저장하고 `kind: "ocr_result"` 산출물 참조를 보존한다. `textGroups`·`mergeConfigVersion`은 `ocr_observation`에 담을 칸이 없고 별도 그룹 테이블도 만들지 않으므로, 그 둘의 영구 보관처는 산출물 파일이다 — 그룹은 원본 관측 배열의 인덱스라 배열을 재정렬하거나 일부만 저장하면 참조가 끊긴다. 그래서 어댑터는 파일의 `output`이 `complete`의 `output`과 **같은지**까지 확인한다. 같은 이유로 BE가 `vlm_metadata`에 넘기는 `inputs.upstream.ocr`에는 `textGroups`가 없다(§4.3.3). 워커의 `UpstreamOcrOutput`이 그것을 요구하지 않는다.

    **`vlm_metadata`는 배선됐지만 아직 배정되지 않는다.** `nextStage()`는 첫 `pending` 단계를 고르고 `supports()`가 거짓인 단계는 claim `capabilities`에서 지워지므로, 6단계 `scene_transcript_mapping`이 뚫리기 전까지 run은 7단계에 도달하지 못한다. 그래서 이 단계의 end-to-end 확인은 위의 남은 티켓이 끝나야 가능하고, 그때까지 검증은 저장 어댑터의 DB 테스트가 전부다. `ocr`은 3단계라 `asr`까지 이어서 확인된다. 게시 조건이 `scene.caption_tokens`·`scene.transcript_tokens`·`ocr_observation.tokens` 중 **하나 이상이 비어 있지 않을 것**이라(`JdbcClipPublicationAdapter`), 등록→게시 end-to-end는 `ocr`이 실제로 글자를 읽어 온 클립에서 처음 성립한다.

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
| 미구현 3단계 — `transcript_selection`은 워커 구현만, `scene_transcript_mapping`·`entity_extraction`은 양쪽 다 없다 | 각 단계 티켓. capabilities에 없는 단계는 미배정이며, 배정 후 어댑터가 없으면 `NO_ADAPTER`로 보고한다. `text_embedding`·`indexing`은 `S15P21A501-183`, `ocr`·`vlm_metadata`는 `S15P21A501-184`에서 BE 저장이 배선됐다 |
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
