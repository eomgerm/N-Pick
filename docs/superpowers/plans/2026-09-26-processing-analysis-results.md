# 영상 처리 상세 분석 결과 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 기존 처리 파이프라인 아래에서 최신 run의 장면별 대표 프레임·캡션·채택 대사·유효 태그·OCR을 검수할 수 있게 한다.

**Architecture:** clip 모듈에 run-scoped 장면 projection 조회를 추가하고, 태그 모듈의 기존 `TagResolutionPolicy`를 run 범위에서도 재사용한다. 프론트는 전용 API parser와 분석 결과 컴포넌트를 만들고 기존 `ProcessingClipDetail`은 조회 조합과 영상 seek만 담당한다.

**Tech Stack:** Java 21, Spring Boot 4, PostgreSQL JDBC projection, Next.js 16, React 19, TanStack Query, CSS Modules, Node test, Playwright

**Spec:** `docs/superpowers/specs/2026-09-26-processing-analysis-results-design.md`

## Global Constraints

- 기존 10단계 `ProcessingPipeline`의 표시와 단계 상세 동작을 유지한다.
- 신규 장면 API는 검수자 전용이며 bigint ID를 문자열로 직렬화한다.
- 대표 프레임은 최소 `keyframe_id`이며 storage key를 공개하지 않는다.
- 태그는 기존 `TagResolutionPolicy`의 최종 유효 태그만 반환한다.
- 한 페이지는 기본 20개, 허용 범위 1~100이다.
- 기존 500줄 초과 파일에는 새 책임을 추가하지 않는다.
- 업로드 폼과 파이프라인 처리 로직은 변경하지 않는다.

## Review Focus

- 최신 run이 현재 active run이 아니어도 그 run의 장면과 태그만 반환하고 `search_applied=false`여야 한다.
- clip에 속하지 않는 run ID로 다른 영상의 분석 결과를 읽을 수 없어야 한다.
- 부분 처리에서 대표 프레임·캡션·대사·태그·OCR 중 일부가 없어도 나머지는 표시되어야 한다.
- 반려되거나 미확정인 태그 후보는 결과에 나타나지 않아야 한다.
- 장면 목록 페이지를 이동하거나 상세 polling이 갱신돼도 원본 video DOM이 불필요하게 교체되지 않아야 한다.

---

### Task 1: 장면 분석 결과 조회 계약과 백엔드 API

**Files:**
- Create: `backend/src/main/java/com/npick/clip/application/query/analysis/GetClipAnalysisScenesUseCase.java`
- Create: `backend/src/main/java/com/npick/clip/application/query/analysis/ClipAnalysisScenesQueryPort.java`
- Create: `backend/src/main/java/com/npick/clip/application/query/analysis/ClipAnalysisScenesResult.java`
- Create: `backend/src/main/java/com/npick/clip/application/query/analysis/ClipAnalysisScenesQueryService.java`
- Create: `backend/src/main/java/com/npick/clip/infrastructure/persistence/query/JdbcClipAnalysisScenesQueryAdapter.java`
- Create: `backend/src/main/java/com/npick/clip/presentation/response/ClipAnalysisScenesResponse.java`
- Create: `backend/src/test/java/com/npick/clip/presentation/ClipAnalysisScenesHttpIntegrationTest.java`
- Modify: `backend/src/main/java/com/npick/clip/presentation/controller/ClipQueryController.java`
- Modify: `backend/src/main/java/com/npick/tag/application/query/ResolveSceneTagsUseCase.java`
- Modify: `backend/src/main/java/com/npick/tag/application/query/FindTagJudgmentsQueryPort.java`
- Modify: `backend/src/main/java/com/npick/tag/application/query/SceneTagResolutionService.java`
- Modify: `backend/src/main/java/com/npick/tag/infrastructure/persistence/query/TagJudgmentQueryAdapter.java`
- Modify: `backend/src/test/java/com/npick/tag/application/query/SceneTagResolutionServiceTest.java`
- Modify: `backend/src/test/java/com/npick/clip/presentation/ClipQuerySecurityTest.java`
- Modify: `docs/contracts/web-api.md`

**Interfaces:**
- Consumes: `scene`, `keyframe`, `ocr_observation`, `tagging`, `tag`, `tag_evidence`, `clip.active_pipeline_run_id`.
- Produces: `GetClipAnalysisScenesUseCase.get(long clipId, long runId, int page, int size)` and `GET /api/v1/clips/{clipId}/runs/{runId}/scenes`.

- [ ] **Step 1: Write the failing HTTP integration tests**

  Add fixtures for two clips and two runs. Assert stable scene order, representative frame timestamp, nullable partial fields, OCR de-duplication, effective tag output, summary counts, pagination, `search_applied`, foreign run 404, deleted clip 404 and invalid page 400.

- [ ] **Step 2: Run the new integration test and verify RED**

  Run: `./gradlew test --tests 'com.npick.clip.presentation.ClipAnalysisScenesHttpIntegrationTest'`

  Expected: compilation or route failure because the analysis use case and endpoint do not exist.

- [ ] **Step 3: Add run-scoped tag resolution tests and verify RED**

  Add a service test proving `resolveForRun(clipId, runId, sceneIds)` delegates to the new port method and still applies `TagResolutionPolicy`.

  Run: `./gradlew test --tests 'com.npick.tag.application.query.SceneTagResolutionServiceTest'`

  Expected: compilation failure because `resolveForRun` does not exist.

- [ ] **Step 4: Implement the minimal query projection and response**

  Implement the use case, service, JDBC port and response records. Validate page bounds before querying. Resolve all run scene IDs once for the exact tagged-scene summary and reuse the page subset from that result. Extend the tag query adapter with a run-scoped predicate while preserving the existing active-run predicate for search.

- [ ] **Step 5: Expose the route and reviewer security**

  Inject the new use case into `ClipQueryController`, map the GET route and add it to the existing security tests. Do not alter `SecurityConfig`; `/api/v1/clips/**` already requires REVIEWER.

- [ ] **Step 6: Run focused backend verification**

  Run: `./gradlew spotlessJavaCheck test --tests 'com.npick.clip.presentation.ClipAnalysisScenesHttpIntegrationTest' --tests 'com.npick.tag.application.query.SceneTagResolutionServiceTest' --tests 'com.npick.clip.presentation.ClipQuerySecurityTest'`

  Expected: all selected tests pass and Spotless reports no violations.

### Task 2: 프론트 API parser와 장면 결과 표현 규칙

**Files:**
- Create: `frontend/src/features/wireframes/clip-analysis-api.ts`
- Create: `frontend/src/features/wireframes/clip-analysis-api.test.mjs`
- Create: `frontend/src/features/wireframes/clip-analysis-view.ts`
- Create: `frontend/src/features/wireframes/clip-analysis-view.test.mjs`

**Interfaces:**
- Consumes: Task 1의 snake_case JSON response.
- Produces: `getClipAnalysisScenes(clipId, runId, page, size, signal)`과 태그·대사 출처 표시 함수.

- [ ] **Step 1: Write parser and request contract tests**

  Assert bigint string preservation, nullable analysis fields, page invariants, scene order uniqueness, interval validity, enum validation and exact request URL/query/session signal.

- [ ] **Step 2: Run parser tests and verify RED**

  Run: `npm test -- src/features/wireframes/clip-analysis-api.test.mjs src/features/wireframes/clip-analysis-view.test.mjs`

  Expected: module-not-found because the analysis modules do not exist.

- [ ] **Step 3: Implement minimal parser and labels**

  Parse without coercing IDs or inventing empty values. Provide Korean labels for transcript source, tag type, scope and verification. Reuse `formatMediaTime(milliseconds / 1000)` for timecode presentation.

- [ ] **Step 4: Run focused frontend unit tests**

  Run: `npm test -- src/features/wireframes/clip-analysis-api.test.mjs src/features/wireframes/clip-analysis-view.test.mjs`

  Expected: all focused tests pass.

### Task 3: 처리 상세의 분석 결과 UI

**Files:**
- Create: `frontend/src/features/wireframes/processing-analysis-results.tsx`
- Create: `frontend/src/features/wireframes/processing-analysis-results.module.css`
- Modify: `frontend/src/features/wireframes/processing-clip-detail.tsx`
- Modify: `frontend/e2e/reviewer-processing.spec.ts`

**Interfaces:**
- Consumes: Task 2의 `getClipAnalysisScenes`, 기존 `SceneThumbnail`, 기존 원본 `<video>` ref.
- Produces: `ProcessingAnalysisResults`와 장면 시작점 seek 동작.

- [ ] **Step 1: Add the failing focused E2E scenario**

  Mock clip detail, analysis page and thumbnail routes. Assert the 10-stage pipeline remains, old `대사 처리 기록` is absent, analysis summary and full caption/transcript/tags/OCR are visible, and `영상에서 보기` seeks the existing video element.

- [ ] **Step 2: Run focused E2E and verify RED**

  Run: `npx playwright test e2e/reviewer-processing.spec.ts --project=chromium -g '분석 결과'`

  Expected: failure because the `분석 결과` region does not exist.

- [ ] **Step 3: Implement the analysis result component**

  Keep `ProcessingPipeline` untouched. Replace the old transcript panel with the new component, query only when a latest run exists, and pass a video seek callback. Use semantic headings, lists, status text, keyboard-visible buttons and explicit empty/error states.

- [ ] **Step 4: Implement responsive visual styling**

  Use a wide frame/text row at desktop and a single-column scene card below 760px. Keep the existing palette and panel treatment, but give the representative frame a stable 16:9 area and align all scene text to one vertical axis. Do not clamp caption or transcript text.

- [ ] **Step 5: Run focused UI verification and capture screenshots**

  Run: `npx playwright test e2e/reviewer-processing.spec.ts --project=chromium -g '분석 결과'`

  Expected: desktop and mobile assertions pass and screenshots show no horizontal overflow.

### Task 4: Cross-layer verification and review

**Files:**
- Modify only files required by failures attributable to Tasks 1–3.

**Interfaces:**
- Consumes: backend route, frontend parser and result component.
- Produces: merge-ready Jira 316 branch evidence.

- [ ] **Step 1: Run backend affected suites**

  Run: `./gradlew spotlessJavaCheck test --tests 'com.npick.clip.*' --tests 'com.npick.tag.application.query.SceneTagResolutionServiceTest'`

  Expected: all affected backend tests pass.

- [ ] **Step 2: Run frontend unit, format, lint and type checks**

  Run: `npm test && npm run format:check && npm run lint && npm run typecheck`

  Expected: all commands pass.

- [ ] **Step 3: Run the processing E2E file**

  Run: `npx playwright test e2e/reviewer-processing.spec.ts --project=chromium`

  Expected: all processing scenarios pass.

- [ ] **Step 4: Review the final diff**

  Confirm every changed line maps to Jira 316, no file exceeds 500 lines due to this work, no private storage path is serialized, and the existing pipeline component has no behavior change.
