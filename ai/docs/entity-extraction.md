# Entity extraction — S15P21A501-99

## 1. 범위와 선택

FRD v3.2 F-03·F-04·F-06과 `job-api.md` §4.3.6을 따른다. 순수 함수 `extract`가
장면 입력과 NER span을 받아 후보를 만들며 `build_inputs`가 OCR 병합·최종 대사 매핑·VLM
산출물을 입력으로 옮긴다. `LocalNer`는 별도 I/O 경계에서 자체 GPU 추론만 수행한다.
`jobs/entity_extraction.py`가 이 순수 모듈을 잡 레이어에 잇는다(92·98번과 같은 범위).
BE의 tag·tagging·tag_evidence 저장은 이번 범위가 아니다.

초기 실행 방법은 **KPF/KPF-bert-ner 전용 NER**다. 텍스트마다 원문 offset을 반환해
생성 모델의 source ID 추측 없이 근거를 고정할 수 있다. [공식 모델 카드](https://huggingface.co/KPF/KPF-bert-ner)는
신문 말뭉치 기반 150개 클래스 분류를 설명한다. 이 설명은 우리 영상의 품질 보장이 아니다.
고정 revision은 `efff871f686098933bf76d699c437c3f53abc19e`다.

캐시 모델의 `id2label`은 `LABEL_n`이어서 학습 BIO 표가 필요하다. 기존 비교 산출물
`entity-compare-20260916/external-labels.py`의 **라벨 데이터만**
`config/entity_kpf_labels.v1.json`으로 보존했다. 원 제공자는 모델 카드가 연결하는
[KPF-bigkinds](https://github.com/KPF-bigkinds)다. 300개 BIO 출력과 classifier 크기를
로드 시 대조하고 표 전체를 설정 해시에 포함한다. 실행 중 원격 코드나 라벨 표를 받지 않는다.

## 2. 방법 비교와 품질 평가

### 2.1 실행 비용 비교 (기존 기록)

비교 하네스 `ai/tools/entity_sample_compare.py`와 그 실행 기록
`samples/out/entity-compare-20260916/`를 확인했다. 하네스는 이 브랜치에서 저장소에
넣었고(타입 주석과 줄바꿈만 손봤고 동작은 그대로다), 실행 산출물은 샘플 권리 게이트
(`ai/.gitignore`) 안에 남아 있다 — 아래 표는 그 기록의 인용이며 커밋된 코드를 다시 돌린
결과가 아니다. 동일 스포츠 영상 10개의 장면 OCR·제공 대사 corpus를 사용했다.

| 방법 | 기록된 추론 시간 | 후보 수 | 확인된 제한 |
| --- | ---: | ---: | --- |
| Kiwi 고유명사 | 1.17초 CPU | 243 | 유형을 내지 못한다 |
| Qwen3-1.7B | 311.56초 GPU | 146 | JSON 실패 3장면, 주장한 source ID 일치율 0.89 |
| KPF NER | 9.08초 GPU | 1,027 | 외부 BIO 표 필요, 기존 유형 매핑에 오류 |
| KoELECTRA small NER | 9.13초 GPU | 1,406 | 대분류 AF가 시설보다 넓음 |

이 숫자는 **기존 비교 기록**이며 현재 구현을 새로 실행한 결과와 구분한다. 기존 스크립트는
NER 입력에 NFKC를 적용하고 두 글자 미만을 버리며 AF 접두를 전부 facility로 바꾼다.
따라서 그 후보 수·groundedRatio를 현재 계약 준수율이나 정확도로 해석하지 않는다.
LLM의 `groundedRatio=1`도 출력 source ID의 정확성을 보장하지 않았다.
[KoELECTRA 모델 카드](https://huggingface.co/Leo97/KoELECTRA-small-v3-modu-ner)의 AF 정의에는
건물 외에도 악기·무기·운송수단·제품명이 포함되어 있다.

### 2.2 사람 정답 기반 품질 평가

위 표는 실행 비용이지 품질이 아니었다. 그래서 같은 corpus(83장면, `corpusSha256`
`15cdbae8…`)에 **사람이 확인한 정답표**를 만들고 두 방법을 같은 정답으로 채점했다.
도구는 `ai/tools/entity_gold_review.py`(단계 검수)와 `ai/tools/entity_gold_compare.py`
(다른 방법을 같은 정답표에 대고 채점)이고, 지표 정본은
[반입 결과](sample-results/entity-gold-99-20260917.json)다. 채운 시트는 자막 원문을
담고 있어 `ai/samples/out/entity-gold-99/`에 남는다.

후보 하나에 축을 **둘** 매긴다. `verdict`는 *이 단계가 받은 텍스트를 기준으로* 옳았는지고,
`screenTruth`는 *그 텍스트가 화면·실제와 같은지*다. 섞으면 OCR 오독이 추출 오류로 계산되어
어느 단계를 고쳐야 하는지가 숫자에서 사라진다. 실제로 이 corpus에서 대사 자막이 선수 이름을
`나스야` 대신 `아나스`로 적은 장면이 셋 있는데, 받은 문장 기준으로는 그 인물이 맞으므로
`verdict=correct`·`screenTruth=misread`로 갈린다 — 정밀도는 그대로고 실물 대조 지표만 내려간다.

| | **KPF NER (채택)** | **Qwen3-1.7B (자체 GPU LLM)** |
| --- | ---: | ---: |
| 답을 낸 장면 | **83 / 83** | 52 / 83 (JSON 파싱 실패 3, 빈 결과 28) |
| 후보 수 | 706 | 146 |
| 추론 시간(§2.1 하네스, 같은 로컬 GPU) | **9.08초** | 311.56초 |
| precision strict | 0.4093 | 0.4726 |
| precision **attributable** | **0.6800** (분모 425) | 0.5565 (분모 124) |
| recall (83장면 전체) | **0.7896** (289/366) | 0.1885 (69/366) |
| recall (LLM이 답한 52장면) | **0.7852** (212/270) | 0.2556 (69/270) |
| precision attributable (같은 52장면) | **0.6709** | 0.5565 |

`strict`만 LLM이 높은데 이것은 품질이 아니라 양이다. LLM은 깨진 OCR 조각을 거의 집지
않아 `unreadable_source`가 22건인 반면 KPF는 281건이다. **그 왜곡을 뺀 값이
`attributable`이고(입력이 애초에 글자가 아닌 후보를 분모에서 제외한다), 거기서는 KPF가
앞선다.** 재현율은 답을 낸 장면만 골라 세도 0.785 대 0.256이다.

후보 수와 시간은 §2.1 하네스 기록이라 현재 구현의 실행 수치(706개, §6)와 다르다 —
판정·정밀도·재현율은 현재 구현의 706개 후보를 사람이 검수한 값이다.

KPF 오답의 성격은 이렇다. 전체 706건 중 `correct` 289, `unreadable_source` 281,
`fragment` 89, `type_wrong` 22, `not_entity` 20, `overspan` 5.

| 입력 출처 | 후보 | correct | OCR 깨짐 |
| --- | ---: | ---: | ---: |
| 대사 | 203 | 184 | 0 |
| OCR | 492 | 96 | 281 |
| 둘 다 | 11 | 9 | 0 |

**오답의 절반 이상이 추출기가 아니라 상류 OCR 품질이다.** 대사에서 온 후보는
`unreadable_source`가 하나도 없다. 입력이 멀쩡한데 틀린 유형·경계 오류는 27건(type_wrong
22 + overspan 5)이고 종류가 몰려 있다 — 구단 영문명을 event로(`KBO` `TWINS` `TIGERS`
`DOOSAN` `Lions`), 구단명을 person으로
(`삼성감독`→`삼성`), 그리고 경계 초과(`LG감독`, `신한은행 SOLKBO리그`). LLM도 같은 경계
오류를 냈다(`LG감독` `삼성감독` `롯데감독`).

### 2.3 비교하지 못한 범위

- **Kiwi 고유명사**는 유형을 내지 않아 같은 정답표로 채점할 수 없다. 시간·후보 수만 비교된다.
- **KoELECTRA small NER**은 같은 corpus 실행 기록은 있으나 이 정답표로 채점하지 않았다.
- LLM 비교는 **저장된 출력 재채점**이다. 프롬프트·디코딩 설정을 바꾸면 결과가 달라질 수 있고,
  더 큰 모델은 시도하지 않았다. 따라서 "LLM 계열 전체보다 낫다"가 아니라 "같은 GPU에서
  이 설정의 Qwen3-1.7B보다 낫다"까지가 이 표가 말하는 범위다.
- corpus는 스포츠 뉴스 10편이다. 다른 장르·다른 OCR 품질에서 같은 수치가 나온다고 주장하지 않는다.
- 706건 중 10건은 키프레임 대조를 하지 않아 실물 대조 지표(`endToEnd`)의 분모에서 빠졌다.
  그 10건이 전부 뒤집혀도 폭은 0.363~0.377이다.

### 2.4 선정 결론

KPF를 유지한다. 근거는 ① 전 장면 응답(83/83 대 52/83), ② 34배 빠른 추론, ③ 귀속 가능
정밀도 0.680 대 0.557, ④ 재현율 0.790 대 0.189, ⑤ 원문 offset을 돌려줘 생성 모델의
source ID 추측 없이 근거를 고정할 수 있다는 점(§1). 한계는 ⑥ 구단 영문명·구단+직책
경계 오류가 남아 있고, ⑦ 태그 품질의 상한을 정하는 것은 이 단계가 아니라 상류 OCR이라는 점이다.

## 3. 유형·날짜·출처

- NER는 person·organization·location·facility·keyword·event만 생성한다. 매핑은 TOML에
  있다. 건물·도로만 시설로, PS_NAME·PS_CHARACTER만 인물로 보낸다. 반려동물·제품·문서·
  무기·운송수단 등을 시설/인물로 억지 변환하지 않는다. 알려진 미지원 라벨은 명시적으로 제외한다.
- season·weather·scene_type은 VLM 입력 후보만 보존한다. shot_type은 태그가 아니다.
- 날짜 언급은 방송일/촬영일의 역할을 확인하지 못한다. v1은 두 날짜 태그를 생성하지 않으며
  원문을 OCR·대사에 남긴다. 실제 달력 날짜 검사와 사람 검증을 우회하는 날짜 후보를 만들지 않는다.
- NER 생성 출처는 rule, VLM 후보는 vlm이다. 입력 관측의 출처로 위장하지 않는다.
  BE는 기본 unverified를 부여한다. 제외 여부·hard filter·검증 상태 필드는 없다.

## 4. 근거와 원표기

OCR은 병합 대표 관측의 원문에서 span을 잘라 표시값으로 쓴다. 정규화된 모델 word를
표시값으로 사용하지 않는다. 원본 OCR 관측 배열의 인덱스·키프레임 시각·storageKey가
남는다. 병합의 다른 관측은 상류 textGroups를 통해 확인한다.

대사는 선택된 scene link만 읽고 원본 segments snapshot 키·segmentId·원본 구간·sourceDetail을
보존한다. DB 참조는 scene으로 접되 상세 구간을 잃지 않는다. VLM의 해석된 keyframe/OCR/대사
참조도 그대로 옮긴다. clip 전체 태그는 만들지 않는다.

중복 제거는 FRD의 NFKC → 공백 및 ZWSP/BOM/soft hyphen 제거 → NFKC를 사용한다.
대소문자·ZWJ·ZWNJ는 유지한다. 같은 유형·비교값·생성 출처의 근거를 합치고 최초 표시값과
최대 confidence를 보존한다. 다른 source는 독립 evidence 후보라 BE가 같은 tagging에 연결한다.

## 5. 설정·실패·버전

`config/entity_extraction.v1.toml`에 revision·유형 매핑·confidence 임계값·stride·aggregation을
둔다. stride 64는 긴 입력 누락을 피하는 초기 overlap 값이며 품질 최적값이라고 주장하지 않는다.

`minimum_confidence=0.0`은 **실측 결과 그대로 둔 값**이다. 첫 실제 실행의 후보 706개를
NER 점수대로 갈라 보면 이렇다.

| 점수대 | 후보 | OCR 유래 | 대사 유래 |
| --- | ---: | ---: | ---: |
| `[0.0, 0.5)` | 69 | 69 | 0 |
| `[0.5, 0.7)` | 91 | 89 | 2 |
| `[0.7, 0.9)` | 117 | 85 | 32 |
| `[0.9, 1.0]` | 429 | 260 | 169 |

점수가 낮은 쪽은 전부 화면 글자에서 왔고 대사에서 온 것은 하나도 없다. 그렇다고 임계값이
쓸 만한 칼이 되지는 않는다. §2.2의 사람 정답표로 각 점수대를 판정별로 갈라 보면 이렇다.

| 점수대 | 후보 | correct | OCR 깨짐 | 나머지 오답 |
| --- | ---: | ---: | ---: | ---: |
| `[0.0, 0.5)` | 69 | 3 | 45 | 21 |
| `[0.5, 0.7)` | 91 | 6 | 62 | 23 |
| `[0.7, 0.9)` | 117 | 32 | 54 | 31 |
| `[0.9, 1.0]` | 429 | 248 | 120 | 61 |

**최상위 구간에 정답 248개와 깨진 글자 120개가 같이 있다.** 각 값에서 잘랐을 때 지표는
이렇게 움직인다.

| 임계값 | 남는 후보 | precision strict | precision attributable | recall |
| ---: | ---: | ---: | ---: | ---: |
| **0.0 (현행)** | 706 | 0.4093 | 0.6800 | **0.7896** |
| 0.5 | 637 | 0.4490 | 0.7132 | 0.7814 |
| 0.7 | 546 | 0.5128 | 0.7527 | 0.7650 |
| 0.9 | 429 | 0.5781 | 0.8026 | 0.6776 |

0.9까지 올리면 정밀도는 오르지만 **깨진 글자 120개는 그대로 남고 정답 41개가 잘려
재현율이 0.790 → 0.678로 떨어진다.** 이 단계는 soft 신호 전용이고 BE가 근거로 검증
상태를 매기므로(계약 §4.3.2), 정답을 버려 가며 정밀도를 사는 교환은 이 단계에서 할 일이
아니다. 원인도 여기 있지 않다 — `R`←`Rinnai`, `여자수`←`여자수는기리` 같은 두 글자 이하
파편이 0.9 이상에 43개 있는 반면 실제 단체인 `KBO`가 0.584, `KOVO`가 0.299다.
**이 점수는 "이 글자열이 어떤 유형인가"에 대한 확신이지 "그 글자가 화면에 실제로 있었나"가
아니므로**, 어느 값에서 자르든 파편은 남고 진짜가 잘린다. 잘라야 할 신호는 NER 점수가
아니라 OCR 읽기 품질이며, 그 판정은 상류에 `ocr_observation.confidence`와 `unverified`로
이미 있다. **따라서 사람 정답 평가를 마친 뒤에도 `minimum_confidence=0.0`을 유지한다.**
설정·`configVersion`·테스트를 바꾸지 않았다.

TOML과 BIO 표 전체가 `configVersion`에 포함된다. 모델 로드 이후에만 재현 식별자를 노출한다.

알 수 없는 라벨, 범위 밖 span, NaN/무한대 점수, 다른 장면 근거, 누락된 응답은 실패다.
실패 전 만들어 둔 일부 후보를 돌려주지 않는다. 잡 어댑터가 이 실패를
`ENTITY_SCHEMA_INVALID`(영구), 가중치 미배치를 `MODEL_UNAVAILABLE`(일시), 상류 오류를
`VALIDATION_ERROR`로 신고한다(§9.2). 단계가 비치명이므로 run은 계속되고 오류는
`stage_states_json`에 남는다. BIO 표는 개수만이 아니라 표 안 여섯 자리의 라벨 이름까지
로드 시 대조한다 — 표를 다시 만들 때 순서가 바뀌면 `num_labels`는 그대로인 채 모든 유형이
조용히 어긋난다. 모델 파일은 사전 배치하고 실행은 `local_files_only=True`로 제한한다. 입력 텍스트의 외부 전송 경로는 없다.

## 6. 실제 샘플 재현과 완료 경계

저장소 루트에서:

```powershell
ai/.venv/Scripts/python.exe -m npick_worker.entity_extraction.report --corpus ai/samples/out/entity-compare-20260916/corpus.json --out ai/samples/out/entity-extraction-99-20260916 --archive ai/docs/sample-results/entity-extraction-99-20260916.json
ai/.venv/Scripts/python.exe -m pytest ai/tests/test_entity_extraction.py -q -p no:cacheprovider
```

`--archive`가 반입 파일을 만든다. 그 단계는 각 후보 근거가 저장된 같은 장면 입력을 실제로
가리키고 비교값이 그 원문에 존재하는지 확인한 뒤 파일 해시와 발췌를 기록한다. **발췌에
원문 문장을 싣지 않는다** — 근거가 붙었다는 사실은 유형·비교값·근거 참조로 보이고,
AI-Hub/KBS 자막 원문은 `ai/.gitignore`의 권리 게이트가 `samples/`·`eval/` 밖으로 내보내지
않는 것이다. 원문이 필요한 검토는 로컬 결과 디렉터리에서 한다.

같은 corpus를 두 장비에서 돌렸다. 둘 다 10개 영상·83장면에서 **706개 후보**를 만들었고
검토용 발췌 48건도 같다.

| | 로컬 | **SSAFY GPU** |
| --- | --- | --- |
| 장치 | RTX 4070 Laptop GPU | **NVIDIA L40S** (46,068 MiB, 드라이버 570.211.01 / CUDA 12.8) |
| 엔진 | transformers 5.17.0 + torch 2.13.0+cu130 | transformers 5.17.0 + **torch 2.11.0+cu128** |
| 모델 로딩 | 13.081초 | **8.028초** |
| 추론 합계 | 23.856초 | **5.487초** |
| peak allocated | 465,347,072 bytes | 466,395,648 bytes |
| peak reserved | 515,899,392 bytes | 517,996,544 bytes |
| `stageVersion` | `…/v1:7c6b99ce` | `…/v1:0489c4de` |
| 반입 결과 | [4070](sample-results/entity-extraction-99-20260916.json) | [SSAFY](sample-results/entity-extraction-99-ssafy-20260916.json) |

메모리는 사실상 같고(차이 1 MB 미만) 시간만 4.3배 빠르다. 후보가 같으므로 장비 교체가
출력을 바꾸지 않는다는 것도 확인된다. `stageVersion`이 다른 것은 `engineVersion`에 torch
빌드가 들어가기 때문이며, 같은 입력·같은 `configVersion`·같은 `corpusSha256`에서 나온 값이다.
수치의 정본은 각 반입 결과이고 여기 적은 값은 그 인용이다.

그 파일의 `stageVersion`은 하네스가 계산한 네 축(`local_ner.identity`) 기준이다. 잡
어댑터는 OCR 병합 설정을 직접 다시 계산하므로 `mergeVersion`을 더한 다섯 축을 쓴다
(계약 §7) — 하네스는 corpus에서 텍스트를 바로 읽어 그 축이 없다. 두 값은 그래서 다르며,
파이프라인이 보고하는 것은 다섯 축 쪽이다.

품질 평가를 다시 매기려면:

```powershell
ai/.venv/Scripts/python.exe ai/tools/entity_gold_review.py score --sheet ai/samples/out/entity-gold-99/sheet.json --out ai/samples/out/entity-gold-99/metrics.json
ai/.venv/Scripts/python.exe ai/tools/entity_gold_compare.py score --sheet ai/samples/out/entity-gold-99/compare-llm.json --gold ai/samples/out/entity-gold-99/sheet.json --out ai/samples/out/entity-gold-99/compare-llm-metrics.json
```

`build` 하위 명령이 실행 기록에서 빈 시트를 만들고 `score`가 채운 시트를 읽는다. **채운
시트는 장면별 OCR·대사 원문을 그대로 담으므로 `ai/samples/` 밖으로 나가지 않는다** —
반입되는 것은 원문이 없는 [지표 파일](sample-results/entity-gold-99-20260917.json)뿐이다.

실제 입력·raw span·출력·대사 snapshot은 로컬 결과 디렉터리에 보존하며 반입 JSON은
해시·버전·통계와 검토용 발췌를 싣는다. 대사는 제공 샘플 자막이며 새 ASR 실행이 아니다.
텍스트 corpus에는 VLM이 없어 VLM 합류는 별도 계약 테스트로 검증한다. 두 반입 결과의
`limitations`에 남아 있는 `No human entity gold labels; no accuracy claim`은 실행 시점의
기록이며 §2.2가 이를 대체한다. 영상 전체를 동일 revision으로 재처리한 E2E나 BE 저장
검증이 아니며, MR 승인·머지도 별도 완료 조건이다.
