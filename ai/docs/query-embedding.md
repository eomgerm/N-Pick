# 질의 임베딩 — 입력과 경계

검색 시점 모듈이다. `stages.py` 에 없다. 구현은 `src/npick_worker/query_embedding/`.

검색어를 dense 벡터 하나로 만들어 `POST /query/resolve` 응답에 싣는다. 그 벡터가 색인
측 `scene.embedding` 과 코사인으로 비교되고 BM25 순위와 RRF 로 결합된다.

모델 선정 실측은 `S15P21A501-175`, 색인 측 구현은 [text-embedding.md](text-embedding.md)
(`S15P21A501-100`) 다. 여기서는 **질의 측이 색인 측과 무엇을 공유하고 무엇이 다른가**만
적는다.

## 1. 원문을 임베딩한다

정규화 질의(`normalized_query`)가 아니다. 근거 셋.

| 근거 | 내용 |
| --- | --- |
| 입력 분포 | 색인 측은 캡션+대사라는 **자연어 문장**을 넣는다(`compose_text`). `normalized_query` 는 형태소만 남기고 불용어를 걷어낸 뒤 **정렬**까지 한 토큰 나열이라 문장이 아니다 |
| 실측 | `S15P21A501-175` 의 비교표가 `"query: " + 원질의` 로 측정한 값이다(`eval/text_embedding/measure.py`). 운영에서 다른 것을 넣으면 그 수치가 재현된다고 말할 수 없다 |
| 쓰임 | `search_tokens` 는 BM25 전용이다 — 색인 토큰과 **같은 규칙만** 적용한 값이고 dense 채널과 목적이 다르다 |

`query_api` 가 해석에도 원문을 넘기는 것과 같은 결이다. 정규화·해석·임베딩 셋이 순차가
아니라 **각자 원문에서 출발한다.**

앞뒤 공백은 벗긴다. `" 부산 "` 과 `"부산"` 이 다른 벡터가 되면 같은 질의가 다른 검색이 된다.

## 2. 접두가 색인 측과 다르다

arctic-ko 는 **질의에만** `query: ` 를 요구한다. 문서측은 접두가 없다(`document_prefix = ""`).

이것이 이 모듈이 따로 있는 이유다. 색인 측 `compose_text` 를 그대로 쓸 수 없다.

후보 셋(arctic·PIXIE·KURE)이 같은 접두를 쓰므로 `S15P21A501-175` 의 재평가 조건이 걸려
모델이 바뀌어도 이 값은 움직이지 않는다. e5 계열로 가면 `query: ` 가 그대로 맞고 문서측만
`passage: ` 로 바뀐다.

## 3. 이 모듈은 자기 설정 파일을 갖지 않는다

벡터 공간을 정하는 값은 **한 곳에만** 있다.

| 값 | 어디 | 왜 거기인가 |
| --- | --- | --- |
| `dimension` = 1024 | `config/text_embedding.v1.toml` | 색인 결과의 재현 식별자(`stageVersion`)에 들어가야 한다. 질의 측은 **읽기만** 한다 |
| `normalize` = true | 같은 파일 | 같다 |
| `query_prefix` = `"query: "` | `NPICK_AI_EMBEDDING_QUERY_PREFIX` (settings) | 질의 벡터만 바꾸고 **색인 결과를 바꾸지 않는다.** 버전 붙는 toml 에 있으면 `config_version` 과 `stageVersion` 이 함께 움직여 계약 §7 의 버전 불일치가 난다 — 배정이 끊기거나 전 클립이 재처리 대상이 된다 |

`query_prefix` 의 자리는 `embedding_model` 과 **같은 판단**이다. 모델 이름도 벡터를 바꾸는
값이지만 같은 이유로 settings 에 있다.

**질의 측이 자기 `dimension` 을 갖지 않는 것이 이 설계의 요점이다.** 값을 복제해 두고 두
곳이 같은지 감시하는 방법도 있지만, 복제를 만들지 않으면 감시할 것도 없다. 이 모듈은 이미
`text_embedding` 에서 Protocol·`finalize_vector`·어댑터를 가져다 쓴다 — 행동 셋을 공유하면서
값 둘만 복제할 이유가 없었다.

## 4. 색인 측과 공유하는 것

`ai/AGENTS.md` 의 공유 표에 셋째 항목으로 등록돼 있다. 기존 둘(`versioning.py`,
`korean_tokens.py`)과 **같은 종류의 요구**다 — 색인과 질의가 같은 Kiwi 설정을 써야 하고
어긋나면 검색이 0 건이 되듯, 같은 벡터 공간을 써야 하고 어긋나면 유사도가 무의미해진다.

| 공유하는 것 | 어디 | 왜 |
| --- | --- | --- |
| `TextEncoder` Protocol | `text_embedding/encoder.py` | 모델 교체 경계가 하나여야 한다 |
| `finalize_vector` | 같은 파일 | 차원·NaN·0 검사와 L2 정규화. 규칙이 갈리면 코사인이 다른 값을 낸다 |
| `shared_encoder()` | `text_embedding/sentence_transformers_backend.py` | **가중치 한 벌(1.7GB)을 프로세스가 나눠 쓴다.** 더 중요하게는 `NPICK_AI_EMBEDDING_MODEL` 하나를 함께 읽는다 — 그것이 "같은 모델" 제약의 실제 집행 수단이다 |
| `dimension`·`normalize` | `config/text_embedding.v1.toml` | §3 |

앞의 둘과 다른 점 하나: 이것들은 **단계 패키지 안에** 있다. 벡터 공간의 정본이 색인 측
`stageVersion` 에 들어가야 해서 그쪽에 둔 결과다. 중립 모듈로 옮기는 정리는 하지 않았다.

공유하지 **않는** 것은 장면 전용 조립이다. `compose_text`·`SceneText`·`SceneEmbedding`·
`embed_scenes` 는 "장면 N 개 → 벡터 N 개 + `skipped`" 모양이고 질의는 "문자열 하나 →
벡터 하나" 다. 질의에는 건너뛸 대상이 없다 — 빈 질의는 오류다.

## 5. 실패는 검색을 죽이지 않는다

FRD v3.1 §6.2 — "텍스트 의미 검색 실패 → 단어 검색과 사용 가능한 신호로 결과 제공, 누락 안내".

| 무엇이 | 응답 |
| --- | --- |
| 정규화 실패 | **400.** 지문을 만들 수 없어 검색 자체가 성립하지 않는다 |
| 임베딩 실패 | 200. `embedding: null` + `embedding_error.category: "EMBEDDING_FAILED"` |
| 해석 실패 | 200. `resolution: null` + `error.category` |

**해석과 임베딩은 독립된 축이다.** 한쪽이 죽어도 다른 쪽은 살아 나간다 — 해석이 죽으면
필터를 잃고 임베딩이 죽으면 dense 채널만 잃는다. 응답 타입을 둘로 나눈 이유가 그것이다.

사유가 `EMBEDDING_FAILED` 하나뿐인 것은 리졸버가 **동기 호출 전용이고 재시도가 없기**
때문이다. 어댑터는 호출 실패와 가중치 부재를 가르지만 호출부가 할 일은 양쪽 다 같다.
BE 가 실제로 갈라서 처리할 일이 생기면 그때 쪼갠다.

### 이름이 층마다 다르다

| 층 | 이름 | 정본 |
| --- | --- | --- |
| 워커 → BE | `EMBEDDING_FAILED` | `query_api.py` |
| BE → FE (검색 응답 `degraded_reasons`) | `dense_unavailable` | `docs/contracts/web-api.md` §5.1 |

계약이 `degraded_reasons` 값을 `resolver_fallback`·`dense_unavailable`·`snapshot_save_failed`
셋으로 닫아 두었고, 그 값을 붙이는 것은 BE 의 `SearchDegradedReason`(`S15P21A501-53`)이다.
`RESOLVER_*` 가 `QueryResolverErrorCode` 로 옮겨지는 것과 같은 층 관계다 — **같은 사실에
붙은 다른 층의 이름이지 둘 중 하나가 틀린 것이 아니다.**

예외 메시지는 응답에 싣지 않는다 — 모델 경로가 들어 있어 FRD v3.1 §6.4 를 어긴다.
진단용 상세는 워커 로그에만 남는다.

## 6. 기동 시 워밍업

**리졸버 배포 단위는 `jobs.warm_up()` 을 타지 않는다.** 그쪽은 `job_poll_enabled` 가 켜진
프로세스에서만 도는데 리졸버는 폴링하지 않는다(`app.lifespan` 의 갈래가 배포 단위 둘을
가른다).

어댑터는 인스턴스만 만들고 가중치는 첫 `encode` 에서 올라간다. 그래서 `lifespan` 이
`warm_query_encoder()` 를 부른다 — 없으면 **부팅 후 첫 검색**이 1.7GB 로딩과 CUDA 컨텍스트
초기화를 통째로 물고 동기 예산(p95 10초)을 날린다. 이후 호출은 실측 p95 110~120ms 로
예산의 약 1.2% 다(`S15P21A501-175`).

워밍업은 `yield` **앞**이라 끝날 때까지 서버가 연결을 받지 않는다. 그게 의도다 — 뒤로
미루면 첫 검색이 로딩과 겹친다. 대신 **상한이 있다**(`NPICK_AI_EMBEDDING_WARMUP_TIMEOUT_SECONDS`,
기본 60초). 캐시 볼륨이 비어 원격에서 가중치를 받는 콜드 스타트가 플랫폼의 startup probe
유예를 넘기면 **재시작 루프**가 되고, 리졸버는 사용자 검색의 동기 경로 앞단이라 그 루프가
바로 장애로 보인다.

상한을 넘겨도 버리는 일이 아니다. 대기만 끊고 스레드는 계속 돌아 로딩을 끝낸다.

### 상한만으로는 부족하다 — readiness gate 가 짝이다

기동을 끊어도 **요청 경로를 끊지 않으면 의미가 없다.** 어댑터는 첫 `encode` 에서 가중치를
올리므로, 로딩 중에 들어온 요청이 그대로 `_ensure_loaded()` 에 진입해 1.7GB 로딩을 기다린다.
동시 요청 수만큼 로딩이 겹치기까지 한다.

그래서 둘을 함께 둔다.

| 무엇 | 어디 | 막는 것 |
| --- | --- | --- |
| `is_ready` 확인 | `query_api._embed` | 준비 전 요청이 로딩을 **기다리는 것**. 즉시 `EMBEDDING_FAILED` 로 나가 BM25 가 이어진다 |
| single-flight 락 | `SentenceTransformerEncoder._ensure_loaded` | 같은 인스턴스에서 로딩이 **중복되는 것**. `shared_encoder()` 의 `lru_cache` 는 인스턴스만 공유시킬 뿐 이걸 막지 못한다 |

`is_ready` 는 읽어도 로딩하지 않는다 — 조회가 부작용을 가지면 gate 가 자기가 막으려던
블로킹을 스스로 만든다. `TextEncoder` Protocol 에는 없는 속성이라 `getattr(..., True)` 로
읽고, **상태를 말하지 못하는 구현은 준비된 것으로 본다**(원격 API 처럼 로딩이 지연 단계가
아닌 어댑터).

워밍업 스레드가 뒤늦게 끝나면 `is_ready` 가 참이 되고 그때부터 벡터가 나간다. 재기동은
필요 없다.

검증은 `tests/test_query_embedding_readiness.py` 다 — 준비 전 요청의 **벽시계 시간**을 재고,
동시 4스레드에서 `_load` 가 한 번만 도는지 본다.

### 동시 `encode` 상한은 두지 않았다

워밍업이 끝난 뒤에는 sync endpoint 의 스레드풀만큼 같은 모델에 `encode` 가 동시에 들어올 수
있다. 세마포어를 두지 않은 이유는 **재 본 적이 없기 때문**이다 — 질의당 문장은 1개이고
S15P21A501-175 실측이 CPU 단건 p95 110~120ms(예산의 약 1.2%)다. 상한이 필요하다는 신호를
먼저 본 뒤에 넣는다.

넣어야 할 신호: 리졸버의 p95 가 예산을 위협하거나, CPU 경합으로 지연이 스레드 수에 비례해
늘거나, GPU 리졸버에서 CUDA OOM 이 난다.

## 7. 돌려 보기

```bash
uv sync --group gpu --group cu130   # 드라이버가 CUDA 12.8 이면 cu128
uv run pytest -m smoke -k query_embedding -s
```

같은 인코더로 질의 하나와 관련 장면 하나를 임베딩해 **차원이 실제로 같은지**와 코사인이
양수인지를 본다. 두 경로가 같은 설정을 읽는지는 일반 테스트가 이미 본다 — smoke 가 보는
것은 그 값이 실물과 맞는가다.

나머지 테스트는 전부 가짜 인코더를 쓴다.

## 8. 아직 하지 않은 것

- **BE 배선.** 응답 필드는 추가됐지만 `search_execution` 에 임베딩 버전을 담을 칸이 없다
  (`normalization_version` 은 있다). BE 가 `search_config_json`·`degraded_reasons_json` 중
  어디에 넣을지는 그쪽 몫이다. 담을 칸이 생기면 그때 응답 필드를 더한다 — 지금 버전을
  만들어 두면 아무도 읽지 않는 값이 된다.
- **리졸버 API 계약 문서.** `docs/contracts/README.md` 가 "미작성" 으로 두고 있다. 이 필드
  셋도 그 문서가 생길 때 함께 적힌다.
- **접두를 모델 카드에서 읽기.** 지금은 설정값이다. `-175` 하네스는 모델 카드의
  `prompts.query` 를 읽는 경로를 갖고 있고, 후보가 전부 같은 접두라 당장 이득이 없다.

## 9. 언제 다시 볼 것인가

| 신호 | 무엇을 의심하나 |
| --- | --- |
| dense 채널이 날짜·고유명사를 못 잡는다 | 정상이다. FR-SRH-002 — 임베딩은 exact term 매칭을 대체하지 않는다 |
| `dimension` 을 바꿔야 한다 | 색인·질의가 같은 값을 읽으므로 고칠 곳은 한 곳이다. 다만 **전체 재색인**이고 `scene.embedding vector(N)` 컬럼도 함께 고쳐야 한다 |
| 첫 검색만 느리다 | 워밍업이 실패했다. 로그의 "질의 임베딩 워밍업 실패" 를 본다 |
| `embedding_error` 가 계속 난다 | 가중치·VRAM. 검색은 BM25 로 돌고 있으므로 장애로 보이지 않는다 |
| 모델을 바꿨다 | 질의 접두를 확인한다. 색인 재생성과 **같은 배포**여야 한다 |
