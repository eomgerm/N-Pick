# text embedding 단계 산출 지표 (S15P21A501-100)

`src/npick_worker/text_embedding/` 이 **실제로 낸 벡터**로 지표를 낸다. 단계 구현 문서는
[../../docs/text-embedding.md](../../docs/text-embedding.md) 다.

## `eval/embedding/` 과 무엇이 다른가

`eval/embedding/`(S15P21A501-175)과 묻는 질문이 다르다. 둘 다 필요하다.

**그 디렉터리는 아직 이 저장소에 없다** — `ai/feat/embedding-model-benchmark-S15P21A501-175`
브랜치(MR !62)에만 있고 dev 에 머지되지 않았다. 아래 참조는 그 브랜치 기준이다.

| | `eval/embedding/` (-175) | **여기** (-100) |
| --- | --- | --- |
| 묻는 것 | 어느 **모델**이 좋은가 | 그 모델을 **파이프라인에 태워도** 성능이 보존되는가 |
| 호출 | `model.encode` 직접 | **`embed_scenes()`** — 조립·skip·정규화·검증을 거친다 |
| `npick_worker` import | 하지 않는다 | **한다** (`PYTHONPATH=src`) |
| 산출 | 모델 비교표 | 단계 산출 지표 + MLflow run |

-175 는 모델을 고르고 끝났다. 그 뒤로 `compose_text()` 의 캡션·대사 조립, 텍스트 없는
장면의 skip 판정, L2 정규화, 차원·0벡터·NaN 검증이 사이에 끼어들었다. **그것들이 검색
품질을 깎지 않는지는 -175 가 답할 수 없는 질문이다.** 같은 골드셋·같은 모델을 쓰므로
ndcg 가 -175 수치에서 벗어나면 그 차이는 전부 파이프라인이 만든 것이다.

## 돌리기

```bash
# 드라이버가 CUDA 12.8 이면 cu128, 13+ 면 cu130 (README.md 의 설치 표)
uv sync --group gpu --group cu128
uv pip install mlflow==3.16.0     # lock 밖. 아래 "왜 lock 에 없는가" 참고

export MLFLOW_TRACKING_URI=https://j15a501.p.ssafy.io/mlflow
export MLFLOW_TRACKING_USERNAME=... MLFLOW_TRACKING_PASSWORD=...
export CUDA_DEVICE_ORDER=PCI_BUS_ID CUDA_VISIBLE_DEVICES=0
export NPICK_AI_EMBEDDING_MODEL=dragonkue/snowflake-arctic-embed-l-v2.0-ko
export NPICK_AI_DEVICE=cuda PYTHONPATH=src

.venv/bin/python eval/text_embedding/measure.py \
    --gold <골드셋>.json --run-name emit-gold-arctic
```

`--limit N` 으로 문서 수를 줄여 빠르게 확인할 수 있고 `--no-mlflow` 로 기록을 끌 수 있다.

**골드셋은 저장소에 없다.** AI-Hub 71699·KBS 자막 전사 원문이 들어가고 FRD §6.4 와
`S15P21A501-134` 가 dataset 권리를 Gate S 조건으로 걸어 두었다 — -175 가 같은 이유로
제외했다. 만드는 절차는 -175 브랜치의 `eval/embedding/README.md` 와 `build_gold.py` 이고
(`git show ai/feat/embedding-model-benchmark-S15P21A501-175:ai/eval/embedding/README.md`),
SSAFY GPU 서버에는 `~/npick/eval/embedding/gold{,_hard,_broad}.json` 으로 이미 있다.

### 왜 mlflow 가 lock 에 없는가

-175 가 겪은 사고다. `mlflow-skinny → opentelemetry-proto → protobuf` 가 lock 전체의
protobuf 를 `7.36.1 → 6.33.6` 으로 내렸고, 그 protobuf 를 런타임 의존인 onnxruntime
(rapidocr, OCR 단계)이 쓴다. 배포 이미지는 `uv sync --no-dev` 라 mlflow 를 안 받지만
**내려간 protobuf 는 받는다.** 평가 도구가 제품 런타임의 버전을 움직이는 경로다.

그래서 mlflow 는 `uv pip install` 로 **lock 밖에서** 넣는다. 그 venv 안의 protobuf 가
내려가도 lock 과 배포 이미지는 그대로이고, 이 스크립트는 onnxruntime 을 임포트하지 않아
측정도 깨지지 않는다.

**venv 자체는 lock 에서 파생시킨다.** `-175` 의 `requirements.txt` 는 비주석 4줄
(`mlflow` `sentence-transformers` `torch` `numpy`)로 **측정에 쓴 조합을 고정하는 것**이
목적이고 그 자체로 타당하다. 다만 목록에 없는 패키지(`pydantic` 등)는 해상도 시점에 따라
정해지므로 운영과 같다는 보장이 없다. lock 에서 파생시키면 그 축이 통째로 사라진다.

**대신 이 방식은 -175 의 "프로젝트 venv 와 섞지 않는다" 를 뒤집는다.** mlflow 가 끌어내린
protobuf 가 lock 이 아니라 **개발자 작업 venv** 에 남고, 그 venv 에는 `onnxruntime`·
`rapidocr` 가 함께 있다(기본 그룹). 로컬 OCR 테스트를 같은 venv 에서 돌린다면
`uv venv .venv-eval` 을 만들고 거기에 lock 파생 설치를 하는 쪽이 안전하다 — 목표(lock 파생)와
격리를 둘 다 지킨다. 또 `uv sync` 를 다시 돌리면 mlflow 가 prune 되므로 그때 다시 넣는다.

## 무엇을 재는가

네 갈래다. 품질만 보면 파이프라인이 깨져도 모르고, 운영만 보면 품질 퇴행을 놓친다.

| 갈래 | 지표 | 왜 |
| --- | --- | --- |
| 품질(라벨 필요) | `ndcg_at_10`·`recall_at_10`·`mrr` | **-175 수치와 대조**하는 것이 목적. 모델을 다시 고르려는 것이 아니다. 셋 다 **@10** 이다 — `mrr` 도 상위 10 만 보고 11 위의 정답은 0 으로 센다(-175 와 같은 정의) |
| 내재(라벨 불필요) | `rankme`·`uniformity`·`mean_pairwise_cosine` | 배선 후 운영에서 **골드셋 없이** 감시할 수 있는 값 |
| 운영 | 처리량·단건 지연·`vram_peak_mb`·`skip_ratio` | -175 는 **질의측** 지연만 쟀다. 문서측은 배치라 다르다 |
| 입력 형상 | `token_len_*`·`truncated_scenes` | 잘림이 있으면 `source_text` 에 남는 전문과 실제 임베딩된 것이 달라진다(FRD §7.2) |

내재 지표의 근거 논문은 `measure.py` 의 각 함수 docstring 에 있다 — RankMe(Garrido et al.,
ICML 2023), uniformity(Wang & Isola, ICML 2020), 이방성(Ethayarajh, EMNLP 2019).

### 레벨을 섞어 평균 내지 않는다

골드셋 질의는 `summary`·`category`·`event` 세 레벨이고 **-175 비교표는 summary 만**의
값이다. 섞으면 -175 가 "구조적으로 무효" 로 판정한 category(22건 중 20건이 화면을 설명하는
장소 라벨을 자막에 매칭하는 구조)와 dense 가 원래 못 잡는 event 가 함께 들어가 값이
내려간다. 대표값 `ndcg_at_10` 은 summary 이고 레벨별 값은 `ndcg_at_10_<level>` 로 따로 남는다.

`hard`·`broad` 는 전부 summary 라 이 구분이 드러나지 않는다. 처음 측정에서 curation 만
0.098 벌어졌던 것이 이것 때문이었다.

## 실측 (2026-09-14)

`torch 2.11.0+cu128` · `NVIDIA L40S` · `sentence-transformers 5.7.0` ·
`dragonkue/snowflake-arctic-embed-l-v2.0-ko@55ec6e9358a56d56af759bc8372e970caf8c305f` ·
`config text-embedding/v1:5ade8628`

MLflow experiment `stage-text-embedding-v1`.

### 파이프라인이 모델 성능을 보존한다

| 골드셋 | 문서/질의 | **-100 (summary)** | -175 | 차이 |
| --- | --- | --- | --- | --- |
| curation | 5,537 / 300 | 0.9507 | 0.9519 | −0.0012 |
| hard | 40,000 / 2,000 | 0.9602 | 0.9602 | 0.0000 |
| broad | 50,000 / 2,000 | 0.9738 | 0.9742 | −0.0004 |

조립·skip·정규화·검증을 거쳐도 `model.encode` 직접 호출과 같은 검색 품질이 나온다.

### 내재 지표

| | curation | hard | broad |
| --- | --- | --- | --- |
| `rankme` / 1024 | 534 (52%) | 548 (53%) | 564 (55%) |
| `uniformity` | −3.26 | −3.25 | −3.43 |
| `mean_pairwise_cosine` | 0.168 | 0.174 | 0.129 |

코퍼스가 9배 늘어도 RankMe 가 52~55% 로 거의 움직이지 않는다 — 표본 수가 아니라 표현
자체의 성질이라는 뜻이다. 평균 코사인도 1 에서 충분히 멀다. **차원 1024 가 낭비라는 근거는
나오지 않았다.**

### 운영·입력 형상

운영 수치는 **워밍업 후** 값이다. `shared_encoder()` 가 인스턴스만 만들고 가중치는 첫
`encode` 에서 올라가므로, 워밍업 없이 `embed_scenes` 를 재면 1.7GB 로딩과 CUDA 컨텍스트
초기화가 처리량에 통째로 들어간다.

| | curation | hard | broad |
| --- | --- | --- | --- |
| 처리량 (scene/s) | 300 | 288 | 291 |
| 단건 p50 / p95 (ms) | 10.9 / 14.7 | 10.9 / 12.5 | 10.8 / 11.9 |
| `vram_peak_mb` | 2,526 | 3,572 | 2,804 |
| `mrr`(@10) | 0.9397 | 0.9518 | 0.9680 |

처리량이 코퍼스 크기와 무관하게 290 전후로 모이는 것이 **워밍업이 실제로 듣고 있다는
신호**다. 워밍업 전에는 5,537 건 코퍼스가 162/s 로 나왔는데, 가중치 로딩 시간이 분모에
들어가 작은 코퍼스일수록 크게 깎였기 때문이다.
| 토큰 p50 / p95 / max | 100 / 205 / 504 | 98 / 212 / **1,945** | 96 / 212 / 896 |
| `truncated_scenes` | 0 | 0 | 0 |

상한 8,192 에 대해 95,537 건 중 최대가 1,945 다. **-175 의 "장면 텍스트가 짧아
`max_seq_length` 는 실제로 걸리지 않는다" 가 실데이터로 확인됐다.**

### FR-SRH-002 가 재현된다

curation 의 `event` 레벨(방송일로만 갈리는 사건) `ndcg_at_10 = 0.1208`. -175 가 전 모델
0.115~0.125 로 측정한 범위 안이다. dense 가 날짜를 못 보는 것이 정상이고, **그 성질이
파이프라인을 거쳐도 그대로**라는 것이 여기서 확인된다.

## 모델을 바꾸면 확인할 것

이 스크립트가 **어댑터 경계를 넘지 않으려고** 근사한 값이 둘이다. 현재 모델에서는 실제와
같지만 모델을 갈면 조용히 어긋난다.

| 값 | 여기서 쓰는 것 | 실제 | 현재 모델 |
| --- | --- | --- | --- |
| 잘림 기준 | `tokenizer.model_max_length` | ST 는 `model.max_seq_length` 로 자른다 | 둘 다 8192 |
| 질의 접두 | `"query: "` 하드코딩 | 모델 카드 `prompts.query` (-175 는 이걸 읽는다) | 둘 다 `"query: "` |

tokenizer 가 길이를 선언하지 않으면 `model_max_length` 가 `VERY_LARGE_INTEGER`(≈1e30)라
`truncated_scenes` 가 영원히 0 이 된다 — 이 스크립트의 목적 하나가 무력화되므로 param 에
찍힌 `max_seq_length` 를 함께 본다. 접두 비대칭은 -175 가 "오류 없이 품질만 떨어지는" 최대
운영 리스크로 지목한 자리다.

## 이 수치의 한계

- **캡션이 없다.** 골드셋 `note` 가 적은 그대로 텍스트가 자막 전사뿐이라
  `SceneText(caption="", dialogue=(text,))` 로 넣었다. **임베딩 입력의 절반만 잰 것이고**
  `skip_ratio = 0` 도 그래서 나온 값이다. 실제 파이프라인에서는 VLM 이 비치명이라 skip 이
  발생한다. 캡션을 넣은 장면 단위 골드셋은 -175 의 재평가 조건이다.
- **운영 수치는 이 하드웨어의 것이다.** L40S · `torch 2.11.0+cu128` 에서 잰 값이고 RunPod
  파드를 대표하지 않는다. `03-deployment.md` 가 "성능 수치에 GPU 모델을 반드시 기록한다 —
  남기지 않으면 그 수치는 재현 불가능하고 합격 근거로 쓸 수 없다" 로 요구하므로
  `host_gpu`·`torch_version`·`torch_cuda` 를 run params 에 싣는다.
- **품질 지표는 모델 선정 근거가 아니다.** 그것은 -175 의 몫이고 통계 판정(부트스트랩
  신뢰구간·본페로니 보정)도 거기 `compare.py` 에 있다.
