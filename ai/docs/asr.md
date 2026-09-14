# ASR 선정과 설정

`asr` 단계(FRD §3 F-03 「자막·음성 처리」)가 무엇을 쓰고 무엇을 아직 정하지 않았는지.
티켓은 `S15P21A501-96`.

> **이 문서의 §5 표는 아직 비어 있다.** 실측 장비(GPU)를 잡기 전이라 임계값·모델 크기가
> 확정되지 않았다. `config/asr.v1.toml` 의 수치는 잠정값이고, 그 사실을 파일 안에도
> 적어 두었다. FRD §11 이 금지하는 것은 "실측 없이 숫자를 확정하는 것" 이지 잠정값으로
> 코드를 굴리는 것이 아니다 — 대신 **무엇이 재어졌고 무엇이 아닌지를 흐리지 않는다.**

## 1. 계약이 정한 것과 정하지 않은 것

정한 것은 [docs/contracts/job-api.md](../../docs/contracts/job-api.md) §4.5·§9.2 다.
이 단계가 고를 수 있는 것이 아니다.

| 항목 | 값 |
| --- | --- |
| 출력 | `{"segments": [...], "reasonCode"?}`. 빈 배열도 정상 |
| 구간 | `segmentId`·정수 ms `s`/`e`·원문 `t`·`sourceDetail="asr"` |
| 발화 미감지 | `reasonCode = "NO_SPEECH_DETECTED"`, `status = succeeded` |
| 실패 | `ASR_FAILED`(일시) · 구현 없음 `NO_ADAPTER`(영구 → `skipped`) |
| 가중치 부재 | `MODEL_UNAVAILABLE`(일시) · 디코드 불가 `UNSUPPORTED_MEDIA`(영구) |
| 시간 | 원본 영상 기준. 시작 포함·종료 제외(FRD F-03) |

정하지 않은 것 — **모델 크기, compute type, VAD 임계값, 디코딩 임계값.** 전부 §5 의
실측이 정한다. 모델과 정밀도는 배포 설정(`NPICK_AI_ASR_MODEL`·`..._COMPUTE_TYPE`)이고
나머지는 `config/asr.v*.toml` 이다.

## 2. 엔진 — faster-whisper

**이 선택은 이 티켓이 처음 한 것이 아니다.** 저장소가 이미 그것을 전제하고 있었다.

- `pyproject.toml` 의 `gpu` 그룹에 `faster-whisper>=1.2,<2` 가 있다.
- `tests/test_smoke_models.py` 가 `tiny` 모델을 CPU 로 실제 로드한다.
- `README.md` 의 의존성 표가 ASR 단계의 것으로 적고 있다.

그래서 이 티켓이 새로 정한 것은 엔진이 아니라 **그 안에서 무엇을 쓰는가**다.

### 왜 이 경계로 두는가

`AsrEngine` Protocol(`engine.py`) 뒤에 구현을 둔다. 실측 결과로 바뀔 수 있는 것이
backend 파일 하나와 설정에 머물러야 하기 때문이다 — `recognizer.py` 도 `jobs/` 도
faster-whisper 를 모른다(`ai/AGENTS.md` 의 어댑터 경계).

### 후보에서 뺀 것

| 후보 | 뺀 이유 |
| --- | --- |
| `openai-whisper`(원본) | 같은 모델을 더 느리게 돌린다. CTranslate2 판이 이미 있다 |
| 외부 STT API | FRD §6.4 의 외부 전송 보호. VLM 이 같은 이유로 fail-closed 인데 오디오만 예외를 둘 근거가 없다 |
| 별도 VAD 라이브러리(silero-vad·webrtcvad) | faster-whisper 에 Silero VAD 가 들어 있다. 밖에 하나 더 두면 두 판정이 갈릴 수 있고, 그 어긋남은 타임스탬프로만 드러나 찾기 어렵다 |

**저장소에 기존 VAD 구성은 없었다.** 티켓의 "기존 VAD 구성·연동을 먼저 확인해 재사용"
은 이 확인으로 닫는다 — 재사용할 구성이 없고, 라이브러리 내장 VAD 가 그 자리다.

## 3. 무엇을 산출물로 남기는가

구간마다 시작·종료·원문·신뢰도다. 모델 버전은 구간이 아니라 `versions.modelVersion` 에
한 번 실린다(계약 §7).

`confidence` 는 `exp(avg_logprob)` 이다. **보정된 확률이 아니라 근사다.** 토큰 확률의
기하평균이라 "이 문장이 맞을 확률" 과 같지 않다. 그래도 싣는 이유는 F-04 가 근거에
확인 가능한 값을 요구하고, 구간 사이의 상대 비교에는 쓸 수 있기 때문이다.

**이 값이 높다고 검증된 사실이 되지 않는다.** ASR 근거는 기본 미검증이고
(`docs/frd.md:158`), 인식된 날짜 문자열을 방송일·촬영일로 승격하지 않는다
(`docs/frd.md:157`).

### 버리는 구간

계약을 지킬 수 없는 두 경우만 버리고 그 수를 metrics 에 남긴다.

- 글자가 없는 구간(`t` 가 공백뿐) — 계약이 빈 문자열을 거부한다.
- ms 로 반올림하니 길이가 0 이 된 구간 — 계약이 `e > s` 를 요구한다. **1ms 를 지어내
  통과시키지 않는다.**

낮은 신뢰도라고 버리지 않는다. 무엇을 채택할지는 우선순위를 아는 하류가 정한다.

## 4. VAD 는 실행기 안에 있다

`vad_filter=True` 로 켜고 파라미터는 설정에서 온다. 라이브러리가 무음을 잘라 인식에
넣고 타임스탬프를 원본 타임라인으로 되돌린다.

> **실측 때 반드시 확인할 것.** 되돌림이 실제로 맞는지는 코드로 증명할 수 없다. 발화
> 시각을 아는 표본에서 `report.py` 출력과 라벨을 눈으로 맞춘다. 여기가 어긋나면 장면
> 연결(#98)에서야 드러나고, 그때는 원인이 ASR 인지 매핑인지 가리기 어렵다.

VAD 는 작은 목소리를 놓칠 수 있다. **발화 미감지는 무음의 확정도 환각 제거의 증명도
아니다.** 그래서 §5 의 지표가 환각과 누락을 함께 본다.

### 발화 미감지의 근거는 여기서만 나온다

VAD 가 남긴 오디오 길이(`duration_after_vad`)가 0 일 때만 "말이 없었다" 고 적는다.
**전사가 비었다는 사실은 근거가 아니다** — 라이브러리가 임계(`no_speech_threshold`·
`log_prob_threshold`)에 걸린 구간과 글자가 없는 구간을 스스로 버리므로, 말이 있어도
목록은 빈다. VAD 가 3 초를 남겼는데 문장이 하나도 안 나온 실행을 무음으로 적으면 그
3 초에 대한 거짓이 정본에 남는다.

VAD 를 끄면(`enabled = false`) 판정 자체가 없다. 그때 `duration_after_vad` 는 클립
전체 길이이므로 무음을 말할 근거가 되지 못하고, 이 단계는 **아무 사유도 붙이지
않는다.** 실험 설정으로 돌린 실행은 `NO_SPEECH_DETECTED` 를 영영 내지 않는다는 뜻이다.

두 값을 metrics 에 함께 남긴다 — `vadSpeechMs`(VAD 가 남긴 오디오)와 `speechMs`
(내보낸 구간의 합). 앞이 크고 뒤가 0 인 실행이 §5 에서 누락으로 세야 하는 자리다.

## 5. 임계값은 실측으로 정한다 — 아직 재지 않았다

### 표본

| # | 표본 | 무엇을 보는가 |
| --- | --- | --- |
| 1 | 무음·배경음만 | **환각.** 생성된 구간이 0 이어야 한다. 0 이 아니면 그 문장이 곧 없는 대사다 |
| 2 | 작은 목소리·짧은 발화 | **누락.** VAD 임계값을 올릴수록 여기서 잃는다 |
| 3 | 음성과 무음이 섞인 것 | 경계. 무음 뒤 첫 어두, 발화 끝 어미가 잘리는지 |
| 4 | 표준 리포트(`KNI_02205`) | 일반 정확도와 처리 시간 |

라벨은 `samples/asr-ground-truth.<클립>.json` 이다(형식은 `samples/README.md`).
**라벨 없이는 §5 의 어느 칸도 채울 수 없다** — 놓친 발화는 라벨과 대조해야만 보인다.

### 지표

| 지표 | 정의 | 어느 표본 |
| --- | --- | --- |
| 환각 구간 수 | 라벨에 발화가 없는데 생성된 구간 | 1, 3 |
| 발화 누락률 | 라벨 발화 중 겹치는 구간이 없는 비율 | 2, 3, 4 |
| CER | 라벨과 인식 원문의 문자 오류율 | 4 |
| RTF | 처리 시간 ÷ 클립 길이 | 4 |

### 비교 축

- 모델 크기 (`small` · `medium` · `large-v3-turbo`)
- compute type (`float16` · `int8_float16`)
- VAD on/off, `threshold`, `min_silence_duration_ms`
- `condition_on_previous_text` on/off — 원리상 off 가 맞지만 잃는 것을 재 둔다

비교는 **설정 파일을 따로 두고** 돌린다(`report.py --config`). 동봉 설정을 고쳐 가며
재면 어느 값으로 잰 표인지 나중에 알 수 없다.

### 왜 아직 못 쟀는가

GPU 가 필요하다. CPU `tiny` 로 잰 수는 선정 근거가 되지 못한다 — 모델 크기가 곧 비교
축인데 CPU 에서는 큰 모델을 돌릴 수 없다. 실측 장비는 VLM(`S15P21A501-92`)이 기다리는
것과 같은 것이므로 함께 잡는다.

| 표본 | 모델 | 환각 | 누락 | CER | RTF |
| --- | --- | --- | --- | --- | --- |
| — | — | 미측정 | 미측정 | 미측정 | 미측정 |

## 6. 빈 결과·실패·미실행은 다른 사실이다

티켓의 완료 조건이 이 구분을 요구한다. 코드가 넷을 갈라 놓는다.

| 상황 | 어디서 갈리는가 | 계약 |
| --- | --- | --- |
| 발화 미감지 | VAD 가 남긴 오디오가 0 이다 | `succeeded` + `NO_SPEECH_DETECTED` |
| 전사가 비었다 | VAD 는 남겼는데 엔진이 임계로 다 버렸다 | `succeeded`, 사유 없음 |
| 걸러서 비었다 | 엔진은 냈는데 계약을 못 지켜 우리가 버렸다 | `succeeded`, 사유 없음 |
| 판정이 없다 | VAD 를 끄고 돌았다 | `succeeded`, 사유 없음 |
| 실행 실패 | `AsrCallError` | `failed` + `ASR_FAILED`(일시) |
| 구현 없음 | faster-whisper 미설치 | `skipped` + `NO_ADAPTER`(영구) |
| 가중치 없음 | 모델 미설정·내려받기 실패 | `failed` + `MODEL_UNAVAILABLE`(일시) |

**가운데 세 줄이 요점이고, 셋 다 빈 `segments` 로 나간다.** 빈 목록만 보고 사유를
만들면 "무음이었다" 는 거짓이 정본에 남는다. 그래서 판정은 목록의 길이가 아니라
엔진에서 온다(`Transcription.speech_detected`, §4). BE 도 같은 구분을 한다
(`ProcessingRecordReader`: "빈 segments 만으로 사유를 만들지 않는다").

`ASR_FAILED` 는 **명시적으로 발신한다**(`AsrFailedError`). 맨 `TransientStageError` 는
`STAGE_FAILED` 로 적히는데 그 값의 뜻은 "분류를 미룬다" 이고, 인식 실패는 미룰 것이
없는 분류된 사실이다.

VAD 오류나 실행 실패를 발화 미감지 정상 종료로 바꾸지 않는다. 그래서 `_run_asr` 은
실패를 삼키지 않고 그대로 올린다 — 비치명 단계라 run 은 계속 간다(FRD F-03).

## 7. 설정과 버전

값은 전부 `config/asr.v1.toml` 에 있고 그 해시가 `configVersion` 이다. 재현 식별자는
네 축이다.

```
stageVersion = hash(configVersion, engine, engineVersion, modelVersion)
```

`modelVersion` 은 **엔진 이름으로 시작한다** — `faster-whisper/large-v3-turbo@float16`.
이 문자열 하나가 봉투에 실려 정본에 남는데, 가중치 이름만으로는 무엇이 그것을 돌렸는지
알 수 없다(같은 Whisper 가중치를 여러 런타임이 돌리고 결과가 다르다). `ocr` 도 같은
모양이다(계약 §4.5 예시 `rapidocr/rapidocr3.9.2+onnxruntime1.29.0`). compute type 이
함께 들어가는 이유는 `float16` 과 `int8` 이 같은 모델의 다른 수치이기 때문이다.

`engine` 이 재현 튜플의 별도 축으로도 있는 것은 detail 을 사람이 읽기 위한 것이지
`modelVersion` 을 대신하지 않는다.

`tokenizer` 축이 **없다.** 이 단계는 색인 토큰을 만들지 않는다. 대사의 토큰화는 채택된
구간을 다루는 하류의 일이고, 여기 넣으면 이 단계와 무관한 변경으로 `stageVersion` 이
바뀌어 재처리가 도는 자리가 생긴다(`ocr` 과 반대 방향의 판단 — 그쪽은 `tokens` 가
자기 산출물이라 넣어야 한다).

## 8. 언제 다시 볼 것인가

- **GPU 를 잡았을 때.** §5 를 채우고 `config/asr.v2.toml` 로 확정한다.
- **1080p·다른 음향 조건의 방송분이 들어왔을 때.** 표본 4 를 다시 잰다.
- **`transcript_selection`(단계 5)이 붙었을 때.** `asrRequired=false` 로 배정이 오지
  않게 되는지, `candidateRanges` 가 metrics 에 제대로 실리는지 확인한다.
- **`scene_transcript_mapping`(#98)이 붙었을 때.** 타임스탬프 되돌림(§4)이 장면 경계와
  맞는지 그때 다시 본다.
