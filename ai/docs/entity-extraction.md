# Entity extraction — S15P21A501-99

## 1. 범위와 선택

FRD v3.2 F-03·F-04·F-06과 `job-api.md` §4.3.6을 따른다. 순수 함수 `extract`가
장면 입력과 NER span을 받아 후보를 만들며 `build_inputs`가 OCR 병합·최종 대사 매핑·VLM
산출물을 입력으로 옮긴다. `LocalNer`는 별도 I/O 경계에서 자체 GPU 추론만 수행한다.
잡 배선과 BE 저장은 이번 모듈 구현 범위가 아니다.

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

기존 `ai/tools/entity_sample_compare.py`와 `samples/out/entity-compare-20260916/`의
기록을 확인했다. 동일 스포츠 영상 10개의 장면 OCR·제공 대사 corpus를 사용했다.

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
둔다. `minimum_confidence=0.0`은 품질 임계값을 실측으로 정하기 전 필터링하지 않는 설정이다.
stride 64는 긴 입력 누락을 피하는 초기 overlap 값이며 품질 최적값이라고 주장하지 않는다.
TOML과 BIO 표 전체가 `configVersion`에 포함된다. 모델 로드 이후에만 재현 식별자를 노출한다.

알 수 없는 라벨, 범위 밖 span, NaN/무한대 점수, 다른 장면 근거, 누락된 응답은 실패다.
실패 전 만들어 둔 일부 후보를 돌려주지 않는다. 비치명 단계의 run 계속·errorCode 기록은
향후 잡 어댑터에서 §9.2에 맞춰 연결해야 한다. 모델 파일은 사전 배치하고 실행은
`local_files_only=True`로 제한한다. 입력 텍스트의 외부 전송 경로는 없다.

## 6. 실제 샘플 재현과 완료 경계

저장소 루트에서:

```powershell
ai/.venv/Scripts/python.exe -m npick_worker.entity_extraction.report --corpus ai/samples/out/entity-compare-20260916/corpus.json --out ai/samples/out/entity-extraction-99-20260916
ai/.venv/Scripts/python.exe -m pytest ai/tests/test_entity_extraction.py -q -p no:cacheprovider
```

반입 파일은 `report.archive(output_dir, destination)`로 다시 만들 수 있다. 이 함수는
각 후보 근거가 저장된 같은 장면 입력을 실제로 가리키고 비교값이 그 원문에 존재하는지
확인한 뒤 파일 해시와 발췌를 기록한다.

첫 실제 실행은 RTX 4070 Laptop GPU, transformers 5.17.0, torch 2.13.0+cu130에서
10개 영상·83장면, 706개 후보를 생성했다. 로딩 5.099초, 추출 16.857초,
peak allocated 465,347,072 bytes, peak reserved 515,899,392 bytes였다.
재실행의 최종 버전·시간은 [반입 결과](sample-results/entity-extraction-99-20260916.json)를 따른다.

실제 입력·raw span·출력·대사 snapshot은 로컬 결과 디렉터리에 보존하며 반입 JSON은
해시·버전·통계와 검토용 발췌를 싣는다. 대사는 제공 샘플 자막이며 새 ASR 실행이 아니다.
텍스트 corpus에는 VLM이 없어 VLM 합류는 별도 계약 테스트로 검증한다. 영상 전체를 동일
revision으로 재처리한 E2E나 BE 저장 검증이 아니며, MR 승인·머지도 별도 완료 조건이다.
