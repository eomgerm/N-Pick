<img src="TODO_배너_이미지" alt="N-Pick" width="100%"/>

# N-Pick

> AI 기반 방송 영상 아카이빙 자동화 및 검색 보조 시스템

## 🎬 서비스 소개

<img src="TODO_소개_이미지" alt="N-Pick 소개"/>

- N-Pick(엔픽)은 영상을 올리면 AI가 장면마다 설명과 태그를 붙여 두고, 편집기자가 자연어로 원하는 장면을 찾게 해 주는 아카이빙 서비스예요.
- "태풍에 날아가는 우산"처럼 문장으로 검색하면 영상 전체가 아니라 그 장면 구간이 결과로 나와요.
- 검색 결과가 이상하면 바로 문의를 넣을 수 있고 아카이브 팀이 교정한 내용은 다음 검색부터 반영돼요.
- 편집기자와 아카이브 팀, 두 역할로 나눠 씁니다.

### 프로젝트 기간

`2026.08.31 ~ 2026.09.29`

## 목차

- [📼 4,800분을 봐야 50분이 나온다](#-4800분을-봐야-50분이-나온다)
- [🔎 "경찰 시민" 대신 "길 안내하는 경찰"로 찾기까지](#-경찰-시민-대신-길-안내하는-경찰로-찾기까지)
- [🛠 기술 스택](#-기술-스택)
- [🏗 아키텍처](#-아키텍처)
- [📁 폴더 구조](#-폴더-구조)
- [🗂 ERD](#-erd)
- [📦 실행 방법](#-실행-방법)
- [📖 API 명세](#-api-명세)
- [📺 핵심 기능](#-핵심-기능)
- [👥 팀원](#-팀원)
- [📌 컨벤션](#-컨벤션)

## 📼 4,800분을 봐야 50분이 나온다

「다큐 3일」 50분 한 편을 만들려면 원본 영상 4,800분을 봐야 한다고 합니다. 방영분은 그중 1%뿐이에요. 현직 방송국 편집기자 인터뷰에서도 편집보다 장면을 찾는 데 시간이 더 든다는 이야기를 들었습니다.

방송국에도 검색 시스템은 있습니다. 다만 사람이 영상마다 대상·상황·촬영 방식을 손으로 적어 두고 그 메타데이터를 키워드로만 찾습니다. "경찰이 시민에게 길을 안내하는 장면"을 찾으려고 `경찰 시민`을 넣으면 길 안내와 상관없는 영상이 줄줄이 나옵니다. 키워드는 장면의 맥락을 담지 못하기 때문입니다.

그래서 설명은 AI가 대신 쓰고, 검색은 문장의 뜻까지 보도록 만들기로 했습니다.

## 🔎 "경찰 시민" 대신 "길 안내하는 경찰"로 찾기까지

> 단어 검색과 의미 검색을 한 PostgreSQL 안에서 돌리고, 두 순위를 RRF로 합친 과정입니다.

### [BE] ParadeDB BM25와 pgvector를 RRF로 묶은 하이브리드 검색

- **무엇이 문제였나**
    - 한국어는 조사와 어미 때문에 같은 뜻의 문장도 표면형이 다 다릅니다. 단어 검색만으로는 표현이 조금만 달라도 놓치고, 의미 검색만으로는 "주민들이 대피하는 장면"에 "주민들이 더위를 피하는 장면"이 딸려 옵니다.
    - 검색 완료 시 결과 스냅샷과 실행 상태를 한 트랜잭션에 커밋해야 해서, 검색 엔진을 따로 두면 outbox와 재동기화 작업이 줄줄이 따라옵니다.
- **어떻게 풀었나**
    - 별도 검색 엔진 대신 PostgreSQL 확장 `pg_search`(ParadeDB, Tantivy 기반 BM25)와 `pgvector`를 정본 DB에 같이 올렸습니다.
    - `pg_search`의 `lindera(korean)` 토크나이저는 조사를 떼지 못했습니다. `정체를`로 검색하면 무관한 문서가 `를` 토큰 하나로 정답보다 높은 점수를 받았습니다. 그래서 워커가 Kiwi 형태소 분석 결과를 별도 컬럼에 넣고 `pdb.whitespace`로 색인하도록 바꿨습니다. 색인과 질의가 같은 Kiwi 설정을 써야 해서 이 설정은 `search_version`에 묶었습니다.
    - 두 채널의 순위는 `R(d) = Σ w_c / (k + rank_c)`로 합칩니다(`k = 60`). 정규화 분모는 관측 최고점이 아니라 설정에서 계산합니다. 후보가 적은 질의에서 점수가 부풀어 실행 간 비교가 깨지는 걸 막기 위해서입니다.
- **무엇이 달라졌나**
    - 활성 색인 세대(`generation_id`) 필터가 BM25 Custom Scan 안에서 함께 처리돼 `1.5ms`에 끝납니다. 세대 교체에 alias가 필요 없어졌습니다.
    - 랭킹 계산과 커밋이 한 프로세스, 한 트랜잭션 안에 남았습니다.
- 설계 근거는 [02 Container 문서](docs/architecture/02-container.md#주목할-아키텍처-결정)에 정리했습니다. RRF 산식 커밋은 [d2b56081](https://github.com/eomgerm/N-Pick/commit/d2b56081)입니다.

### [BE] 근거 없는 검색 결과 30.2% 걷어내기

- **무엇이 문제였나**
    - dense 후보의 거리 컷이 `distance <= 2`로 박혀 있었습니다. 코사인 거리의 전 범위라 사실상 필터가 아니었고, BM25가 0건인 질의에서도 결과 10칸이 dense로 다 채워졌습니다.
    - 운영 기록(실행 229건, 결과 2,290행)을 보니 반환 결과의 `30.2%`가 질의 토큰도, 태그 점수도 없는 행이었습니다.
- **어떻게 풀었나**
    - 운영 질의 27개를 직접 임베딩해 거리를 쟀습니다. 정답 영상이 있는 질의의 1위는 `0.45~0.61`, 없는 질의의 1위는 `0.76~0.79`, 무의미한 질의는 `0.83`이었습니다.
    - 그 사이인 `0.75`를 `max-distance` 설정으로 뺐습니다. 유효성 판정에는 걸지 않았는데, 같이 걸면 유사도가 낮은 장면이 손상 벡터로 집계돼 매 검색이 부분 실패로 떨어지기 때문입니다.
- **무엇이 달라졌나**
    - 근거 없는 행의 `72%`가 사라졌습니다.
- 측정 과정 → [1aac9cf3](https://github.com/eomgerm/N-Pick/commit/1aac9cf3)

### [BE] "중국 음식"이 "중국 OR 음식"으로 쪼개지던 문제

- **무엇이 문제였나**
    - LLM이 뽑은 확장어 중 여러 어절짜리가 BM25 조회 직전에 평탄화돼 「중국 음식」이 `중국 OR 음식`으로 걸렸습니다. 두 단어 중 하나만 맞는 장면도 후보가 됐습니다.
- **어떻게 풀었나**
    - 포트가 확장어를 구 단위 묶음(`List<List<String>>`)으로 넘기게 바꾸고, 구 안은 AND, 구 사이는 OR로 걸었습니다. 문서빈도 컷도 함께 넣었다가 구 AND를 부분적으로 되돌리는 걸 발견해 다시 뺐습니다.
- **무엇이 달라졌나**
    - 운영 DB에서 「중국 음식」 오매칭이 `19건 → 0건`이 됐고, 불·비·산 같은 정상 동의어는 그대로 잡힙니다.
- 커밋 → [e3163140](https://github.com/eomgerm/N-Pick/commit/e3163140)

### [AI] Recall@10 `0.17`은 검색 품질이 아니라 배포 사고였다

- **무엇이 문제였나**
    - 검색 설정을 바꿀 때마다 좋아졌는지 잴 수단이 없어서 질의·정답 장면 200쌍을 동결한 회귀 하네스를 만들었습니다.
    - 첫 측정에서 Recall@10이 `0.17`이 나왔습니다. 들여다보니 200건 중 145건이 nginx 502였고 그 시각이 Jenkins 배포로 backend가 교체된 시각과 정확히 겹쳤습니다.
- **어떻게 풀었나**
    - 상태 코드만으로 실패를 가르지 않았습니다. 서버 오류 봉투가 있는 5xx는 0점으로, 봉투 없는 5xx와 연결 실패는 인프라 실패로 따로 셉니다. 인프라 실패가 5회 이어지면 측정을 멈추고 부분 결과를 남깁니다.
- **무엇이 달라졌나**
    - 재측정 결과 Recall@10은 `0.5150`, nDCG@10은 `0.2986`입니다. 적중 103건 중 57건이 1~3위였습니다.
    - 실패 97건을 캡션과 질의의 bigram 겹침으로 갈라 보니 겹침 0 구간 적중률은 `13%`, 25% 이상 구간은 `85%`였습니다. 병목이 랭킹이 아니라 장면 설명이라는 걸 숫자로 확인했습니다.
- 측정 절차와 결과는 [검색 회귀 하네스 문서](ai/eval/search/README.md)에 있습니다.

### [AI] 표기만 다른 같은 질의가 다른 토큰으로 갈리던 문제

- **무엇이 문제였나**
    - "부산시 교통사고"는 `교통사고 부산`으로, "부산 교통사고"는 `교통 부산 사고`로 토큰이 갈렸습니다. 별칭 치환이 형태소 분석 뒤에 일어나 뒤따르는 단어의 분절까지 바꿨기 때문입니다.
    - 기존 테스트 7쌍은 우연히 안정적인 꼬리말만 봐서 CI에 잡히지 않았습니다.
- **어떻게 풀었나**
    - 별칭 34개 × 꼬리말 20개를 전수로 돌려 보고 별칭 값을 Kiwi 사용자 사전에 넣었습니다(`38 → 1`). 남은 1건은 사용자 사전 점수를 `3.0`으로 조정해 없앴습니다.
- **무엇이 달라졌나**
    - 어긋나는 조합이 `38/680 → 0`이 됐습니다. 전수 스윕을 테스트로 남겨 사전을 고칠 때 다시 깨지지 않게 했습니다.
- 커밋 → [216a9793](https://github.com/eomgerm/N-Pick/commit/216a9793)

### [AI] 자막이 1ms 모자라 영상 전체에 음성 인식이 돌던 문제

- **무엇이 문제였나**
    - 자막을 다 붙였는데도 ASR이 영상 전체를 다시 돌았습니다. 백엔드는 자막 종료 시각을 ffprobe 길이(`15181.833ms`)로 검증하고, 파이프라인은 프레임 수로 계산한 길이(`15182ms`)로 커버리지를 쟀기 때문입니다. 15182까지 덮으면 백엔드가 자막을 거절하고, 15181에서 끝내면 마지막 한 칸이 미커버 구간으로 남았습니다.
- **어떻게 풀었나**
    - b-roll 60클립에서 두 길이의 차이를 쟀습니다. 최소 `-0.467ms`, 최대 `+0.500ms`였고 반올림 오차뿐이었습니다. 꼬리 틈은 최대 1ms라 `min_uncovered_ms`를 `0 → 2`로 올렸습니다.
- **무엇이 달라졌나**
    - 전 구간을 덮는 자막이 있으면 ASR이 더 이상 돌지 않습니다.
- 계산 과정 → [36622ecb](https://github.com/eomgerm/N-Pick/commit/36622ecb)

## 🛠 기술 스택

| 역할 | 종류 |
| --- | --- |
| 프런트엔드 | ![Next.js](https://img.shields.io/badge/Next.js_16-000000?style=for-the-badge&logo=nextdotjs&logoColor=white) ![TypeScript](https://img.shields.io/badge/TypeScript-3178C6?style=for-the-badge&logo=typescript&logoColor=white) ![React](https://img.shields.io/badge/React_19-61DAFB?style=for-the-badge&logo=react&logoColor=black) ![Tailwind CSS](https://img.shields.io/badge/Tailwind_CSS-06B6D4?style=for-the-badge&logo=tailwindcss&logoColor=white) ![TanStack Query](https://img.shields.io/badge/TanStack_Query-FF4154?style=for-the-badge&logo=reactquery&logoColor=white) |
| 백엔드 | ![Java](https://img.shields.io/badge/Java_21-007396?style=for-the-badge&logo=openjdk&logoColor=white) ![Spring Boot](https://img.shields.io/badge/Spring_Boot_4-6DB33F?style=for-the-badge&logo=springboot&logoColor=white) ![Flyway](https://img.shields.io/badge/Flyway-CC0200?style=for-the-badge&logo=flyway&logoColor=white) ![Swagger](https://img.shields.io/badge/Swagger-85EA2D?style=for-the-badge&logo=swagger&logoColor=black) |
| AI 파이프라인 | ![Python](https://img.shields.io/badge/Python_3.12-3776AB?style=for-the-badge&logo=python&logoColor=white) ![FastAPI](https://img.shields.io/badge/FastAPI-009688?style=for-the-badge&logo=fastapi&logoColor=white) ![PyTorch](https://img.shields.io/badge/PyTorch-EE4C2C?style=for-the-badge&logo=pytorch&logoColor=white) ![Hugging Face](https://img.shields.io/badge/Hugging_Face-FFD21E?style=for-the-badge&logo=huggingface&logoColor=black) ![Ollama](https://img.shields.io/badge/Ollama-000000?style=for-the-badge&logo=ollama&logoColor=white) |
| 모델 | VLM `Qwen3.5-9B` · ASR `faster-whisper large-v3-turbo` · OCR `RapidOCR(PP-OCRv5)` · 임베딩 `snowflake-arctic-embed-l-v2.0-ko` · NER `KPF-bert-ner` · 형태소 `Kiwi` |
| 데이터베이스·검색 | ![PostgreSQL](https://img.shields.io/badge/PostgreSQL_18-4169E1?style=for-the-badge&logo=postgresql&logoColor=white) ![ParadeDB](https://img.shields.io/badge/ParadeDB_pg__search-000000?style=for-the-badge) ![pgvector](https://img.shields.io/badge/pgvector-555555?style=for-the-badge) |
| 인프라·CI/CD | ![Docker](https://img.shields.io/badge/Docker_Compose-2496ED?style=for-the-badge&logo=docker&logoColor=white) ![Nginx](https://img.shields.io/badge/Nginx-009639?style=for-the-badge&logo=nginx&logoColor=white) ![Jenkins](https://img.shields.io/badge/Jenkins-D24939?style=for-the-badge&logo=jenkins&logoColor=white) ![AWS](https://img.shields.io/badge/AWS_EC2-232F3E?style=for-the-badge) ![RunPod](https://img.shields.io/badge/RunPod_GPU-000000?style=for-the-badge) |
| 평가 | ![MLflow](https://img.shields.io/badge/MLflow-0194E2?style=for-the-badge&logo=mlflow&logoColor=white) |

## 🏗 아키텍처

<img src="TODO_아키텍처_다이어그램" alt="시스템 아키텍처"/>

검색은 p95 10초 안에 끝나야 하는 동기 작업이고, 장면 분석은 클립 하나에 GPU를 몇 분씩 붙잡는 비동기 작업입니다. 그래서 AI 코드는 저장소 하나를 쓰되 질의 리졸버(EC2 상주)와 파이프라인 워커(GPU)로 나눠 배포합니다.

워커는 항상 발신자입니다. 서비스 서버가 연 잡 API를 long-poll로 가져가고 heartbeat로 lease를 갱신하므로, GPU 파드에 인바운드 포트를 열 필요가 없고 파드가 내려가도 잡이 유실되지 않습니다. 정본 쓰기는 권한을 제한한 plpgsql function으로만 합니다.

자세한 구조는 C4 문서 [01 Context](docs/architecture/01-context.md) · [02 Container](docs/architecture/02-container.md) · [03 Deployment](docs/architecture/03-deployment.md)에 있습니다.

## 📁 폴더 구조

`frontend → backend ← ai` — 브라우저는 nginx를 거쳐 서비스 서버만 부르고, AI 워커도 DB가 아니라 서비스 서버의 잡 API만 부릅니다.

<details>
<summary>펼쳐 보기</summary>

```text
├── frontend                  # Next.js 웹 (편집기자 검색 · 아카이브 팀 처리·문의 화면)
│   ├── src/app               # App Router 라우트
│   ├── src/features          # 기능 단위 화면 (search 등)
│   ├── src/lib               # API 클라이언트, 환경변수 경계
│   └── e2e                   # Playwright 시나리오
├── backend                   # Spring Boot 서비스 서버
│   └── src/main/java/com/npick
│       ├── clip              # 영상 등록, 원본·Preview 스트리밍
│       ├── pipeline          # 분석 잡 디스패치 (claim · heartbeat · complete)
│       ├── search            # BM25·dense 후보 조회, RRF 결합, guard
│       ├── tag               # 태그 사전과 근거
│       ├── feedback          # 검색 결과 문의와 교정 규칙
│       ├── member            # 계정·세션 로그인
│       └── common            # 보안·오류·공통 설정
├── ai                        # Python 파이프라인 워커 · 질의 리졸버
│   ├── src/npick_worker
│   │   ├── scene_detection   # 픽셀 변화량으로 장면 분할
│   │   ├── frame_extraction  # 장면별 대표 프레임 추출
│   │   ├── ocr               # 화면 속 글자 인식
│   │   ├── transcript_selection · asr · scene_transcript_mapping   # 자막 매핑, 없으면 음성 인식
│   │   ├── vlm_metadata      # 프레임·글자·대사를 VLM에 넘겨 장면 설명 생성
│   │   ├── entity_extraction # 개체명 태그 추출
│   │   ├── text_embedding    # 장면 임베딩
│   │   ├── query_*           # 질의 정규화·해석·임베딩 (리졸버)
│   │   └── config            # 단계별 버전 설정 (*.v1.toml)
│   ├── docs                  # 단계별 모델 선정 근거와 실측
│   └── eval                  # OCR·임베딩·리졸버·검색 회귀 하네스
├── infra
│   ├── compose/profiles      # media · pipeline · runtime 설정 프로필
│   ├── jenkins               # 빌드·배포·롤백·RunPod 스크립트
│   └── nginx                 # 리버스 프록시 설정
├── docs
│   ├── architecture          # C4 다이어그램 (Context · Container · Deployment)
│   ├── contracts             # web · job · resolver API 계약
│   ├── prd.md · frd.md       # 요구사항 정본
│   └── templates             # 이슈 템플릿
├── compose.yaml              # 로컬·서버 공통 스택
└── scripts                   # 커밋·브랜치 규칙 검사 (lefthook)
```

</details>

## 🗂 ERD

<img src="TODO_ERD_이미지" alt="데이터베이스 ERD"/>

| 테이블 | 담는 것 |
| --- | --- |
| `member` | 편집기자·검수자 계정과 역할 |
| `clip` | 영상 1건과 원본 파일 정보. 검색에 쓸 분석 실행을 가리킵니다 |
| `pipeline_run` | 영상 1건의 분석 실행 1회. 단계 상태와 처리 버전 |
| `scene` | 검색 결과 단위인 시간 구간. 설명·대사·검색 토큰·임베딩 벡터 |
| `keyframe` | 장면의 대표 프레임과 시각 |
| `ocr_observation` | 키프레임에서 읽은 화면 글자 원문과 위치 |
| `tag` · `tagging` · `tag_evidence` | 태그 사전, 클립·장면 연결, 추출 근거와 검수 판단 |
| `search_execution` · `search_result` | 검색 실행 1회의 해석·후보·결과 스냅샷과 최종 순위 |
| `feedback` | 검색 결과 문의와 검수·최종 조치 |
| `search_rule` | 문의에서 만든 장면 제외·해석 교정 규칙 |

## 📦 실행 방법

**사전 준비**: Docker Desktop (Compose v2)

```bash
# 1. 환경변수 파일 준비 — 네 개 모두 있어야 compose 가 뜹니다
cp .env.example .env
cp frontend/.env.example frontend/.env
cp backend/.env.example backend/.env
cp ai/.env.example ai/.env

# 2. .env 의 POSTGRES_PASSWORD, MLFLOW_DB_PASSWORD 를 채웁니다 (base64 말고 hex)
openssl rand -hex 24

# 3. 전체 스택 실행 — 첫 빌드는 5~10분 걸립니다
docker compose up -d --build
docker compose ps
```

| 확인 | URL |
| --- | --- |
| 웹 | http://127.0.0.1:3000 |
| Swagger UI | http://127.0.0.1:8080/swagger-ui/index.html |
| BE health | http://127.0.0.1:8080/actuator/health |
| AI 워커 health | http://127.0.0.1:8000/health |
| MLflow | http://127.0.0.1:5000/mlflow |

GPU 단계(VLM·ASR 등)는 로컬 compose에서 돌지 않습니다. GPU 노드 구성은 [03 Deployment](docs/architecture/03-deployment.md)와 [infra/compose/README.md](infra/compose/README.md)를 참고하세요.

## 📖 API 명세

- [Swagger UI](http://127.0.0.1:8080/swagger-ui/index.html) — `POST /api/v1/auth/login`으로 세션을 받고, 변경 요청에는 `GET /api/v1/auth/csrf`로 받은 토큰을 `X-XSRF-TOKEN` 헤더에 실어 보냅니다.
- 요청·응답 스키마와 오류 코드의 정본은 [docs/contracts](docs/contracts/README.md)입니다.

## 📺 핵심 기능

### 영상 등록과 AI 분석

> 영상과 자막만 올리면 장면 분할부터 설명·태그 생성까지 AI가 처리해요.

<table align="center">
<tr>
  <td><img src="TODO_영상_등록_GIF" alt="영상 등록" width="540"/></td>
  <td><img src="TODO_처리_단계_진행_GIF" alt="처리 단계 진행 표시" width="540"/></td>
</tr>
<tr>
  <td align="center">제목·방송일·자막을 넣고 등록하면 업로드가 시작돼요</td>
  <td align="center">지금 어느 분석 단계인지 화면 아래에서 바로 보여요</td>
</tr>
</table>

### 자연어 장면 검색

> 문장으로 검색하면 영상이 아니라 장면 구간이 나와요.

<table align="center">
<tr>
  <td><img src="TODO_자연어_검색_GIF" alt="자연어 검색" width="540"/></td>
  <td><img src="TODO_장면_다운로드_GIF" alt="장면 Preview와 다운로드" width="540"/></td>
</tr>
<tr>
  <td align="center">"태풍에 날아가는 우산" 검색</td>
  <td align="center">Preview로 확인하고 그 장면만 내려받기</td>
</tr>
</table>

### 문의와 교정

> 엉뚱한 결과는 문의 한 번으로 검색에서 빠지고 반영 전에 미리 확인할 수 있어요.

<table align="center">
<tr>
  <td><img src="TODO_문의_접수_GIF" alt="검색 결과 문의" width="540"/></td>
  <td><img src="TODO_교정_후보_검증_GIF" alt="교정과 후보 검증" width="540"/></td>
</tr>
<tr>
  <td align="center">검색어와 맞지 않는 장면을 문의로 접수</td>
  <td align="center">아카이브 팀이 장면 제외·태그 정리 후, 같은 검색을 미리 돌려 보는 후보 검증</td>
</tr>
</table>

## 👥 팀원

| BE · AI · Infra | BE | BE | FE | AI | BE · Infra |
| :---: | :---: | :---: | :---: | :---: | :---: |
| <img src="https://avatars.githubusercontent.com/u/52905679?v=4" width="120"/> | <img src="https://avatars.githubusercontent.com/u/63864983?v=4" width="120"/> | <img src="https://avatars.githubusercontent.com/u/96732583?v=4" width="120"/> | <img src="https://avatars.githubusercontent.com/u/202645497?v=4" width="120"/> | <img src="https://avatars.githubusercontent.com/u/244402022?v=4" width="120"/> | <img src="https://avatars.githubusercontent.com/u/135516362?v=4" width="120"/> |
| [엄기훈](https://github.com/eomgerm) | [천기오](https://github.com/CheonKiO) | [김용휘](https://github.com/HOKAGO-MEMORIES) | [김윤석](https://github.com/rasegqw) | [최재영](https://github.com/young010514) | [이다인](https://github.com/crolvlee) |

## 📌 컨벤션

커밋과 브랜치 이름은 lefthook이 커밋할 때 검사합니다. 전체 규칙은 [CONTRIBUTING.md](.github/CONTRIBUTING.md)에 있습니다.

<details>
<summary>커밋 · 브랜치 규칙</summary>

### 커밋

- `<:gitmoji:> <type>(<scope>): <설명> (#이슈 번호)`
- 예: `:sparkles: feat(fe): 로그인 페이지 UI 구현 (#123)`
- scope는 `fe` `be` `ai` `infra` 중 하나이고, type 15종과 이모지 매핑은 [scripts/git-rules.cjs](scripts/git-rules.cjs)가 정합니다.

### 브랜치

- `[<플랫폼>/]<type>/<설명-kebab>-<이슈 번호>`
- 예: `fe/feat/login-page-123`
- 브랜치 이름에 이슈 번호가 있으면 커밋 메시지에 자동으로 붙습니다.

</details>

<!-- 남은 작업
  - [ ] TODO_배너_이미지 — 1200x600
  - [ ] TODO_소개_이미지
  - [ ] TODO_아키텍처_다이어그램
  - [ ] TODO_ERD_이미지
  - [ ] TODO_영상_등록_GIF · TODO_처리_단계_진행_GIF — 540px, 10초 이내
  - [ ] TODO_자연어_검색_GIF · TODO_장면_다운로드_GIF
  - [ ] TODO_문의_접수_GIF · TODO_교정_후보_검증_GIF
  - [ ] 팀원 파트 확인 (커밋 scope 기준으로 적음), 담당 업무 행 추가
-->
