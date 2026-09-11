# OCR 선정과 설정

FRD `F-03` 화면 글자 읽기 구현 근거. 구현은 `src/npick_worker/ocr/`.

만들어야 하는 것은 FRD가 정한 넷이다 — **읽은 원문, 신뢰도, 위치, 해당 키프레임**
(`docs/frd.md:123`). 잡 API 쪽 규약(입력·출력 모양)은
[../../docs/contracts/job-api.md](../../docs/contracts/job-api.md) §4.3.2가 정본이고, 이
문서는 **왜 이렇게 읽는가**를 적는다.

## 1. 스키마가 정한 것과 정하지 않은 것

이 단계의 설계 여지도 대부분 테이블이 이미 좁혀 놓았다.

```sql
CREATE TABLE "ocr_observation" (
    "ocr_observation_id" bigint       NOT NULL,
    "keyframe_id"        bigint       NOT NULL,
    "raw_text"           text         NOT NULL,
    "tokens"             text         NOT NULL,
    "confidence"         numeric(5,4) NOT NULL,
    "bounding_box_json"  jsonb        NOT NULL,
    CONSTRAINT ck_ocr_confidence CHECK (confidence BETWEEN 0 AND 1)
);
```

| 요구 | 스키마의 자리 | 결과 |
| --- | --- | --- |
| 해당 keyframe | `keyframe_id` | 워커는 `(sceneIndex, timestampMs)`로 보내고 BE가 행을 찾는다 (§7) |
| OCR 원문 | `raw_text` | 엔진이 읽은 그대로. 컬럼 주석이 "절대 덮어쓰지 않는다" |
| 검색 토큰 | `tokens` | Kiwi 형태소. **색인과 질의가 같은 설정**이어야 한다 (§6) |
| 신뢰도 | `confidence` | `numeric(5,4)` — 넷째 자리까지다 |
| 위치 | `bounding_box_json` | 원본 해상도 픽셀 좌표. 사변형을 그대로 담는다 |
| 검증 상태 | **없음** | 컬럼 주석: "이 값의 임계값으로 검증 상태를 판정한다" (§5) |
| 병합 그룹 | **없음** | 그래서 병합을 저장하지 않는다 (§4) |

## 2. 엔진 선정 — 실측

한국어 OCR 후보 다섯을 **같은 입력**에 돌렸다. 입력은 `frame_extraction`이 샘플 클립
`KNI_02205`에서 뽑은 keyframe 23장(800x450)이고, 정답은 그 23장을 사람이 직접 보고 적은
`samples/ocr-ground-truth.KNI_02205.json`이다. 읽을 수 있는 문구 37개가 분모다.

측정 장비는 Intel 22코어 CPU, GPU 미사용(§9와 같다).

| 후보 | 재현율 | 장당 | 명백한 오탐 | 설치 | 패키지 | 실행기 |
| --- | --- | --- | --- | --- | --- | --- |
| PaddleOCR (server det) | **31/37** | 12,016 ms | 0 | 807 MB | 72 | paddlepaddle |
| PaddleOCR (mobile det) | 28/37 | 1,124 ms | 4 | 807 MB | 72 | paddlepaddle |
| **RapidOCR korean (선택 설정)** | **27/37** | **~430 ms** | 1 | 276 MB | 22 | onnxruntime |
| RapidOCR korean (server det) | 26/37 | 2,205 ms | 0 | 276 MB | 22 | onnxruntime |
| EasyOCR `ko`+`en` | 16/37 | 1,240 ms | 0 | 910 MB | 26 | torch |
| RapidOCR 1.4.4 기본 (`ch`/`en`) | 13/37 | 2,505 ms | 0 | — | — | onnxruntime |

- **"명백한 오탐"** 은 화면에 글자가 아예 없는 프레임(23장 중 4장)에서 나온 출력이다.
  글자가 있는데 잘못 읽은 것은 사람도 못 읽는 작은 글자와 구분할 수 없어 따로 센다.
- **"설치"** 는 빈 venv에 그 후보만 넣었을 때의 크기다. 모델 가중치는 별도로
  PaddleOCR 103 MB, RapidOCR 19 MB(검출 4.8 + 인식 13.5 + 방향 0.6), EasyOCR 95 MB다.
- 마지막 줄은 **한국어 모델이 없는** 기본 설정이다. "RapidOCR은 한국어를 못 읽는다"가
  아니라 "기본값이 중국어·영어다"라는 것을 남겨 둔다.

### 왜 RapidOCR인가

**인식 모델이 PaddleOCR과 같은 것이다.** 둘 다 `korean_PP-OCRv5_rec_mobile`을 쓰고,
RapidOCR은 그 ONNX 판을 onnxruntime으로 돌린다. 남는 차이는 검출 전처리이고 그건 우리
설정 파일의 축이다 — 실제로 검출 파라미터만 바꿔 22/37에서 27/37까지 움직였다.

그래서 선택 기준은 정확도가 아니라 비용이 됐다.

| | PaddleOCR | RapidOCR |
| --- | --- | --- |
| 재현율(이 표본) | 28/37 | 27/37 |
| 이미지 증가 | +807 MB / 72 패키지 | +276 MB / 22 패키지 |
| CPU 실행 | Windows에서 `enable_mkldnn=False` 필수 | 그대로 동작 |
| GPU 전환 | paddlepaddle-gpu (별도 휠) | onnxruntime-gpu (같은 API) |

`infra/compose/profiles/pipeline.yml`이 "CPU 워커와 GPU 파드가 같은 이미지를 쓴다"로
적었으므로 그 807 MB는 OCR을 돌리지 않는 워커에도 실린다.

**Windows CPU의 paddlepaddle 3.3.1은 실제로 죽었다.** oneDNN 경로에서
`NotImplementedError: ConvertPirAttribute2RuntimeAttribute`가 나 `enable_mkldnn=False`로
우회해야 했고, 그 우회가 위 표의 12초짜리 측정을 만들었다. 팀 개발 환경이 Windows다.

**1/37 차이는 이 표본에서 결정적이지 않다.** 클립 한 편·800x450·문구 37개다. 그래서
엔진은 `OcrEngine` Protocol 뒤에 두었다(`ocr/engine.py`). 1080p 클립으로 다시 재서
PaddleOCR이 유의미하게 앞서면 바뀌는 것은 backend 파일 하나와 설정이다.

### 후보에서 뺀 것

- **외부 OCR API**(Naver CLOVA, Google Vision) — FRD §6.4가 미확인 자료의 외부 전송을
  금지하고 §13.4가 외부 전송을 별도 승인 대상으로 둔다. 승인 절차 없이 기본 경로로
  삼을 수 없다.
- **Tesseract** — 한국어 방송 화면(굵은 고딕·색 배경·기울어진 현판)에서 위 후보들과
  비교 대상이 되지 못하고, 시스템 바이너리 의존이 추가된다.
- **VLM에게 읽히기** — 4단계와 3단계(`vlm_metadata`)가 같은 일을 두 번 하게 되고,
  글줄 단위 `bounding_box_json`을 얻을 수 없다.

## 3. 무엇을 관측으로 남기는가

**글줄 하나가 행 하나다.** 문단으로 합치지 않는다 — 합치면 `bounding_box_json`이 여러
줄을 덮는 사각형이 되어 "어디서 읽었는지"를 가리키지 못하고, confidence도 하나로 뭉개져
줄마다 다른 품질이 사라진다.

**빈 원문은 버린다.** 검출기가 상자를 잡았는데 인식이 아무 글자도 내지 못하는 경우가
있다(측정에서 PaddleOCR이 `''`를 여러 건 냈다). `raw_text`가 `NOT NULL`이고, 빈 행은
검색에도 근거 표시에도 쓸 수 없다.

**`raw_text`는 손대지 않는다.** 정규화(NFKC·소문자)는 `tokens`를 만들 때만 적용한다.
컬럼 주석이 "읽은 그대로. 결과 화면에 근거로 보여준다. 절대 덮어쓰지 않는다"이다.
**앞뒤 공백도 떼지 않는다** — `strip()`은 빈 원문을 가려내는 판정에만 쓰고 저장할 값에는
쓰지 않는다. 공백 하나라도 고치면 "엔진이 읽은 그대로"가 아니게 된다.

**confidence는 넷째 자리에서 반올림해 보낸다.** `numeric(5,4)`라 다섯째 자리를 보내면
DB가 반올림하고, 그러면 워커 로그의 값과 저장된 값이 갈린다.

**상자는 사변형 그대로다.** 검출기가 주는 것이 축에 나란한 사각형이 아니다. 축에 맞춰
펴면 기울어진 현판·배너에서 실제보다 넓은 영역을 가리키는데, 이 값의 용도가 근거 이미지
위에 상자를 그리는 것(컬럼 주석)이라 그 어긋남이 그대로 보인다. `x`·`y`·`width`·`height`는
사변형에서 유도해 함께 담아, 사변형을 쓰지 않는 소비자가 다시 계산하지 않게 한다.

## 4. 병합하지 않는다

티켓은 "frame 간 동일·유사 문구를 병합하는 경우에도 원본 OCR 관측과 해당 keyframe으로
역추적 가능하도록" 을 요구한다. 병합하고 되돌아갈 길을 두는 방법과 애초에 합치지 않는
방법이 있는데 후자를 골랐다.

| 안 | 내용 | 판단 |
| --- | --- | --- |
| 합치지 않는다 (선택) | 관측을 프레임마다 남기고 `text_key`로 묶을 수 있게만 한다 | 스키마 변경 없음. 역추적이 아니라 **애초에 잃지 않는다** |
| 합치고 그룹을 저장 | 대표 관측 + 그룹 테이블 | `ocr_observation`에 그룹 컬럼이 없고 baseline이 별도 테이블 추가를 금지한다 |
| 합치고 나머지를 버린다 | 대표 하나만 저장 | 나머지의 `bounding_box_json`·`confidence`가 사라진다. "역추적 가능"의 반대다 |

`text_key`는 **색인 토큰의 해시**다. 유사도 임계값을 쓰지 않은 이유는 그 숫자에 실측
근거가 없기 때문이다(FRD §11 — 실측 없이 숫자를 확정하지 않는다). 토큰 일치는
대소문자·전각·구두점·띄어쓰기 차이를 이미 흡수하므로 "동일 문구"는 전부 잡는다. 토큰이
하나도 없는 원문(기호만 읽은 경우)은 정규화한 원문으로 키를 만든다 — 토큰이 비었다고
서로 다른 기호를 한 덩어리로 만들면 안 된다.

샘플에서 관측 44건이 문구 35종이었다. 9건이 프레임 사이의 중복이고, 그 9건은 전부
그대로 남아 있다.

## 5. 임계값은 실측으로 정했다

티켓 제약이 "OCR confidence 품질 기준값은 임의의 고정값으로 정하지 않고 개발 중 실제
데이터로 측정해 확정"이다. FRD §11의 "실행 환경 설정값은 개발하면서 실측으로 정함"과
같은 요구다.

샘플 keyframe 23장에서 나온 관측 44건(정답 27건, 오독 17건)을 ground truth와 대조해
임계값을 훑었다.

| 임계값 | 검증됨 | 그중 정답 | unverified | 그중 오독 | 잃은 정답 |
| --- | --- | --- | --- | --- | --- |
| 0.60 | 34 | 27 | 10 | 10 | 0 |
| 0.65 | 34 | 27 | 10 | 10 | 0 |
| **0.70** | **32** | **27** | **12** | **12** | **0** |
| 0.75 | 32 | 27 | 12 | 12 | 0 |
| 0.80 | 28 | 24 | 16 | 13 | 3 |
| 0.90 | 24 | 21 | 20 | 14 | 6 |

**0.80이 무너지는 자리다.** 거기서 처음으로 맞게 읽은 것이 `unverified`로 떨어지고, 그
대가로 걸러지는 오독은 12건에서 13건으로 하나 늘 뿐이다. 0.70과 0.75는 이 표본에서
결과가 같아 낮은 쪽을 골랐다.

`min_confidence = 0.70`에서 `unverified`로 표시되는 12건은 **전부 오독**이다.

### 미달이라고 버리지 않는다

`unverified`는 **표시**이지 필터가 아니다. 티켓 제약이 "검색 후보에는 사용할 수 있으나
검증된 사실이나 hard conflict 근거로 사용하지 않음"이므로 관측은 전부 저장한다.

그래서 rapidocr의 `Global.text_score`를 0으로 고정하고 **설정 키로 열지 않았다.** 그 값의
기본이 0.5라 그 아래를 말없이 버리는데, toml 한 줄로 관측이 사라지는 길을 남겨 두면
"원문을 절대 덮어쓰지 않는다"가 설정 실수 하나로 깨진다.

**높은 confidence만으로 `verified`가 되지도 않는다.** FRD `docs/frd.md:151`이 "AI의 높은
신뢰도만으로 검증된 사실로 올리지 않는다"이고, 검증 상태를 담는 곳은
`tag_evidence.verification_status`이지 이 단계가 아니다. 이 단계가 하는 일은 임계값과
그때 쓴 값을 결과에 함께 실어 나중에 재현할 수 있게 하는 것까지다.

## 6. 토큰은 질의 쪽과 같은 규칙이어야 한다

`ocr_observation.tokens`는 이 단계의 산출물이다. BE에는 Kiwi가 없고
`docs/architecture/02-container.md:110`이 "워커가 Kiwi로 토큰화한 결과를 별도 컬럼에 넣고
`pdb.whitespace` 토크나이저로 색인한다"로 정했다. 같은 문장이 **색인과 질의가 동일한
Kiwi 설정을 써야 하며 다르면 검색이 0건이 된다**고 못 박는다.

그래서 규칙을 `npick_worker/korean_tokens.py` 하나로 옮기고 양쪽이 그것을 쓴다.

- 질의 쪽 `query_normalization.normalize()`의 `search_tokens`
- 문서 쪽 `ocr` 단계의 `tokens`

`timecode.py`를 `scene_detection`과 `frame_extraction`이 나눠 쓰는 것과 같은 구조다.
설정 파일도 하나여야 하므로 `korean_tokens`가 `query_normalization`의 설정을 읽는다 —
`keep_pos` 하나만 어긋나도 토큰 경계가 달라진다. `ai/AGENTS.md`가 "리졸버와 워커가
공유하는 것은 `versioning.py` 뿐"으로 적어 두었던 경계는 이 요구 때문에 한 칸 넓어졌고,
그 문서도 함께 고쳤다.

**별칭·불용어·정렬은 공유하지 않는다.** 그것들은 질의 지문(`normalized_query`) 전용이고,
색인 측이 하지 않는 변형을 질의에만 걸면 매칭이 어긋난다.

`tests/test_ocr.py`의 `test_tokens_use_the_same_rule_as_the_query_side`가 이 한 문장을
지킨다.

## 7. keyframe을 어떻게 가리키는가

`ocr_observation.keyframe_id`는 TSID이고 그것을 발급하는 쪽은 BE다. `complete` 응답의
`assignedIds`는 scene만 돌려준다(계약 §4.3). 그래서 워커는 **`(sceneIndex, timestampMs)`**
로 말하고 BE가 `keyframe`의 `UNIQUE(scene_id, timestamp_ms)`로 행을 찾는다.

셋 중 하나를 골라야 했다.

| 안 | 판단 |
| --- | --- |
| `(sceneIndex, timestampMs)` (선택) | 스키마도 `assignedIds`도 안 바꾼다. UNIQUE 제약이 이미 이 쌍을 행 하나로 보증한다 |
| `assignedIds.keyframes` 추가 | 계약과 BE 구현을 함께 고쳐야 한다. 얻는 것은 조회 한 번 |
| `storageKey`로 참조 | BE가 `keyframe.storage_key`로 역조회해야 하는데 그 컬럼에 인덱스가 없다 |

`storageKey`는 **근거로 함께 싣는다** — 어느 파일을 읽었는지가 남아야 나중에 조사할 수
있다. 참조 키는 아니다.

## 8. 원본 해상도와 검출 축소

FRD는 "작은 글자를 읽을 때는 축소된 대표 이미지 대신 원본 해상도의 프레임을 사용한다"
(`docs/frd.md:131`)를 요구한다. 이 단계는 **`frame_extraction`이 원본 해상도로 저장한
JPEG을 그대로 읽는다.** 영상에서 프레임을 다시 뽑지 않는다 — 다시 뽑으면 `keyframe` 행이
가리키는 이미지와 읽은 이미지가 달라질 수 있다.

**다만 검출기에는 축소본이 들어간다.** PP-OCR 구조가 그렇다.

1. 검출기가 `det_limit_side_len`에 맞춰 줄인 이미지에서 글자 상자를 찾는다.
2. 찾은 상자를 **원본 좌표로 되돌린다.**
3. 인식기는 **원본 이미지에서 잘라낸 조각**을 읽는다.

그래서 FRD가 요구하는 "원본 해상도로 읽기"는 3번에서 지켜진다. 축소가 영향을 주는 것은
"작은 글자를 **찾아내는가**"뿐이다.

기본값 960은 샘플(800x450)에서 아무 일도 하지 않는다 — 긴 변이 이미 960 미만이라 축소가
일어나지 않는다. **그래서 이 값은 아직 실측으로 확정된 것이 아니다.** 1080p가 들어오면
실제 축소가 걸리므로 그때 다시 잰다(§10).

### 축이 하나 더 있었다 — 전체 이미지 전처리

위 3단계는 rapidocr의 기본 설정에서 **참이 아니었다.** 검출기 리사이즈 앞에 전처리가
하나 더 돌고(`rapidocr/main.py`의 `preprocess_img`), 그것이 `Global.max_side_len=2000`으로
**이미지 자체를 줄인 뒤 그 줄인 이미지에서 인식 조각을 잘라낸다**(`detect_and_crop` →
`crop_text_regions`). 상자 좌표는 원본으로 복원되지만 인식에 들어간 픽셀은 돌아오지 않는다.

| 입력 | 전처리 후 |
| --- | --- |
| 3840x2160 | 1984x1120 |
| 2560x1440 | 1984x1120 |
| 1920x1080 | 그대로 |

`Global.use_preprocess_img = False`로 껐다. FRD의 요구에는 해상도 조건이 없으므로
1440p 이상에서만 깨지는 것도 깨지는 것이다. 이 값은 `det_limit_side_len`과 달리 **설정
키가 아니다** — `text_score`와 같은 이유로, toml 한 줄로 FRD 요구가 무너지는 길을 두지
않는다(§5의 같은 판단).

이 결함은 코드 리뷰에서 나왔고 샘플이 800x450이라 이 문서의 수치에는 영향이 없다. 고친
뒤 다시 돌려 `ocr.json`이 **sha256까지 같았다.**

## 9. 샘플 클립 실측

```bash
uv run --directory ai python -m npick_worker.ocr.report \
    samples/out/KNI_02205-frames --out samples/out/KNI_02205-ocr
```

`KNI_02205`(76초, 800x450, scene 10개, keyframe 23장) 결과다.

| 항목 | 값 |
| --- | --- |
| 관측 | 44건 (글자가 있던 프레임 14장) |
| 문구 | 35종 (프레임 사이 중복 9건, 전부 보존) |
| `unverified` | 12건 (임계값 0.70) |
| legible 재현율 | 27/37 |
| 경과 시간 | **9.8~10.0초** (장당 427~433 ms, 엔진 생성 포함) |
| 재실행 | 두 번 돌려 `ocr.json`이 sha256까지 동일 |

장비는 Intel 22코어 CPU·GPU 미사용, Python 3.12.14, rapidocr 3.9.2 + onnxruntime 1.29.0.
FRD §8.2가 "장비·영상 조건 명시"를 요구하므로 함께 적는다. **한산한 장비 기준이다** —
onnxruntime이 22스레드를 다 쓰므로 다른 작업이 도는 동안 재면 같은 입력이 4~5배까지
느려진다(재측정 시 47~52초). 이 표의 값과 비교할 때는 부하 조건을 맞춰야 한다.

이 경과 시간은 **CLI를 한 번 돌린 값이라 엔진 생성이 한 번 들어 있다.** 워커에서는
엔진이 프로세스에 하나뿐이므로(`rapidocr_backend.shared_engine`) 생성 비용은 기동 때
워밍업에서 한 번만 들고, 잡이 실제로 내는 것은 프레임 처리 시간이다.

읽어 낸 것의 예(현판이 큰 프레임):

```
    8      71833  5   강원도(1.00) | 대표 볼거리관(1.00) | 강원도 대표(0.94) |
                      볼거리관(0.99) | 강원도(1.00)
    9      73833  7   우수상품관(1.00) | 우수상품관(1.00) | Best Probuct pavliet(0.79) |
                      !fee Paidt palie(0.55) | Life Style(0.92) | !IfeStle(0.45) | Beauty Sty(0.98)
```

`!`가 `unverified`다. 오독이 낮은 confidence에 몰리는 것이 §5 표의 근거다.

**76초 클립에서 이 단계의 몫은 약 10초다.** 상류 두 단계가 19.4~19.6초이므로
(`frame-extraction.md` §9) 세 단계 합이 30초 안팎이다. FRD §8.2의 "짧은 영상 분석 30초"는
파이프라인 10단계 전체의 목표이므로 **이 조합으로는 이미 빠듯하다.** 남은 일곱 단계가
붙기 전에 볼 자리는 scene 분할(14.8~15.0초)이 먼저다.

### 결정론

같은 입력·같은 설정으로 두 번 돌려 `ocr.json`이 sha256까지 같았다. 계약 §8의 재처리
중복 방지 중 워커의 몫이 이것이다.

**다만 장비 사이의 동일성은 보장하지 않는다.** onnxruntime의 스레드 수가 기본값(코어 수)
이라 부동소수 누적 순서가 장비마다 달라질 수 있다. 같은 장비에서의 재실행이 같다는 것과
개발 머신과 GPU 파드의 결과가 비트 단위로 같다는 것은 다른 주장이고, 여기서 확인한 것은
앞엣것뿐이다.

## 10. 설정과 버전

정본: `src/npick_worker/config/ocr.v1.toml`. **임계값을 코드에 두지 않는다.**

| 키 | 의미 | 비고 |
| --- | --- | --- |
| `schema` | 설정 스키마 이름 | `version_id` 앞부분 |
| `ocr_version` | 모델 세대 | `PP-OCRv5` |
| `det_lang` / `det_model_type` | 검출 모델 | PP-OCRv5 검출기는 `ch` 하나가 다국어 공용이다 |
| `rec_lang` / `rec_model_type` | 인식 모델 | `korean` — paddleocr `lang="korean"` 과 같은 가중치 |
| `det_limit_type` / `det_limit_side_len` | 검출 입력 크기 | 인식은 원본에서 자른다 (§8) |
| `det_thresh` | 확률 맵 이진화 문턱 | |
| `det_box_thresh` | 상자 평균 점수 하한 | 0.3으로 낮추면 재현율 23→25, 오탐 1→10 |
| `det_unclip_ratio` | 상자를 부풀리는 배율 | 글자가 경계에서 잘리는 것을 막는다 |
| `det_use_dilation` | 확률 맵 팽창 | 켜면 이웃한 별개 문구가 한 상자로 붙었다 |
| `min_confidence` | `unverified` 판정 하한 | **버리는 기준이 아니다** (§5) |

**설정 키가 아닌 것 둘.** `Global.text_score`(§5)와 `Global.use_preprocess_img`(§8)는
코드에 상수로 박아 두었다. 품질을 조절하는 값이 아니라 **깨지면 안 되는 요구**이기
때문이다 — 앞엣것은 "미달 관측도 남긴다", 뒤엣것은 "원본 해상도로 읽는다"이고, 둘 다
toml 한 줄로 조용히 무너질 수 있는 자리에 두면 안 된다.

모델 가중치의 위치는 이 파일이 아니라 환경 변수 `NPICK_AI_OCR_MODEL_DIR`이다. 품질을
바꾸는 값이 아니라 경로이기 때문이다. 컨테이너에서는 반드시 준다 — 기본값이
site-packages 안이라 컨테이너를 다시 만들 때마다 모델을 새로 받는다.

재현성 식별자는 `(config_version, engine, engine_version, tokenizer)` 튜플이다. 앞의 두
단계보다 축이 하나 많다 — `tokens`가 이 단계의 산출물이라 Kiwi 설정이 바뀌면 읽은 글자가
같아도 색인이 달라진다(§6). 고정 테스트 벡터는 계약 문서 §7에 있고
`tests/test_job_contract.py`가 대조한다.

## 11. 언제 다시 볼 것인가

- **1080p 클립으로 전부 다시 잰다.** 이 문서의 수치는 800x450 한 편에서 나왔다. 셋이
  함께 움직인다 — `det_limit_side_len`(960이 그때는 실제 축소가 된다), `min_confidence`
  (해상도가 오르면 오독의 confidence 분포가 바뀐다), 그리고 엔진 선정 자체(§2의 1/37
  차이). `samples/README.md`의 1번 클립이 들어오면 그때다.
- **뉴스 하단 자막바(슈퍼)를 아직 못 봤다.** 이 클립에 없다. 자막바는 배경이 반투명
  박스이고 글자가 얇아 현판·배너와 성질이 다르다. FRD가 첫 번째로 부르는 대상이므로
  (`docs/frd.md:123` "뉴스 자막") 표본이 생기면 우선 확인한다.
- **`jpeg_qscale`과의 관계.** `frame-extraction.md` §10이 "OCR 정확도와 qscale의 관계를
  개발셋으로 측정한 뒤 확정한다"로 남겨 둔 항목이다. 이제 재는 도구가 생겼다 —
  `frame_extraction.report`를 qscale별로 돌리고 이 단계의 재현율을 비교하면 된다.
- **GPU.** 지금은 CPU다. `onnxruntime-gpu`로 바꾸면 같은 API로 돌아가지만, 이 단계가
  76초 클립에서 10초이므로 아직 근거가 없다. 파이프라인 전체가 §8.2 목표에 걸릴 때 본다.
- **장비 사이의 결정론.** §9의 한계다. 필요해지면 onnxruntime 스레드 수를 고정한다
  (`frame_extraction`이 mjpeg 인코더 스레드를 1로 고정한 것과 같은 처방).
