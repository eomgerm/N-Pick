# 자막·CC 선택 (4단계, S15P21A501-213)

FRD §5.1 의 4단계 `transcript_selection` 이다. 제공 자막과 내장 CC 중 무엇을 쓸지 예비
판정을 내고, 자막이 덮지 못한 구간을 후보로 내어 ASR 이 필요한지 정한다.

인수 조건 정본: [../../docs/frd.md](../../docs/frd.md) F-03 (§ "자막·음성 처리", "제공 자막과
내장 CC가 겹치면 제공 자막을 우선한다") + [계약 §4.5](../../docs/contracts/job-api.md).

## 1. 이 단계가 하는 일과 하지 않는 일

| | |
| --- | --- |
| 한다 | 예비 채택 판정(`decisions`), 미커버 구간(`candidateRanges`), `asrRequired`·`reasonCode` |
| 하지 않는다 | 자막 파일 파싱, CC 추출, 구간 생성·수정·삭제 |

자막 파싱과 mov_text CC 추출은 **BE 가 claim 을 가로채** 한다
(`PreparedStageClaimService` → `LocalTranscriptInputPreparation`, `S15P21A501-35`). 워커가
받는 것은 이미 파싱된 구간 파일 하나와 그 참조다. 그래서 이 단계는 영상을 열지 않는다 —
`needs_video=False`.

**원본을 고치지 않는다.** BE 는 결과를 저장할 때 준비 파일을 다시 읽어 ID 집합이 같고 각
구간이 JSON 노드 단위로 동일한지 검사한다(계약 §4.5). 구간을 추가·삭제하거나 필드를 바꾸면
그 자리에서 거절된다. 겹쳐 제외된 구간도 원문 전체가 남는다 — 시간만 잘라 부분 발화로 만들지
않는다.

## 2. 채택 규칙의 소유자가 여기다

`transcript_selection/selector.py` 의 `select()` 가 정본이고 **6단계
`scene_transcript_mapping` 이 같은 함수를 다시 돌린다.**

계약 §4.5 가 그것을 요구한다 — "두 단계는 위의 같은 채택 규칙(제공 자막 → CC → ASR, 채택된
상위 출처만 제외 근거)을 따라야 하며, 어긋나면 같은 run 안에서 CC 채택 여부가 갈린다."
사본을 두면 결함이 조용하다: 대사가 사라지지 않고 CC 채택 여부만 달라지므로, 화면과 정본이
갈린 뒤에야 드러난다. 그래서 규칙 한 벌을 공유하는 것이 그 요구를 집행하는 수단이다.

두 단계의 `versions.detail` 이 **같은 키 `selection` 으로 같은 값**을 싣는다. 사람이 두
`detail` 을 나란히 놓고 규칙 일치를 확인할 수 있어야 하기 때문이다.
`tests/test_transcript_selection.py` 의 `test_both_stages_share_one_adoption_rule` 이 그
사실을 기계로 고정한다.

우선순위는 `uploaded`(제공 자막) < `embedded`(내장 CC) < `asr` 이고, 제외 근거는 **채택된**
상위 출처만이다. 제외된 CC 가 자막 공백의 ASR 을 막지 못한다.

## 3. 이 단계의 판정은 예비다

4단계의 `decisions` 와 6단계의 `decisions` 가 같은 run 에 두 벌 남는다. **최종 채택 정본은
6단계 snapshot** 이고 저장·검색·VLM 은 언제나 그쪽을 쓴다(계약 §4.5). 이 단계의 판정을
최종으로 되살리지 않는다.

이 단계의 판정이 필요한 이유는 둘이다. `asrRequired=false` 면 BE 가 ASR 을 `skipped` 로 닫아
GPU 분을 아끼고, 검수자가 "자막이 아예 없다"와 "일부만 덮었다"를 구분해 볼 수 있다.

## 4. `reasonCode` 세 값

```
reason_code = "SUBTITLE_COVERED"  if not ranges        # 자막·CC 가 전 구간을 덮었다
         else "NO_VALID_SUBTITLE" if not originals      # 쓸 수 있는 자막이 하나도 없다
         else "UNCOVERED_RANGES"                        # 자막은 있으나 덮지 못한 구간이 있다
```

BE 가 강제하는 등가식이 둘이다 — `asrRequired == (candidateRanges 비어 있지 않음)` 과
`SUBTITLE_COVERED ⇔ candidateRanges == []`. 보내는 쪽에서도 막는다
(`jobs/transcripts.py` 의 `TranscriptSelectionSnapshot.judgement`).

**세 칸은 `output.transcript` 안이다.** 참조 두 개 옆이 아니다.

```json
{
  "transcript": {
    "segmentsArtifact": {…}, "decisionsArtifact": {…},
    "asrRequired": true, "candidateRanges": [{"s": 0, "e": 500}],
    "reasonCode": "UNCOVERED_RANGES"
  }
}
```

BE 는 `complete` 검사(`StageExecutionService`)와 저장 어댑터
(`JdbcWorkerStageOutputAdapter.validateTranscript`) 둘 다에서 `transcript` 객체를 열어
그 안에서 세 칸을 찾는다. 형제로 올리면 값이 맞아도 세 칸이 모두 없는 것으로 읽혀
`INVALID_RESULT` 이고, 자리만 다를 뿐이라 원인이 멀다. 6단계의 `transcript` 는 참조
두 개뿐이라 타입도 나눠 둔다 — `TranscriptSelectionSnapshot` 이 `TranscriptSnapshot` 을
상속해 세 칸을 더한다.

**"구간은 있으나 전부 제외" 는 도달 불가다.** 제외는 채택된 상위 출처와 겹칠 때만 일어나므로
우선순위가 가장 높은 구간들은 반드시 채택된다. 그래서 원본이 1건 이상이면 채택도 1건
이상이고, `NO_VALID_SUBTITLE` 은 원본 0건만을 뜻한다. 그 분기를 코드에 두지 않는다.

**`embeddedInspection` 을 판정에 쓰지 않는다.** BE 가 CC 추출 상태(`NO_TRACK`,
`EXTRACTION_FAILED` 등)를 준비 입력에 실어 주지만 계약에는 그 필드가 없고, BE 가 이미 처리
상세의 별도 칸으로 노출한다(`ProcessingRecordReader`). 같은 사실을 `reasonCode` 에도 접으면
두 칸이 갈린다. 워커의 상류 모델은 그 필드를 선언조차 하지 않는다 — `selectedStreamIndex` 가
CC 트랙이 없을 때 null 이라, 필수 정수로 선언하면 CC 없는 클립마다 이 단계가 영구 실패한다.

## 5. 클램프는 정책이 아니라 출력 계약이다

`candidateRanges` 는 `[0, mediaDurationMs)` 에서 채택된 자막·CC 구간을 뺀 여집합이다. 그
계산에서 **클립 길이로 클램프하고 비양수 구간을 버린다.**

자막 시간축과 `mediaDurationMs` 는 서로 다른 측정값이다 — 자막은 등록 시 ffprobe 의
`format.duration`(초)으로 상한을 검사하고, `mediaDurationMs` 는 장면 분할이 프레임 수로
계산한다. 두 값은 일치하지 않으므로 채택 구간의 끝이 클립 길이를 넘을 수 있고, 클램프 없이
여집합을 구하면 음수 시작이나 길이 0 이하 구간이 나와 BE 가 `e > s` 에서 거절한다.

클립 길이는 1단계 산출물의 `mediaDurationMs` 로 온다. 1단계는 치명 단계라 4단계 시점에 반드시
성공해 있다. `UpstreamSceneDetection` 을 재사용하지 않는다 — 그것이 `scenes`(min_length=1)와
`frameRate` 를 필수로 끌고 오는데 이 단계는 장면을 보지 않는다.

자막이 하나도 없으면 후보가 `[0, mediaDurationMs)` 하나 나온다. BE 가 `asrRequired=true` 인데
후보가 비어 있으면 거절하므로 그것이 필요한데, **특례 분기가 아니라 여집합의 자연 결과다** —
`mediaDurationMs` 가 항상 양수이기 때문이다.

## 6. 설정과 버전

`config/transcript_selection.v1.toml`

| 키 | 기본값 | 뜻 |
| --- | --- | --- |
| `min_uncovered_ms` | `0` | ASR 을 부를 미커버 구간의 최소 길이. 0 은 필터 없음 |

**숫자를 짓지 않았다.** FRD §11·§11.1 이 실행 환경 설정값을 실측 없이 확정하지 말라고 하고,
계약 §4.5 에도 최소 구간 길이 규정이 없다. `config/ocr-merge.v1.toml` 의
`similarity_threshold = 1.0`(= 병합 없음)과 같은 형태로 자리만 잡아 두었다. 값을 채우면
`configVersion` → `stageVersion` 이 자동으로 움직인다.

**실측이 필요한 이유.** `0` 이면 자막이 촘촘한 뉴스 클립에서 자막 사이 수십~수백 ms 틈이 전부
후보가 되고, 후보가 하나라도 있으면 `asrRequired=true` 라 **1ms 틈 하나가 전체 영상 ASR 을
부른다** — ASR 은 `candidateRanges` 를 구간 제한에 쓰지 않고 영상 전체를 돌린다
(`jobs/registry.py` 의 `_run_asr`). 실측 항목: 자막이 촘촘한 클립 하나로
`len(candidateRanges)` 와 그때의 ASR 시간을 잰다.

재현 식별자 축은 둘이다.

| 축 | 값 |
| --- | --- |
| `selection` | `transcript-selection/v1` — 채택 규칙. 6단계가 같은 키로 같은 값을 싣는다 |
| `configVersion` | 위 toml 의 해시. `StageVersion.config_version` 에도 같은 값이 들어간다 |

`tokenizer` 축은 없다. 이 단계는 색인 토큰을 만들지 않는다 — 대사의 토큰화는 채택된 구간을
다루는 하류의 일이다(`asr` 과 같은 판단). 가중치도 프롬프트도 쓰지 않으므로
`model_version`·`prompt_version` 은 키를 남기고 값만 비운다.

**toml 설정과 `inputs.config` 는 다른 개념이다.** 계약 §4.5 대로 배정은 이 단계에 빈 설정만
보내고, 비어 있지 않은 `inputs.config` 가 오면 러너가 `StageConfigUnsupportedError` 로
거절한다. 위 toml 은 워커 이미지에 들어 있는 값이고 배정이 실어 보내는 것이 아니다.

## 7. 오류

| 상황 | 코드 | 분류 |
| --- | --- | --- |
| 준비된 snapshot 에 `sourceDetail == "asr"` 가 섞여 있다 | `INVALID_TRANSCRIPT` | 영구 |
| 상류 봉투·구간 모양이 계약과 다르다 | `VALIDATION_ERROR` | 영구 |
| 두 JSON 업로드 실패 | `ARTIFACT_UPLOAD_FAILED` | 일시 |

`INVALID_TRANSCRIPT` 의 용도는 **하나뿐**이다. "snapshot 이 계약과 다르다" 는 판정 대부분은
러너가 핸들러를 부르기 전에 이미 낸다 — `jobs/artifacts.py` 가 내려받은 문서를
`TranscriptSegments`(`extra="forbid"`)로 검증하고 실패를 `VALIDATION_ERROR` 로 보고한다.
그 공용 검증기가 통과시키지만 이 단계만 아는 사실이 ASR 구간의 혼입이다: 4단계 시점에 ASR 은
돌지 않았고 BE 의 준비 어댑터는 `uploaded`·`embedded` 만 만든다. 그것이 왔다면 배정이나 준비가
어긋난 것이고, 그대로 예비 판정을 내면 실제 인식 없는 `ASR_SUPPLEMENT` 채택이 최종 정본으로
되살아날 수 있다.

`segmentId` 의 `{uploaded|embedded}-{index}` 형식은 검사하지 않는다 — 계약은 snapshot 안에서의
유일성만 요구하고 접두 형식을 강제하지 않는다.

## 8. 검증과 검증하지 않는 것

```
uv run pytest tests/test_transcript_selection.py
```

순수 계산(커버리지 클램프·병합·여집합, 채택 우선순위·겹침·순서 무관), 두 snapshot 생성 →
공용 `validate_snapshot` 통과 → 6단계가 그대로 소비, 상류 결함 11종에서 파일을 한 장도 쓰지
않음, 모의 HTTP 잡 API 의 다운로드·업로드·complete 순서를 검증한다.

**검증하지 않는 것**: 실제 BE 왕복, DB 저장, 실제 배정.

배정에 필요한 `infra/compose/profiles/pipeline.yml` 의 `stage_versions` 는 채웠다 — compose 의
ai-worker 컨테이너에서 재서 넣었고 로컬 venv 값과도 같았다(2026-09-18).

```
transcript_selection      npick.stage.transcript_selection/v1:fdcd0780
scene_transcript_mapping  npick.stage.scene_transcript_mapping/v1:286181fa   # 옛 e1d97878
```

6단계 값이 바뀐 것은 이 티켓이 그 단계 identity 에 `selection` 축을 더했기 때문이다. 축만
더하고 그 줄을 그대로 두면 6단계가 **오류 없이** 배정에서 빠진다.
