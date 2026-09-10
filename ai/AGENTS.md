# N-Pick AI Worker 규약

`ai/` 하위 작업의 진입점.

| 문서 | 내용 |
| --- | --- |
| **[../docs/frd.md](../docs/frd.md)** | **처리 파이프라인 정본.** F-03~04 처리·F-14 재처리와 복구·§6.2 실패·§11 결정. 코드를 쓰기 전에 읽는다 |
| [../docs/contracts/job-api.md](../docs/contracts/job-api.md) | **BE↔워커 잡 API 정본.** 엔드포인트·결과 봉투·오류 코드·버전 규약 |
| [README.md](README.md) | 실행·테스트·의존성 그룹·환경 변수 |
| [docs/scene-detection.md](docs/scene-detection.md) | scene detection 선정 근거·설정 키·실측 후 확정 항목 |
| [docs/frame-extraction.md](docs/frame-extraction.md) | frame extraction 선정 근거·대표 이미지 규약·인코딩 실측·설정 키 |
| **[../docs/frd.md](../docs/frd.md) F-04~06, §6.2, §11** | **Query Resolver 정본.** 질의 해석·명시 조건 보호·실패 처리. 출력 schema와 span 검증 방식은 모듈 계약. `query_resolver/` 를 고치기 전에 읽는다 |
| [../AGENTS.md](../AGENTS.md) | 저장소 공통 규칙 (커밋/브랜치/문서 템플릿) |

- 커밋 scope 와 브랜치 플랫폼은 `ai` 를 쓴다.
- 외부 모델 호출은 모듈 Protocol 어댑터 경계 뒤에 두고 FRD §6.4의 전송 보호를 따른다. 호출부에 provider SDK 를 직접 노출하지 않는다.
- `src/npick_worker/stages.py`는 기존 단계 선언이다. FRD v3.1과의 파이프라인 정합성 정리는 별도 작업이며 이번 Resolver 리뷰에서 변경하지 않는다.
- 단계 구현은 단계 이름과 같은 패키지에 둔다: `src/npick_worker/<stage_name>/`. 순수 함수로 두고 pipeline run 배선은 하지 않는다.
- `query_resolver/`는 검색 시점 모듈이다(FRD F-05). 배포 경계는 `docs/architecture/02-container.md` 요소 표를 따른다. 여기에는 프롬프트·모델 호출 어댑터·검증을 두며 정규화·규칙 적용·fallback 전환·검색 기록은 호출부 책임이다.
- **실행 설정은 FRD §11에 따라 실측 후 확정한다.** 임계값·timeout은 `src/npick_worker/config/*.toml`에 두고 설정 해시를 버전으로 노출해 §7.2 기록을 지원한다. §8.2 품질 목표는 유지한다.
- **HTTP 표면은 헬스·운영용과 검색 시점 질의 해석뿐이다.** 잡 수신은 반대 방향이다 — 워커가 BE 의 claim/heartbeat/complete/artifacts 를 호출한다. 계약 정본은 [../docs/contracts/job-api.md](../docs/contracts/job-api.md) 다. **인바운드 잡 엔드포인트를 추가하지 않는다.** 질의 해석(`POST /query/resolve`, S15P21A501-45)은 잡 수신이 아니라 검색의 동기 호출이므로 이 금지에 걸리지 않는다.
- **`ai/` 는 배포 단위 둘을 담는다.** 질의 리졸버(동기 호출 전용·재시도 없음)와 파이프라인 워커(long-poll). `stages.py`·`<stage_name>/`·`jobs/` 는 워커의 것이고 리졸버 코드는 자기 패키지에 둔다. 둘이 공유하는 것은 `versioning.py` 의 버전 형식뿐이다.
- `gpu` 그룹(torch·faster-whisper)은 선택 의존성이다. GPU 없이도 워커가 기동하는 성질을 깨지 않는다.

정본과 어긋나는 내용을 발견하면 정본을 따르고 어긋난 지점을 보고한다. 정본에 없는 규칙을 임의로 만들지 않는다.
