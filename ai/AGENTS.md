# N-Pick AI Worker 규약

`ai/` 하위 작업의 진입점.

| 문서 | 내용 |
| --- | --- |
| **[../docs/frd.md](../docs/frd.md)** | **처리 파이프라인 정본.** F-03 장면 분석·F-14 재처리와 복구. 코드를 쓰기 전에 읽는다 |
| [../docs/contracts/job-api.md](../docs/contracts/job-api.md) | **BE↔워커 잡 API 정본.** 엔드포인트·결과 봉투·오류 코드·버전 규약 |
| [README.md](README.md) | 실행·테스트·의존성 그룹·환경 변수 |
| [docs/scene-detection.md](docs/scene-detection.md) | scene detection 선정 근거·설정 키·Gate B 미동결 항목 |
| [../AGENTS.md](../AGENTS.md) | 저장소 공통 규칙 (커밋/브랜치/문서 템플릿) |

- 커밋 scope 와 브랜치 플랫폼은 `ai` 를 쓴다.
- 외부 모델 호출은 FRD §2.1 의 adapter 경계 뒤에 둔다. 호출부에 provider SDK 를 직접 노출하지 않는다.
- `src/npick_worker/stages.py` 는 FRD §5.1 표의 전사다. 단계 이름·순서·치명 여부를 코드에서 임의로 바꾸지 않는다.
- 단계 구현은 단계 이름과 같은 패키지에 둔다: `src/npick_worker/<stage_name>/`. 순수 함수로 두고 pipeline run 배선은 하지 않는다.
- **Gate B(FRD §15.4) 미동결 수치는 코드에 두지 않는다.** 임계값·최소 길이 같은 값은 `src/npick_worker/config/*.toml` 에만 두고, 그 값들의 해시를 version 으로 노출한다(FR-PRC-015).
- **HTTP 표면은 헬스·운영용이다.** 잡 수신은 반대 방향이다 — 워커가 BE 의 claim/heartbeat/complete/artifacts 를 호출한다. 계약 정본은 [../docs/contracts/job-api.md](../docs/contracts/job-api.md) 다. **인바운드 잡 엔드포인트를 추가하지 않는다.**
- **`ai/` 는 배포 단위 둘을 담는다.** 질의 리졸버(동기 호출 전용·재시도 없음)와 파이프라인 워커(long-poll). `stages.py`·`<stage_name>/`·`jobs/` 는 워커의 것이고 리졸버 코드는 자기 패키지에 둔다. 둘이 공유하는 것은 `versioning.py` 의 버전 형식뿐이다.
- `gpu` 그룹(torch·faster-whisper)은 선택 의존성이다. GPU 없이도 워커가 기동하는 성질을 깨지 않는다.

정본과 어긋나는 내용을 발견하면 정본을 따르고 어긋난 지점을 보고한다. 정본에 없는 규칙을 임의로 만들지 않는다.
