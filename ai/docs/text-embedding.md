# 텍스트 임베딩 — 입력과 설정

`stages.py` 9단계 `text_embedding` (비치명). 구현은 `src/npick_worker/text_embedding/`.

장면의 **캡션과 대사를 합쳐** dense 벡터 하나를 만든다. 그 벡터가 `scene.embedding
vector(1024)` 한 칸에 들어가 pgvector 로 색인되고 BM25 순위와 RRF 로 결합된다.

모델 선정 실측은 이 문서가 아니라 `S15P21A501-175` 다 — 후보 5종·골드셋 3종·질의 4,300건의
비교표와 통계 판정은 Notion "임베딩 모델 실측 비교·선정" 이 정본이고 하네스는 그 티켓의
`ai/eval/embedding/` 이다. 여기서는 **그 결과가 코드의 어느 값이 되었는가**만 적는다.

## 1. 스키마가 정한 것과 정하지 않은 것

```sql
"embedding" vector(1024),
COMMENT: '캡션+대사를 합친 dense 벡터. pgvector 로 색인하고 BM25 순위와 RRF 결합.
          차원 1024 는 임시값 — 임베딩 모델 확정 시 재설정해야 하며 바꾸면 전체 재색인'
```

| 요구 | 스키마의 자리 | 결과 |
| --- | --- | --- |
| 장면당 벡터 하나 | `scene.embedding` 칸 하나 | 캡션용·대사용으로 나누지 않는다 |
| 차원 | `vector(1024)` | 설정 `dimension` 과 어긋나면 INSERT 가 통째로 실패 |
| 텍스트 없는 장면 | `NULL` 허용 | 벡터를 지어내지 않고 건너뛴다 |
| 어느 장면인가 | `scene_id` | 워커는 `scene_index` 로 말한다 — DB 에 접속하지 않는다 |
| 무엇을 임베딩했나 | **없음** | `SceneEmbedding.source_text` 로 결과에만 싣는다 |

차원 1024 는 `S15P21A501-175` 가 선정 모델의 차원으로 정한 값이고 컬럼도 그 값이다.

**정본 세 곳은 아직 "임시값" 으로 열려 있다.** `docs/frd.md:618` 은 미결 "임베딩 모델과
벡터 차원" 의 담당을 -175 가 아니라 **이 일감(`S15P21A501-100`)** 으로 지정한 채 남아
있고, 마이그레이션 주석(`V20260907092019__baseline.sql:381`)도 "임시값" 그대로다. 노션
FRD 가 상위 정본이라 그쪽도 함께 봐야 한다. 이 문서가 그 미결을 닫지 않는다 — FRD 개정은
별도 작업이고, `ai/AGENTS.md` 규칙대로 어긋난 지점을 적어 두는 데까지가 여기 몫이다.

## 2. 무엇을 입력으로 넣는가 — 캡션과 대사

일감 `S15P21A501-100` 본문은 입력을 "caption·OCR·transcript 요약" 으로 적었다. **정본은
그것이 아니다.** FRD §11.4 (`docs/frd.md:606`)와 위 컬럼 주석이 둘 다 "캡션과 대사를 합쳐
벡터 하나" 로 정한다. `ai/AGENTS.md` 규칙대로 정본을 따랐다.

OCR 을 넣지 않는 이유는 문서가 그렇게 말해서만이 아니다. 화면 글자는
`ocr_observation.tokens` 로 **BM25 채널에 이미 들어가 있다**. 같은 문자열을 dense 채널에도
넣으면 RRF 결합에서 한 신호가 두 번 세어진다 — 자막이 그대로 박힌 뉴스 화면에서 특히
그렇다. 그 중복이 검색 품질에 도움이 되는지는 재 본 적이 없고, 재기 전에 넣을 이유가 없다.

입력은 상류 두 단계에서 온다. **둘 다 비치명이라 없을 수 있다.**

| 재료 | 상류 | 없을 때 |
| --- | --- | --- |
| `caption` | 3단계 `vlm_metadata` | 대사만으로 벡터를 만든다 |
| `dialogue` | 7단계 `scene_transcript_mapping` | 캡션만으로 벡터를 만든다 |

둘 다 비면 그 장면은 `skipped` 다. 빈 문자열을 임베딩하면 **모든 빈 장면이 서로 최근접이
되어** 보조 채널이 오염된다. `scene.embedding` 이 nullable 인 이유가 그것이고, 그 장면은
BM25 채널로만 검색된다. 공백만 있는 캡션도 없는 것으로 본다.

조립 규칙은 `compose_text()` 하나다. 캡션 덩어리와 대사 뭉치를 `SECTION_SEPARATOR`(줄바꿈)
로 나누고 대사 줄끼리는 `DIALOGUE_SEPARATOR`(공백)로 잇는다. 둘을 같은 구분자로 이으면
"설명" 과 "발화" 의 경계가 사라지는데, 그 경계는 모델이 문장 구조를 읽는 단서다.

## 3. 모델은 코드가 고르지 않는다

가중치 식별자는 `NPICK_AI_EMBEDDING_MODEL` 이 정한다. 설정 파일에도 코드에도 모델 이름이
없다 — `vlm_model`·`ollama_model` 과 같은 판단이다. `S15P21A501-175` 의 선정은 **잠정**이고
리뷰어 승인 전이며, 일감 제약이 "선정 동결은 Gate B 시점 — 그 전까지 어댑터로 교체 가능해야
함" 이다. 코드가 하나를 고르면 그게 곧 근거 없는 동결이다.

값이 비어 있으면 어댑터가 `EmbeddingModelUnavailableError` 를 낸다. 배선되면 계약 §9.2 의
`MODEL_UNAVAILABLE`(일시)이 되도록 옮겨야 한다 — 구현이 없는 `NO_ADAPTER`(영구)와 다른
사실이기 때문이다. **그 번역은 아직 없다**(§7).

### 교체 층이 둘이다

| 무엇을 바꾸나 | 어디를 고치나 | 얼마나 흔한가 |
| --- | --- | --- |
| 가중치만 (arctic ↔ PIXIE ↔ KURE) | `NPICK_AI_EMBEDDING_MODEL` + 재색인 | 흔하다 |
| 런타임 자체 (ONNX·원격 API 등) | `TextEncoder` 구현 하나 추가 | 드물다 |

앞엣것이 흔한 이유는 `S15P21A501-175` 의 재평가 조건이 그 축이기 때문이다 — 캡션을 임베딩
입력에 넣고 장면 단위 골드셋을 만든 뒤 PIXIE 가 거기서 유의하게 앞서면 교체한다. **교체
비용은 차원(1024)·질의 접두(`query: `)가 같아 재색인뿐이다.**

`document_prefix` 가 설정에 있는 것도 같은 이유다. arctic-ko 는 문서측 접두를 쓰지 않지만
e5 계열은 `passage: ` 를 요구한다. 접두가 코드에 박혀 있으면 그 계열로는 갈아 끼울 수 없다.

## 4. 정규화는 embedder 가 한다

L2 정규화를 어댑터가 아니라 `embedder.py` 에서 한다. `SentenceTransformer.encode` 의
`normalize_embeddings` 는 꺼 둔다. 어댑터마다 기본값이 달라(sentence-transformers 는 꺼져
있고 어떤 런타임은 켜져 있다) 각자 하게 두면 **같은 설정에서 다른 크기의 벡터가 나온다.**

0 벡터는 거부한다. pgvector 의 코사인 거리가 정의되지 않아 NaN 이 되고, 그러면 그 장면이
모든 질의에서 조용히 빠진다. 증상이 "검색 결과에 안 나온다" 라서 원인을 벡터에서 찾기까지가
멀다.

NaN·inf 성분도 거부한다. fp16 에서 NaN 이 나오면 norm 도 NaN 이 되는데 `norm == 0.0` 은
False 라 0 벡터 검사만으로는 빠져나가고, 저장 후 증상은 0 벡터와 **정확히 같다**. 두 검사
모두 `normalize` 분기 **밖**에 있다 — 못 쓰는 벡터인 것은 정규화 여부와 무관하다.

차원 검사는 **실제로 나온 벡터의 길이**로 한다. `TextEncoder` 는 차원을 선언하지 않는다 —
모델이 말한 값은 실제와 다를 수 있고, 그 값을 읽으려고 가중치를 올리게 만들면 Protocol 이
부작용을 갖는다. 그래서 선언 차원과 실제가 다른 모델(차원을 잘라 쓰는 Matryoshka 설정)은
자동으로 맞춰지지 않고 `ValueError` 가 된다 — 그런 모델로 갈아 끼우려면 어댑터가
`truncate_dim` 을 넘기도록 고쳐야 한다.

## 5. 설정과 버전

`config/text_embedding.v1.toml` 이 값의 정본이고 그 파일의 해시가 `config_version` 이다.

| 키 | 기본값 | 바꾸면 |
| --- | --- | --- |
| `dimension` | `1024` | **전체 재색인.** `scene.embedding vector(N)` 도 함께 고쳐야 한다 |
| `normalize` | `true` | 코사인 검색이 깨진다. 끄는 경우는 실험뿐 |
| `document_prefix` | `""` | 전체 재색인. 모델 카드가 요구할 때만 |

**결과를 바꾸지 않는 값은 이 파일에 없다.** 배치 크기(`NPICK_AI_EMBEDDING_BATCH_SIZE`,
기본 16)와 모델 경로는 `settings.py` 다. VRAM 사정으로 배치를 16→8 로 내리는 것만으로
`config_version` 이 바뀌면 그 값이 들어간 `stageVersion` 도 달라지고, 계약 §7 의 버전
불일치로 배정이 끊기거나 전 클립이 재처리 대상이 된다. `ocr_model_dir` 이 설정 파일 밖에
있는 것과 같은 판단이다.

구분자는 `embedder.py` 의 상수(`SECTION_SEPARATOR`·`DIALOGUE_SEPARATOR`)다. 바꾸면 전체
재색인인데 바꿀 이유가 없고, 버전 붙는 설정에 두면 실수로 움직였을 때 대가가 크다.

재현 식별자는 네 축이다 — `(config_version, engine, engine_version, model_version)`.

| 축 | 어디서 | 무엇이 바뀌면 움직이나 |
| --- | --- | --- |
| `config_version` | 위 toml 해시 | 차원·정규화·접두 |
| `engine` | `TextEncoder.name` | 런타임 교체 (예: ONNX·원격 API) |
| `engine_version` | `TextEncoder.version` | 라이브러리 업그레이드 |
| `model_version` | `TextEncoder.model_version` | **가중치** — `<repo>@<SHA>` |

어휘가 `engine`/`engine_version` 인 것은 계약 §7 의 재현 튜플 키가 그것이고
`ocr`·`vlm_metadata` 도 같은 말을 쓰기 때문이다.

`config_version` 만으로는 부족하다. 그 값은 설정 파일만 해시하므로 가중치가 바뀌면 값이
그대로인데 벡터는 달라진다. 일감의 "모델 교체 시 재생성 흐름 — 버전 다르면 다른 산출물"
요구가 성립하는 자리가 `model_version` 이다.

실측 확인 — 선정 모델로 돌린 smoke 가 `dragonkue/snowflake-arctic-embed-l-v2.0-ko@55ec6e93…`
처럼 40자리 SHA 를 기록한다. 어댑터가 `main` 으로 받은 실행에서도 실제로 올라간 가중치의
commit 을 찾아 적는다.

**운영에는 리비전을 SHA 로 고정한다.** `main` 으로 두면 원격이 갱신될 때 같은
`<모델>@main` 이 다른 가중치를 가리키는데 기록은 그대로다. 임베딩에서 이것이 특히 아픈
이유는 벡터가 사람이 보고 이상하다고 알아챌 수 있는 산출물이 아니라서다 — 가중치가 바뀐
것을 검색 품질이 떨어진 뒤에야 알게 된다. 어댑터가 SHA 를 확정하지 못하면 경고를 남긴다.

## 6. 돌려 보기

```bash
uv sync --group gpu --group cu130   # 드라이버가 CUDA 12.8 이면 cu128
NPICK_AI_EMBEDDING_MODEL=dragonkue/snowflake-arctic-embed-l-v2.0-ko \
    uv run pytest -m smoke -k scene_embedding -s
```

샘플 장면 둘(텍스트 있는 것 하나, 없는 것 하나)로 벡터 산출·차원·정규화·버전 기록을 한 번에
확인한다. 모델 이름을 주지 않으면 skip 한다 — 테스트도 모델을 고르지 않는다.

나머지 테스트(`tests/test_text_embedding.py`)는 전부 가짜 인코더를 쓴다. 이 단계에서 규약인
것은 "어떤 벡터가 나오는가" 가 아니라 **무엇을 인코더에 넣고 무엇을 기록하는가**이고, 그건
가중치 없이 검증된다.

## 7. 아직 하지 않은 것

- **pipeline run 배선.** `jobs/registry.py` 에 이 단계의 핸들러가 없고
  `docs/contracts/job-api.md` 에도 `text_embedding` 절이 없다. 상류 7단계
  `scene_transcript_mapping` 이 아직 없어 입력 조립의 절반이 비어 있고, 계약에 절을 더하면
  BE 구현이 따라와야 한다. 이 모듈은 그때 그대로 꽂힌다 — `ai/AGENTS.md` 가 단계 구현을 순수
  함수로 두고 배선하지 말라고 한 이유다.
- **질의측 임베딩.** `S15P21A501-164` 다. 질의는 접두가 `query: ` 로 달라서 이 모듈이
  그대로 쓰이지 않는다.
- **캡션을 넣은 장면 단위 재평가.** `S15P21A501-175` 의 재평가 조건이고 그 티켓의 몫이다.

## 8. 언제 다시 볼 것인가

| 신호 | 무엇을 의심하나 |
| --- | --- |
| `skipped` 비율이 높다 | 상류 VLM·자막이 비어 있다. 이 단계의 문제가 아니다 |
| `S15P21A501-175` 재평가에서 PIXIE 가 이긴다 | `NPICK_AI_EMBEDDING_MODEL` 교체 + 전체 재색인 |
| dense 채널이 날짜·고유명사를 못 잡는다 | 정상이다. FR-SRH-002 — 임베딩은 exact term 매칭을 대체하지 않는다 |
| 모델을 바꿨는데 결과가 같다 | `model_version` 이 안 움직였다. 리비전 고정과 캐시를 본다 |
