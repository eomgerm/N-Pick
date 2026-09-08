# Frontend Architecture Decision Records

이 디렉터리는 프론트엔드의 중요한 기술·구조 선택과 그 이유를 ADR(Architecture Decision Record)로 기록합니다. 현재 적용 중인 구조는 상위 [`architecture.md`](../architecture.md)에서 확인합니다.

## ADR을 작성하는 경우

다음 중 하나에 해당하면 ADR을 작성합니다.

- 새로운 상태관리, form, validation, UI 또는 테스트 라이브러리를 도입할 때
- route, feature, 공통 계층의 책임이나 의존 방향을 바꿀 때
- 인증, cache, 오류 처리처럼 여러 기능의 데이터 흐름을 바꿀 때
- 되돌리는 비용이 크거나 나중에 선택 이유를 다시 확인할 가능성이 높을 때

파일명, 작은 코드 스타일과 한 기능 내부의 구현 세부사항은 ADR 대상이 아닙니다.

## 파일명

```text
NNNN-short-kebab-title.md
```

- 번호는 4자리 순번으로 증가시킵니다.
- 제목은 결정 내용을 나타내는 짧은 영문 kebab-case를 사용합니다.
- 승인된 ADR 파일은 과거 기록 보존을 위해 삭제하거나 내용을 다시 쓰지 않습니다. 결정이 바뀌면 새 ADR에서 이전 ADR을 대체합니다.

## 상태

| 상태       | 의미                              |
| ---------- | --------------------------------- |
| Proposed   | 검토 중이며 아직 코드 기준이 아님 |
| Accepted   | 승인되어 현재 적용하는 결정       |
| Superseded | 새로운 ADR로 대체된 결정          |
| Rejected   | 검토했지만 채택하지 않은 결정     |

## 목록

| ADR                                                   | 상태     | 결정                                   |
| ----------------------------------------------------- | -------- | -------------------------------------- |
| [0001](0001-use-app-router-and-feature-boundaries.md) | Accepted | App Router와 기능 단위 경계 사용       |
| [0002](0002-backend-session-auth.md)                  | Accepted | 백엔드 세션 인증과 TanStack Query 사용 |

## 작성 형식

```md
# NNNN. 결정 제목

- 상태: Proposed
- 날짜: YYYY-MM-DD
- 대체: 없음

## 배경

어떤 문제와 제약 때문에 결정이 필요한지 작성합니다.

## 결정

선택한 방식을 명확하게 작성합니다.

## 이유

주요 대안과 비교해 이 선택을 한 이유를 작성합니다.

## 영향

좋아지는 점, 비용, 제약과 후속 작업을 함께 작성합니다.
```
