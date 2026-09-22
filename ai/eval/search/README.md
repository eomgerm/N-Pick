# 검색 회귀 하네스 (S15P21A501-301)

검색 설정을 바꿨을 때 좋아졌는지 나빠졌는지 잴 수단이다. `application.yml` 의 검색 설정
주석 대부분이 "Gate B 실측 후 정한다"로 미뤄져 있고(S15P21A501-278 1단계), 그 Gate B 가
여기다.

**워커 런타임이 아니다** — 배포 이미지에 들어가지 않는다(기존 3종 하네스와 같은 원칙).
`eval/query_resolver`·`eval/ocr` 와 달리 `npick_worker` 를 import 하지도 않는다. 재는
대상이 BE 의 검색 경로 전체(해석 → 후보 → 랭킹 → guard)라 HTTP 바깥에서 부를 수 있는
자리가 없다.

| 파일 | 하는 일 |
| --- | --- |
| `gold.json` | (질의, 정답 장면) 200쌍. **동결돼 있다** |
| `search_metrics.py` | Recall@10 · nDCG@10 · 통과군/실패군 분할. 순수 함수 |
| `search_bench.py` | `POST /search` 를 돌려 측정하고 `results/` 에 남긴다 |
| `results/` | 측정 결과. 통과군·실패군 목록이 들어 있다 |

## 재현

### 지표 단위 테스트

외부 의존이 없어 프로젝트 기본 venv 로 돈다. 계정도 DB 도 필요 없다.

```bash
uv run --directory ai pytest tests/test_search_eval_metrics.py
```

### 1차 측정

자격증명은 **환경변수로만** 받는다. 기본값도 예시값도 코드에 두지 않는다 — 측정자가
직접 채운다. 계정은 `EDITOR` 또는 `REVIEWER` 면 된다(`/api/v1/search/**` 는 인증 필요).

```bash
export NPICK_BASE_URL=https://<호스트>/api/v1
export NPICK_LOGIN_ID=<측정용 계정>
export NPICK_PASSWORD=<비밀번호>

uv run --directory ai python eval/search/search_bench.py run \
    --out eval/search/results/baseline.json --label baseline
```

`--limit 5` 로 연결만 먼저 확인할 수 있다. `--gold` 로 다른 골드셋을, `--k` 로 다른
절단점을 줄 수 있지만 **k 를 바꾼 결과는 서로 비교하지 않는다** — 10 은 `POST /search`
한 페이지의 크기다(`docs/contracts/web-api.md` §5.1).

직렬로 돈다. 문항마다 해석 LLM 호출이 한 번씩이라 200문항이 몇 분 걸린다.

### 두 설정 비교

```bash
uv run --directory ai python eval/search/search_bench.py compare \
    eval/search/results/a.json eval/search/results/b.json
```

부트스트랩은 `eval/embedding/compare.py` 의 `paired_bootstrap` 을 **그대로 가져다 쓴다**.
모든 설정이 같은 질의를 풀므로 질의별로 짝지어 차이를 구하면 질의 난이도가 상쇄된다.
`goldHash` 가 다르면 거부한다 — 골드셋이 섞이면 짝이 깨진다.

## 지표

| 이름 | 정의 |
| --- | --- |
| `recallAt10` | 정답 장면이 상위 10에 있으면 1. 있거나 없거나다 |
| `ndcgAt10` | 있었다면 몇 번째였는가. 이진 관련성·단일 정답이라 `1/log2(rank+1)` |
| `hits` | Recall 이 1인 문항 수 |
| `byTier.core` / `byTier.hard` | 같은 지표를 난이도별로 |

**두 값을 같이 읽는다.** Recall 만 보면 1위로 올린 개선과 10위로 겨우 밀어 넣은 개선이
같아 보이고, nDCG 만 보면 "몇 건이나 아예 못 찾는가"를 못 본다.

**실패한 호출도 채점한다.** `SRCH_400_101`(검색할 수 있는 단어 없음)이든 타임아웃이든
결과 0건과 같이 센다. 살아남은 응답만 세면 많이 실패한 설정이 좋아 보인다.

## 정답셋

`gold.json` — 활성 장면 120개, 질의 200문항. **저장소에 커밋한다.**

임베딩 하네스의 골드셋은 AI-Hub/KBS 자막 원문이 들어가 `FRD §6.4`·`S15P21A501-134` 의
권리 게이트에 걸렸지만 이 파일에는 그런 것이 없다. 담는 것은 **키프레임을 보고 새로 쓴
질의 문장**과 `scene_id`·`clip_id`·`storage_key` 경로뿐이다. 캡션도 자막도 대사도
들어가지 않고 **키프레임 이미지도 넣지 않는다**. `ai/.gitignore` 의 deny-by-default 는
유지하고 이 이름 하나만 열었다.

### 어떻게 만들었는가 — 순서가 곧 타당성이다

1. **표본.** 활성 장면에서 클립당 최대 2장면, 고정 seed 로 120장면. 아래 SQL 그대로다.
2. **키프레임.** 장면별 대표 키프레임(최소 `keyframe_id`) 한 장을 로컬로 내려받았다.
3. **질의.** **그 이미지만 보고** 썼다. 장면당 1~2문항.
4. **동결.** `gold.json` 을 커밋했다. **1차 측정보다 앞선 커밋이다.**
5. 그 뒤에야 검색을 돌렸다.

3번이 이 하네스의 전부다. `scene.caption` 을 보고 질의를 지으면 캡션 검색이 이기는
순환논증이 되고 측정이 무의미해진다. 질의를 다 쓸 때까지 `caption`·`caption_tokens`·
`tag`·`ocr_observation`·`search_execution` 을 한 번도 조회하지 않았다. 4번은 결과를 보고
질의를 고르는 것을 **순서로** 막는 장치다 — 선언이 아니라 커밋 순서가 증거다.

### 표본 SQL

```sql
with active as (
  select s.scene_id, s.clip_id
  from scene s
  join clip c on c.clip_id = s.clip_id
  where c.active_pipeline_run_id = s.pipeline_run_id and c.deleted_at is null
),
ranked as (
  select scene_id, clip_id,
         row_number() over (partition by clip_id
                            order by md5('S15P21A501-301:' || scene_id)) as rn
  from active
)
select r.scene_id, r.clip_id, k.keyframe_id, k.storage_key
from ranked r
join lateral (
  select keyframe_id, storage_key from keyframe
  where scene_id = r.scene_id order by keyframe_id limit 1
) k on true
where r.rn <= 2
order by md5('S15P21A501-301:' || r.scene_id)
limit 120;
```

`setseed` 를 쓰지 않았다. 해시 순서라 실행 시점·플랜과 무관하게 같은 표본이 나온다.
키프레임 실물은 `docker volume npick-media` 의 `/srv/npick/media` 아래에 있고
`storage_key` 가 그 루트 기준 상대 경로다. **읽기만 한다.**

### 라벨 정책

- **정답은 장면 하나다.** 질의 하나에 정답 장면 하나가 붙는다.
- `tier` 는 **결과가 아니라 키프레임만 보고** 정했다. `core` 는 화면에 그 장면을
  특정하는 글자(자막·간판·현판)나 뚜렷한 사물이 있는 것, `hard` 는 글자가 없고 시각적
  구성으로만 구분되는 B-roll 이다. 120장면 중 core 52 · hard 68, 질의로는 88 · 112.
- `rationale` 은 **키프레임에서 본 것**이다. 캡션 문구가 아니다.
- `labeledBy` 에 모델명과 관찰 조건을 적었다(`eval/ocr/cases.json` 규약). `humanReviewed`
  는 전부 `false` 다 — 사람 검수 전이다.
- `corpusSnapshot` 을 남긴다. 코퍼스가 계속 커져서 뒤의 측정은 분모가 다르다.

### consistency filtering 을 걸지 않는다

Promptagator·InPars 가 "생성한 질의로 검색해서 원문이 안 돌아오면 버린다"를 쓰는 곳은
**학습 데이터**다. 평가셋에 걸면 지금 시스템이 이미 맞히는 질의만 남아 **올라갈 수 없는
셋**이 된다 — 측정하려던 실패가 정확히 삭제된다.

그래서 필터가 아니라 **분할선**으로 쓴다. `split()` 이 통과군(회귀 감시용)과
실패군(개선 목표)으로 가르고 `results/*.json` 에 **둘 다** 남는다. 실패군이 다음
작업의 입력이라 목록에 질의 원문까지 담는다.

## 이 숫자의 한계

1. **라벨을 사람이 달지 않았다.** 모델이 키프레임을 보고 썼다(`labeledBy`). 사람이
   같은 그림을 보고 칠 말과 다를 수 있다. **설정 A·B 의 상대 비교가 절대 점수보다
   훨씬 믿을 만하다** — 같은 골드셋으로 채점하므로 라벨 편향이 양쪽에 같이 걸린다.
2. **대표 키프레임 한 장만 봤다.** 장면 전체가 아니다. 장면 안에서 그림이 바뀌면
   질의가 장면의 일부만 가리킨다.
3. **같은 클립의 이웃 장면이 정답을 가로챌 수 있다.** 클립당 2장면까지 뽑았는데,
   그중 몇 쌍은 그림이 거의 같다(같은 인터뷰의 앞뒤 구간, 같은 접사의 다른 프레임).
   정답이 하나뿐인 채점에서 형제 장면이 1위로 오면 **검색이 맞게 찾았는데 오답으로
   센다.** 실패군을 읽을 때 `returned` 의 `clipId` 가 정답과 같은지 먼저 본다.
4. **질의가 합성이다.** 운영 검색 기록은 정답셋 원천으로 못 썼다 — 전부 팀 QA
   트래픽이고 `asdfasdgad` 류가 섞여 있다(`feedback` 도 같은 이유). 실제 편집기자의
   문장 분포와 다를 수 있다.
5. **코퍼스가 작고 계속 큰다.** `corpusSnapshot` 기준 활성 장면 1,029개다. 후보가
   적으면 상위 10에 들기 쉬워 **점수가 낙관적**이고, 장면이 늘면 같은 설정에서도
   점수가 내려간다. `asOf` 가 다른 결과끼리 절대값을 비교하지 않는다.
6. **200문항에서 검출되는 차이에는 하한이 있다.** `compare` 의 구간이 0 을 포함하면
   "차이 없음"이 아니라 **"이 표본으로는 검출 못 함"** 이다.
7. **한 페이지만 본다.** 더보기를 부르지 않으므로 11위 이하는 전부 같은 실패다.
   11위와 500위를 구분하지 못한다.
8. **날짜 필터를 쓰지 않는다.** `explicit_filters` 를 항상 비워 보낸다. 날짜 해석
   경로는 이 하네스가 재지 않는다.
