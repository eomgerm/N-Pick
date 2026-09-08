# N-Pick AI Worker 규약

`ai/` 하위 작업의 진입점.

| 문서 | 내용 |
| --- | --- |
| **[../docs/frd.md](../docs/frd.md)** | **처리 파이프라인 정본.** F-03~04 처리·§6.2 실패·§11 결정. 코드를 쓰기 전에 읽는다 |
| [README.md](README.md) | 실행·테스트·의존성 그룹·환경 변수 |
| [docs/scene-detection.md](docs/scene-detection.md) | scene detection 선정 근거·설정 키·실측 후 확정 항목 |
| **[../docs/frd.md](../docs/frd.md) F-04~06, §6.2, §11** | **Query Resolver 정본.** 질의 해석·명시 조건 보호·실패 처리. 출력 schema와 span 검증 방식은 모듈 계약. `query_resolver/` 를 고치기 전에 읽는다 |
| [../AGENTS.md](../AGENTS.md) | 저장소 공통 규칙 (커밋/브랜치/문서 템플릿) |

- 커밋 scope 와 브랜치 플랫폼은 `ai` 를 쓴다.
- 외부 모델 호출은 모듈 Protocol 어댑터 경계 뒤에 두고 FRD §6.4의 전송 보호를 따른다. 호출부에 provider SDK 를 직접 노출하지 않는다.
- `src/npick_worker/stages.py`는 기존 단계 선언이다. FRD v3.1과의 파이프라인 정합성 정리는 별도 작업이며 이번 Resolver 리뷰에서 변경하지 않는다.
- 단계 구현은 단계 이름과 같은 패키지에 둔다: `src/npick_worker/<stage_name>/`. 순수 함수로 두고 pipeline run 배선은 하지 않는다.
- `query_resolver/`는 검색 시점 모듈이다(FRD F-05). 배포 경계는 `docs/architecture/02-container.md` 요소 표를 따른다. 여기에는 프롬프트·모델 호출 어댑터·검증을 두며 정규화·규칙 적용·fallback 전환·검색 기록은 호출부 책임이다.
- **실행 설정은 FRD §11에 따라 실측 후 확정한다.** 임계값·timeout은 `src/npick_worker/config/*.toml`에 두고 설정 해시를 버전으로 노출해 §7.2 기록을 지원한다. §8.2 품질 목표는 유지한다.
- **HTTP 표면은 헬스·운영용과 검색 시점 질의 해석뿐이다.** 질의 해석 엔드포인트(`POST /query/resolve`)는 S15P21A501-45 에서 추가했다 — S15P21A501-70 은 파이프라인 워커의 잡 수신 계약이라 검색 시점 모듈과 무관하다. **파이프라인 잡 수신 방식은 여전히 S15P21A501-70 에서 합의하며 그 전에 API 를 추가하지 않는다.**
- `gpu` 그룹(torch·faster-whisper)은 선택 의존성이다. GPU 없이도 워커가 기동하는 성질을 깨지 않는다.

정본과 어긋나는 내용을 발견하면 정본을 따르고 어긋난 지점을 보고한다. 정본에 없는 규칙을 임의로 만들지 않는다.
