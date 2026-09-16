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

## 2. 방법 비교와 제한

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

KPF를 초기 경로로 택한 근거는 이 실행 비용과 세부 유형·원문 offset이다. 사람 정답 기반
precision/recall 우위는 입증하지 않았다. 최종 품질 선정에는 뉴스 도메인 gold 라벨이 필요하다.

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
쓸 만한 칼이 되지는 않는다. OCR 유래 후보 503개 중 165개가 두 글자 이하 파편인데
(`R`←`Rinnai`, `여자수`←`여자수는기리`) 그중 43개가 0.9 이상이고, 반대로 실제 단체인
`KBO`가 0.584, `KOVO`가 0.299다. **이 점수는 "이 글자열이 어떤 유형인가"에 대한 확신이지
"그 글자가 화면에 실제로 있었나"가 아니므로**, 어느 값에서 자르든 파편은 남고 진짜가
잘린다. 지금 잘라야 할 신호는 NER 점수가 아니라 OCR 읽기 품질이며, 그 판정은 상류에
`ocr_observation.confidence`와 `unverified`로 이미 있다(계약 §4.3.2 — BE가 그 값으로
`tag_evidence.verification_status`를 정한다). 이 단계는 soft 신호 전용이므로 모호한 후보를
지우는 쪽보다 근거를 달아 넘기는 쪽을 택하고, 사람 정답 기반 임계값은 §2가 말한 gold
라벨 작업과 함께 정한다.

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

반입된 실행은 RTX 4070 Laptop GPU, transformers 5.17.0, torch 2.13.0+cu130에서
10개 영상·83장면, 706개 후보를 생성했다. 로딩 13.081초, 추론 합계 23.856초,
peak allocated 465,347,072 bytes, peak reserved 515,899,392 bytes다. 수치의 정본은
[반입 결과](sample-results/entity-extraction-99-20260916.json)이고 여기 적은 값은 그 인용이다.

그 파일의 `stageVersion`은 하네스가 계산한 네 축(`local_ner.identity`) 기준이다. 잡
어댑터는 OCR 병합 설정을 직접 다시 계산하므로 `mergeVersion`을 더한 다섯 축을 쓴다
(계약 §7) — 하네스는 corpus에서 텍스트를 바로 읽어 그 축이 없다. 두 값은 그래서 다르며,
파이프라인이 보고하는 것은 다섯 축 쪽이다.

실제 입력·raw span·출력·대사 snapshot은 로컬 결과 디렉터리에 보존하며 반입 JSON은
해시·버전·통계와 검토용 발췌를 싣는다. 대사는 제공 샘플 자막이며 새 ASR 실행이 아니다.
텍스트 corpus에는 VLM이 없어 VLM 합류는 별도 계약 테스트로 검증한다. 영상 전체를 동일
revision으로 재처리한 E2E나 BE 저장 검증이 아니며, MR 승인·머지도 별도 완료 조건이다.
