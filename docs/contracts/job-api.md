# 인터페이스 계약 — 잡 API (서비스 서버 ↔ 파이프라인 워커)

> **문서 유형** 인터페이스 계약 · **상태** 초안 (S15P21A501-70)
> **소유** BE·AI 공동. 바꾸려면 양쪽 티켓이 함께 필요하다.
> **기준 문서** [docs/frd.md](../frd.md) (FRD v3.1, 정본) · [docs/prd.md](../prd.md) · [02-container.md](../architecture/02-container.md) · [03-deployment.md](../architecture/03-deployment.md)
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

### 4.4 artifacts — 입력 내려받기 / 산출물 올리기

```
GET /api/v1/internal/jobs/{runId}/artifacts?key={storageKey}
  → 200 application/octet-stream (봉투 없음)
PUT /api/v1/internal/jobs/{runId}/artifacts/{storageKey}
  Content-Type: image/png
  X-Content-SHA256: <hex>
  → 201 (봉투 없음, 빈 본문)
```

`storageKey`는 미디어 루트 상대 경로다. `clip.storage_key`·`keyframe.storage_key`와 같은 어휘를 쓰고 새 식별자를 만들지 않는다.

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

입력은 그 단계의 **재현 튜플 전체**다. `scene_detection`이면 `{configVersion, detector, engine, engineVersion}`.

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
| `{scene_detection: npick.stage.scene_detection/v1:aaaaaaaa, ocr: npick.stage.ocr/v1:bbbbbbbb}` | `pipelineVersion` = `npick-pipeline/v1:64960bae4565` |

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

## 9. 오류 코드

### 9.1 HTTP 계층 — `JOB_` 접두

BE의 실제 `ErrorType`(`BAD_REQUEST`, `UNAUTHORIZED`, `FORBIDDEN`, `NOT_FOUND`, `CONFLICT`, `SERVICE_UNAVAILABLE`, `INTERNAL_SERVER_ERROR`)에 맞춘다.

| code | HTTP | 의미 | 워커의 정해진 반응 |
| --- | --- | --- | --- |
| `JOB_400` | 400 | 요청 형식 오류 | 버그. 재시도 금지 |
| `JOB_400_001` | 400 | 결과 봉투가 §4.3의 거부 조건에 걸림 | 재시도 금지 |
| `JOB_400_002` | 400 | 산출물 sha256 불일치 | 1회 재시도 후 `ARTIFACT_UPLOAD_FAILED` |
| `JOB_401` | 401 | 토큰 없음·불일치 | **루프 중단** |
| `JOB_403_001` | 403 | `outputKeyPrefix` 밖의 키 | **해당 단계만 실패로 보고. 워커 루프는 계속** |
| `JOB_403_002` | 403 | fleet 불일치 | 프로세스 종료 |
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

v2.2의 `ROLE_FORBIDDEN`은 **승계하지 않는다.** 워커에 역할 개념이 없고 `JOB_401`/`JOB_403_002`가 같은 사실을 더 정확히 말한다.

**재시도 정책** — 영구는 `attempts`를 동결하고 재claim하지 않는다. 일시는 `attempts < maxAttempts`일 때만 재claim한다. 치명 단계(`stages.py`의 `fatal=True`: `scene_detection`·`frame_extraction`·`indexing`)의 최종 실패는 run을 `failed`로 만들고, 비치명 단계 실패는 run을 계속 진행시킨다([docs/frd.md](../frd.md) §3 F-03).

## 10. 시간 수치

Gate D 품질 임계값이 아니라 **프로토콜 타임아웃**이므로 실측 없이 고정해도 "실행 환경 수치를 만들지 않는다"를 위반하지 않는다. 품질 수치(`retry_count`·`timeout_seconds`·`concurrency`)는 `pipeline.yml`에 `null`로 남는다.

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

잡 루프는 라우트가 아니라 FastAPI lifespan 태스크로 돈다. **워커에 인바운드 잡 엔드포인트가 생기지 않는다.**

`NPICK_AI_JOB_POLL_ENABLED`가 배포 단위 둘을 가른다 — 같은 이미지가 이 값 하나로 "잡을 도는 파이프라인 워커"와 "폴링하지 않는 질의 리졸버"가 된다. 컨테이너를 실제로 쪼갤 때 바뀌는 것은 이 값과 `NPICK_AI_JOB_API_BASE_URL`뿐이다.

## 13. 미결

| 항목 | 어디서 정하는가 |
| --- | --- |
| 토큰 발급·회전 절차 | 인프라 티켓 |
| 단계 재시도 횟수·타임아웃 | Gate D 실측 후 `infra/compose/profiles/pipeline.yml` |
| 미구현 9단계 | 각 단계 티켓. 그동안 워커는 `NO_ADAPTER`로 생략을 보고한다 |
| 협조적 취소 | 별도 티켓 (§4.2의 한계) |
| 리졸버/워커 컨테이너 분리 | `docs/architecture/04-implementation-gap.md` (G-3, 아직 없는 파일) |
