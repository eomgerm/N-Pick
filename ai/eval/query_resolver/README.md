# Query Resolver 모델 비교 하네스 (S15P21A501-102)

검색 질의 해석에 쓸 LLM 을 고르기 위한 측정 도구다. **워커 런타임이 아니다** — 배포
이미지에 들어가지 않는다. 다만 임베딩 하네스(`eval/embedding/`)와 달리
`npick_worker.query_resolver` 를 **import 한다**: 실제 프롬프트(`config/query_resolver.v2.toml`)와
실제 검증(`validator.py`)을 거치지 않으면 측정이 무의미하기 때문이다.

## 선정 결과

**`gpt-4o-mini` 로 확정** (2026-09-15). `gpt-4.1` 과 품질 차이가 검출되지 않는데
(`slot_f1` 차이 +0.0004, 95% CI `[-0.056, +0.057]`) 호출당 크레딧이 2.9 vs 42 다.
결정 근거·대안·재검토 조건은 공유 문서 허브의 **ADR-001 Query Resolver LLM 선정**이 정본이다.
설정 위치는 `NPICK_AI_GMS_MODEL` 이고 값은 `ai/.env.example` 에 있다.

측정 절차와 한계는 아래에서 다룬다. 이 문서는 **그 숫자를 어떻게 다시
만드는가**와 **이 숫자를 어디까지 믿을 수 있는가**만 다룬다.

## 환경

의존성은 프로젝트 `pyproject.toml` 이 아니라 `requirements.txt` 에 있고 **별도 venv** 를 쓴다.
이유는 그 파일 머리말에 있다(요약: `uv.lock` 은 패키지당 버전이 하나라 평가용 mlflow 가
런타임 의존인 protobuf 를 끌어내린다).

```bash
cd ai
uv venv .venv-eval
uv pip install --python .venv-eval -r eval/query_resolver/requirements.txt
```

지표 함수의 단위 테스트는 이 venv 가 없어도 된다 — 외부 의존이 없어 프로젝트 pytest 로 돈다.

```bash
uv run pytest tests/test_resolver_eval_metrics.py
```

## 커밋된 결과를 만든 명령

`results/*.json` 은 아래 그대로 실행해 나온 것이다. 골드셋은 저장소에 있다(아래 §골드셋).

```bash
cd ai
PY=.venv-eval/Scripts/python          # Linux·macOS 는 .venv-eval/bin/python

export NPICK_AI_GMS_BASE_URL=https://gms.ssafy.io/gmsapi/api.openai.com/v1/chat/completions
export NPICK_AI_GMS_API_KEY=<GMS 키>
export MLFLOW_TRACKING_URI=https://j15a501.p.ssafy.io/mlflow
export MLFLOW_TRACKING_USERNAME=npick MLFLOW_TRACKING_PASSWORD=<비밀번호>

# 1) 채점 — 200문항 x 6모델. 정확도 지표는 동시성에 영향받지 않는다.
$PY eval/query_resolver/resolver_bench.py \
    --out eval/query_resolver/results/score.json --concurrency 4

# 2) 지연 — 도메인별로 고르게 뽑은 20문항을 **직렬**로. 동시 요청은 서로를 밀어낸다.
$PY eval/query_resolver/resolver_bench.py \
    --out eval/query_resolver/results/latency.json --latency-only
```

`--no-mlflow` 로 콘솔·JSON 만 낼 수 있고, `--only-domain 재난기상` 으로 한 도메인만,
`--config` 로 다른 프롬프트 toml 을 가리킬 수 있다(v1 회귀 비교).

## 지표

joint intent detection + slot filling 의 표준을 따른다.

| 키 | 정의 |
| --- | --- |
| `intent_accuracy` | intent 일치 비율 |
| `slot_precision` · `slot_recall` · `slot_f1` | 슬롯 단위 micro 평균. 슬롯 = `(배열명, type, value)` 완전 일치 |
| `slot_f1_macro` | 질의별 F1 의 평균. 아래 유의성 검정이 쓰는 값 |
| `frame_accuracy` | intent + 슬롯이 **전부** 맞은 질의 비율. 가장 엄격하다 |
| `schema_valid_rate` | `RESOLVER_SCHEMA_INVALID` 없이 통과한 비율 |
| `hallucination_rate` | validator 가 explicit anchor 를 강등한 질의 비율 = 원문에 없는 값을 `explicit_query` 로 냈다 (FRD F-05) |
| `leak_rate` | `자료화면`·`찾아줘` 같은 상투어가 anchor 로 샌 질의 비율 |
| `span_exact_rate` | validator 가 span 을 고치지 않은 explicit anchor 비율. **모델의 span 정확도는 이 지표로만 잰다** |
| `rate_limited_count` | 429 재시도 횟수 합 |
| `latency_p50_ms` · `latency_p95_ms` · `latency_stdev_ms` | 직렬 실행에서만 유효 |
| `slot_f1_dom_<도메인>` | 도메인별 macro F1 12개 |

**실패한 호출도 채점한다.** intent 오답, 슬롯 recall 0, frame 미달로 센다. 살아남은 응답만
세면 많이 실패한 모델이 좋아 보인다.

**`latency_*_concurrent` 는 참고값이다.** 동시성 4 로 잰 것이라 서버 큐잉이 섞여 있다.
순위를 말할 때는 `--latency-only` 로 나온 직렬 값을 쓴다.

그리고 **`latency_stdev_ms` 를 반드시 같이 읽는다.** 20 표본에서 p95 는 nearest-rank 로
`int(20*0.95)=19` 번째, 즉 **정렬 마지막 원소 = 최댓값**이다(6개 모델 전부 확인). 단일
outlier 가 그대로 지표가 되므로 표준편차가 모델 간 차이보다 크면 그 수치로 순위를 말할 수
없다. 임베딩 하네스가 표본을 60→400 으로 늘렸던 것과 같은 함정이고, 여기서는 크레딧 때문에
20 으로 두었다 — p50 을 우선 보고 p95 는 상한 참고로만 쓴다.

**`hallucination_rate` 는 골드셋이 아니라 validator 가 판정한다.** 처음에는 "gold 에 없는
explicit anchor" 로 셌는데 두 가지로 틀렸다 — `slot_precision` 과 같은 것을 재게 되고,
어휘가 열린 `scene_type` 축에서는 규칙을 지킨 출력까지 창작으로 찍는다(그 방식으로
gpt-4.1 이 0.515 였다). 원문에 값이 문자 그대로 있는지는 `validator.py` 가 이미 판정해
`demoted_to_inferred` 로 남기므로 그것을 쓴다.

**문자 단위 F1(KLUE-NER 방식)은 두지 않았다.** 처음에는 넣었는데, `resolve_query` 가
돌려주는 것이 validator 가 span 을 이미 고친 뒤의 결과라 그 값으로는 모델이 아니라
validator 를 재게 된다(값이 맞은 anchor 에서 0.995로 붙박이였다). 모델의 span 정확도는
`span_exact_rate` 가 재고, 경계가 갈리는 복합 지명은 골드셋의 `optional` 이 값 수준에서
처리한다.

`results/score.json` 은 질의별 행에 **해석 결과 전체**를 담는다(파일의 87%가 이것이고,
그래서 1.9MB 다). 점수만 남기면 "이 모델이 왜 낮았나"를 볼 때마다 다시 호출해야 하고,
그건 크레딧이 드는 일이다. 임베딩 하네스의 결과 파일들과 같은 자릿수라 그대로 둔다.

## 유의성

모든 모델이 같은 질의를 푼다. 질의별로 짝지어 차이를 구하면 질의 난이도가 상쇄된다.
`compare()` 가 부트스트랩 10,000회로 95% 신뢰구간을 낸다. **구간이 0 을 포함하면 "차이 없음"이
아니라 "이 표본으로는 검출 못 함"** 이다.

200문항(필수 슬롯 208개, 선택 151개) 규모에서 검출되는 차이는 대략 `slot_f1` 4%p,
`frame_accuracy` 7%p 수준이다. 그보다 작은 차이를 순위로 말하지 않는다.

## 골드셋

`gold.json` — 12 도메인 200문항. **저장소에 커밋한다.**

임베딩 하네스의 골드셋은 AI-Hub/KBS 자막 원문이 들어가서 `FRD §6.4` · `S15P21A501-134` 의
권리 게이트에 걸렸지만, 이 파일에는 그런 것이 없다. **질의 200개가 전부 합성이고 인명도
가공이다.** 그리고 라벨이 곧 자산이라 커밋하지 않으면 재생성할 방법이 없다 — 무작위 표본과
달리 seed 로 다시 뽑히지 않는다. `.gitignore` 의 deny-by-default 는 유지하고 이 이름 하나만 연다.

| 도메인 | 문항 | | 도메인 | 문항 |
| --- | --- | --- | --- | --- |
| 교통인프라 | 29 | | 국제외교 | 15 |
| 정치행정 | 19 | | 스포츠 | 15 |
| 사건사고 | 16 | | 문화연예 | 15 |
| 경제산업 | 16 | | 보건의료 | 15 |
| 재난기상 | 15 | | 교육 | 15 |
| 노동사회 | 15 | | 환경에너지 | 15 |

교통인프라가 두 배인 이유는 기존 대표 질의 20개(id 1~20)를 **그대로 유지**했기 때문이다.
그 20개는 `fixtures/representative_queries.v2.json` 과 1:1 대응이라 이전 육안 검토 결과와
직접 비교할 수 있다.

### 라벨 정책

- `slots` 는 반드시 나와야 하는 anchor, `optional` 은 규칙이 두 갈래를 모두 허용하는 anchor다.
- **어휘가 닫히지 않은 축은 `optional` 로 둔다.** `schema.py` 가 `classifications.value` 를
  열어두었기 때문에 `scene_type` 은 필수 슬롯이 하나도 없다(선택 52개). 규칙을 지킨 모델이
  라벨 취향 때문에 손해보지 않게 하려는 것이고, 대신 **`scene_type` 을 아예 안 내는 모델을
  이 지표로는 잡지 못한다.**
- 경계가 갈리는 복합 지명(`서울시청` / `서울시청 앞`, `학교` / `학교 운동장`)은 짧은 쪽을
  필수, 긴 쪽을 선택으로 둔다.
- 상대 날짜(`최근`·`지난달`)는 **날짜 슬롯을 만들지 않는 것**이 정답이다. 값을 알 수 없어
  라벨을 달 수 없고, 프롬프트 규칙 13("확실하지 않으면 무리하게 만들지 않는다")과도 맞는다.
- `forbidden_common` 은 200문항 공통 상투어이고 문항별 `forbidden` 이 더해진다.

### 라벨 자가검증

200개를 사람이 전부 검토할 수 없다. `load_gold` 가 기계로 잡을 수 있는 오류를 **측정 전에**
거부한다.

1. `span_text` 가 원문에 없으면 거부 — 라벨 오타가 span 지표를 조용히 망가뜨리는 것을 막는다.
2. 날짜 구간이 프롬프트 규칙과 어긋나면 거부 — `2022년` 을 9월로 좁힌 라벨이 들어가면
   규칙을 지킨 모델이 오답 처리된다.
3. `id` 중복 거부.

그래도 **사람이 도메인당 3~5개는 봐야 한다.** 규칙 해석 자체가 틀리면 위 셋 다 통과한다.
각 문항의 `rationale` 이 그 검토용이다.

## 이 숫자의 한계

1. **라벨을 LLM 이 달았다.** 근거는 대부분 프롬프트에 적힌 규칙의 기계적 적용이지만,
   규칙 해석이 틀리면 그 오독이 그대로 정답이 된다.
2. **질의가 합성이다.** 편집기자가 실제로 치는 문장 분포와 다를 수 있다. 실사용 로그가
   쌓이면 그걸로 골드셋을 갈아야 한다.
3. **`scene_type` 은 변별력이 약하다** (위 라벨 정책 참조).
4. **필수 슬롯이 없는 문항이 45개다.** 그 문항들에서 recall 은 항상 1.0 이고 precision 만
   신호가 된다. 상투어 누수·hallucination 함정이라 의도한 것이지만, `slot_recall` 평균을
   읽을 때 이 점을 감안해야 한다.
5. **도메인 분포가 고르지 않다** (교통인프라 29 vs 나머지 15). 도메인 간 점수를 직접
   비교하지 말고 같은 도메인 안에서 모델끼리 비교한다.
6. **크레딧이 든다.** 200문항 x 6모델 1회가 약 19,500 크레딧이다(gpt-4.1 이 호출당 41.5로
   가장 비싸다). 프롬프트를 고칠 때마다 전체를 다시 돌리지 말고 `--models` 로 좁힌다.

## 후보에서 제외된 모델

GMS 의 OpenAI 호환 경로(`gmsapi/api.openai.com/...`)로 붙는 모델만 넣었다. 2026-09-15 실측:

- **gemini 계열 · claude 계열** — 같은 경로에서 404(`does not exist or you do not have access`).
  못 쓰는 것이 아니라 **다른 upstream 이 필요하다**: Anthropic 은
  `gmsapi/api.anthropic.com/v1/messages` + `x-api-key`, Gemini 는
  `gmsapi/generativelanguage.googleapis.com/.../generateContent` + `x-goog-api-key` 로 200 이 온다.
  쓰려면 `GmsResolver` 말고 새 어댑터가 필요하다.
- **gpt-5-nano · gpt-5-mini · o3-mini** — `temperature=0.0` 을 400 으로 거부한다
  (`Only the default (1) value is supported`). 재현성(FRD §7.2)을 포기해야 붙는다.
  같은 계열이어도 `gpt-5.4-nano`·`gpt-5.4-mini` 는 받는다.
