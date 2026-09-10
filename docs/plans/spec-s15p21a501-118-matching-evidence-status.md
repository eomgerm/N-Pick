---
title: 'S15P21A501-118 매칭 근거·검증 상태 표시'
type: 'feature'
created: '2026-09-10'
status: 'done'
review_loop_iteration: 0
baseline_commit: '30f9890bc48dde4553922be78b209b8fb8edb655'
context:
  - 'docs/prd.md'
  - 'docs/frd.md'
  - 'frontend/docs/architecture.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** 결과 카드는 제목·키워드·일치도만 보여 주므로 사용자가 어떤 필드의 어떤 값이 질의와 맞았는지, 그 값을 얼마나 신뢰할 수 있는지 판단할 수 없다. 정보 없음과 미검증도 카드에서 구분되지 않는다.

**Approach:** 기존 mock 결과의 근거를 필드·값·출처·검증 상태로 명시적으로 모델링하고 카드와 Preview에 같은 한국어 상태 문구로 표시한다. 기존 촬영일 상태를 활용해 `미상`, `미검증`, `검증됨`을 텍스트 배지로 구분하며 검색 해석은 사실이 아니라 `검색 해석`으로만 표현한다.

## Boundaries & Constraints

**Always:** `docs/prd.md` v6과 `docs/frd.md` v3.1을 Jira의 구버전 Notion 링크보다 우선한다. 값·출처·상태를 별도 필드로 유지하고 상태 문자열을 출처 텍스트에서 파싱하지 않는다. 상태는 아이콘·색상과 함께 반드시 한국어 텍스트로 제공한다. 자동 추출 근거는 `verified/unverified`, 정보 부재는 `unknown`, 사람 판단의 반려만 `rejected`로 취급한다. 117에서 확정한 카드 선택·Preview·Top 10·간소화된 메타데이터 범위를 보존한다.

**Ask First:** 백엔드 검색 응답 DTO를 확정하거나 실제 API를 연결하는 변경, 새로운 전역 상태/패키지/이미지 자산, 형제 이슈 119의 degraded 경고나 사람 교정 적용 표시까지 확장하는 변경.

**Never:** 미상·미검증 값을 충돌이나 제외 사유로 취급하지 않는다. AI 신뢰도만으로 검증 상태를 올리지 않는다. `rejected` 근거를 질의와 일치한 검색 신호라고 표시하지 않는다. 화면에 Event 정본이 있는 것처럼 사건명을 확정 사실로 표시하지 않는다.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
| --- | --- | --- | --- |
| 검증 근거 | OCR/대사/태그의 필드·값·출처와 `verified` | 카드와 Preview에 동일한 값, 출처, `검증됨` 표시 | 색상 없이도 의미가 읽힘 |
| 미검증 근거 | 값은 있으나 `unverified` | 결과를 유지하고 `미검증` 표시 | 검증된 사실처럼 표현하지 않음 |
| 정보 없음 | 촬영일 값 `null`, 상태 `unknown` | `촬영일 미상` 표시 | 날짜나 출처를 만들어 내지 않음 |
| 미검증 날짜 | 촬영일 값 존재, 상태 `unverified` | `촬영일 미검증` 표시 | 날짜 필터에서 검증된 충돌로 제외하지 않음 |
| 사람 반려 | 사람 판단 상태 `rejected` | 공통 상태 문구는 `반려됨`; 일치 근거로는 사용하지 않음 | 자동 근거 상태와 혼합하지 않음 |

</frozen-after-approval>

## Code Map

- `frontend/src/features/wireframes/demo-scenes.ts` -- 검색 근거와 검증 상태의 mock 응답 모델·표시 문구 원천.
- `frontend/src/features/wireframes/search-result-card.tsx` -- 카드 안의 필드·값·출처와 텍스트 상태 배지.
- `frontend/src/features/wireframes/scene-dialogs.tsx` -- Preview에서도 같은 근거·상태 의미 유지.
- `frontend/src/features/wireframes/wireframe.module.css` -- 근거 영역과 상태별 비색상 구분·반응형 배치.
- `frontend/src/features/wireframes/demo-scenes.test.mjs` -- 상태 모델과 기존 mock 계약 회귀 방지.
- `frontend/src/features/wireframes/search-result-card.test.mjs` -- 카드의 근거·미상·미검증·검증 텍스트와 접근성 검증.
- `frontend/docs/architecture.md` -- 결과 카드의 근거·검증 표시 책임 기록.

## Tasks & Acceptance

**Execution:**

- [x] `frontend/src/features/wireframes/demo-scenes.ts` -- 출처 문자열에 섞인 상태를 분리하고 근거 field/value/source/status 및 상태 라벨을 타입으로 모델링한다.
- [x] `frontend/src/features/wireframes/search-result-card.tsx` -- 일치 근거와 정보 상태를 텍스트 기반으로 표시하되 기존 카드 선택 오버레이와 접근성 이름을 보존한다.
- [x] `frontend/src/features/wireframes/scene-dialogs.tsx` -- Preview 근거와 날짜 상태를 같은 라벨 규칙으로 렌더링한다.
- [x] `frontend/src/features/wireframes/wireframe.module.css` -- 긴 값이 줄바꿈되고 3열·2열·1열 카드에서 겹치지 않는 근거/상태 레이아웃을 추가한다.
- [x] 관련 테스트와 `frontend/docs/architecture.md`를 갱신해 데이터 계약과 화면 책임을 고정한다.

**Acceptance Criteria:**

- Given 기본 Top 10 결과가 보일 때, when 서로 다른 카드를 확인하면, then 어떤 필드·값·출처가 일치했는지와 검증 상태를 카드에서 바로 읽을 수 있다.
- Given 검증됨·미검증·미상 예시가 함께 있을 때, when 색상을 구분하지 못하거나 스크린 리더로 읽으면, then 세 상태가 각각의 한국어 텍스트로 구분된다.
- Given 카드를 열어 Preview로 진입할 때, when 근거와 날짜 상태를 확인하면, then 카드와 동일한 출처·검증 의미가 유지되고 검색 해석은 확정 사실로 표시되지 않는다.
- Given 기존 카드 선택·날짜 필터·정렬·문의 흐름을 사용할 때, when 자동 테스트와 빌드를 실행하면, then 117의 동작과 nullable-safe 필터 계약이 회귀하지 않는다.

## Spec Change Log

## Design Notes

기본 카드에는 일치 근거 1건을 `필드 / 값 / 출처 / 상태`로 요약한다. 촬영일의 `unknown/unverified/verified`는 값과 분리된 텍스트 배지로 제공한다. `rejected`는 사람 판단의 표현 계약만 지원하며 기본 일치 근거 fixture로 만들지 않는다.

## Verification

**Commands:**

- `npm --prefix frontend test` -- 기존 및 신규 근거·상태 테스트 전체 통과.
- `npm --prefix frontend run check` -- format check, lint, typecheck, production build 통과.
- `git diff --check` -- 공백 오류 없음.

**Manual checks:**

- 기본 결과 화면에서 검증됨·미검증·미상 문구, 긴 근거 줄바꿈, 카드 클릭/Enter/Space Preview 진입과 닫기 후 포커스 복귀를 확인한다.
- 1440px, 900px, 700px에서 근거 영역이 카드 선택 오버레이·상태 배지와 겹치지 않는지 확인한다.

**Result:** 2026-09-10 기준 테스트 95/95와 format/lint/typecheck/production build가 통과했다. 격리 브라우저에서 카드 10개와 근거 10개, `검증됨` 4건·`미검증` 6건, 촬영일 `검증됨/미검증/미상`, 마우스·Enter·Space Preview 진입과 포커스 복귀를 확인했다. 1440/900/700px에서 3/2/1열이며 페이지·근거·배지의 가로 넘침이 없었고 개발·프로덕션 브라우저 warning/error도 없었다. 상세 기록은 `S15P21A501-118-e2e.md`에 남겼다.
