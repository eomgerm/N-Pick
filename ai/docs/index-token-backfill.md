# 저장 토큰 재생성과 장면 제외 규칙 재키

`korean_tokens.py` 의 색인 토큰 규칙이 바뀌었을 때 **이미 저장된 행**을 새 규칙에 맞추는 절차다.
처음 쓰인 곳은 S15P21A501-320 이다. 그 배포에서 `normalization_version` 이
`query-norm/v1:b0d96c0c:…:encpos2` → `query-norm/v2:<해시>:…:encpos3` 으로 바뀌었다.

- `encpos3` — 불규칙 활용 `VV-I`·`VA-I`·`VV-R` 을 버리지 않는다. **색인 토큰이 바뀐다** → 재생성 필요.
- `query-norm/v2` — 질의 BM25 토큰에만 색 형용사↔색 명사 묶음(`search_token_synonyms`,
  「빨간」→`빨간색/nng`)을 더한다. 색인과 지문은 그대로다 → 재생성 불필요, 버전만 바뀌므로 재키 필요.
  확장어 토큰화(`POST /query/tokenize`)에는 걸지 않는다 — 원 질의의 `search_tokens` 만이다.
  더한 토큰도 BE 에는 원 질의 토큰으로 보이므로, 맞으면 근거 설명의 `matched_keywords` 에 `origin=query` 로
  나온다(「빨간」으로 쳤는데 `빨간색` 이 원 질의어로 표시). 지금은 받아들이고, 구분이 필요하면 BE 가 나눈다.

검색은 저장된 토큰을 BM25 로 조회할 뿐 질의 시점에 문서를 다시 토큰화하지 않는다. 그래서 코드만
배포하면 **새 질의 토큰(`걷/vv`)이 옛 색인에 없어 효과가 0** 이고, `normalization_version` 이 바뀌어
**모든 장면 제외 규칙이 조용히 안 걸린다.** 두 가지를 함께 옮겨야 한다.

| 도구 | 하는 일 |
| --- | --- |
| [`tools/retokenize_index.py`](../tools/retokenize_index.py) | 원문을 새 규칙으로 다시 토큰화해 바뀐 행만 패치 CSV 로 낸다 |
| [`tools/rekey_scene_exclusions.py`](../tools/rekey_scene_exclusions.py) | `exclude_scene` 규칙의 `normalized_query`·`normalization_version`·`query_fingerprint` 를 원 질의에서 다시 만든다 |

둘 다 `korean_tokens` 를 **프로세스 안에서** import 한다. `POST /query/tokenize` 는 한 문자열을
200자로 자르므로(`query_api.py` `MAX_TOKENIZE_TEXT_LENGTH`) 긴 대사가 빠진다. 워커 이미지에는 DB
드라이버가 없으므로(의도된 경계) DB 입출력은 `psql \copy` 의 CSV 로 한다.

## 1. 무엇을 다시 만드나

토큰 컬럼 셋은 전부 생산자가 `" ".join(index_tokens(원문))` 으로 만든다. 도구는 같은 호출을 한다.

| 컬럼 | 원문 | 생산자 |
| --- | --- | --- |
| `scene.caption_tokens` | `scene.caption` | `vlm_metadata` (`Caption.tokens_text`) |
| `scene.transcript_tokens` | `scene.transcript_text` | `scene_transcript_mapping` — BE 가 연결 구간을 공백 한 칸으로 이어 `transcript_text` 를 만들고, 워커가 **같은 문자열**을 토큰화한다(`JdbcWorkerStageOutputAdapter.transcripts`) |
| `ocr_observation.tokens` | `ocr_observation.raw_text` | `ocr` (`OcrObservation.tokens_text`) |

**옛 이중 형식은 단일 형식으로 바뀐다.** S15P21A501-282 백필이 무중단을 위해 `국민 힘 … 국민/nng 힘/nng …`
처럼 형태만 쓴 옛 토큰을 앞에 남겨 두었다(로컬 운영 복원본: 캡션 1,109 · 대사 1,103 · OCR 12,628 행).
질의 쪽이 `encpos1` 부터 `형태/품사` 만 보내므로 그 앞쪽 절반은 이미 아무 질의에도 안 걸린다. 재생성은
새로 처리한 행과 같은 단일 형식을 만든다. 대사 7 행은 옛 형식만 있었는데(200자 상한으로 -282 에서
빠진 것으로 보인다) 이번에 함께 채워진다.

`text_key`(OCR 병합 키)는 토큰에서 파생되지만 저장 컬럼이 아니므로 손대지 않는다. `updated_at` 도
건드리지 않는다 — 원문도 판정도 바뀌지 않은 파생 컬럼 재계산이다.

**출처 버전은 옛 값으로 남는다.** 재생성은 토큰 컬럼만 바꾸고, 그 토큰을 만든 단계의 재현 기록
(`pipeline_run.stage_states_json` 의 단계별 `versions.detail.tokenizer`)은 `…:encpos2` 그대로다(로컬 942 회차).
-282 백필 때와 같은 출처 어긋남이다 — 백필한 행은 기록된 tokenizer 가 아니라 이 문서의 절차가 만든 것이다.

## 2. 순서

| 단계 | 이유 |
| --- | --- |
| ① 새 워커 이미지를 **빌드만** 한다 | 도구가 새 규칙으로 돌아야 한다. 실행 중인 컨테이너는 그대로 둔다 |
| ② 백업 | 아래 §3 |
| ③ **색인 토큰 백필** | 새 색인은 옛 색인의 `형태/품사` 토큰을 전부 담고 불규칙 용언 토큰이 **더해진** 것이다. 옛 질의(`encpos2`)의 매칭 집합은 줄지 않는다. 먼저 해도 검색이 깨지지 않는다 |
| ④ 재키 CSV 를 **미리 만든다** | 계산은 새 이미지로 한다. 반영 SQL 은 한순간이다 |
| ⑤ **재키 반영 → 곧바로 리졸버(`ai-worker`) 교체**, 같은 창에 **BE `candidate-v4`** 배포 | 재키된 규칙은 새 버전(`query-norm/v2:…:encpos3`) 질의에만 걸린다. 반영부터 새 리졸버가 healthy 가 될 때까지가 「장면 제외가 안 걸리는 창」이다 — 컨테이너 재기동부터 임베딩 워밍업을 마치고 healthy 가 될 때까지이고, 로컬 실측 **15~17 초**(모델 캐시 볼륨이 찬 상태, 첫 `/query/resolve` 응답·임베딩 포함 15.2 초 · healthy 15.2~17.4 초)다. 교체 중에는 어차피 해석이 멈춘다. BE 는 아래 「드리프트 가드」 때문에 같은 창에 올린다 |
| ⑥ 파이프라인 워커 교체 | `ai-cpu-worker`(OCR)·GPU 노드(VLM·대사 매핑)도 `korean_tokens` 로 색인 토큰을 만든다. 하나라도 옛 코드면 **새로 처리한 영상이 옛 토큰으로 저장된다** |
| ⑦ **백필을 한 번 더** | ③ 이후 ⑥ 전까지 옛 워커가 저장한 행을 잡는다. 멱등이라 이미 맞는 행은 건드리지 않는다 |
| ⑧ **재키를 한 번 더** (배포 후 며칠 뒤, 멱등) | BE `CreateSceneExcludeCandidateService` 는 후보의 `query_fingerprint`·`normalized_query`·`normalization_version` 을 **원 검색(`search_execution`)에서 복사**한다. ④의 추출 뒤에 배포 전(옛 버전) 검색이나 ⑤의 창 안 검색에서 만든 후보는 옛 키로 승인되어 영영 안 걸린다. 아래 확인 쿼리가 0 이 될 때까지 §3 의 재키를 다시 돌린다 |

⑧ 확인 쿼리 — 현재 버전은 새 리졸버의 `normalization_version` 이다:

```sql
SELECT r.search_rule_id, r.active, f.status, r.normalization_version
FROM npick.search_rule r JOIN npick.feedback f ON f.feedback_id = r.source_feedback_id
WHERE r.action = 'exclude_scene'
  AND (r.active OR (f.status = 'REVIEWING' AND f.created_rule_id IS NULL))
  AND r.normalization_version <> 'query-norm/v2:6f769b32:kiwi0.23.2:model0.23.0:encpos3';
```

영구 해결은 BE 가 후보를 만들거나 승인할 때 원 질의를 **현재 정규화로 다시 정규화**하는 것이다(BE 후속 과제).
그전까지는 정규화 버전이 바뀔 때마다 ⑧ 이 필요하다.

**BE 설정 버전·드리프트 가드.** 재키는 규칙 ID 를 바꾸지 않고, BE 의 `config_version` 에는
`normalization_version` 이 들어 있지 않다. 그래서 `CorrectionStateFingerprint` 가 바뀌지 않고, 배포 **전에**
검증을 마친 `REVIEWING` 신고는 정규화가 바뀐 뒤에도 재검증 없이 확정된다(-282 리뷰 ③ 과 같은 구멍).
배포 공지에 「검수 중인 신고는 배포 뒤 검증을 다시 돌린다」를 넣는다. 이번 배포에서는 BE `candidate-v4`
(MR !239, `be/fix/bm25-term-scoring-S15P21A501-320`)가 `config_version` 을 바꾸므로 지문의 config 축이 바뀌어
재검증이 강제된다 — **그래서 !239 를 리졸버 교체와 같은 배포 창에 올려야 이 구멍이 덮인다.** (!238 의
`candidate-v3` 은 이미 dev 에 있어 지금 배포되어도 이 창을 덮지 못한다.) 정규화만 바뀌는 다음 배포에서도
막으려면 BE 가 `normalization_version` 을 설정 버전에 실어야 한다 — 이 문서 범위 밖이다.

**재키 범위.** 활성 규칙과 **검수 중인 신고의 대기 후보**(`active = false`, 신고 `REVIEWING`)를 함께
옮긴다. 후보는 승인될 때 키를 그대로 들고 활성화되므로, 옮기지 않으면 켜지는 순간부터 안 걸리고 검증
재검색에서도 제외가 적용되지 않는다. 사용 중단된(신고 `CLOSED`, `active = false`) 옛 규칙은 옮기지 않는다.

**믿을 수 없는 원 질의는 옮기지 않는다.** 원 질의(`search_execution.query_text`)에 U+FFFD(대체 문자 —
잘못된 문자셋으로 디코딩된 흔적)가 있거나 더는 정규화되지 않는 규칙은 도구가 `skipped` 로 알리고 쓰지
않는다. 다시 정규화하면 깨진 글자의 지문이 나올 뿐이라, 옮기면 아무도 칠 수 없는 질의에 규칙이
되살아난다. 이런 규칙은 **BE 규칙 사용 중단 API 로 끈다** — SQL 로 `active` 를 바꾸지 않는다
([web-api.md](../../docs/contracts/web-api.md) `PATCH /review/search-rules/{ruleId}`, 검수자 권한):

```bash
curl -X PATCH "$BASE/api/v1/review/search-rules/<ruleId>" -H 'content-type: application/json' \
  -b "<검수자 세션 쿠키>" -d '{"active": false, "reason": "원 질의 글자 깨짐 — 정규화 재키 불가 (S15P21A501-320)"}'
```

대기 후보가 `skipped` 이면 위 API 가 아니라(`SRCH_409_221`, 이미 중단) 검수 화면에서 그 후보를 버린다.

## 3. 명령

작업 디렉터리와 `psql` 을 정한다. 로컬 compose 기준이다 — 운영에서는 `npsql` 만 그 환경의 접속으로
바꾼다. `-i` 가 있어야 `pstdin` 으로 CSV 를 넘길 수 있다.

```bash
export WORK=$PWD/retokenize-$(date +%Y%m%d) && mkdir -p "$WORK/patch"
npsql() { docker exec -i npick-postgres psql -U npick -d npick -X -v ON_ERROR_STOP=1 "$@"; }
# ① 에서 빌드한 새 이미지로 도구를 한 번씩 돌린다. 실행 중인 워커 컨테이너는 건드리지 않는다
ntool() {
  docker run --rm -i --user "$(id -u):$(id -g)" --entrypoint python \
    -v "$WORK:/work" -v "$REPO/ai/tools:/tools:ro" npick/ai-worker:<새 태그> "$@"
}
```

`REPO` 는 이 저장소 체크아웃이다(도구는 이미지에 들어 있지 않다). 새 규칙인지 먼저 본다:

```bash
ntool -c 'from npick_worker.korean_tokens import tokenizer_version; print(tokenizer_version())'
# query-norm/v2:6f769b32:kiwi0.23.2:model0.23.0:encpos3
```

### 백업

```bash
docker exec npick-postgres pg_dump -U npick -d npick -Fc \
  -t npick.scene -t npick.ocr_observation -t npick.search_rule > "$WORK/backup.dump"
```

### 색인 토큰 (③·⑦)

```bash
npsql -q -c "\copy (SELECT scene_id AS id, caption AS text, coalesce(caption_tokens, '') AS tokens
  FROM npick.scene WHERE caption IS NOT NULL ORDER BY scene_id) TO STDOUT WITH (FORMAT csv, HEADER)" \
  > "$WORK/scene_caption.csv"
npsql -q -c "\copy (SELECT scene_id AS id, transcript_text AS text, coalesce(transcript_tokens, '') AS tokens
  FROM npick.scene WHERE transcript_text IS NOT NULL ORDER BY scene_id) TO STDOUT WITH (FORMAT csv, HEADER)" \
  > "$WORK/scene_transcript.csv"
npsql -q -c "\copy (SELECT ocr_observation_id AS id, raw_text AS text, tokens
  FROM npick.ocr_observation ORDER BY ocr_observation_id) TO STDOUT WITH (FORMAT csv, HEADER)" \
  > "$WORK/ocr_observation.csv"

for name in scene_caption scene_transcript ocr_observation; do
  ntool /tools/retokenize_index.py --in /work/$name.csv --out-dir /work/patch --name $name
done
# rows=7712 changed=… unchanged=… batches=…  (표마다 한 줄)
```

패치 파일 하나(기본 5,000 행)를 한 트랜잭션으로 반영한다. 패치 행은 추출 당시의 원문(`text`)과
`old_tokens` 를 함께 싣고, **둘 다** 지금 값과 같은 행만 바꾸므로 추출 뒤에 다시 쓰인 행은 건너뛴다 —
다음 회차가 잡는다. 토큰만으로는 모자란다. 옛 규칙에서 「사람들이 걷는 모습」과 「사람들이 듣는 모습」은
둘 다 `사람/nng 모습/nng` 라, 첫 문장으로 만든 패치가 두 번째 문장으로 다시 쓰인 행에 `걷/vv` 를 넣는다.

도구는 실행할 때마다 같은 `--name` 의 옛 패치 파일(`<name>.NNNN.csv`)을 먼저 지운다. ⑦ 에서 `changed=0`
이거나 배치 수가 줄어도 ③ 의 파일이 남아 아래 루프에 섞이지 않는다.
그 결과 한 종류의 패치 파일이 하나도 없을 수 있어, 루프마다 `[[ -f "$f" ]] || continue` 로 매치되지 않은
glob(Bash 는 패턴 문자열을 그대로 한 번 넘긴다)을 건너뛴다.

```bash
apply_patch() {  # $1 = 파일, $2 = UPDATE 문
  npsql -1 \
    -c "CREATE TEMP TABLE patch (id bigint PRIMARY KEY, text text NOT NULL, old_tokens text NOT NULL,
      new_tokens text NOT NULL)" \
    -c "\copy patch FROM pstdin WITH (FORMAT csv, HEADER)" \
    -c "$2" < "$1"
}
for f in "$WORK"/patch/scene_caption.*.csv; do [[ -f "$f" ]] || continue; apply_patch "$f" "UPDATE npick.scene s
  SET caption_tokens = p.new_tokens FROM patch p
  WHERE s.scene_id = p.id AND s.caption = p.text AND coalesce(s.caption_tokens, '') = p.old_tokens"; done
for f in "$WORK"/patch/scene_transcript.*.csv; do [[ -f "$f" ]] || continue; apply_patch "$f" "UPDATE npick.scene s
  SET transcript_tokens = p.new_tokens FROM patch p
  WHERE s.scene_id = p.id AND s.transcript_text = p.text
    AND coalesce(s.transcript_tokens, '') = p.old_tokens"; done
for f in "$WORK"/patch/ocr_observation.*.csv; do [[ -f "$f" ]] || continue; apply_patch "$f" "UPDATE npick.ocr_observation o
  SET tokens = p.new_tokens FROM patch p
  WHERE o.ocr_observation_id = p.id AND o.raw_text = p.text AND o.tokens = p.old_tokens"; done
```

파일마다 `COPY n`·`UPDATE n` 이 찍힌다. 두 수가 다르면 그만큼이 추출 뒤에 바뀐 행이다. 다시 추출해 도구를
돌리면 `changed=0` 이어야 한다.

### 장면 제외 규칙 재키 (④·⑤)

```bash
npsql -q -c "\copy (SELECT r.search_rule_id, se.query_text, r.normalized_query,
    r.normalized_filters_json::text AS normalized_filters_json, r.normalization_version, r.query_fingerprint
  FROM npick.search_rule r
  JOIN npick.feedback f ON f.feedback_id = r.source_feedback_id
  JOIN npick.search_result sr ON sr.search_result_id = f.search_result_id
  JOIN npick.search_execution se ON se.search_execution_id = sr.search_execution_id
  WHERE r.action = 'exclude_scene'
    AND (r.active OR (f.status = 'REVIEWING' AND f.created_rule_id IS NULL))
  ORDER BY r.search_rule_id) TO STDOUT WITH (FORMAT csv, HEADER)" > "$WORK/exclude_rules.csv"

ntool /tools/rekey_scene_exclusions.py --in /work/exclude_rules.csv --out /work/rekey.csv
# stderr: 규칙마다 changed / unchanged / skipped (이유) 와 옛 → 새 normalized_query
# stdout: rules=7 changed=6 unchanged=0 skipped=1
#         deactivate via BE rule API: <skipped 규칙 ID>
```

도구는 쓰기 전에 **저장된 지문을 저장된 세 값으로 다시 계산해 전부 맞는지** 본다. BE
`NormalizedSearch.computeFingerprint` 의 이식이 한 건이라도 어긋나면 아무것도 쓰지 않고 멈춘다.
필터(`normalized_filters_json`)는 BE 가 정규화한 값이라 그대로 둔다.

반영은 리졸버 교체 **직전**에 한다:

```bash
npsql -1 -c "CREATE TEMP TABLE rekey (search_rule_id bigint PRIMARY KEY, old_fingerprint text NOT NULL,
    normalized_query text NOT NULL, normalization_version text NOT NULL, query_fingerprint text NOT NULL)" \
  -c "\copy rekey FROM pstdin WITH (FORMAT csv, HEADER)" \
  -c "UPDATE npick.search_rule r SET normalized_query = k.normalized_query,
        normalization_version = k.normalization_version, query_fingerprint = k.query_fingerprint
      FROM rekey k WHERE r.search_rule_id = k.search_rule_id AND r.query_fingerprint = k.old_fingerprint" \
  < "$WORK/rekey.csv"
docker compose up -d --no-deps --force-recreate ai-worker   # 곧바로
```

### 확인

```bash
curl -s -X POST http://127.0.0.1:8000/query/resolve -H 'content-type: application/json' \
  -d '{"query":"사람들이 걷는 모습"}' | grep -o '"search_tokens":[^]]*]'
# ["사람/nng","걷/vv","모습/nng"]
npsql -At -c "SELECT count(*) FROM npick.scene WHERE caption_tokens ~ '(^| )걷/vv( |$)'"
```

## 4. 되돌리기

토큰은 원문에서 언제든 다시 만들 수 있으므로 옛 코드로 되돌릴 때는 옛 이미지로 같은 절차를 돌리면
된다. 규칙 재키는 `rekey.csv` 의 `old_fingerprint` 가 남아 있지만 옛 `normalized_query`·`normalization_version`
은 없으므로 **백업(`search_rule`)에서 복원**한다.

## 5. 로컬 실측 (S15P21A501-320, 2026-09-25)

로컬 운영 복원본(장면 7,860 · OCR 관측 64,648 · 활성 제외 규칙 5 · 대기 후보 2)에서 위 절차를 그대로 돌렸다.

**재생성.** 도구 실행은 표마다 7~8 초(컨테이너 기동 포함)다.

| 컬럼 | 행 | 바뀐 행 | 그중 옛 이중 형식 | 옛 `형태/품사` 토큰을 잃은 행 |
| --- | ---: | ---: | ---: | ---: |
| `caption_tokens` | 7,712 | 2,006 | 1,098 | 0 |
| `transcript_tokens` | 7,608 | 2,444 | 1,104 | 0 |
| `ocr_observation.tokens` | 64,648 | 13,173 | 12,600 | 0 |

바뀌지 않은 행(캡션 5,706 등)은 저장 값과 **글자까지** 같았다 — 생산자 형식을 그대로 재현한다는 확인이다.
다시 추출해 돌리면 세 표 모두 `changed=0` 이다. 가장 많이 더해진 토큰은 `입/vv`(캡션 446)·`받/vv`·`걷/vv`
(캡션 140)·`이렇/va`·`어렵/va` 다.

**옛 질의는 영향이 없다.** 백필만 하고 옛 리졸버(`encpos2`)로 아래 네 질의를 다시 돌리면 후보 수와 상위
10 개 순서가 백필 전과 같았다.

**질의 토큰과 후보.** 후보는 `WordSceneCandidateAdapter` 의 SQL 모양(`term_set` OR, 가중치 1.0, 상한 200)
으로 뽑았다. 「단어 포함」은 캡션 원문에 그 용언의 활용형이 있는 후보 수다.

| 질의 | 질의 토큰 전 → 후 | 후보 수 전 → 후 | 후보 중 단어 포함 전 → 후 | 상위 10 중 단어 포함 전 → 후 |
| --- | --- | ---: | ---: | ---: |
| 빨간 옷 입은 사람 | `옷 사람` → `빨갛/va 옷 입/vv 사람` | 200 → 200 | 0 → 1 | 0 → 0 |
| 사람들이 걷는 모습 | `사람 모습` → `사람 걷/vv 모습` | 200 → 200 | 9 → 22 | 0 → 1 |
| 음악을 듣는 학생 | `음악 학생` → `음악 듣/vv 학생` | 160 → 200 | 1 → 5 | 0 → 0 |
| 파란 하늘 | `하늘` → `파랗/va 하늘` | 71 → 81 | 4 → 13 | 1 → 2 |

토큰이 남는 것까지가 이 변경의 몫이고, 상위 10 은 크게 안 바뀐다. 이유가 둘이다.

- **OR 매칭.** `term_set` 은 토큰 하나만 맞아도 후보다. `사람/nng`·`옷/nng`·`입/vv` 가 흔해 그 셋이 맞은
  장면이 200 칸을 먼저 채운다(정밀도 분류의 3번, salience).
- **색 형용사 대 색 명사.** VLM 캡션은 대부분 「빨간색」(`빨간색/nng`)이라 `빨갛/va` 가 든 캡션은 6 개뿐이다
  (원문에 빨간·빨갛이 든 캡션 16 개). 형태소가 달라 이번 변경으로도 안 맞는다.

**색 묶음(`query-norm/v2`).** 색인은 그대로고(v2 이미지로 재생성을 다시 돌려도 세 표 모두 `changed=0`)
질의 토큰만 늘어난다. 「색 포함」은 캡션 원문에 그 색의 형용사·명사 어느 쪽이든 있는 후보 수다.

| 질의 | -320 전 | `encpos3` (불규칙만) | `v2` (색 묶음) | 후보 수 | 후보 중 색 포함 | 상위 10 중 색 포함 |
| --- | --- | --- | --- | ---: | ---: | ---: |
| 빨간 옷 입은 사람 | `옷 사람` | `+빨갛/va 입/vv` | `+붉/va 빨간색/nng` | 200 / 200 / 200 | 1 / 4 / 17 | 0 / 0 / 1 |
| 파란 하늘 | `하늘` | `+파랗/va` | `+파란색/nng` | 71 / 81 / 140 | 4 / 13 / 69 | 1 / 2 / 2 |
| 하얀 눈 | `눈` | `+하얗/va` | `+희/va 흰색/nng 하얀색/nng` | 139 / 146 / 200 | 4 / 8 / 122 | 0 / 0 / 3 |

후보 풀에는 색이 든 장면이 크게 늘지만 상위 10 은 조금만 바뀐다. OR 매칭이라 `하늘`·`눈`·`옷` 만 맞은
장면도 같은 풀에서 겨룬다. **이 표는 dev 의 `term_set` 모양으로 쟀다** — `term_set` 은 칸마다 상수 점수라
맞은 텀 수가 순위에 거의 안 실린다. BE `candidate-v4`(!239, 토큰별 `term` BM25 점수)에서는 색 토큰이 맞은
장면이 점수를 더 받으므로 상위 10 이 달라질 수 있고, 그 모양으로는 다시 재지 않았다.
`normalized_query` 는 v1 설정과 같다(테스트로 고정).

**재키.** 활성 4 · 대기 후보 2(`아이돌`, `태풍에 날아가는 우산`)가 `query-norm/v2:…:encpos3` 으로 옮겨졌고,
여섯 모두 저장 지문이 이식한 해시로 재현됐다. 새 리졸버로 원 질의를 다시 해석해 BE 조회 조건으로 대조하면
`축구경기` 2 · `백악관` 1 · `다중충돌` 1(활성), `아이돌` 1 · `태풍에 날아가는 우산` 1(대기)이 걸린다. 원 질의가
깨진 활성 규칙 1 개(U+FFFD)는 도구가 `skipped` 로 걸렀다. 로컬에서는 SQL 로 사용 중단했지만 운영에서는
§2 의 API 로 끈다.
