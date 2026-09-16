# N-Pick AI Worker 규약

`ai/` 하위 작업의 진입점.

| 문서 | 내용 |
| --- | --- |
| **[../docs/frd.md](../docs/frd.md)** | **처리 파이프라인 정본.** F-03~04 처리·F-14 재처리와 복구·§6.2 실패·§11 결정. 코드를 쓰기 전에 읽는다 |
| [../docs/contracts/job-api.md](../docs/contracts/job-api.md) | **BE↔워커 잡 API 정본.** 엔드포인트·결과 봉투·오류 코드·버전 규약 |
| [README.md](README.md) | 실행·테스트·의존성 그룹·환경 변수 |
| [docs/scene-detection.md](docs/scene-detection.md) | scene detection 선정 근거·설정 키·실측 후 확정 항목 |
| [docs/frame-extraction.md](docs/frame-extraction.md) | frame extraction 선정 근거·대표 이미지 규약·인코딩 실측·설정 키 |
| [docs/vlm-metadata.md](docs/vlm-metadata.md) | VLM 장면 metadata — 출력 계약·어휘·거부 규칙·외부 처리 게이트. **Qwen3.5-9B 선정, 4B 대체; 실측 비교는 §9.6** |
| [docs/ocr.md](docs/ocr.md) | OCR 엔진 선정·frame 간 병합과 원본 보존·임계값 실측·설정 키 |
| [docs/asr.md](docs/asr.md) | ASR — 엔진 경계·VAD 가 실행기 안인 이유·빈 결과/실패/미실행 구분·설정 키. **임계값과 모델 크기는 미측정(§5)** |
| [docs/text-embedding.md](docs/text-embedding.md) | scene dense 벡터 — 입력이 캡션+대사인 이유·모델 교체 층·재현 식별자 네 축. **모델은 `S15P21A501-175` 가 확정** |
| **[../docs/frd.md](../docs/frd.md) F-04~06, §6.2, §11** | **Query Resolver 정본.** 질의 해석·명시 조건 보호·실패 처리. 출력 schema와 span 검증 방식은 모듈 계약. `query_resolver/` 를 고치기 전에 읽는다 |
| [eval/query_resolver/README.md](eval/query_resolver/README.md) | Query Resolver 모델 비교 하네스 — 골드셋 200문항·지표 정의·유의성 판정·라벨 한계. **프롬프트나 모델을 바꾸면 여기로 회귀를 잰다** |
| [../AGENTS.md](../AGENTS.md) | 저장소 공통 규칙 (커밋/브랜치/문서 템플릿) |

- 커밋 scope 와 브랜치 플랫폼은 `ai` 를 쓴다.
- 외부 모델 호출은 모듈 Protocol 어댑터 경계 뒤에 두고 FRD §6.4의 전송 보호를 따른다. 호출부에 provider SDK 를 직접 노출하지 않는다.
- `src/npick_worker/stages.py`는 기존 단계 선언이다. FRD v3.1과의 파이프라인 정합성 정리는 별도 작업이며 이번 Resolver 리뷰에서 변경하지 않는다.
- 단계 구현은 단계 이름과 같은 패키지에 둔다: `src/npick_worker/<stage_name>/`. 순수 함수로 두고 pipeline run 배선은 하지 않는다.
- `query_resolver/`는 검색 시점 모듈이다(FRD F-05). 배포 경계는 `docs/architecture/02-container.md` 요소 표를 따른다. 여기에는 프롬프트·모델 호출 어댑터·검증을 두며 정규화·규칙 적용·fallback 전환·검색 기록은 호출부 책임이다.
- **실행 설정은 FRD §11에 따라 실측 후 확정한다.** 임계값·timeout은 `src/npick_worker/config/*.toml`에 두고 설정 해시를 버전으로 노출해 §7.2 기록을 지원한다. §8.2 품질 목표는 유지한다.
- **HTTP 표면은 헬스·운영용과 검색 시점 질의 해석뿐이다.** 잡 수신은 반대 방향이다 — 워커가 BE 의 claim/heartbeat/complete/artifacts 를 호출한다. 계약 정본은 [../docs/contracts/job-api.md](../docs/contracts/job-api.md) 다. **인바운드 잡 엔드포인트를 추가하지 않는다.** 질의 해석(`POST /query/resolve`, S15P21A501-45)은 잡 수신이 아니라 검색의 동기 호출이므로 이 금지에 걸리지 않는다.
- **`ai/` 는 배포 단위 둘을 담는다.** 질의 리졸버(동기 호출 전용·재시도 없음)와 파이프라인 워커(long-poll). `stages.py`·`<stage_name>/`·`jobs/` 는 워커의 것이고 리졸버 코드는 자기 패키지에 둔다. 둘이 공유하는 것은 `versioning.py` 의 버전 형식과 `korean_tokens.py` 의 색인 토큰 규칙뿐이다. **후자는 공유가 요구다** — `docs/architecture/02-container.md` 가 색인과 질의에 동일한 Kiwi 설정을 요구하고, 어긋나면 검색이 0 건이 된다. 새 공유 모듈을 이 둘 밖으로 늘리지 않는다.
- `gpu` 그룹(faster-whisper·transformers·sentence-transformers)은 선택 의존성이다. GPU 없이도 워커가 기동하는 성질을 깨지 않는다.
- **torch 는 `gpu` 가 아니라 `cu128`/`cu130` 에 있고 둘은 배타다.** PyTorch 휠이 빌드된 CUDA 이상의 드라이버를 요구해서다 — RunPod 파드는 `cu130`, 드라이버가 12.8 인 SSAFY GPU 서버는 `cu128` 이다(`docs/architecture/03-deployment.md` 의 GPU 노드 둘). `uv sync --group gpu --group cu130` 처럼 **반드시 짝지어** 쓴다. `--group gpu` 만 주면 PyPI torch 가 끌려온다(Windows 는 CPU 전용, **Linux 는 CUDA 13 번들**이라 12.8 드라이버에서 GPU 를 못 잡는다). 설치 표는 [README.md](README.md) 다.

정본과 어긋나는 내용을 발견하면 정본을 따르고 어긋난 지점을 보고한다. 정본에 없는 규칙을 임의로 만들지 않는다.
