# N-Pick Backend 규약

`backend/` 하위 작업의 진입점.

## 코드를 쓰기 전에 읽는다

| 문서 | 내용 |
| --- | --- |
| **[docs/ddd-package-architecture.md](docs/ddd-package-architecture.md)** | **설계 규약 정본** — 실용적 DDD 기반 Spring Boot 패키지 아키텍처 가이드 (패키지 구조, 의존성 방향, Command/Query, UseCase, Port/Adapter, 예외 경계, 테스트 구조, 최종 체크리스트) |
| [README.md](README.md) | 실행·빌드·프로파일·환경 변수 |
| [../AGENTS.md](../AGENTS.md) | 저장소 공통 규칙 (커밋/브랜치/문서 템플릿) |

정본은 Notion 원본의 사본이다. 이 파일이나 다른 문서가 정본과 어긋나면 **정본을 따르고 어긋난 지점을 보고한다.** 정본에 없는 규칙을 임의로 만들지 않는다.

작업을 마치기 전 정본 §19 최종 체크리스트를 확인한다.

## 정본에서 가장 자주 어기는 항목

전체 규칙은 정본에 있다. 아래는 정본 §4·§17에서 발췌한 것이다.

- 최상위 패키지를 `controller` / `service` / `repository` 로 나누지 않는다. **도메인 단위**로 나눈다.
- `domain` 은 Spring, JPA, Feign, HTTP를 모른다. 도메인 모델에 `@Entity` `@Table` `@Column` 을 붙이지 않는다.
- Controller 는 구체 Application Service 가 아니라 **UseCase 인터페이스**에 의존한다.
- 비즈니스 상태 변경은 반드시 Aggregate behavior 를 통과한다.
- Aggregate 조회는 Domain Repository, Projection 조회는 QueryPort 를 쓴다.
- 다른 도메인의 JPA Entity·infrastructure·Repository 구현체에 직접 접근하지 않는다.
- `common` 에 비즈니스 코드를 쌓지 않는다.

## 빈 패키지를 만들지 않는다

정본 §16 에 따라 디렉터리 구조를 맞추기 위한 빈 패키지를 미리 만들지 않는다. 현재 `src/main/java/com/npick` 에는 `NpickApplication.java` 하나뿐이며, 첫 도메인과 `common` 패키지는 실제 코드가 생길 때 만든다.
