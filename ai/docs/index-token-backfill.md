# 저장 토큰 재생성과 장면 제외 규칙 재키

`korean_tokens.py` 의 색인 토큰 규칙이 바뀌었을 때 **이미 저장된 행**을 새 규칙에 맞추는 절차다.
처음 쓰인 곳은 S15P21A501-320(불규칙 활용 `VV-I`·`VA-I`·`VV-R` 을 버리지 않게 한 변경,
`normalization_version` `…:encpos2` → `…:encpos3`)이다.

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

## 2. 순서

| 단계 | 이유 |
| --- | --- |
| ① 새 워커 이미지를 **빌드만** 한다 | 도구가 새 규칙으로 돌아야 한다. 실행 중인 컨테이너는 그대로 둔다 |
| ② 백업 | 아래 §3 |
| ③ **색인 토큰 백필** | 새 색인은 옛 색인의 `형태/품사` 토큰을 전부 담고 불규칙 용언 토큰이 **더해진** 것이다. 옛 질의(`encpos2`)의 매칭 집합은 줄지 않는다. 먼저 해도 검색이 깨지지 않는다 |
| ④ 재키 CSV 를 **미리 만든다** | 계산은 새 이미지로 한다. 반영 SQL 은 한순간이다 |
| ⑤ **재키 반영 → 곧바로 리졸버(`ai-worker`) 교체** | 재키된 규칙은 `encpos3` 질의에만 걸린다. 반영과 교체 사이가 곧 「장면 제외가 안 걸리는 창」이라 둘을 붙여 실행한다. 교체 중에는 어차피 해석이 멈춘다 |
| ⑥ 파이프라인 워커 교체 | `ai-cpu-worker`(OCR)·GPU 노드(VLM·대사 매핑)도 `korean_tokens` 로 색인 토큰을 만든다. 하나라도 옛 코드면 **새로 처리한 영상이 옛 토큰으로 저장된다** |
| ⑦ **백필을 한 번 더** | ③ 이후 ⑥ 전까지 옛 워커가 저장한 행을 잡는다. 멱등이라 이미 맞는 행은 건드리지 않는다 |

**BE 설정 버전·드리프트 가드.** 재키는 규칙 ID 를 바꾸지 않고, BE 의 `config_version` 에는
`normalization_version` 이 들어 있지 않다. 그래서 `CorrectionStateFingerprint` 가 바뀌지 않고, 배포 **전에**
검증을 마친 `REVIEWING` 신고는 정규화가 바뀐 뒤에도 재검증 없이 확정된다(-282 리뷰 ③ 과 같은 구멍).
배포 공지에 「검수 중인 신고는 배포 뒤 검증을 다시 돌린다」를 넣는다. 코드로 막으려면 BE 가
`normalization_version` 을 설정 버전에 실어야 한다 — 이 문서 범위 밖이다.

**재키 범위.** 아래 추출 SQL 은 `active = true` 규칙만 고른다. 검수 중인 신고의 **대기 후보**
(`active = false`, 신고 `REVIEWING`)도 옛 버전을 들고 있어, 승인되면 활성화되어도 안 걸리고 검증
재검색에서도 제외가 적용되지 않는다. 함께 옮기려면 추출 SQL 의 `WHERE` 에 주석으로 적어 둔 조건을
쓴다. 비활성화된(`CLOSED`) 옛 규칙은 옮기지 않는다.

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
# query-norm/v1:b0d96c0c:kiwi0.23.2:model0.23.0:encpos3
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

패치 파일 하나(기본 5,000 행)를 한 트랜잭션으로 반영한다. `old_tokens` 가 지금 값과 같은 행만 바꾸므로
추출 뒤에 다시 쓰인 행은 건너뛴다 — 다음 회차가 잡는다.

```bash
apply_patch() {  # $1 = 파일, $2 = UPDATE 문
  npsql -1 \
    -c "CREATE TEMP TABLE patch (id bigint PRIMARY KEY, old_tokens text NOT NULL, new_tokens text NOT NULL)" \
    -c "\copy patch FROM pstdin WITH (FORMAT csv, HEADER)" \
    -c "$2" < "$1"
}
for f in "$WORK"/patch/scene_caption.*.csv; do apply_patch "$f" "UPDATE npick.scene s
  SET caption_tokens = p.new_tokens FROM patch p
  WHERE s.scene_id = p.id AND coalesce(s.caption_tokens, '') = p.old_tokens"; done
for f in "$WORK"/patch/scene_transcript.*.csv; do apply_patch "$f" "UPDATE npick.scene s
  SET transcript_tokens = p.new_tokens FROM patch p
  WHERE s.scene_id = p.id AND coalesce(s.transcript_tokens, '') = p.old_tokens"; done
for f in "$WORK"/patch/ocr_observation.*.csv; do apply_patch "$f" "UPDATE npick.ocr_observation o
  SET tokens = p.new_tokens FROM patch p
  WHERE o.ocr_observation_id = p.id AND o.tokens = p.old_tokens"; done
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
  WHERE r.action = 'exclude_scene' AND r.active
  -- 대기 후보도 옮기려면: AND (r.active OR (f.status = 'REVIEWING' AND f.created_rule_id IS NULL))
  ORDER BY r.search_rule_id) TO STDOUT WITH (FORMAT csv, HEADER)" > "$WORK/exclude_rules.csv"

ntool /tools/rekey_scene_exclusions.py --in /work/exclude_rules.csv --out /work/rekey.csv
# stderr: 규칙마다 옛 → 새 normalized_query.  stdout: rules=5 changed=5 unchanged=0
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

로컬 운영 복원본(장면 7,860 · OCR 관측 64,648 · 활성 제외 규칙 5)에서 위 절차를 그대로 돌렸다.

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

**재키.** 활성 규칙 5 개 모두 저장 지문이 이식한 해시로 재현됐고 `…:encpos3` 으로 옮겨졌다. 새 리졸버로
`축구경기`·`백악관`·`다중충돌` 을 해석해 BE 조회 조건으로 대조하면 각각 2·1·1 개가 걸린다.
