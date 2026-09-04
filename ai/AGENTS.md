# N-Pick AI Worker 규약

`ai/` 하위 작업의 진입점.

| 문서 | 내용 |
| --- | --- |
| **[../docs/frd.md](../docs/frd.md)** | **처리 파이프라인 정본.** §5.1 단계·§5.2 상태·§10.8 outbox/lease. 코드를 쓰기 전에 읽는다 |
| [README.md](README.md) | 실행·테스트·의존성 그룹·환경 변수 |
| [docs/scene-detection.md](docs/scene-detection.md) | scene detection 선정 근거·설정 키·Gate B 미동결 항목 |
| **[../docs/frd.md](../docs/frd.md) §6** | **Query Resolver 정본.** 출력 schema·span 검증·fallback. `query_resolver/` 를 고치기 전에 읽는다 |
| [../AGENTS.md](../AGENTS.md) | 저장소 공통 규칙 (커밋/브랜치/문서 템플릿) |

- 커밋 scope 와 브랜치 플랫폼은 `ai` 를 쓴다.
- 외부 모델 호출은 FRD §2.1 의 adapter 경계 뒤에 둔다. 호출부에 provider SDK 를 직접 노출하지 않는다.
- `src/npick_worker/stages.py` 는 FRD §5.1 표의 전사다. 단계 이름·순서·치명 여부를 코드에서 임의로 바꾸지 않는다.
- 단계 구현은 단계 이름과 같은 패키지에 둔다: `src/npick_worker/<stage_name>/`. 순수 함수로 두고 pipeline run 배선은 하지 않는다.
- `query_resolver/` 는 파이프라인 단계가 아니다(FRD §6, 검색 시점). 여기 있는 이유는 프롬프트·schema 가 AI 산출물이기 때문이고, 런타임 배치는 FRD §2.1 상 Search Service 다. fingerprint·override·fallback·snapshot 을 이쪽에 만들지 않는다.
- **Gate B(FRD §15.4) 미동결 수치는 코드에 두지 않는다.** 임계값·최소 길이 같은 값은 `src/npick_worker/config/*.toml` 에만 두고, 그 값들의 해시를 version 으로 노출한다(FR-PRC-015).
- **HTTP 표면은 헬스·운영용이다.** 작업 수신 방식(§10.8 outbox claim)과 BE 호출 인터페이스는 S15P21A501-70 에서 합의한다. 그 전에 API 를 추가하지 않는다.
- `gpu` 그룹(torch·faster-whisper)은 선택 의존성이다. GPU 없이도 워커가 기동하는 성질을 깨지 않는다.

정본과 어긋나는 내용을 발견하면 정본을 따르고 어긋난 지점을 보고한다. 정본에 없는 규칙을 임의로 만들지 않는다.
