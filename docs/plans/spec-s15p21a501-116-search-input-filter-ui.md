---
title: "S15P21A501-116 검색 입력·명시 필터 UI"
type: "feature"
created: "2026-09-11"
status: "done"
review_loop_iteration: 0
baseline_commit: "91aea01d76aa8c95f7bd80c802db9be3f519429b"
context:
  - "{project-root}/frontend/AGENTS.md"
  - "{project-root}/frontend/docs/architecture.md"
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** `/search`는 검색어만 받고 날짜 필터는 결과 화면에만 있다. 연속 제출을 즉시 잠그지 않아 같은 탐색이 중복될 여지도 있다.

**Approach:** 기존 날짜 picker와 URL 계약을 진입 화면에서도 재사용한다. 한 URL 생성 경계가 검색어·방송일·촬영일을 분리해 직렬화하고, 즉시 잠금과 navigation transition이 중복 제출과 이전 결과 노출을 막는다.

## Boundaries & Constraints

**Always:** 검색어는 trim 후 빈 값을 거부한다. 방송일·촬영일은 독립적인 양 끝 포함 범위이며 `q`, `broadcastFrom/To`, `filmingFrom/To`로 보존한다. 이동 중 모든 검색 조작을 잠그고 한국어 상태를 live region으로 알린다.

**Ask First:** 실제 `POST /search`, 새 필터·URL key, 새 패키지·전역 상태, 검색 기록 UI 변경이 필요하면 먼저 확인한다.

**Never:** 필터를 검색어에 합치거나 resolver 추정값으로 취급하지 않는다. 날짜 의미를 복사하거나 잘못된 범위를 전송하지 않는다. 클라이언트 재필터링·정렬 및 `S15P21A501-167` API 연동은 제외한다.

## I/O & Edge-Case Matrix

| Scenario    | Input / State              | Expected Output / Behavior    | Error Handling          |
| ----------- | -------------------------- | ----------------------------- | ----------------------- |
| 기본 검색   | `  명절 교통  `            | 결과 URL에 trim한 `q`만 기록  | 빈 검색어는 이동 안 함  |
| 필터 검색   | 유효한 방송일·촬영일       | 네 날짜 key를 `q`와 별도 기록 | 검색어에 합치지 않음    |
| 잘못된 날짜 | 한쪽 누락·역순·비실재 날짜 | picker가 적용 거부            | 기존 확정값 유지        |
| 연속 제출   | 더블 클릭·Enter 반복       | 최초 navigation 한 번만 실행  | 완료 전 조작 잠금       |
| 결과 재검색 | 기존 카드 표시 중          | 카드를 숨기고 `검색 중` 표시  | query·필터 유지         |
| URL 재진입  | query와 날짜 key 존재      | 입력과 두 범위 복원           | 잘못된 범위는 전체 기간 |

</frozen-after-approval>

## Code Map

- `frontend/src/features/wireframes/search-entry-shell.tsx` -- 최초 검색 form.
- `frontend/src/features/wireframes/wireframe-shell.tsx` -- 결과 재검색·로딩·URL 상태.
- `frontend/src/features/wireframes/date-range-picker.tsx` -- 기존 접근 가능 날짜 picker.
- `frontend/src/features/wireframes/date-range.ts` -- 날짜 검증·복원.
- `frontend/src/features/wireframes/entry.module.css` -- 진입 화면 반응형 스타일.
- `frontend/src/features/wireframes/wireframe-shell.test.mjs` -- 결과 상태 회귀.

## Tasks & Acceptance

**Execution:**

- [x] `frontend/src/features/wireframes/search-navigation.ts`, `search-navigation.test.mjs` -- trim·날짜 검증·URL escaping을 담당하는 순수 생성기와 경계 테스트를 추가한다.
- [x] `frontend/src/features/wireframes/search-entry-shell.tsx` -- 두 날짜 picker, 즉시 제출 잠금, disabled·`aria-busy`·live 안내를 연결한다.
- [x] `frontend/src/features/wireframes/wireframe-shell.tsx` -- 공용 URL 생성기와 즉시 잠금을 적용하고 로딩 중 기존 카드를 숨긴다.
- [x] `frontend/src/features/wireframes/entry.module.css` -- 필터와 pending 상태를 기존 신한 화면·좁은 화면에 맞춘다.
- [x] `frontend/src/features/wireframes/search-entry-shell.test.mjs`, `wireframe-shell.test.mjs` -- 필터 접근 이름과 로딩/결과 분리를 회귀 검증한다.
- [x] `frontend/docs/architecture.md` -- `/search` 필터 소유권, URL 전달, 중복 방지 경계를 갱신한다.

**Acceptance Criteria:**

- Given 유효한 검색어·날짜가 있을 때, when 제출하면, then 다섯 URL 필드가 한 번 전달되고 결과 화면에서 복원된다.
- Given 빈 검색어일 때, when 버튼 또는 Enter로 제출하면, then navigation이 시작되지 않는다.
- Given 이동 중일 때, when 재제출·필터 조작하면, then 추가 이동 없이 잠기고 `검색 중`이 텍스트와 live region에 표시된다.
- Given 결과가 보일 때, when 재검색하면, then 이전 카드를 즉시 숨기고 조건을 보존한 로딩 상태를 표시한다.
- Given 키보드 사용자일 때, when 입력·기간 선택·초기화·제출하면, then visible focus와 논리적 순서로 완료할 수 있다.

## Spec Change Log

## Design Notes

이번 작업은 URL을 검색 요청 상태의 정본으로 만든다. 후속 `#167` adapter가 같은 key를 request DTO에 매핑하며, 로컬 UI 상태는 각 form이 소유한다.

## Verification

**Commands:**

- `npm test` -- URL·날짜·검색 화면 회귀 통과.
- `npm run check` -- format, lint, typecheck, build 통과.

**Manual checks:**

- 필터 없음·각 날짜·두 날짜 조합의 URL과 복원을 확인한다.
- 더블 클릭·Enter 반복 및 느린 전환에서 단일 navigation과 로딩 대체를 확인한다.
- 날짜 dialog의 키보드 선택·초기화·취소·초점 복귀를 확인한다.

## Suggested Review Order

**검색 진입과 URL 경계**

- 검색어·날짜·즉시 잠금을 한 제출 경계에서 연결한다.
  [`search-entry-shell.tsx:45`](../../frontend/src/features/wireframes/search-entry-shell.tsx#L45)

- 검색어와 두 날짜 범위를 검증해 단일 결과 URL을 만든다.
  [`search-navigation.ts:22`](../../frontend/src/features/wireframes/search-navigation.ts#L22)

- 같은 조건 재이동을 순서와 무관하게 차단한다.
  [`search-navigation.ts:36`](../../frontend/src/features/wireframes/search-navigation.ts#L36)

- 날짜 입력 Enter가 바깥 검색 form을 제출하지 않게 한다.
  [`search-entry-shell.tsx:131`](../../frontend/src/features/wireframes/search-entry-shell.tsx#L131)

**결과 전환**

- 이동 중 기존 카드를 로딩 상태로 즉시 대체한다.
  [`wireframe-shell.tsx:82`](../../frontend/src/features/wireframes/wireframe-shell.tsx#L82)

- 결과 재검색도 같은 URL·잠금 경계를 재사용한다.
  [`wireframe-shell.tsx:105`](../../frontend/src/features/wireframes/wireframe-shell.tsx#L105)

**표현과 정본**

- 데스크톱·좁은 화면에 맞춘 필터 레이아웃을 제공한다.
  [`entry.module.css:435`](../../frontend/src/features/wireframes/entry.module.css#L435)

- 검색 상태 소유권과 후속 API 경계를 문서화한다.
  [`architecture.md:109`](../../frontend/docs/architecture.md#L109)

**검증과 후속 작업**

- URL escaping·날짜·동일 목적지 경계를 검증한다.
  [`search-navigation.test.mjs:28`](../../frontend/src/features/wireframes/search-navigation.test.mjs#L28)

- 진입 화면의 접근 가능한 검색·필터 구조를 검증한다.
  [`search-entry-shell.test.mjs:65`](../../frontend/src/features/wireframes/search-entry-shell.test.mjs#L65)

- 결과 URL에서 두 날짜 범위를 독립 복원한다.
  [`wireframe-shell.test.mjs:111`](../../frontend/src/features/wireframes/wireframe-shell.test.mjs#L111)

- 기존 결과 화면의 별도 UX 결함을 후속 항목으로 남긴다.
  [`deferred-work.md:1`](deferred-work.md#L1)
