---
title: 'S15P21A501-117 결과 카드 목록'
type: feature
created: 2026-09-09
status: done
review_loop_iteration: 0
baseline_commit: 59e051f47bfb90fb388b1329c2608a716cdcd43a
context: 'Jira S15P21A501-117, docs/prd.md, docs/frd.md'
---

# S15P21A501-117 결과 카드 목록

## Frozen Intent

### Problem

검색 결과 화면의 인라인 mock 카드가 3개뿐이고 필드도 일부만 노출한다. Jira 117과 `docs/prd.md`, `docs/frd.md`가 요구하는 Top 10 장면 카드의 식별 정보, 두 날짜, 유형, 구간 정보를 한눈에 확인할 수 없으며 날짜가 없는 데이터의 안전한 표시도 없다.

### Approach

기존 wireframe 검색 흐름과 Preview 다이얼로그 연결을 유지하면서 재사용 가능한 결과 카드 컴포넌트를 만든다. 공유 mock을 10개 장면으로 확장하고 카드 표시 모델에 순위, 화면 표시명, nullable 방영일/촬영일, 장면 유형을 추가한다. 날짜 필터와 최신순 정렬을 nullable-safe하게 보정하고 정적 렌더·fixture·날짜 유틸 테스트로 계약을 고정한다.

### Boundaries

#### Always

- `docs/prd.md`와 `docs/frd.md`를 제품/기능 정본으로 사용한다.
- 기존 결과 ID 1~3, 제목, 타임코드, 미디어/evidence 값은 검색 이력·문의·Preview 회귀 방지를 위해 보존한다.
- 카드 선택 시 기존 `ScenePreviewDialog`를 열고 키보드 Enter/Space로도 같은 동작을 제공한다.
- 날짜가 없으면 카드에서 정확히 `미상`으로 표시한다.
- 카드에는 순위, 썸네일, 표시명, 방영일, 촬영일, 샷 유형, 장면 유형, 시작/종료 타임코드를 노출한다.

#### Ask First

- 새 패키지나 이미지 자산 추가, 서버 DTO 확정, 형제 이슈(118·119·120·122·167) 기능까지 범위를 넓혀야 하는 경우

#### Never

- 매칭 근거/검증 상태 UI, 저하 경고, 결과 부족 사유, 실제 영상 재생, 실 API 연동을 구현하지 않는다.
- 확정되지 않은 전역 장면 유형 enum이나 저장 스키마를 새로 정의하지 않는다.
- 사용자 작업 또는 현재 브랜치와 무관한 파일을 수정하지 않는다.

### Inputs and Outputs

| 구분 | 입력                                  | 출력                                        |
| ---- | ------------------------------------- | ------------------------------------------- |
| 목록 | `SearchResult[]`, 검색/기간/정렬 상태 | 최대 10개 결과 카드 그리드                  |
| 카드 | 장면 결과, 선택 여부, 선택 callback   | 필수 메타데이터와 접근 가능한 선택 동작     |
| 날짜 | ISO 날짜 또는 `null`                  | 표시 시 `미상`, 필터/정렬 시 예외 없는 처리 |
| 선택 | 클릭 또는 Enter/Space                 | 기존 Preview 다이얼로그에 선택 장면 전달    |

## Code Map

| 파일                                                      | 변경                                               |
| --------------------------------------------------------- | -------------------------------------------------- |
| `frontend/src/features/wireframes/demo-scenes.ts`         | `SearchResult` 표시 필드와 10개 mock 장면 추가     |
| `frontend/src/features/wireframes/search-result-card.tsx` | 결과 카드 컴포넌트 신설                            |
| `frontend/src/features/wireframes/wireframe-shell.tsx`    | 인라인 카드 제거, 새 카드 사용, nullable 정렬 적용 |
| `frontend/src/features/wireframes/scene-dialogs.tsx`      | `filmedDate` 명명 변경에 맞춰 Preview 입력 갱신    |
| `frontend/src/features/wireframes/date-range.ts`          | nullable 날짜 필터 안전성 보강                     |
| `frontend/src/features/wireframes/wireframe.module.css`   | 카드 목록/메타데이터/포커스/반응형 스타일 갱신     |
| `frontend/src/features/wireframes/*.test.mjs`             | fixture, 날짜, 카드 정적 렌더 계약 검증            |
| `frontend/docs/architecture.md`                           | 재사용 결과 카드와 mock 데이터 역할 기록           |

## Tasks and Acceptance

### Execution Checklist

- [x] `SearchResult`에서 `filmingDate`를 정본 용어 `filmedDate`로 정리하고 순위·표시명·장면 유형·nullable 날짜를 모델링한다.
- [x] 기존 3개 결과를 보존하면서 고유 ID/순위 1~10과 유효한 시작·종료 구간을 가진 mock 10개를 구성한다.
- [x] 결과 카드 컴포넌트를 추출하고 모든 필수 정보를 렌더한다.
- [x] 선택/키보드/포커스/선택 상태를 접근 가능하게 제공하고 기존 Preview를 연다.
- [x] nullable 날짜가 검색 기간 필터와 최신순 정렬을 깨뜨리지 않게 한다.
- [x] 관련 테스트와 프런트엔드 아키텍처 문서를 갱신한다.

### Acceptance Criteria

#### AC1 — Top 10 목록

Given 기본 검색 결과 화면이 열렸을 때  
When 결과 목록을 확인하면  
Then 고유 ID와 순위 1~10을 가진 장면 카드 10개가 렌더되고 각 종료 시각은 시작 시각보다 늦다.

#### AC2 — 카드 필수 정보

Given 결과 카드가 렌더될 때  
When 사용자가 카드 내용을 확인하면  
Then 썸네일, 표시명, 방영일, 촬영일, 샷 유형, 장면 유형, 시작/종료 타임코드가 보이며 null 날짜는 `미상`으로 표시된다.

#### AC3 — Preview 진입

Given 결과 카드에 포커스가 있거나 포인터로 선택할 수 있을 때  
When 클릭 또는 Enter/Space를 실행하면  
Then 해당 장면이 선택 상태가 되고 기존 Preview 다이얼로그가 열린다.

#### AC4 — nullable 날짜 회귀 방지

Given 방영일 또는 촬영일이 null인 결과가 있을 때  
When 기간 필터 또는 최신순 정렬을 적용하면  
Then 런타임 예외 없이 결과 목록이 유지되며 날짜 미상 항목은 기간 필터에서 임의로 제거되지 않는다.

#### AC5 — 기존 연동 보존

Given 기존 검색 이력·문의·등록/리뷰 Preview가 공유 결과를 참조할 때  
When 타입 검사와 테스트를 실행하면  
Then 기존 ID 1~3 참조와 Preview 입력 계약이 깨지지 않는다.

## Spec Change Log

| 일자       | 변경                                             |
| ---------- | ------------------------------------------------ |
| 2026-09-09 | Jira 117과 저장소 정본 문서를 기준으로 최초 작성 |

## Verification

- `npm --prefix frontend test`
- `npm --prefix frontend run check`
- `npm --prefix frontend run build`
- 기본/필터/최신순 상태에서 카드 10개, `미상`, 키보드 선택, Preview 진입을 수동 확인

검증 결과: 테스트 93/93, format/lint/typecheck/build, `git diff --check` 통과. 로컬 브라우저에서 클릭·Enter·Space Preview 진입, 닫기 후 포커스 복귀, 1440/900/700px의 3/2/1열을 확인했다. 리뷰 후 카드 의미 구조, 10개 상한, 화면 순번, 혼합 날짜 정렬과 Preview 메타데이터를 보강했다.

## Suggested Review Order

**카드 구성과 화면 연결**

- 카드의 필수 정보와 선택 동작을 한 컴포넌트에 모았다.
  [`search-result-card.tsx:21`](../../frontend/src/features/wireframes/search-result-card.tsx#L21)

- Shell은 정렬 후 10개 상한과 화면 순번만 조합한다.
  [`wireframe-shell.tsx:77`](../../frontend/src/features/wireframes/wireframe-shell.tsx#L77)

- 목록은 재사용 카드에 Preview 선택 상태를 전달한다.
  [`wireframe-shell.tsx:285`](../../frontend/src/features/wireframes/wireframe-shell.tsx#L285)

**데이터와 날짜 계약**

- 공유 결과 타입에 카드 표시 필드와 nullable 날짜를 명시했다.
  [`demo-scenes.ts:3`](../../frontend/src/features/wireframes/demo-scenes.ts#L3)

- 기존 세 장면을 보존하며 Top 10 mock을 구성했다.
  [`demo-scenes.ts:29`](../../frontend/src/features/wireframes/demo-scenes.ts#L29)

- null과 혼합 날짜 포맷을 필터·최신순에서 안전하게 처리한다.
  [`date-range.ts:34`](../../frontend/src/features/wireframes/date-range.ts#L34)

- Preview도 촬영일 명칭과 샷·장면 유형을 일관되게 표시한다.
  [`scene-dialogs.tsx:375`](../../frontend/src/features/wireframes/scene-dialogs.tsx#L375)

**접근성과 레이아웃**

- 실제 버튼 오버레이로 카드 의미 구조와 키보드 포커스를 보존한다.
  [`wireframe.module.css:555`](../../frontend/src/features/wireframes/wireframe.module.css#L555)

- 메타데이터는 반응형 2열 정의 목록으로 배치한다.
  [`wireframe.module.css:887`](../../frontend/src/features/wireframes/wireframe.module.css#L887)

**검증과 문서**

- fixture 수·순위·구간·기존 값 보존을 고정한다.
  [`demo-scenes.test.mjs:21`](../../frontend/src/features/wireframes/demo-scenes.test.mjs#L21)

- 카드 필드·미상·선택 상태·화면 순번을 정적 렌더로 검증한다.
  [`search-result-card.test.mjs:46`](../../frontend/src/features/wireframes/search-result-card.test.mjs#L46)

- nullable 필터와 혼합 포맷 최신순을 경계값으로 검증한다.
  [`date-range.test.mjs:40`](../../frontend/src/features/wireframes/date-range.test.mjs#L40)

- 컴포넌트 소유권과 공유 mock 역할을 아키텍처에 기록했다.
  [`architecture.md:99`](../../frontend/docs/architecture.md#L99)
