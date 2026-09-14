# 임베딩 모델 비교 하네스 (S15P21A501-175)

scene 텍스트 dense embedding 에 쓸 한국어 임베딩 모델을 고르기 위한 측정 도구다.
**워커 런타임이 아니다** — 배포 이미지에 들어가지 않고, `src/npick_worker` 를 import 하지도
않는다(리졸버 fixture 경로 하나만 읽는다).

선정 결과와 해석은 Notion **"임베딩 모델 실측 비교·선정 — S15P21A501-175"** 가 정본이다.
이 문서는 **그 숫자를 어떻게 다시 만드는가**만 다룬다.

## 환경

의존성은 프로젝트 `pyproject.toml` 이 아니라 `requirements.txt` 에 있고 **별도 venv** 를 쓴다.
이유는 그 파일 머리말에 있다(요약: 의존성 그룹은 설치 여부만 가르지만 `uv.lock` 은 패키지당
버전이 하나라, 평가용 mlflow 가 런타임 의존인 protobuf 를 끌어내렸다).

```bash
cd ai
uv venv .venv-eval
uv pip install --python .venv-eval -r eval/embedding/requirements.txt
```

지표 함수의 단위 테스트는 이 venv 가 없어도 된다 — numpy 만 쓰므로 프로젝트 pytest 로 돈다.

```bash
uv run pytest tests/test_embedding_metrics.py
```

## 커밋된 결과를 만든 명령

`results/*.json` 세 개는 아래 그대로 실행해 나온 것이다. **골드셋 자체는 저장소에 없으므로
(자막 원문 포함) 먼저 만들어야 한다.** `<b-roll>` 은 AI-Hub 71699 인덱스(`71699_index.jsonl`)가
있는 디렉터리다.

```bash
cd ai
B=eval/embedding
PY=.venv-eval/bin/python          # Windows 는 .venv-eval/Scripts/python.exe

# 1) 골드셋 3종. seed 고정이라 같은 파일이 나온다.
#    curation 은 저장소에 커밋된 라벨(broll-holiday-curation.json)을 기본으로 쓴다.
$PY $B/build_gold.py --source <b-roll>/data
$PY $B/build_gold.py --source <b-roll>/data --mode hard \
    --category 사건사고뉴스 --corpus-size 40000 --summary-queries 2000 \
    --out $B/gold_hard.json
$PY $B/build_gold.py --source <b-roll>/data --mode broad \
    --corpus-size 50000 --summary-queries 2000 \
    --out $B/gold_broad.json

# 2) 측정. curation 만 차원 스윕을 함께 돌린다(-100 차원 확정 근거).
M="nlpai-lab/KURE-v1 dragonkue/snowflake-arctic-embed-l-v2.0-ko telepix/PIXIE-Rune-v1.5"
export CUDA_DEVICE_ORDER=PCI_BUS_ID CUDA_VISIBLE_DEVICES=0
export MLFLOW_TRACKING_URI=https://j15a501.p.ssafy.io/mlflow
export MLFLOW_TRACKING_USERNAME=npick MLFLOW_TRACKING_PASSWORD=<비밀번호>

$PY $B/embedding_bench.py --gold $B/gold.json --out $B/results/curation.json \
    --dims 256 384 512 768 --dtype float32 --models $M --experiment search-eval-v2-curation
$PY $B/embedding_bench.py --gold $B/gold_hard.json --out $B/results/hard.json \
    --dtype float32 --models $M --experiment search-eval-v2-hard
$PY $B/embedding_bench.py --gold $B/gold_broad.json --out $B/results/broad.json \
    --dtype float32 --models $M --experiment search-eval-v2-broad

# 3) 유의성 검정
$PY $B/compare.py $B/results/hard.json --level summary
```

생성 인자는 결과 파일의 `params` 에도 남는다(`gold_source` `gold_seed` `gold_corpus_size`
`gold_levels` `host_*` `model_revision`). 명령을 잃어버려도 결과에서 되짚을 수 있다.

## 왜 이렇게 재는가

**CPU 스레드를 4로 고정한다.** 질의는 EC2 4 vCPU 동기 경로다(`03-deployment`). GPU 서버는
96코어라 제한 없이 재면 배포 환경보다 낙관적인 숫자가 나온다.

**dtype 을 `--dtype` 으로 통일한다.** 모델 카드의 `torch_dtype` 을 그대로 두면 Qwen 만 bf16 로
로드돼 VRAM 이 38% 적고 지연이 21% 빨라 보인다. 비교가 성립하지 않는다.

**지연 표본은 400(질의 20 x 20회)이다.** 60 표본에서는 같은 모델·같은 host 인데 run 간 p95 가
24% 흔들렸다 — p95 가 사실상 "세 번째로 느린 값"이라 단일 outlier 가 지표가 된 탓이다.
400 으로 늘린 뒤 변동이 0.8~2.4% 로 떨어졌다. **지연을 비교에 쓸 때는 `query_embed_stdev_ms`
를 함께 봐야 한다** — 표준편차가 모델 간 차이보다 크면 그 수치로 순위를 말할 수 없다.

**평균이 아니라 짝지은 검정으로 읽는다.** 모든 모델이 같은 질의를 풀기 때문에 질의별로
짝지어 차이를 구하면 질의 난이도가 상쇄된다. `compare.py` 가 부트스트랩 10,000회로 95%
신뢰구간을 낸다. 구간이 0 을 포함하면 "차이 없음"이 아니라 **"이 표본으로는 검출 못 함"**이다.

## 골드셋 3종과 그 한계

| 골드셋 | 코퍼스 | 질의 | 성격 |
| --- | --- | --- | --- |
| `curation` | 5,537 | 341 (event 19 / category 22 / summary 300) | 사람 큐레이션. 명절 귀성길 |
| `hard` | 40,000 | 2,000 | `사건사고뉴스` 한정 → 오답이 전부 같은 주제 |
| `broad` | 50,000 | 2,000 | 전체 무작위 → 오답이 주제적으로 이질 |

**검색을 어렵게 만드는 것은 코퍼스 크기가 아니라 주제 동질성이다.** 5,537 → 50,000 으로 9배
키웠더니 점수가 오히려 오르고 모델 간 격차는 3분의 1로 줄었다. 그래서 카테고리 한정 `hard`
세트를 따로 만들었다.

숫자를 읽을 때 감안할 것:

1. **단위가 클립이다.** N-Pick 의 단위는 장면이고 클립당 장면이 평균 6.7개다. 모델 간 상대
   순위는 유지되지만 절대 recall 은 장면 단위로 옮겨오지 않는다.
2. **캡션이 없다.** 골드셋 텍스트는 자막 전사뿐인데 우리 임베딩 입력은 캡션 + 대사다.
   입력의 절반만 재고 있다.
3. **`category` 레벨은 모델 변별에 쓸 수 없다.** 22건 중 20건이 장소 코드북 라벨("행사/사무공간
   나오는 자료화면")을 자막에 매칭하는 구조라, 정보가 텍스트에 없다. 전 모델이 0.04~0.11 로
   바닥이다. 미스매치가 없는 축 질의 2건에서는 상위 두 모델이 **둘 다 1.0** 이다.
4. **`summary` 는 천장에 붙어 있다.** 세 모델이 0.946~0.975 에 몰리고 무승부가 95~98% 다.
   1:1 질의라 재방·후속 보도로 자막이 거의 같은 클립이 1위로 오면 정답인데 오답으로 채점된다
   — 절대 수치는 그만큼 과소평가다.
5. **세 골드셋은 독립 반복이 아니다.** hard·broad 가 같은 AI-Hub 71699 에서 나오고 질의 형식도
   같다. "세 세트에서 같은 결과"는 독립 3회 확인이 아니라 한 소스를 세 번 자른 것이다.
6. **색인 처리량(`index_texts_per_s`)은 인용하지 말 것.** 공용 GPU 경합으로 520↔1238 로 2.4배
   진동한다. peak VRAM 은 2355~2357 로 안정적이라 대비가 뚜렷하다. VRAM 도 50~80 토큰짜리
   합성 텍스트로 잰 값이라 긴 자막의 상한이 아니다.

## 골드셋을 커밋하지 않는 이유

골드셋에는 AI-Hub 71699 / KBS 자막 전사가 **원문 그대로** 들어간다. `FRD §6.4` 와
`S15P21A501-134` 가 dataset 권리를 Gate S 조건으로 걸어둔 상태라 저장소에 넣지 않는다.

`.gitignore` 는 **deny-by-default** 다 — `eval/**/*.json` 을 전부 막고 커밋할 것만 연다.
이름 몇 개를 막는 allowlist 였을 때는 `--out gold-hard.json`(하이픈) 하나로 뚫렸다.
권리 게이트를 파일명 철자에 맡길 수 없다.

커밋되는 것은 둘뿐이다:

- `broll-holiday-curation.json` — 클립 ID 와 사건 정의만. 본문 없음. **재생성 불가능한
  유일한 자산**이라 커밋한다(무작위 표본은 seed 만 있으면 다시 뽑힌다).
- `results/*.json` — 질의 인덱스·레벨·점수만. 질의 원문도 자막도 없다.

## 공용 GPU 주의

SSAFY GPU 서버 `jupyter05` 의 device 0 은 다른 팀과 공유된다. 측정 중 남의 작업이 들어와
**OOM 이 3회, 색인 처리량 오염이 1회** 발생했다. 측정 전 `nvidia-smi` 로 비었는지 확인하고,
긴 런은 `setsid nohup` 으로 띄운다(결과는 모델마다 증분 저장되므로 중간에 죽어도 앞부분은
남는다).

드라이버가 CUDA 12.8 이라 torch 기본 휠(cu130)은 GPU 를 못 잡는다 — `requirements.txt` 의
cu128 안내를 따른다.
