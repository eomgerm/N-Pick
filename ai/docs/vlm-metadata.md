# VLM 장면 metadata — 선정과 설정

FRD `F-03` 영상 설명 생성 구현 근거. 구현은 `src/npick_worker/vlm_metadata/`.

만들어야 하는 것은 FRD가 정한 셋이다 — **장면 설명·샷 유형·태그 후보**(`docs/frd.md:121`).
잡 API 쪽 규약(입력·출력 모양·오류)은
[../../docs/contracts/job-api.md](../../docs/contracts/job-api.md) §4.3.3이 정본이고, 이
문서는 **왜 이렇게 만드는가**를 적는다.

> **2026-09-13 실제 smoke 비교 반영:** 동일한 10개 scene·23장 selected keyframe으로
> Qwen3.5-4B, Qwen3.5-9B, Qwen3-VL-8B-Instruct를 비교했다. 세 모델 모두 schema 10/10을
> 통과했다. **이번 이슈의 선정 모델은 Qwen3.5-9B**, 메모리 제약 시 대체 모델은 Qwen3.5-4B다.
> 9B는 4B의 복수 프레임 혼합·동작 오인을 줄였고, 평균 14.432초/scene,
> peak reserved 18.58 GiB였다. Qwen3-VL-8B는 가장 빨랐지만 샷 유형 오류가 확인됐다.
> 상세 수치·장면별 AI 예비 판정·원시 결과는 §9.6에 있다. 사람의 품질 검수와 별도 Gold Set은
> 미완료이며, 운영 합격이나 자동 fallback 구현을 뜻하지 않는다. 모델 설정은 변경하지 않았다.

## 1. 스키마가 정한 것과 정하지 않은 것

이 단계의 설계 여지는 대부분 `scene` 테이블과 태그 표가 이미 좁혀 놓았다.

| 요구 | 스키마의 자리 | 결과 |
| --- | --- | --- |
| 장면 설명 | `scene.caption`, `scene.caption_tokens` | 토큰은 워커가 만든다 (§5) |
| 샷 유형 | `scene.shot_type varchar(32) NOT NULL` | 닫힌 4값. **비울 수 없다** (§3) |
| 장면 유형 | **없음** — `tag.tag_type`의 `scene_type` | 태그 후보로만 존재한다 (§4) |
| 인물·장소 등 | `tag`·`tagging`·`tag_evidence` | 후보를 낼 뿐 태그를 만들지 않는다 |
| 근거 프레임 | `tag_evidence.source_ref_type='keyframe'` | `(sceneIndex, timestampMs)`로 가리킨다 |
| 검증 상태 | `tag_evidence.verification_status` | 이 단계 결과는 전부 `unverified` |
| 신뢰도 | `tag_evidence.confidence numeric(5,4)` | 넷째 자리까지 반올림해 보낸다 |

`scene`에 `scene_type` 칸이 없고 `shot_type`만 있는 것이 우연이 아니다. baseline의 컬럼
주석이 그 이유를 적는다 — 랭킹이 매 검색마다 `shot_type`을 읽고(`b_roll`에 보조 가산점)
평가 지표에도 있어서 칸으로 남았고, 계절·날씨·상황유형은 태그로 갔다.

## 2. 모델 선정

### 2.1 결론

| 역할 | 모델 | 선택 |
| --- | --- | --- |
| 선정 모델 | `Qwen/Qwen3.5-9B` | 실제 smoke 10/10. 이번 AI 예비 검토에서 caption 오류가 가장 적고 20 GiB allocator 예산 내 실행 (§9.6) |
| 메모리 제약 시 대체 모델 | `Qwen/Qwen3.5-4B` | 실제 smoke 10/10, reserved 9.58 GiB. 품질 저하를 감수하는 별도 실행용 fallback이며 자동 전환은 아님 |
| 비교 완료 baseline | `Qwen/Qwen3-VL-8B-Instruct` | 실제 smoke 10/10, 평균 6.776초로 가장 빠름. 기자·스크린 장면의 샷 유형 오류로 주 후보에서 제외 |
| 향후 다른 계열 비교 | `google/gemma-4-12B-it` | 이번 실측에 포함하지 않음. 추가 GPU 예산·품질 개선 필요 시 검토 |
| 향후 최신 품질 비교 | `Qwen/Qwen3.8-27B` | 이번 실측에 포함하지 않음. 추가 GPU 예산·품질 개선 필요 시 검토 |

이번 비교에 따른 조건부 설정 예시는 다음과 같다. **운영 배포값을 확정한 것은 아니다.**
`revision=main`은 움직이는 표적이므로 쓰지 않는다. 실제
benchmark를 시작할 때 확인한 **40자리 commit SHA**를 평가 run과 배포 설정에 고정한다.

```text
NPICK_AI_VLM_MODEL=Qwen/Qwen3.5-9B
NPICK_AI_VLM_MODEL_REVISION=c202236235762e1c871ad0ccb60c8ee5ba337b9a
```

이 선택으로 **이번 이슈의 기술 선정을 확정**한다. 운영 품질 승인은 별도다. 공개 benchmark는 일반 VQA·OCR·문서·
추론 성능을 보여 줄 뿐, 한국어 뉴스의 `anchor / interview / b_roll / unknown` Macro F1이나
N-Pick caption 판정률을 대신하지 못한다. 최종 품질 판정은 장면 100개 이상의 별도 Gold
Set에서 shot type Macro F1 0.80 이상, 설명 정확+부분정확 85% 이상을 확인한다(FRD §8.2).

이번 이슈(S15P21A501-92)는 후보 비교·모델 선정·구조화 prompt/schema·버전 관리와 실제
복수 keyframe smoke를 다룬다. 검증기·실패 처리의 기반 코드도 이 브랜치에 포함되어 있지만,
정본 저장의 부분 적용 방지와 오케스트레이터 누락 채널 연동의 실제 동작 확인은 후속
검증 이슈에서 다룬다. 100개 이상 scene Gold Set 최종 품질 판정도 별도 작업이다.
원시 출력은 Git 밖에서 보존하며, 전달되지 않은 실행 설정·로그의 보존 상태는 §9.6에 명시한다.

### 2.2 후보 비교 — 공개 사양과 사전 판단

아래 표는 실험 전 공개 사양과 자원 계획이다. 공개일·입력·라이선스는 §11 공식 자료에 따른다.
4B·9B·Qwen3-VL-8B의 실제 시간·메모리·schema 결과와 예비 품질 비교는 §9.6을 따른다.
Gemma 4·Qwen3.8은 미실행이며 N-Pick 최종 품질 점수는 세 실측 후보도 아직 없다.

| 항목 | Qwen3.5-9B | Qwen3.5-4B | Gemma 4 12B Unified | Qwen3.8-27B |
| --- | --- | --- | --- | --- |
| 공개일 | 2026-03-02 | 2026-03-02 | 2026-06-03 | 2026-08-14 |
| 입력 | 이미지·영상·텍스트 | 이미지·영상·텍스트 | 이미지·영상·음성·텍스트. 이 단계는 이미지만 사용 | 이미지·영상·텍스트 |
| caption·shot type·태그 품질 | §9.6 예비 평가, Gold Set 미완료 | §9.6 예비 평가, Gold Set 미완료 | N-Pick 실측 필요 | N-Pick 실측 필요 |
| JSON 계약 | smoke 10/10 (§9.6) | smoke 10/10 (§9.6) | 미실측 | 미실측 |
| BF16 가중치 규모 근사 | 약 18GB 이상 | 약 8GB 이상 | 약 24GB | 약 54~56GB |
| GPU 검증 시작점 | BF16 24~32GB, 여유 있게 48GB | BF16 16GB | BF16 32~48GB | BF16 80GB, 공식 FP8은 48GB부터 별도 검토 |
| 라이선스 | Apache-2.0 | Apache-2.0 | Apache-2.0 | Apache-2.0 |

가중치 규모는 **공개 parameter 수 × 2 byte에 기반한 근사**이며 실제 다운로드 크기나
최대 VRAM 실측값이 아니다. 모델명의 크기가 vision encoder 등을 포함한 전체 parameter
수와 다를 수 있다. GPU profile도 **최소 사양 보장이 아닌 실험 시작점**이다.

실제 peak VRAM에는 가중치 외에 이미지 처리 activation, 이미지 토큰, KV cache와 allocator가
포함된다. scene당 최대 5장, 해상도·출력 길이·context 상한·동시성을 함께 고정해야 한다.
공식 예제의 긴 context 기본값을 그대로 쓰지 말고 이 작업의 입력 토큰 예산을 검증한다.
attention 구현과 실제 dtype, 모델별 processor의 이미지 토큰 수를 기록한다.

Qwen3.8-27B의 공식 FP8 가중치는 별도 배포되어 있지만 현재 어댑터에서 실행을 검증하지
않았다. GPU·커널·엔진 지원을 먼저 확인하고 BF16과 별도 실험으로 기록한다.
FP8과 BF16 결과를 섞어 모델 크기만의 성능 차이라고 결론 내리지 않는다.

### 2.3 최초 평가를 Qwen3.5-4B부터 시작한 이유

1. **작업에 맞는 입력을 지원한다.** 시간순 selected keyframe 2~5장으로 한국어 장면 설명,
   닫힌 샷 유형과 태그 후보를 생성한다. 실제 이미지 전달과 프레임 간 관계 이해는 smoke와
   개발셋에서 확인한다.
2. **현재 메모리 예산에 맞춘 출발점이다.** 4B는 9B보다 가중치 메모리 부담이 작아
   약 22.5 GiB 안에서 다중 이미지·생성에 쓸 여유를 확보하기 쉽다. 9B의 품질 향상이
   추가 메모리와 시간을 정당화하는지는 §9.6에서 비교했다. 12B·27B 필요성은 미평가다.
3. **짧은 JSON을 먼저 평가한다.** Qwen3.5·Qwen3.8은 기본적으로 thinking이 켜져 있다.
   첫 비교는 `enable_thinking=False`를 적용한다. §9.5의 benchmark CLI가 이 인자를
   명시적으로 전달하고 실행 설정에 기록한다. 운영 기본값은 모델 template을 따른다.
4. **배포 구조가 맞는다.** 외부 GPU 서버에 워커와 가중치를 직접 배치한다.
   [Deployment](../../docs/architecture/03-deployment.md)에 따라 GPU 워커가 EC2의
   잡 API를 claim/heartbeat/complete/artifacts로 호출한다. GPU 인바운드 잡 API를
   추가하지 않으며 검색 시점 질의 리졸버는 별도 배포 경계를 유지한다.

외부 GPU 임대 서버에서의 자체 호스팅은 §8의 외부 모델 제공자 API 위탁과 구분한다.
현재 정본은 RunPod에서 직접 워커를 구동하는 구성을 포함한다. 공급자 API로 프레임을 보내는
대체 경로는 기존 외부 처리 게이트를 따른다.

### 2.4 fallback의 의미

fallback은 한 결과 안에서 9B 실패 필드만 4B로 채우는 기능이 아니다. 어느 모델이 어느 필드를
만들었는지와 원자적 schema 검증을 유지해야 한다. **배포·평가 설정에서 실행 모델을 선택**하며,
운영 중 자동 모델 전환이 구현되었다는 뜻이 아니다. 모델을 바꾼 재실행은 계약상 단계 성공/실패
단위를 유지하고 별도 `model_version`을 남긴다.

- 9B가 해당 GPU profile에서 OOM이거나 처리 예산을 넘으면 4B를 별도로 평가한다.
  4B도 Gold Set 품질 목표를 통과해야 운영 후보가 된다.
- 9B 결과가 schema-invalid이면 그 결과는 VLM 단계 실패다. 운영 중 즉석으로 4B를 호출해
  숨기지 않는다. 재시도/fallback 정책을 별도 versioned config로 승인한 뒤에만 새 실행으로
  전환한다.
- 외부 호출 조건이 하나라도 빠지면 외부 모델로 우회하지 않는다. 로컬 4B adapter 또는
  정의된 단계 실패를 사용한다.

### 2.5 이번 shortlist에서 제외한 후보

기존 `google/gemma-3-12b-it` 대신 최신 Gemma 4 12B Unified를 비교한다.
Gemma 3의 라이선스·접근 조건을 Gemma 4에 그대로 적용하지 않는다.
`OpenGVLab/InternVL3_5-8B-HF`는 이번 평가 범위를 제한하기 위해 우선 제외한다.
새 후보 네 모델과 기존 baseline이 목표를 못 넘으면 다음 평가에서 재검토한다.
이 제외는 N-Pick 품질이 낮다는 실측 판단이 아니다.

Qwen3.6-27B는 같은 규모의 더 최근 후보 Qwen3.8-27B를 우선해 별도 평가하지 않는다.
더 큰 모델의 도입은 GPU 사양과 처리량을 측정한 뒤 검토한다.

### 어댑터가 하나뿐인 것은 아니다

`VlmClient` Protocol 뒤에 `transformers` 어댑터가 있다. 새 후보의 공식 예제는
`AutoProcessor` + `AutoModelForMultimodalLM` 경로를 안내한다. 저장소의
`AutoModelForImageTextToText`가 설치된 런타임에서 각 모델을 올바르게 resolve하는지,
다중 이미지 입력과 chat template이 맞는지 먼저 확인한다.

§9.5의 benchmark CLI는 dtype과 thinking 옵션을 어댑터에 전달하고 별도
`benchmarkConfigVersion`과 설정 원문을 저장한다. 운영 기본값은 dtype `auto`, thinking
옵션 미지정이다. 실측에서는 모델 로딩, 기록된 실제 dtype, 원문에 reasoning이 섞이지
않는지를 확인한다. 실행 도구의 구현은 각 후보가 실제 GPU에서 성공했다는 증거가 아니다.

**이미지는 `images=`로 따로 넘긴다.** chat template의 `url`·`path` 키를 쓰지 않는다. 그
경로는 런타임 버전마다 의미가 갈리는데, 어긋났을 때의 증상이 예외가 아니라 **"이미지를 못
본 채로 그럴듯한 답"**이라 검증에서도 걸리지 않는다.

## 3. 왜 `shot_type`만 코드에 박혀 있는가

어휘 셋이 서로 다른 자리에 있다.

| 어휘 | 자리 | 근거 |
| --- | --- | --- |
| `shot_type` 4값 | `schema.py`의 `Literal` | FRD가 확정했다(`docs/frd.md:159`). 실측으로 바뀌지 않는다 |
| 태그 유형 9종 | `schema.py`의 `Literal` | `tag.tag_type` 11종에서 날짜 2종을 뺀 것 (§6) |
| `scene_type` 값 | `config/vlm_metadata.v1.toml` | 닫힌 어휘지만 **초안**이다. 실측 후 확정한다 |

`scene_type`만 설정에 있는 것은 타협이 아니라 그 값의 성질이다. 지금 어휘를 코드에 박으면
실측으로 고칠 때마다 출력 schema 버전이 올라가는데, 바뀐 것은 계약이 아니라 어휘다.

### `unknown`이 `shot_type`에만 있는 이유

`scene.shot_type`은 `NOT NULL`이다. 근거가 없다고 비울 수 없으므로 "모르겠다"를 표현할 값이
어휘 안에 있어야 하고, 그것이 `unknown`이다. 반대로 태그는 없으면 안 붙는 것이라
`scene_type`에 `unknown`을 두지 않는다 — 그런 값을 두면 "장면 유형이 unknown"이라는 태그가
장면 수만큼 쌓인다.

그래서 **근거가 비어 있어도 되는 판단은 `shot_type`의 `unknown` 하나뿐이다.** "판단할 근거가
부족하다"는 판단에 근거 프레임을 요구하는 것은 뜻이 통하지 않고, 그렇다고 근거를 지어내게
하는 것이 대안일 수는 없다.

**단계가 실패해도 그 컬럼은 채워진다.** 비치명 단계라 VLM이 실패한 run에서도 scene 행은
만들어지고, 그때 BE가 쓰는 값이 `unknown`이다(계약 §4.3.3).

## 4. `scene_type`을 전용 필드로 받아 태그 후보로 내보낸다

모델에게는 전용 필드로 묻고(`schema.RawSceneType`), 검증을 통과한 결과에는 그 필드가 없다 —
`type="scene_type"`인 태그 후보 하나가 된다(`models.SceneMetadata`).

둘로 나눈 이유가 각각 있다.

- **묻는 쪽** — 닫힌 어휘 하나를 고르는 일과 자유로운 태그 후보를 여러 개 만드는 일은
  모델에게 다른 작업이다. 한 배열에 섞으면 어휘 준수율이 떨어진다.
- **내보내는 쪽** — 칸으로 착각할 수 있는 필드를 아예 두지 않는다. "`scene_type`은 태그다"를
  주석이 아니라 타입으로 말한다.

프롬프트의 태그 유형 목록에서는 `scene_type`을 뺀다. 남겨 두면 모델이 같은 값을 두 곳에 내고
검증이 거부하는데, **지시하지 않은 규칙으로 거부하는 셈**이라 불공정하다. 검증이 거부하는
것과 프롬프트가 금지하는 것은 항상 같아야 한다.

## 5. 근거를 어떻게 가리키는가

모델에게 `timestamp_ms`를 묻지 않는다. 화면에 없는 값이므로 물으면 지어낸다. 대신 이미지마다
`kf_1`·`kf_2` 라벨을 붙여 주고 그 라벨로만 근거를 말하게 한다. 라벨을 실제 keyframe으로
되돌리는 일은 검증이 한다.

- **주지 않은 라벨은 거부다.** 그 라벨이 가리키는 프레임이 없으므로
  `tag_evidence.source_ref_id`를 채울 수 없다. 근거 없는 값이 근거 있는 것처럼 저장되는
  경로가 정확히 여기다.
- **라벨을 만드는 쪽과 되돌리는 쪽이 같은 함수를 쓴다**(`prompt.labels_for`). 두 곳에서 따로
  만들면 "근거가 있는데 없다고 거부되는" 실패가 되고, 증상이 schema 오류라 원인을 찾기 어렵다.
- **라벨은 1-base다.** `kf_0`은 사람이 쓰는 목록 표기가 아니라 모델이 `kf_1`로 고쳐 부르기
  쉽고, 그러면 멀쩡한 근거가 미지의 라벨로 거부된다.

`caption_tokens`도 워커가 만든다. BE에 Kiwi가 없고 색인과 질의가 같은 설정을 써야 하기
때문이다(`korean_tokens.py`, `ocr_observation.tokens`와 같은 규약). 그래서 재현 식별자에
`tokenizer`가 들어간다 — 설명이 같아도 Kiwi 설정이 바뀌면 색인이 달라진다.

## 6. 무엇을 거부하는가

검증은 **강등하지 않고 거부한다.** `query_resolver`는 어긋난 항목을 강등하고 기록만 남기는데,
그쪽은 검색 한 번이 빈 해석으로도 성립하기 때문이다(FRD §6.2). 이 단계는 결과가
`scene.caption`·`scene.shot_type`이라는 **정본 컬럼**에 들어가고, 한 번 들어가면 그것이 그
장면의 설명이 된다.

| 거부 사유 | 왜 |
| --- | --- |
| JSON이 아니다 / 객체가 아니다 | 코드펜스만 벗긴다. 그 밖의 교정은 추측이다 |
| schema에 없는 키 | 조용히 버리면 "프롬프트를 고쳤는데 출력이 그대로"를 못 잡는다 |
| `shot_type` 어휘 밖 | FRD가 정한 4값이다 |
| `scene_type` 어휘 밖 | 닫힌 초안 어휘다. 새 값이 필요하면 설정을 고친다 |
| 근거 없는 값 | `null`·`unknown`이라는 답이 이미 열려 있다 (§3) |
| 입력에 없는 근거 라벨 | 가리킬 프레임이 없다 (§5) |
| 날짜 태그 유형 | 유형 자체가 schema에 없다 (아래) |
| `tag_candidates` 안의 `scene_type` | 전용 필드가 있다 (§4) |
| caption이 상한을 넘음 | 자르지 않는다. 잘린 문장은 그 설명의 일부이지 설명이 아니다 |
| 태그 후보 상한 초과 | 프롬프트가 상한을 말하므로 지시된 규칙이다 |

**날짜 유형을 schema에서 뺀 것은 티켓보다 한 칸 엄격하다.** 티켓은 "화면에 날짜가 보인다는
이유만으로 `broadcast_date`·`filmed_date`로 **확정**하지 않는다"이고, 이 구현은 후보 자체를
만들지 못하게 한다. 근거는 둘이다 — 화면에 표시된 문자열을 읽는 일은 OCR의 몫이고
(`docs/frd.md:133`), FRD F-04가 "OCR 신뢰도와 원본 문맥을 확인하지 않고 화면의 날짜
문자열을 방송일·촬영일로 간주하지 않는다"로 그 판단의 입력을 OCR 쪽에 두었다. VLM이 날짜
후보를 만들 자리가 없다.

### 장면 하나가 깨지면 전체가 실패다

깨진 장면만 빼고 나머지를 반납하는 선택지가 있었다. 쓰지 않았다 — 빠진 장면은 "설명이 없는
장면"으로 저장되고, 그러면 **실패가 정상 데이터로 보인다.** `ocr`이 keyframe 하나를 못 읽을
때 전체를 실패로 보는 것과 같은 판단이다.

대가는 있다. 장면 200개짜리 클립에서 한 장면의 출력이 깨지면 그 클립의 VLM 결과가 전부
없어진다. `VLM_SCHEMA_INVALID`는 영구라 재시도도 없다. **실측에서 거부율이 실제로 문제가
되면 여기를 다시 본다**(§8) — 그때 선택지는 "장면 단위 부분 성공"이 아니라 `stage_states_json`에
부분 실패를 표현할 자리를 만드는 쪽이다. 계약을 고치지 않고 조용히 빼는 것만 하지 않는다.

## 7. 설정과 버전

정본: `src/npick_worker/config/vlm_metadata.v1.toml`. **임계값을 코드에 두지 않는다.**

| 키 | 의미 | 비고 |
| --- | --- | --- |
| `schema` | 설정 스키마 이름 | `config_version` 앞부분 |
| `max_keyframes_per_scene` | 한 장면에서 모델에 넣을 최대 장 수 | VRAM·추론 시간·외부 payload 상한이 전부 여기 걸린다 |
| `max_tag_candidates_per_scene` | `scene_type` 포함 전체 태그 후보 상한 | 중복 제거 전 검사하며 초과 시 전체 거부. 프롬프트에도 같은 값이 나간다 |
| `caption_max_chars` | 설명 글자 수 상한 | 넘으면 자르지 않고 거부한다 |
| `scene_type_vocabulary` | 장면 유형 닫힌 어휘 **초안** | 실측 후 확정 |
| `call.temperature` | 0.0 | 품질이 아니라 **재현성** 때문이다(계약 §8 멱등성) |
| `call.max_output_tokens` | 출력 상한 | 넘치면 잘린 JSON이 오고 검증에서 걸린다 |
| `call.timeout_seconds` | 호출 하나의 상한 | `MaxTimeCriteria`로 생성을 끊고 `STAGE_TIMEOUT`(일시)으로 보고한다. 단계 전체 timeout은 BE 소유 |
| `prompt.system`·`prompt.user` | 프롬프트 템플릿 | 어휘는 코드에서 주입된다 |

**모든 수치가 실측 후 확정(FRD §11) 잠정값이다.** 근거가 생기면 파일을 v2로 복사하고 이
문서에 변경 근거를 남긴다.

### 버전이 셋인 이유

| 값 | 무엇이 바뀌면 움직이나 |
| --- | --- |
| `config_version` | 설정 파일의 아무 값 |
| `prompt_version` | **렌더링된** 프롬프트 — 템플릿, 어휘, 글자 수 상한 |
| `metadata_schema_version` | 모델에게 요구하는 JSON의 모양 (`schema.py`, 손으로 올린다) |

`model_version`은 `<모델>@<리비전>`인데, 가중치를 올린 뒤에는 **리비전 자리에 실제 commit
SHA가 들어간다**. `main` 같은 움직이는 ref를 그대로 남기면 원격이 갱신돼도 기록이 같아서,
다른 가중치로 만든 결과가 같은 `modelVersion`·`stageVersion`을 달게 된다 — 재현도 처리 버전
구분도 거짓이 된다. SHA를 알아내지 못한 실행은 경고를 남긴다.

워커는 VLM 워밍업이 성공한 뒤에만 capability에 해당 버전을 싣는다. 워밍업 실패 시
VLM을 배정받지 않으며, 재워밍업 또는 워커 재시작으로 복구한다. claim과 실패 기록을
만드는 도중에는 가중치를 로딩하지 않는다.

리뷰 반영으로 `scene_type`을 포함한 태그 상한을 명시한 현재 설정은
`vlm-metadata-config/v1:13d50f07`, 프롬프트는 `vlm-metadata-prompt/v1:78a02dbd`이다.
§9.6의 기존 GPU 비교는 변경 전 설정의 실측이며 해당 원시 결과와 버전은 그대로 보존한다.
변경된 프롬프트로 실제 GPU smoke를 재실행한 결과는 아직 없다.

`prompt_version`을 템플릿만으로 만들지 않는 이유가 있다. 템플릿은 `{scene_types}` 같은 자리만
갖고 실제 목록은 렌더링할 때 채워지므로, 템플릿만 해시하면 어휘를 바꿔도 값이 그대로다 —
모델에게 한 말은 달라졌는데 기록은 같다고 말하는 셈이다.

재현 식별자는 다섯 축이다 — `(config_version, engine, engine_version, model_version,
tokenizer)`. `prompt_version`은 축이 아니다. 프롬프트가 설정 파일의 한 절이라 `config_version`이
이미 그것을 덮는다. 고정 테스트 벡터는 계약 문서 §7에 있고 `tests/test_job_contract.py`가
대조한다.

## 8. 외부 제공자 — 지금은 닫혀 있다

`02-container.md`의 *요소* 표가 이 단계를 워커의 **자체 GPU**에 둔다. 외부 호출은 PRD §12.4의
조건을 전부 만족할 때만 쓰는 대체 경로이고, `external_policy.py`가 그 조건을 코드로 옮겼다.

일곱 조건 중 여섯은 설정으로 판정할 수 있다. 나머지 하나 — **clip별 외부 처리 권리와 clip별
허용** — 은 설정에서 읽지 않는다. PRD가 "media clip별 승인과 deployment-level 승인은 서로
대신할 수 없다"로 못 박았기 때문이다. 전역 플래그로 읽으면 운영자가 한 번 켜는 것이 모든
클립의 권리 확인을 대신하게 되고, 그게 정확히 금지된 것이다.

**그래서 외부 경로는 항상 거절된다.** 잡 계약에 clip별 권리 확인을 실어 보내는 자리가 없고,
FRD §11이 그 확인을 담는 DB 컬럼·정책 테이블을 만들지 않기로 했다. 거절은 전송 **전**이고
`EXTERNAL_PROCESSING_NOT_ALLOWED`(영구)로 기록된다. 나머지 여섯 검사를 지금 구현해 둔 이유는,
그 자리가 생겼을 때 통과 조건을 새로 발명하지 않기 위해서다.

감사 기록에는 원문을 남기지 않는다 — provider profile·payload category·크기·판정·결과만
남는다(PRD §12.4).

## 9. 샘플 클립으로 돌리기

```bash
uv run --directory ai python -m npick_worker.vlm_metadata.report \
    samples/out/KNI_02205-frames --out samples/out/KNI_02205-vlm --model <후보> --smoke
```

입력은 `frame_extraction.report`가 만든 디렉터리다. 장면마다 한 줄로 설명·샷 유형·장면
유형을 출력하고, `--out`에 **설정 version과 원시 출력을 그대로** 저장한다 — "평가에 사용한
설정 version과 원시 결과를 보존한다"가 티켓의 요구다. 저장되는 것은 통과한 출력만이 아니다.
**결과를 얻지 못한 장면도 `rejected[]`로 남는다** — schema 거부는 원문과 사유가, 호출 실패는
오류와 걸린 시간이, 양쪽 다 **실제로 넣은 keyframe 목록과 함께** 남는다. malformed output이
프롬프트를 고칠 근거이듯, "10장면 중 3장면이 상한을 넘었다"는 그 후보를 탈락시키는 근거다.
카운트만 남기면 둘 다 사라지고, 실패가 기록되지 않는 후보일수록 비교표에서 유리해진다.

**파일은 장면마다 다시 쓴다.** 마지막에 한 번만 쓰면 중간에 OOM으로 실행이 끊길 때 앞선
장면의 실측까지 함께 사라진다. OOM처럼 그 장면만 버리고 계속할 수 없는 실패는 그대로
올려보내되(도구의 버그를 후보의 성적으로 기록하지 않는다), 끊긴 자리는 `aborted`로 남는다.

`--smoke`를 주면 티켓의 두 조건을 **도구가 검사한다** — 장면 10건 이상, 장면마다 keyframe
2장 이상. 세는 것은 디렉터리에 있는 프레임이 아니라 **실제로 모델에 넣는 프레임**이다. 둘은
설정 때문에 다를 수 있고(`max_keyframes_per_scene = 1`), 그 실행은 장면마다 열 장이 있어도
"각 scene의 복수 keyframe을 입력"을 증명하지 못한다. 조건에 미달하면 모든 출력이 유효해도
종료 코드가 1이다. 조건을 만족한 실행에서 거부가 하나라도 있으면 역시 1이다. 종료 코드로
판정할 수 있어야 그 실행이 증거가 된다.

실행 머리글과 저장 JSON에 **device와 peak VRAM**이 함께 남는다. device가 `cpu`로 찍힌
실행의 시간·메모리 수치는 후보 비교에 쓸 수 없다.

**이 도구는 거부된 장면에서 멈추지 않는다.** 운영 경로와 다른 점이고 의도적이다 — 단계는
하나만 깨져도 전체 실패지만(§6), 후보를 비교할 때는 몇 장면이 왜 깨졌는지가 그 후보의
성적이기 때문이다.

### 9.1 현재 실행 상태

저장소에는 `KNI_02205-frames`의 scene 10건과 scene별 2~5개 keyframe이 있어 smoke 입력은
준비돼 있다. **외부 자체 호스팅 L40S에서 세 후보의 실제 smoke를 완료했다 (§9.6).**
로컬 RTX 4070 Laptop 8GB에서는 실행하지 않았다. 현재 adapter는 양자화나 CPU offload를
사용하지 않으며 4B의 실측 allocated peak도 9.39 GiB이므로 이번 조건을 8GB로 재현할 수 있다고 보지 않는다.

2026-09-13 사용자가 제공한 `nvidia-smi` 출력에서 GPU 0은 **NVIDIA L40S 한 장**이며,
전체 VRAM은 **46,068 MiB**, 당시 사용량은 **0 MiB**, GPU 사용률은 **0%**다.
두 팀이 같은 디바이스를 공유하며 우리 팀은 전체 VRAM의 절반만 사용한다.
따라서 팀 전체 GPU 프로세스의 메모리 예산은
`46,068 / 2 = 23,034 MiB ≈ 22.49 GiB ≈ 24.15 GB`다.
다른 팀이 아직 실행하지 않아 전체 메모리가 비어 있어도 우리 팀 예산은 늘리지 않는다.
이는 팀 간 사용 규칙이며 메모리 격리나 강제 제한이 설정됐다는 증거는 아니다.
실행 전후 서버 상태는 다음 명령으로 확인한다.

```bash
nvidia-smi --query-gpu=index,name,memory.total,memory.used,memory.free --format=csv
```

공유 L40S에서 장면 요청 동시성 1로 **4B BF16부터** 검증한다. 초기 실험에서는 팀 예산 중
2~3 GiB를 여유로 남기고 VLM 실행의 peak VRAM을 약 19.5~20.5 GiB 이내로 잡는다.
이는 운영 여유 목표이며 팀 전체를 격리하는 제한이 아니다. §9.5 benchmark는 별도로
20 GiB PyTorch allocator 상한을 적용한다. OCR·ASR이 함께
상주한다면 그 점유량도 같은 예산에서 차감한다. 팀 전체 프로세스 점유량은 별도로 확인하며,
단일 VLM report의 PyTorch 메모리 값만으로 팀 예산 준수를 판정하지 않는다.
두 팀의 동시 실행 여부를 평가 기록에 남기고 단독 실행과 공유 실행의 처리 시간을 구분한다.
VRAM을 절반씩 쓰는 규칙만으로 연산 성능도 절반씩 보장되는 것으로 간주하지 않는다.

9B BF16은 이번 최대 5장 입력에서 reserved 18.58 GiB로 실행됐다. 더 큰 해상도·동시성은 추가 검증한다.
Gemma 12B BF16은 가중치만 약 24GB라 현재 예산에서 우선 실행하지 않는다.
27B BF16·공식 FP8도 더 큰 할당을 확보한 뒤 평가한다. 9B 양자화는 별도 선택지지만
현재 adapter에 양자화 설정이 없으므로 모델명 변경만으로 실행 가능하다고 간주하지 않는다.
이 미실행 상태를 schema 통과나 품질 합격으로 간주하지 않는다.

### 9.2 동일 조건 smoke test

동일 GPU에 들어가는 후보끼리 같은 driver/CUDA/PyTorch/Transformers, 같은 BF16,
같은 입력과 `vlm_metadata.v1.toml`로 순차 실행한다. 새 모델의 non-thinking 적용은 먼저
어댑터에서 검증하고 관련 설정 버전을 기록한다. 기존 baseline은 Instruct 조건을 사용한다.
해상도·출력 상한·동시성을 고정하되 모델별 processor가 만드는 이미지 토큰 수도 기록한다.
27B BF16까지 동일 장비로 속도를 비교하려면 모두 같은 80GB GPU에서 재측정한다.
장비가 다르면 품질과 비용은 조건을 명시해 비교하고 속도 차이를 모델만의 효과로 해석하지 않는다.
FP8은 지원 확인 후 별도 profile로 평가한다. 후보마다 다음을 보존한다.

| 기록 | 방법 |
| --- | --- |
| 입력 | `KNI_02205-frames`의 동일 scene 10건, 각 scene의 selected keyframe 전부(최대 5장) |
| 버전 | GPU·driver·CUDA, model 40자리 revision, engine/runtime, schema, prompt/config, tokenizer |
| 형식 안정성 | 10건 중 schema-valid 건수, 거부 사유별 건수. 10/10이 smoke 합격 |
| 시간 | scene별 elapsed와 총시간. 현재 report의 첫 호출에는 모델 로딩이 포함되므로 warm-up 분리 측정은 추가 계측 필요. 10건의 p95는 운영 지연 판정에 쓰지 않음 |
| 메모리 | 현재 report는 로딩을 포함한 실행 전체의 `max_memory_allocated`와 `max_memory_reserved`를 기록. scene별 peak는 추가 계측 필요 |
| 원시 결과 | 성공·실패 모두 원문, 입력 keyframe 목록, 검증 오류를 artifact로 보존 |

각 후보의 실행 예시는 다음과 같다. 실제 디렉터리는 후보와 revision을 구분해 덮어쓰지 않는다.

```bash
uv run --directory ai python -m npick_worker.vlm_metadata.report \
  samples/out/KNI_02205-frames \
  --out samples/out/vlm-qwen35-4b-<revision> \
  --model Qwen/Qwen3.5-4B \
  --revision <40자리-commit-sha> \
  --limit 10 --smoke
```

report는 위 표의 기록을 artifact 하나에 담는다 — 장면별 elapsed, 성공·실패 **양쪽의 원문과
검증 오류**(`rejected[]`, 실패는 `kind`로 `schema_invalid`·`call_failed`·`aborted`를 구분한다),
설정·프롬프트·모델·tokenizer version, device, 그리고 실행 전체의
`max_memory_allocated`·`max_memory_reserved`(`memory`). torch·CUDA가 없으면 `memory`는
`null`이고 **0이 아니다** — "재지 못했다"와 "0을 썼다"는 다르다.

`--revision`에 40자리 SHA를 주는 것이 위 표의 요구다. 주지 않고 `main`으로 돌리면 어댑터가
**실제로 올라간 가중치의 commit SHA를 읽어 기록한다**. 읽지 못하면(로컬 디렉터리에서 올린
경우) 선언한 값이 그대로 남고 경고가 뜬다 — 그 실행의 한 줄은 나중에 같은 가중치를 가리키지
못하므로 최종 비교표에 올리지 않는다.

수동으로 본 `nvidia-smi` 값이나 터미널에만 남은 오류는 최종 비교표의 근거로 쓰지 않는다.
다른 프로세스와 allocator 캐시가 섞여 재현할 수 없기 때문이다.

### 9.3 품질 비교

smoke 10건으로 형식과 후보 간 예비 품질을 비교하되 최종 품질 합격을 판정하지 않는다.
이번 AI 예비 검토는 §9.6에 있으며, 사람의 개발셋 검수에서는 다음을 비교한다.
최종셋은 prompt·taxonomy·threshold 조정에 사용하지 않는다.

- `shot_type`: confusion matrix와 Macro F1. `interview↔b_roll`, `anchor↔b_roll`을 따로 본다.
- `caption`: 2인 판정의 정확/부분 정확/오류/판정 불가 비율과 불일치 조정 기록.
- `scene_type`: closed vocabulary 정확도뿐 아니라 정답이 어휘에 없는 비율을 함께 기록한다.
- tag candidate: precision, 무근거 고유명사 비율, 날짜 태그 시도 여부, `null`/빈 배열 회수율.
- 복수 keyframe 효과: 같은 모델에서 대표 1장 입력과 selected keyframe 전체 입력을 비교한다.
  다른 모델끼리 1장/복수 조건을 섞어 multi-image 품질 차이라고 결론 내리지 않는다.
- calibration: confidence 구간별 실제 정확도를 보고, confidence를 verified 승격 기준으로 쓰지 않는다.

운영 후보 확정 순서는 `10/10 schema-valid` → 개발셋 품질·속도·VRAM 비교 → 별도 Gold Set 목표
확인이다. 세 후보의 첫 실측 결과에 따라 9B를 조건부 주 후보로 삼았다 (§9.6).
사람 검수·개발 평가와 Gold Set에서 품질 목표를 못 넘으면 prompt 개선을 별도 version으로
비교하고, 추가 GPU 할당이 필요할 때 Gemma 4 12B Unified, Qwen3.8-27B를 검토한다.

GPU 규모는 사용자 수보다 영상당 장면 수·하루 유입량·처리 완료 기한으로 산정한다.
단일 요청 기준 VLM 소요시간의 1차 추정은 `총 장면 수 × 평균 초/scene`이며, 동시성 확장 효과는
실측한다. 최종 용량 판단에는 영상 전송·모델 준비·OCR·ASR·결과 반환을 포함한 전체 파이프라인
시간을 사용한다. OCR·ASR 모델 동시 상주 여부와 peak VRAM도 따로 기록한다.
FRD의 기존 333클립·약 2,200장면·약 2시간 목표는 평가 기준이며 현재 확보량이나 처리 성과가 아니다.

### 9.4 시간 상한이 하는 일

`call.timeout_seconds`는 기록이 아니라 제약이다. 생성이 그 시간을 넘으면 어댑터가
`MaxTimeCriteria`로 끊고 **잘린 출력을 돌려주지 않는다** — `TimeoutError`를 던져
`STAGE_TIMEOUT`(일시)으로 보고한다.

잘린 JSON을 그대로 검증에 넘기면 `VLM_SCHEMA_INVALID`(영구)가 되는데, 그건 두 번 틀린
기록이다. 원인이 형식 오류가 아니고, 재시도로 풀릴 수 있는 실패가 재시도 불가로 남는다.

`MaxTimeCriteria`가 없는 런타임에서는 생성을 중간에 끊지 못한다. 그때도 상한이 사라지지는
않는다 — 호출이 끝난 뒤 경과 시간을 재서 같은 `TimeoutError`를 던진다. 끊지 못했다는 사실은
경고 로그로 남는다.

### 9.5 Linux GPU 서버에서 직접 실행하기

코드는 `src/npick_worker/vlm_metadata/benchmark.py`, 후보별 별도 프로세스를 순차 실행하는
진입점은 `run-vlm-smoke.sh`다. 자체 호스팅 개발 GPU에서 BE·DB 없이 평가한다. 승인된 서버와
자료 이용 범위 안에서 selected keyframe만 복사한다. 모델 제공자 추론 API로 프레임을
보내는 경로가 아니며 가중치·revision은 Hugging Face에서 내려받는다.

공유 L40S에서는 우선 GPU 0, PyTorch allocator 예산 20 GiB로 시작한다. 이는 실험용 상한이며
운영 확정값이 아니다. CUDA context 등 allocator 밖의 메모리는 별도이므로 팀 전체
22.49 GiB를 강제 격리하는 기능은 아니다. 같은 팀의 다른 GPU 프로세스도 예산에 포함한다.

1. 로컬 `ai/src`, `ai/pyproject.toml`, `ai/uv.lock`, `ai/run-vlm-smoke.sh`와
   `ai/samples/out/KNI_02205-frames/s*/kf-*.jpg`를 같은 상대 경로로 서버에 복사한다.
   이번 작업의 `ai/samples/out/vlm-smoke-kit.tgz`는 이 코드와 10장면의 프레임을 담는다.
   `.env`, 모델 캐시, 전체 영상은 제외한다. 미커밋 코드도 포함되며 소스를 바꾸면 묶음도
   다시 만들어야 한다. Git 이력이 없어도 실행 시 `source-hashes.json`이 실제 코드를 구분한다.
2. Linux 서버에서 묶음을 풀고 설치한다. 기존 체크아웃이면 새 코드까지 반영한 뒤 `cd ai`부터 한다.

   ```bash
   mkdir -p ~/npick-vlm/ai
   tar -xzf ~/vlm-smoke-kit.tgz -C ~/npick-vlm/ai
   cd ~/npick-vlm/ai
   # uv가 없을 때 1회. 공식 설치: https://docs.astral.sh/uv/getting-started/installation/
   curl -LsSf https://astral.sh/uv/install.sh | sh
   export PATH="$HOME/.local/bin:$PATH"
   uv sync --locked --group gpu --group cu128
   nvidia-smi
   df -h . "$HOME"
   export CUDA_VISIBLE_DEVICES=0
   uv run --locked --group gpu --group cu128 python -c 'import torch; print(torch.__version__, torch.version.cuda); assert torch.cuda.is_available(); print(torch.cuda.get_device_name(0))'
   ```

   최초 설치·모델 다운로드에는 네트워크와 디스크 여유가 필요하다. CUDA 드라이버와 잠긴
   torch 휠이 맞지 않거나 AutoModel이 후보를 지원하지 않으면 로그를 보존하고 환경부터
   맞춘다. 한 후보만 라이브러리를 갱신했다면 다른 후보도 같은 환경으로 다시 측정한다.
3. 먼저 4B 한 개로 smoke를 실행한다. SSH를 끊을 예정이면 `tmux` 세션에서 실행한다.

   ```bash
   bash run-vlm-smoke.sh samples/out/KNI_02205-frames samples/out/vlm-bench 20 Qwen/Qwen3.5-4B
   ```

   `summary.json`의 `smokePassed: true`를 확인한다. 파일이 없으면 로딩 이전 실패일 수 있어
   `run.json`과 `.log`부터 본다. 모델명만 주면 시작 시 `main`을 40자리 SHA로 해석해 로딩 전에
   고정한다. 같은 가중치의 재실행은 아래 명령에 `run.json`의 SHA를 넣는다. 출력 경로는 새로 쓴다.

   ```bash
   uv run --locked --group gpu --group cu128 python -u -m npick_worker.vlm_metadata.benchmark \
     samples/out/KNI_02205-frames --model Qwen/Qwen3.5-4B \
     --revision <run.json의_modelRevision> --out samples/out/vlm-bench/repeat-4b-01 \
     --memory-budget-gib 20 --dtype bfloat16 --thinking off --limit 10
   ```

4. 같은 입력·설정으로 나머지 두 후보를 실행한다. 12B·27B는 현재 공유 예산의 첫 비교에서 제외한다.

   ```bash
   bash run-vlm-smoke.sh samples/out/KNI_02205-frames samples/out/vlm-bench 20 \
     Qwen/Qwen3.5-9B Qwen/Qwen3-VL-8B-Instruct
   ```

   OOM이면 해당 조건의 실패로 보존한다. 예산을 임의로 늘리지 않는다. 후보 하나가 실패해도
   다음 후보를 별도 프로세스로 실행하며, 전부 통과해야 shell 종료 코드가 0이다.
   FP16·프레임 수·출력 상한을 바꾸면 별도 실험이다. 실패한 장면만 다른 모델로 채우지 않는다.
5. 결과 폴더 전체를 보존하고 품질은 실제 프레임을 보며 직접 판정한다.

   | 파일 | 내용 |
   | --- | --- |
   | `run.json` | 실행 ID·시각, 고정 모델 SHA, 설정 버전, GPU/runtime, 실제 dtype, 모델 준비 시간, 실패 이유 |
   | `config.toml`, `schema.json`, `prompts.json`, `generation-config.json`, `image-processor.json` | 실제 설정·출력 계약·프롬프트·모델 생성/이미지 처리 기본값 |
   | `inputs.json`, `source-hashes.json` | selected 프레임 순서·SHA256과 코드 해시 |
   | `vlm-metadata.json` | 원시 출력·근거 입력·거부/호출 실패·소요 시간 |
   | `summary.json` | schema 통과 수, 실패 포함 평균·중앙값·nearest-rank p95, peak allocated/reserved VRAM |
   | `quality-review.json` | 사람이 채울 장면별 판정. 초기 `null`은 미평가 |
   | 실행 디렉터리 옆 `.log`, `.exit-code.txt` | 설치/로딩/호출 오류와 프로세스 종료 상태 |

   모델 다운로드·로딩은 장면 시간에서 제외하고 `loadSeconds`에 따로 기록한다. 첫 추론의
   캐시·커널 준비 비용은 장면 시간에 포함한다. 공유 GPU의 다른 팀 부하도 기록하고 같은
   조건에서 반복한다. PyTorch peak와 `nvidia-smi`의 GPU 전체 점유량은 다른 측정이다.

   `quality-review.json`은 실제 프레임과 원시 결과를 함께 보고 채운다. `shotTypeExpected`는
   닫힌 4값, `captionRating`은 `correct / partial / incorrect / unclear`, `sceneTypeExpected`는
   현재 taxonomy 또는 `null`이다. 나머지 근거·복수 프레임 이해 판정은 true/false와 `notes`로
   남긴다. 모델 confidence로 대신하지 않는다. 후보별 caption·shot type·scene metadata·복수
   프레임 이해 판정을 비교표로 정리하고 시간·메모리 실패까지 반영해 조건부 후보를 고른다.
   10장면은 smoke·개발 비교용이며 별도 Gold Set 100장면의 품질 목표 달성을 증명하지 않는다.

### 9.6 2026-09-13 실제 세 후보 비교

실행일은 2026-09-13 UTC (한국 시각 2026-09-13~14), 문서 검토일은 2026-09-14 KST다.
사용자가 자체 호스팅 Linux GPU 서버에서 실행한 결과를 받아 비교했다. 새 추론을 로컬에서
실행한 결과가 아니다. 원본 위치는 `ai/samples/out/vlm-benchmarks/<모델>/`이며,
원시 JSON 12개는 이 로컬 경로에 보존하고 Git에는 포함하지 않는다. 아래 링크는 로컬 파일 참조이므로
다른 체크아웃에서는 원본 결과를 별도로 받아야 한다.
원시 결과의 `qualityVerdict: not_evaluated`는 수정하지 않았다. 아래 품질 판정은 원시 결과와
분리된 **Codex의 이미지 대조 예비 평가**이고, 2인 사람 검수·Gold Set 정답이 아니다.

| 모델 | 로컬 원시 결과 (Git 제외) | 실행 ID | 고정 model revision |
| --- | --- | --- | --- |
| Qwen3.5-4B | [metadata](../samples/out/vlm-benchmarks/Qwen3.5-4B/vlm-metadata.json), [summary](../samples/out/vlm-benchmarks/Qwen3.5-4B/summary.json), [run](../samples/out/vlm-benchmarks/Qwen3.5-4B/run.json), [inputs](../samples/out/vlm-benchmarks/Qwen3.5-4B/inputs.json) | `20260913T130828Z-Qwen--Qwen3.5-4B-889142` | `851bf6e806efd8d0a36b00ddf55e13ccb7b8cd0a` |
| Qwen3.5-9B | [metadata](../samples/out/vlm-benchmarks/Qwen3.5-9B/vlm-metadata.json), [summary](../samples/out/vlm-benchmarks/Qwen3.5-9B/summary.json), [run](../samples/out/vlm-benchmarks/Qwen3.5-9B/run.json), [inputs](../samples/out/vlm-benchmarks/Qwen3.5-9B/inputs.json) | `20260913T144301Z-Qwen--Qwen3.5-9B-890606` | `c202236235762e1c871ad0ccb60c8ee5ba337b9a` |
| Qwen3-VL-8B-Instruct | [metadata](../samples/out/vlm-benchmarks/Qwen3-VL-8B/vlm-metadata.json), [summary](../samples/out/vlm-benchmarks/Qwen3-VL-8B/summary.json), [run](../samples/out/vlm-benchmarks/Qwen3-VL-8B/run.json), [inputs](../samples/out/vlm-benchmarks/Qwen3-VL-8B/inputs.json) | `20260913T144812Z-Qwen--Qwen3-VL-8B-Instruct-890606` | `0c351dd01ed87e9c1b53cbc748cba10e6187ff3b` |

**비교 조건과 검증 범위**

- **입력은 순서까지 동일한 10개 scene·23장**이다. Scene 0은 5장,
  나머지 9개 scene은 각 2장이다. 세 `inputs.json`의 내용이 같고, 각 입력의 SHA256을
  로컬 `samples/out/KNI_02205-frames` 이미지와 대조해 69/69 일치를 확인했다.
- GPU는 NVIDIA L40S, 전체 VRAM 46,068 MiB, driver `570.211.01`, Linux x86_64,
  Python `3.12.6`, PyTorch `2.13.0+cu126`, CUDA runtime `12.6`,
  torchvision `0.28.0+cu126`, Transformers `5.17.0`이다. 세 run의 패키지 목록은 동일하다.
- 실제 dtype은 `torch.bfloat16`, attention은 `sdpa`, processor는 `Qwen3VLProcessor`다.
  4B·9B의 model class는 `Qwen3_5ForConditionalGeneration`, 8B는 `Qwen3VLForConditionalGeneration`이다.
  공통 요청 설정은 `thinking=off`, allocator budget 20 GiB, limit 10이다.
  Instruct 모델에서 thinking 옵션의 의미가 Qwen3.5와 동일하다는 뜻은 아니다.
- `benchmarkConfigVersion=vlm-benchmark/v1:1525101584860a3b`,
  `configVersion=vlm-metadata-config/v1:fcd15e10`,
  `promptVersion=vlm-metadata-prompt/v1:587f345d`, `schemaVersion=vlm-metadata/v1`이다.
  tokenizer는 `query-norm/v1:b0d96c0c:kiwi0.23.2:model0.23.0`이다.
  현재 저장소 config·prompt 버전과도 일치한다.
- 전달받은 30개 `rawOutput`을 현재 `parse_raw`와 `validate`로 다시 검사했다.
  schema·닫힌 어휘·근거 라벨 검증 모두 통과했고, scene별 시간으로 summary 평균·중앙값·p95를 재계산해 일치를 확인했다.
  이는 **근거 라벨의 유효성**을 확인한 것이며 이미지가 주장을 뒷받침하는지는 별도로 판단한다.
- `gpuBefore`는 세 실행 모두 GPU 0 사용량 0 MiB, GPU 1 사용량 22,745 MiB를 기록한다.
  실행 중 부하·선택 장치 UUID·다른 팀 동시 실행 여부는 이 파일들만으로 확인할 수 없다.
  각 후보 1회 실행이므로 반복 측정이나 운영 부하에서의 처리량 보장은 아니다.

**추론 시간·메모리 실측**

| 항목 | Qwen3.5-4B | Qwen3.5-9B | Qwen3-VL-8B-Instruct |
| --- | ---: | ---: | ---: |
| schema-valid / 시도 scene | 10/10 | 10/10 | 10/10 |
| 실패 scene | 0 | 0 | 0 |
| 평균 초/scene | 11.134 | 14.432 | 6.776 |
| 중앙값 초/scene | 9.045 | 13.330 | 6.110 |
| P95 초/scene | 22.290 | 28.720 | 13.430 |
| 모델 준비 시간 초 (`loadSeconds`) | 84.57 | 163.42 | 148.89 |
| peak allocated GiB | 9.39 | 18.41 | 17.16 |
| peak reserved GiB | 9.58 | 18.58 | 17.55 |
| 요청 allocator 상한 GiB | 20 | 20 | 20 |

장면 시간은 모델 준비를 제외하고 첫 추론 및 실패를 포함하는 기준이다. 모델 준비 시간에는
다운로드·캐시 상태 영향이 섞일 수 있어 순수 로딩 속도의 우열로 쓰지 않는다.
10건의 nearest-rank P95는 최댓값이고 첫 scene은 유일하게 5장이다. 이를 운영 P95나
모델 자체의 토큰 생성 속도로 해석하지 않는다. 8B는 출력이 짧고 태그 수도 적어 실행 시간이
짧은 이유를 모델 구조만으로 설명할 수 없다. 출력 토큰 수와 이미지 토큰 수는 전달 자료에 없다.
메모리는 PyTorch allocator 통계이며 CUDA context나 다른 프로세스를 포함한 GPU 전체 사용량이 아니다.
이번 입력에서 실행된 값이지 최소 GPU 사양 또는 OCR·ASR 동시 상주 가능성을 증명한 값은 아니다.

**Caption 장면별 예비 판정**

판정 규칙은 세 모델에 동일하게 적용했다. `정확`은 주요 대상·동작·공간 관계가 이미지와
부합하는 경우, `부분`은 핵심 일부가 맞지만 부수적 추정·대상 세부 오류·중요 장면 누락이나
표현 오류가 있는 경우, `오류`는 주요 동작·장소·대상을 잘못 규정하는 경우다.
이는 이번 검토의 기준이며 제품의 최종 판정 기준으로 확정하지 않는다.
태그와 scene_type 오류는 caption과 별도 평가한다. 아래 번호는 0부터 시작하는 `sceneIndex`다.

| Scene / 이미지 내용 | 4B | 9B | Qwen3-VL-8B |
| --- | --- | --- | --- |
| 0: 야외 기자 → 실내 체험 → 건물 외관 | 부분 — 실내 체험을 기자의 배경처럼 합침 | 정확 — 야외 보도와 이후 실내 체험을 구분 | 부분 — 기자를 복수로 표현하고 체험 장면을 취재 배경으로 축약 |
| 1: 실내 경기장·무대 준비 | 정확 — 경기장·조명·준비 인원 | 정확 — 시설·준비 상황 구체화 | 정확 — 경기장·조명·인원 |
| 2: 매트 위 도약·발차기 | 오류 — 복싱 훈련으로 규정 | 정확 — 차기 동작을 묘사하고 종목 단정 회피 | 부분 — 격투기 동작은 부합하나 실제 경기 중이라고 단정 |
| 3: 매트 위 동작 연습·주변 인원 | 오류 — 스케이팅 선수 맞이 의식 | 부분 — 발차기 계열은 포착하나 경기 전 준비·태권도 경기장 단정 | 부분 — 장소·모인 인원만 서술하고 매트 위 동작 누락 |
| 4: 행사장 여러 스크린의 남성 영상 | 부분 — 화면 구조는 맞으나 뉴스 방송 단정 | 정확 — 여러 스크린에 같은 발표 영상 | 오류 — 뉴스 스튜디오 진행자로 전체 장면 오인 |
| 5: 실내 조명 운영 장비·작업자 | 오류 — 스튜디오·촬영 장비로 규정 | 오류 — 스튜디오·카메라 장비로 규정 | 오류 — 스튜디오·카메라로 규정 |
| 6: 무대 의자·악보대 | 정확 | 정확 | 정확 |
| 7: 야외 눈 조형물 | 부분 — 곰 형태의 조각을 사자로 식별 | 부분 — 조형물은 맞지만 사람의 작업 여부 단정 | 정확 — 동물 종을 단정하지 않고 눈 조각 묘사 |
| 8: 전시관 외관 | 정확 | 정확 | 정확 |
| 9: 전시 부스·방문객 | 부분 — 내용은 부합하나 `전시ブース` 일본어 혼입 | 정확 — 부스와 방문객 묘사 | 정확 — 부스와 방문객 묘사 |
| 정확 / 부분 / 오류 | 3 / 4 / 3 | 7 / 2 / 1 | 5 / 3 / 2 |
| 정확+부분 / 10 (AI 예비 판정) | 70% | 90% | 80% |

9B의 90%는 **제품 목표 85% 달성 판정이 아니다**. 단일 클립의 작은 개발 표본을 모델 출력을
본 AI가 평가한 값으로, 사람의 독립 판정·불일치 조정이 필요하다. 특히 scene 0·2·3·5·7을
우선 재검수한다. 입력 scene 0 자체가 여러 촬영 구간을 포함하므로 상류 scene 경계의 적절성도
별도 점검해야 한다. 단일 프레임 대조 실험은 없어서 복수 프레임의 정량적 개선 효과는 미측정이다.

**Shot type·scene metadata·근거 비교**

현행 prompt는 `anchor`를 스튜디오 진행, `interview`를 취재원·전문가 발화,
`b_roll`을 그 외 현장·자료 화면으로 정의한다. 이 기준으로 이번 이미지의 예비 정답은
10개 모두 `b_roll`이다. 야외 기자의 보도는 인터뷰로, 행사장 스크린 속 남성은 전체 장면의
앵커로 취급하지 않는다. 4B·9B는 10/10 일치, 8B는 scene 0을 `interview`, scene 4를
`anchor`로 판단해 8/10 일치했다. **정답 클래스가 하나뿐이므로 4종 Macro F1은 보고하지 않는다.**
실제 anchor·interview 및 판단 불가 장면을 보강해야 분류 품질을 비교할 수 있다.

| 항목 | 4B | 9B | Qwen3-VL-8B |
| --- | --- | --- | --- |
| scene_type | 2·3을 스포츠 경기로 분류. 연습·행사와 경기 경계 모호 | 5를 스튜디오로 오인. 2·3 경계 모호 | 0 실내 시설, 1 스포츠 경기, 4·5 스튜디오, 7 거리 등 불일치·과도한 분류 |
| 시각 기반 태그 | 3의 대한민국 빙상대표팀·선수 맞이 의식은 무근거 | 6의 `weather=조명`은 명백한 유형 오류. 3의 태권도 경기 단정 | 3의 스케이트 경기, 4의 뉴스 진행자 등 근거 부족 |
| 태그 수 (raw, scene_type 제외) | 49 | 38 | 23 |
| OCR 역할 분리·표현 | 9의 간판 문자열 나열·동일 태그 중복, 1의 `스태지` 오타 | 8·9의 간판 이름·문구 의존 지속 | 9는 문자열 나열 없이 전시 부스 묘사, 8은 간판 이름 의존 |
| caption confidence | 10건 모두 0.95 | 0.90~0.95 | 0.95~1.00; 잘못된 scene 4도 0.98 |
| 유보 출력 | caption/scene_type null, shot_type unknown 모두 0건 | 동일 | 동일 |

세 모델 모두 scene 8의 건물 **외관**을 `실내 시설`로 분류한다. taxonomy가 실내 화면을
뜻하는지 실내 용도의 시설까지 포함하는지 지침이 불충분하므로 이번에는 그 정답을 임의
확정하지 않는다. 행사/실내 시설/스포츠 경기의 중첩 기준도 사람 검수 후 정한다.
따라서 scene_type 정확도와 tag precision은 수치로 확정하지 않고 오류 사례를 기록했다.
눈 조형물·쌓인 눈을 현재 날씨 `눈`으로 확장하거나 실내 빙상장을 계절 `겨울`로 확장한
판단도 검수 대상이다. 원시 후보 수가 많은 것을 품질 향상으로 보지 않는다.

`visible_text`, 방송일·촬영일 태그는 30건에서 생성되지 않았지만 간판 문자열 의존은 남았다.
이는 독립 OCR 관측을 대체할 수 없다. 유보 출력이 0건이라는 사실은 유보 능력의 합격 근거가
아니며, 근거 부족에서도 단정한 사례가 존재한다. 모든 AI 판단은 계속 `unverified`로 취급한다.
보고서 JSON에는 검증 상태가 직렬화되어 있지 않아 이 결과만으로 BE 저장 경로를 입증하지 않는다.

**선정과 남은 검증**

- **선정 모델: Qwen3.5-9B.** 이번 예비 caption 판정이 가장 좋고, scene 0의 시간·공간 구분과
  scene 2의 동작 설명이 개선됐다. 4B 대비 평균 시간은 약 29.6% 증가하고 reserved 메모리는
  약 9.00 GiB 증가했지만 이번 20 GiB allocator 조건에서 10/10 실행됐다.
  `weather=조명` 등 태그 오류가 있어 현재 출력의 무검수 사실 확정은 허용하지 않는다.
- **메모리 제약 시 대체 모델: Qwen3.5-4B.** reserved 9.58 GiB로 자원 여유가 크다.
  caption 오류가 더 많아 동일 품질 fallback으로 승인한 것은 아니다. 별도 실행·검수로만 사용한다.
- **속도 비교 후보: Qwen3-VL-8B-Instruct.** 평균은 9B보다 약 53.0% 짧지만,
  reserved 차이는 약 1.03 GiB뿐이고 샷 유형·화면 맥락 오인이 있어 이번 주 후보로 고르지 않았다.
- 세 모델 모두 `10 scene × 복수 selected keyframe` 실제 smoke는 완료했다. 이번 이슈는
  리뷰어 승인·MR 머지 후 종료한다. 추가 사람 품질 검수, class-balanced 개발 평가,
  100개 이상 Gold Set과 운영 부하 검증은 후속 평가이며 이번 기술 선정과 구분한다.
  학습은 수행하지 않았으므로 learning rate·학습 시간은 비교 항목이 아니다.

전달된 것은 후보별 JSON 4종뿐이다. `gitCommit=unavailable`이고 원격 `source-hashes.json`,
`config.toml`, `schema.json`, `prompts.json`, 생성·이미지 processor 설정, 실행 로그는 아직 없다.
현재 로컬 config/prompt 해시는 일치하지만 실행 당시 전체 코드·모델별 토큰 예산까지 동일했다는
증거를 대신하지 못한다. 원격 실행 폴더 전체는 추가 보존해야 하며 원본 이미지도 권한이 있는
저장소에서 inputs의 해시와 함께 유지한다. 이 문서에는 영상·이미지·가중치를 복사하지 않았다.
또한 원격은 cu126·torchvision 환경이지만 로컬 lock은 cu130 기반이므로 로컬 기본 설치만으로
동일 환경을 재현했다고 보지 않는다. 이번 변경은 문서와 평가 근거 보존이며 의존성·prompt는 바꾸지 않았다.

## 10. 언제 다시 볼 것인가

- **모델 선정** — §2의 1차/fallback은 기술 선정이다. §9 실측과 Gold Set 뒤 운영 revision을 고정한다.
- **`scene_type_vocabulary`** — 실측에서 쓰이지 않는 값은 빼고, 자주 나오는데 없는 값은
  더한다. 그때 설정을 v2로 복사한다.
- **`max_keyframes_per_scene`** — 장 수와 장면 이해 품질의 관계는 개발셋으로만 말할 수 있다.
  상류 `frame_extraction`의 장 수 정책과 함께 본다 — 그쪽이 장면당 장 수를 내용으로 정하므로
  두 값이 서로를 좌우한다.
- **거부율** — §6의 "장면 하나가 깨지면 전체 실패"가 실측에서 문제가 되면 `stage_states_json`에
  부분 실패를 표현할 자리를 계약과 함께 정한다.
- **`caption_max_chars`** — 300자는 폭주 방지값이고 품질 근거가 없다. 검수자가 결과 카드에서
  읽는 길이와 맞는지 사용자 평가에서 확인한다.
- **외부 경로** — §8. 계약에 clip별 권리 확인 자리가 생기면 그때 나머지 검사가 실제로 쓰인다.

## 11. 외부 근거

2026-09-13에 확인했다. 모델 제작자 또는 공식 배포자의 자료만 선정 근거로 사용했다.
아래 자료는 공개 사양의 근거이며 §2.2 GPU 시작점과 N-Pick 품질·속도의 실측 근거는 아니다.

- [Qwen 공식 릴리스 기록](https://github.com/QwenLM/Qwen3.8#news) — Qwen3.5 9B·4B는
  2026-03-02, Qwen3.8-27B는 2026-08-14 공개.
- [Qwen3.5-9B model card](https://huggingface.co/Qwen/Qwen3.5-9B) — 이미지·영상 이해,
  다국어 지원, Apache-2.0, 기본 thinking 모드와 해제 방법, 공식 실행 경로.
- [Qwen3.5-4B model card](https://huggingface.co/Qwen/Qwen3.5-4B) — 작은 규모의 비교 후보,
  입력·라이선스·실행 사양.
- [Gemma 공식 릴리스 기록](https://ai.google.dev/gemma/docs/releases) —
  Gemma 4 12B Unified는 2026-06-03 공개.
- [Gemma 4 12B Unified model card](https://huggingface.co/google/gemma-4-12B-it) —
  encoder-free 구조, 이미지·영상 입력, 다국어 지원, Apache-2.0, processor 사용법.
- [Qwen3.8-27B model card](https://huggingface.co/Qwen/Qwen3.8-27B) — 이미지·영상 입력,
  27B 언어 모델과 약 28B 전체 parameter 표기, Apache-2.0, thinking 제어.
- [Qwen3.8-27B 공식 FP8](https://huggingface.co/Qwen/Qwen3.8-27B-FP8) — 별도 양자화 배포.
  현재 어댑터에서의 실행 가능 여부와 실제 VRAM은 검증 대상.
- [Qwen3-VL-8B-Instruct model card](https://huggingface.co/Qwen/Qwen3-VL-8B-Instruct) —
  기존 baseline의 Apache-2.0, Transformers 사용법, multi-image 입력과 모델 규모.
- [Qwen3-VL Technical Report](https://arxiv.org/abs/2511.21631) — interleaved multi-image·video
  문맥과 모델 family의 크기별 구성.
