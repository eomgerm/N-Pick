# Frontend Architecture

이 문서는 N-Pick 프론트엔드의 구조, 책임 경계와 데이터 흐름을 설명합니다. 반드시 지켜야 하는 작업 규칙은 [`../AGENTS.md`](../AGENTS.md), 설치와 실행 방법은 [`../README.md`](../README.md)를 기준으로 합니다.

## 목표

- Next.js App Router의 route 계층을 얇게 유지합니다.
- 기능 전용 코드와 여러 기능에서 공유하는 코드를 구분합니다.
- Server Component를 기본으로 사용해 불필요한 클라이언트 JavaScript를 줄입니다.
- API, 환경변수와 UI의 경계를 분리합니다.
- 요구사항이 생기기 전에 디렉터리, 상태와 추상화를 미리 만들지 않습니다.

## 현재 구조

검색 결과의 0건 안내는 `wireframes/search-result-details.tsx`의 UI 입력 모델을 사용합니다. `WireframeShell`은 선택적인 `execution`·`resultDetails` 입력을 받아 빈 결과에서도 degraded 상태를 유지합니다. `SearchResultState`는 실제 해석 상태와 제외 정보를 표시합니다. 사용자 결정에 따라 1~9건에는 별도 부족 안내 없이 기존 결과 개수와 카드만 표시합니다. 기록이 없는 수치는 0으로 바꾸지 않습니다. 서버 DTO·사유 코드 변환은 167의 검색 adapter 연결 범위로 남아 있고, 현재 제품 경로는 기존 데모 데이터입니다.

```text
src/
├─ app/                    Next.js route와 화면 조합
│  ├─ layout.tsx           HTML 뼈대, Metadata, 전역 CSS
│  ├─ page.tsx             랜딩 기본 버전으로 이동
│  ├─ landing/            신한 디자인의 역할 선택 랜딩
│  ├─ login/              백엔드 세션 로그인 (역할 query는 안내용)
│  ├─ search/             편집자 검색 입력
│  │  └─ results/         검색 결과와 URL 필터
│  ├─ review/             문의·영상 등록·처리 현황
│  ├─ error.tsx            서버 조회 실패 안내·재시도
│  └─ globals.css          전역 토큰과 전역 스타일
├─ components/             여러 기능에서 공유하는 UI
│  ├─ app-shell.tsx        제품 공통 헤더·역할별 메뉴와 페이지 본문 조합
│  ├─ api-error-notice.tsx  한국어 오류·코드·요청 ID 공통 표시
│  ├─ query-provider.tsx   TanStack Query와 인증 만료·탭 간 세션 변경 처리
│  ├─ session-boundary.tsx 서버 사용자 snapshot과 클라이언트 세션 재확인
│  └─ session-controls.tsx 현재 계정과 로그아웃
├─ features/               기능 단위 UI와 로직
│  ├─ search/
│  │  └─ search-placeholder.tsx
│  └─ wireframes/
│     ├─ landing-shell.tsx        브랜드 인트로와 역할 선택
│     ├─ landing-cube.tsx         랜딩 전용 Three.js 유리 큐브 Canvas와 모션 경계
│     ├─ landing.module.css       신한 랜딩과 글자 합치기 인트로
│     ├─ login-shell.tsx          로그인 form·mutation·오류와 안전한 복귀
│     ├─ search-entry-shell.tsx   편집자 검색 입력과 결과 진입
│     ├─ search-navigation.ts     검색어·명시 날짜 필터의 결과 URL 생성
│     ├─ search-api-contract.ts   검색 요청 변환·응답 타입과 런타임 계약 검증
│     ├─ date-range.ts            날짜 범위 검증과 URL 복원
│     ├─ date-range-picker.tsx    방송일·촬영일 기간 선택 dialog
│     ├─ search-history.tsx       검색·문의 예시 기록과 부분 노출 시트
│     ├─ search-history.module.css 기록 목록·상태 칩·펼침 레이아웃
│     ├─ demo-scenes.ts          결과·기록이 공유하는 10개 예시 장면과 표시 모델
│     ├─ scene-dialogs.tsx        구간 영상·문의 공통 팝업과 상태별 조회
│     ├─ inquiry-api.ts          문의 제출 snapshot·응답 검증·접수 API
│     ├─ entry-chrome.tsx         로그인 헤더와 로그인·검색 공통 푸터
│     ├─ entry.module.css         신한 로그인·검색 반응형 레이아웃
│     ├─ reviewer-shell.tsx    처리 상태·문의 검수 로컬 상호작용
│     ├─ review-inquiry-workspace.tsx 문의 목록 조회·URL 보정·목록/상세 조합
│     ├─ review-inquiry-list.tsx 문의 목록·상태 필터·페이지 이동
│     ├─ review-inquiry-detail.tsx 문의 상세 조회·선점·오류 복구와 캐시 갱신
│     ├─ review-inquiry-resolution.tsx 담당자 판정 입력·사유 검증·저장과 캐시 갱신
│     ├─ review-inquiry-snapshots.tsx 당시 필터·검색 해석·결과 기록 표시
│     ├─ review-inquiry-view.ts 화면 매핑·목록 URL 상태·페이지 보정·처리 결과 색상
│     ├─ reviewer-scene-preview.tsx 문의 장면 카드와 공통 영상 팝업 연결
│     ├─ reviewer-progress.tsx 처리 현황 요약과 문의·영상 진행 목록
│     ├─ reviewer-progress-state.ts 진행 목록 선택과 건수 집계
│     ├─ reviewer-progress.module.css 진행 목록의 테마·반응형 레이아웃
│     ├─ reviewer-board.tsx    검수자 문의 게시판·검색·필터·페이지네이션
│     ├─ reviewer-board-state.ts 목록 조건과 정렬·10개 단위 페이지 계산
│     ├─ reviewer-board.module.css 문의 게시판의 테마·반응형 레이아웃
│     ├─ reviewer-inquiries.ts 목록·상세가 공유하는 23개 독립 데모 문의
│     ├─ reviewer-resolution.tsx 검수자용 검색 해석 요약·항목별 입력
│     ├─ reviewer-resolution-state.ts 전체 검색 해석 보존과 입력·날짜 변환
│     ├─ reviewer-resolution.module.css 검색 해석 요약·입력 레이아웃
│     ├─ video-registration.tsx 파일 선택·드롭·등록 form
│     ├─ video-registration-api.ts multipart 변환·응답 검증·등록 오류 정책
│     ├─ video-registration.module.css 영상 등록의 테마·반응형 레이아웃
│     ├─ registration-files.ts 영상·자막·대본 파일 선택 검증
│     ├─ reviewer.module.css   검수자 화면의 테마와 반응형 레이아웃
│     ├─ wireframe-shell.tsx   검색 결과 UI와 로컬 상호작용
│     ├─ search-result-card.tsx 순위·구간·일치 근거·검증 상태를 표시하는 접근 가능한 결과 카드
│     ├─ search-execution-status.ts 검색 실행의 정상·degraded 표시 모델과 문의 가능 규칙
│     ├─ search-result-notices.tsx 결과·Preview 공용 경고와 송출 전 확인 고지
│     ├─ wireframe-themes.ts   신한 단일 테마 타입과 route 검증
│     └─ wireframe.module.css  신한 토큰과 반응형 레이아웃
└─ lib/                    프레임워크·인프라 성격의 공통 코드
   ├─ routes.ts            화면 경로 상수
   ├─ env.ts               공개 환경변수 읽기와 검증
   ├─ server-env.ts        server-only 내부 API 주소
   ├─ auth/               앱 전역 보안 계약·API·서버 guard·캐시/초안 정리
   └─ api/
      ├─ client.ts         공통 응답 envelope 해석과 HTTP client
      ├─ idempotency.ts    새 제출용 불투명한 UUID 멱등성 키 생성
      └─ error.ts          안전한 ApiClientError와 개발용 진단 정보 분리
```

현재 와이어프레임 UI를 제품 화면으로 사용하며 디자인은 신한(`shinhan`)을 유지합니다. `/`는 `/landing`으로 이동하고 역할 카드는 `/login?role=editor|reviewer`로 연결합니다. 로그인 후 실제 계정의 역할에 따라 편집자는 `/search`, 검수자는 `/review`로 이동합니다. 권한이 있는 내부 `returnTo`가 있으면 우선 복귀합니다. 검색 결과는 `/search/results`에서 표시합니다. 화면 경로 상수는 `src/lib/routes.ts`가 소유합니다. 기존 테마 주소는 `next.config.ts`의 307 redirect로 새 화면에 연결하고 query를 보존하며 알 수 없는 테마는 404로 처리합니다. 인증·영상 등록 POST·검수 문의 목록·상세·선점·판정 API는 연결되어 있으며 검색·문의 접수·처리 조회는 아직 로컬 데모입니다. 비밀번호와 세션 토큰은 프론트 저장소에 저장하지 않습니다.

랜딩의 Three.js 유리 큐브는 `landing-shell.tsx`의 Client Component 경계에서 `next/dynamic({ ssr: false })`로 지연 로드합니다. Three.js physical material과 rounded box geometry로 굴절·두께·분산·무지갯빛 테두리를 표현하며, 투명 Canvas texture의 N-Pick 타이포그래피를 큐브 뒤에 배치합니다. 글자는 알파 컷아웃으로 불투명 렌더 패스에 포함해 유리의 굴절 대상이 되며, 큐브는 알파 블렌딩 없이 앞면의 transmission으로 글자를 굴절시킵니다. `Need? Pick!`의 글자 폭을 줄여 `N-Pick`으로 합치는 CSS 인트로 뒤에 큐브와 역할 카드가 나타납니다. 인트로는 WebGL 준비 여부와 무관하게 끝나고 CSS 유리 큐브는 로딩·WebGL 실패 fallback으로 유지합니다. WebGL render loop와 CSS animation은 `prefers-reduced-motion`에서 정지하고 Canvas의 DPR을 제한합니다.

편집자 검색 입력 화면 하단에는 `이전 검색 기록`과 `문의 기록` 시트를 일부만 노출합니다. 제목 버튼으로 한 시트씩 위로 펼치고 접을 수 있으며 Escape로도 접습니다. 기존 검색 결과의 뉴스 썸네일을 재사용하고 구간·내용·경과일을 표시합니다. 검색 기록 항목은 검색 결과와 동일한 `ScenePreviewDialog`를 열고, 문의 기록 항목은 `InquiryDialog`에서 당시 검색어·구간·문의 내용을 읽기 전용으로 보여 줍니다. 문의 생명주기는 `open/reviewing/closed`, 종료 결과는 `exclude_scene/no_action/deferred/tag_correction/patch_parse`를 사용합니다. native dialog로 배경 조작을 막고 키보드 포커스를 가두며 Escape·닫기로 복귀합니다. 새로 접수한 문의는 `SearchHistory` 메모리에서 유지하며 새로고침·페이지 이동 시 초기화됩니다. 실제 검색·문의 이력 API는 연결하지 않은 디자인 시안입니다.

제품 화면 `/search`, `/search/results`, `/review`는 `AppShell`이 공통 헤더와 화면 이동 메뉴를 제공합니다. `SessionBoundary`의 현재 계정으로 역할을 읽고 `SessionControls`가 계정·역할·로그아웃을 표시합니다. 편집기자에게는 장면 검색, 검수자에게는 장면 검색과 검수 메뉴를 제공하며 pathname은 현재 메뉴 표시에만 사용합니다. 검색 입력은 밝은 헤더, 검색 결과·검수는 기존 브랜드 배경을 사용합니다. 공개 랜딩·로그인과 각 page의 서버 접근 검사는 별도로 유지합니다.

검수자는 `/review`에서 Spring의 `GET /review/inquiries`를 통해 최근 접수 순 10개 단위 문의 목록과 전체 상태 건수를 봅니다. 상태는 전체·접수(`open`)·검수 중(`reviewing`)·종료(`closed`)로 서버 필터링하며 `status/page/inquiry`를 URL에 보존합니다. 상세는 `GET /review/inquiries/{feedbackId}`로 문의 당시 검색 실행·필터·근거·처리 이력을 읽고, 접수 상태에서는 `POST /review/inquiries/{feedbackId}/claim`으로 선점합니다. 선점 버튼은 요청 중 중복 입력을 막고 동일 재시도에 같은 멱등성 키를 사용하며 성공 후 목록과 상세를 다시 읽습니다. 선점 충돌·권한·연결 실패는 성공 상태로 바꾸지 않고 최신 상태 확인·목록 복귀·안전한 재시도 중 다음 행동을 안내합니다. 종료 여부와 `resolution`은 분리해 표시합니다. `no_action`과 `deferred`는 수정 완료 색상으로 표현하지 않습니다. 현재 백엔드 목록 계약에 검색·임의 정렬 조건이 없어 해당 조작은 제공하지 않습니다. 페이지는 `useSearchParams`를 위한 Suspense 경계를 제공하고 현재 계정은 `/auth/me`의 `loginId`를 사용합니다.

문의 목록과 상세의 장면 요약은 API의 `clipTitle`, `startTimeMs`, `endTimeMs`를 사용하며 제목이 없으면 `제목 없는 영상`으로 표시합니다. 썸네일·영상 API가 계약에 없으므로 가짜 썸네일이나 재생 기능을 만들지 않습니다. 상세는 문의 당시 검색 결과·필터·해석·적용 기록과 현재 태그를 분리합니다. 과거 JSON과 `resolverOutputJson`은 화면에 원문으로 노출하지 않으며, 현재 근거는 태그별 출처·검증 상태·장면/클립 범위를 함께 표시합니다. 당시 결과와 적용 기록은 허용된 항목을 읽기 전용 목록으로 표시하며, 미지원 형식은 기록 없음과 구분합니다. 미지원 JSON 구조는 BE snapshot 내부 계약 확정 후 확장해야 합니다. 선점 후 담당자를 로그인 계정으로 합성하지 않고 상세 재조회 응답만 표시합니다. 과거 검색 기록은 읽기 전용이고 현재 태그로 다시 계산해 덮어쓰지 않습니다.

선점한 검수자는 `PUT /review/inquiries/{feedbackId}/resolution`으로 판정과 최대 2,000자의 사유를 저장합니다. `no_action/deferred`는 사유가 필수이며 즉시 `closed`로 종료됩니다. `tag_correction/patch_parse/exclude_scene`는 판정만 저장하고 후속 교정·검증 API가 완료될 때까지 `reviewing`을 유지합니다. 프론트는 서버 응답 뒤 목록과 상세를 다시 읽어 상태를 확정하고 요청 중 중복 입력과 다른 검수자의 저장을 막습니다.

검색 결과의 문의 입력은 Preview의 `이상해요`에서만 열며 `POST /search/results/{resultId}/inquiries`로 선택 설명을 전송합니다. 화면 장면 ID와 서버의 저장된 결과 ID를 분리하고 `SearchResult.searchResultId`만 API 경로에 사용합니다. 데모 결과에는 서버 ID를 만들지 않으며 저장 ID가 없거나 snapshot 저장에 실패하면 이유와 함께 문의를 비활성화합니다. 현재 검색 결과는 데모이므로 실제 접수 진입은 검색 API adapter 연동 후 활성화됩니다. 제출은 trim한 설명과 frozen snapshot·UUID 멱등성 키를 보존하며 자동 재시도하지 않습니다. 실패 후 설명을 바꾸지 않은 수동 재시도는 같은 snapshot/key를 사용하고 입력 변경 시 새 요청으로 바꿉니다. 제출 중에는 입력·중복 제출·dialog 닫기를 잠급니다. 성공 응답의 양의 `feedbackId`와 `OPEN`을 확인한 뒤에만 접수 완료를 표시하며 접수 자체로 현재 결과를 숨기거나 즉시 개선하지 않습니다.

검수 화면 상단의 문의·처리 이동 메뉴는 URL로 선택 상태를 계산합니다. 기본 `/review`는 문의, `view=processing`은 처리이며 `view=upload` 등록 화면도 처리 영역으로 표시합니다. 상위 메뉴 이동은 `getReviewTabUrl`로 상세 선택(`inquiry/clip`)과 처리 하위 `tab`을 지우고 목록 조건과 나머지 query를 유지합니다. 처리 하위 탭은 기존 `tab=uploads/completed`를 사용하며 생략하거나 알 수 없는 값이면 문의 처리 중을 표시합니다. 문의·처리 이동은 브라우저 이력에 남고 새로고침·뒤로가기·앞으로가기로 복원됩니다. URL 변경에 따른 재마운트 없이 기존 검수 초안을 유지하며, 등록 취소는 문의 목록으로 돌아갑니다.

영상 등록은 `/review?view=upload`에서 제공하며 문의 목록과 영상 처리 화면에서 진입할 수 있습니다. MP4/MOV 영상 1개, 선택 자막(SRT/VTT) 1개, 선택 일반 대본(TXT) 1개를 클릭 또는 드래그로 고르고 파일명·용량 확인과 삭제·교체가 가능합니다. 방송분 `broadcast`과 자료 영상 `archive`, 선택 제목, 독립적인 방송일·촬영일, 이용 권한·외부 처리 확인을 입력합니다. 자료 영상은 방송일 입력을 숨기고 multipart에도 포함하지 않습니다. 일반 대본은 fatal UTF-8 검사 뒤 `script_text`로 읽으며 자막 파일의 서버 저장·처리는 선행 이슈가 소유합니다.

폼은 검증을 통과한 File 포함 snapshot과 UUID 멱등성 키를 함께 소유하고 `POST /api/v1/clips`를 실행합니다. 네트워크·취소·비정상 응답·5xx·처리 중/결과 확인 불가 오류의 수동 재시도는 같은 snapshot/key를 쓰되 매 시도마다 새 `FormData`를 만듭니다. 입력을 수정하거나 서버가 새 요청 키를 요구하면 snapshot/key를 폐기하며 mutation을 자동 재시도하지 않습니다. 대본 읽기부터 응답까지 폼과 `ReviewerShell` 상단 이동을 잠그고 실패 시 File을 포함한 입력을 유지합니다. 허용된 검증 필드의 안전한 문자열만 인라인 오류로 사용하고 나머지는 공통 API 오류 UI로 표시합니다.

성공 응답은 문자열 `clip_id`, `pipeline_run_id`와 `queued`만 인정합니다. `ReviewerShell`은 이 서버 ID를 가진 로컬 레코드를 처리 목록에 합성하고 `view=processing&tab=uploads&clip=<clipId>` 상세로 이동합니다. 이 POST는 실제 파일을 전송하지만 처리 목록/상세 GET과 polling은 아직 연결하지 않았으므로, 방금 등록한 상세는 새로고침하면 사라질 수 있습니다. media decode, pipeline enqueue와 등록 결과의 영속성은 서버 책임이며 프론트가 성공 상태를 추정하지 않습니다.

처리 현황은 `view=processing`에서 `문의 처리 중` 탭을 먼저 보여주고, `tab=uploads`로 `영상 등록 중` 탭을 엽니다. 문의는 `reviewing`만 진행 목록에 포함하며, 전체 종료 건수는 `closed`로 집계하며 처리 결과는 `resolution`으로 구분합니다. 영상은 최신 작업의 대기·진행·실패를 표시하고, 실제 완료 단계 수로 진행 막대를 계산합니다. 새로 등록한 영상은 0단계 대기로 추가합니다. 문의 선택과 영상 `상세 보기`는 기존 조치·재시도 화면으로 연결하며 복귀 시 선택 탭을 유지합니다. 상태·건수·진행률은 시간 경과로 임의 증가하지 않습니다.

검수 상세는 문의자의 설명, 당시 검색 조건, 영상에서 확인한 내용과 검색 해석 요약을 보여줍니다. 개발용 JSON·로그는 화면에 노출하지 않고, 검색 해석 수정은 사건·인물·기관·장소·관련 검색어와 날짜 입력으로 구성합니다. 전체 snapshot과 수정하지 않은 근거 정보는 내부에 유지하며, 문의자가 명시한 날짜 필터는 변경하지 않습니다. 종료일은 화면에서 해당 날짜를 포함하는 기간으로 표시하고 내부의 배타적 종료일로 변환합니다. 검수 시작 후에는 당시 결과와 같은 검색어·필터로 다시 검색한 결과를 먼저 비교하고, 수정안 작성 → 수정 후 결과 확인 → 종료 순서로 진행합니다. 수정 전 재검색과 수정 후 검증은 별도 상태로 유지하여 최초 재검색만으로 종료되지 않게 합니다. 이전 단계로 돌아가도 작성 내용을 유지하며, 입력 변경이나 수정 전 재검색을 다시 실행하면 수정 후 확인을 무효화합니다. 각 문의의 진행 단계와 비교 결과는 목록 이동 중 메모리에 보존하고, 단계 전환 시 해당 영역으로 키보드 포커스를 옮깁니다. 수정 전 재검색은 당시 결과와 같은 고정 예시 목록, 수정 후 검증은 기존 변경 후 예시 목록을 사용하며 실제 검색 API가 연결되지 않았음을 표시합니다. 담당팀 확인 요청은 문의에 포함된 장면·근거를 읽기 쉬운 이름으로 선택하며, 정보가 아직 수정되지 않았음을 종료 내역에도 표시합니다. 영상 처리 단계와 실패 사유 역시 사용자가 이해할 수 있는 작업 설명으로 표현합니다.

문의 상세의 선택된 장면은 `ReviewerScenePreview`에서 해당 문의의 제목·시작/종료 시각·확인 근거를 공통 `ScenePreviewDialog`에 전달합니다. 문의 생성 버튼은 검수자 팝업에서 제공하지 않으며, 닫기·Escape 후 장면 카드로 포커스를 돌려주고 검수 진행 상태를 유지합니다. 문의별 키로 팝업 상태를 분리해 다른 문의나 목록으로 이동하면 팝업을 닫습니다. 실제 원본 영상은 연결 전으로 기존 재생·일시정지·구간 재생 UI 데모를 사용하고 팝업에 이를 명시합니다. 원본 길이·파일명·날짜가 없는 문의에는 값을 만들어 넣지 않습니다.

다른 디자인 시스템의 전용 스타일과 화면 분기는 제거하고 신한(`shinhan`) 구현만 유지합니다. 신한 검색 입력과 결과 화면은 방송일·촬영일별 Date Range Picker를 제공하며, 달력의 시작일·종료일 선택과 직접 입력, 취소·초기화, 키보드 방향 이동을 지원합니다. 진입 화면의 입력 중 값은 form 가까이에 두고, 제출하면 `search-navigation.ts`가 검색어와 두 날짜를 `q/broadcastFrom/broadcastTo/filmingFrom/filmingTo`로 분리해 결과 URL을 만듭니다. 결과 화면도 같은 생성기를 사용하며 URL을 검색 요청 상태의 정본으로 읽습니다. 선택한 기간은 양 끝 날짜를 포함합니다. navigation 중에는 검색 입력·날짜·제출을 잠그고, 결과 화면은 이전 카드를 `검색 중` 상태로 바꿔 새 결과와 혼동되지 않게 합니다. 실제 검색 API를 연결할 때 `search-api-contract.ts`가 URL 상태를 [웹 API 계약](../../docs/contracts/web-api.md)의 요청 본문으로 변환하며, 백엔드가 필터링·정렬한 응답 순서를 클라이언트가 다시 필터링하거나 정렬하지 않습니다. `state=empty`와 `state=failed`는 디자인 확인용 상태 URL이며, 실패 화면의 재시도는 검색어·기간을 보존하고 실패 시연 상태를 해제합니다. 현재 실제 검색 API는 연결하지 않았습니다. 결과 그리드는 `SearchResultCard`가 제목·썸네일 우하단 장면 구간·키워드와 키워드 행 우측의 텍스트 검증 칩을 맡고, 칩의 hover·focus 툴팁에는 사용자용 근거 필드와 값만 표시합니다. 출처와 OCR·VLM 같은 기술명은 카드에서 숨깁니다. 결과 카드에는 백엔드 내부 점수를 퍼센트 일치도로 변환해 노출하지 않으며 검색 채널 선택과 클라이언트 재정렬 UI를 제공하지 않습니다. `WireframeShell`은 날짜 요청 상태와 선택한 Preview 상태를 소유하고, Preview는 전체 근거와 상태 모델을 유지합니다. 표시명·방송일·촬영일·샷 유형·장면 유형은 검색 API 응답 모델에 보존하되 결과 카드에는 노출하지 않습니다. `demo-scenes.ts`의 10개 장면은 검색 결과와 검색·문의 기록이 공유합니다. 자동 생성 근거에는 `verified/unverified`만 사용하고, 정보 부재는 `unknown`, 사람의 판단은 `rejected/withdrawn`으로 분리합니다.

### 검색 API FE 적용 규칙

요청·응답 JSON과 상태 불변식의 정본은 [웹 API 계약](../../docs/contracts/web-api.md#5-장면-검색-api--계약-확정-연결-대기)입니다. FE는 URL의 `q/broadcastFrom/broadcastTo/filmingFrom/filmingTo`를 계약의 `query/explicit_filters`로 변환하고 `search-api-contract.ts`에서 응답을 런타임 검증합니다. 백엔드가 정한 `data.results` 순서를 다시 필터링하거나 정렬하지 않습니다.

검색 실행의 정상·degraded 상태와 사람 검수 규칙 적용 여부는 개별 장면이 아니라 `WireframeShell`이 한 번 소유합니다. `SearchResultNotices`가 결과 상단과 Preview에 같은 누락 사유·검수 규칙·송출 전 확인 문구를 제공하며, `state=degraded-resolver`, `degraded-dense`, `degraded-snapshot`, `review-rule`은 실제 API 연결 전의 화면 검증용 상태입니다. snapshot 저장 실패 결과는 볼 수 있지만 저장된 검색 식별자가 필요한 문의는 이유와 함께 비활성화합니다. 검색 API 연동 작업은 검증된 응답을 기존 화면 모델로 매핑합니다.

신한 Preview는 진입 시 로딩 안내와 비활성 재생 제어를 보여준 뒤 예시 준비 화면으로 전환합니다. `/search/results?preview=loading`은 로딩 상태를 유지해 디자인을 확인하는 주소입니다. 실제 미디어 로딩 성공을 뜻하지 않으며 화면 안에 데모임을 안내합니다. 신한 처리 현황에는 `tab=completed`의 `등록 완료` 탭이 추가됩니다. 고정 완료 예시의 장면 수·완료 시각·누락 정보·단계·검색 이동을 확인할 수 있고, 새로 등록한 영상도 같은 처리 상세에서 파일 정보와 0단계 대기를 확인할 수 있습니다. 신규 영상은 시간이 지났다는 이유로 완료 처리하지 않습니다. 새로고침 시 로컬 등록 메모리는 초기화됩니다. 처리 상태 변환은 `registration-processing.ts`, 날짜 검증은 `date-range.ts`가 담당합니다.

## 계층별 책임

| 계층         | 책임                                                | 포함하지 않는 것                        |
| ------------ | --------------------------------------------------- | --------------------------------------- |
| `app`        | route, layout, metadata, 페이지 수준 조합           | 재사용 가능한 기능 로직, 공통 HTTP 처리 |
| `features`   | 특정 사용자 기능의 UI, 상태, API 함수, Hook         | 다른 기능의 내부 코드, 전역 기반 코드   |
| `components` | 공유 UI, 앱 전역 세션 UI 경계와 인증 query·mutation | 특정 기능의 업무 규칙·API 호출          |
| `lib`        | 환경변수, HTTP client, 앱 전역 보안 기반 코드       | 화면 표현과 기능별 상태                 |
| `public`     | 브라우저에 그대로 제공하는 정적 파일                | 빌드가 필요한 소스 파일                 |

## 의존 방향

```text
app ───────→ features ───────→ lib
 │              │
 ├──────────────┴────────────→ components
 └───────────────────────────→ lib
```

- `app`은 화면 조합을 위해 `features`, `components`, `lib`를 사용할 수 있습니다.
- `features`는 `components`와 `lib`를 사용할 수 있습니다.
- `components`와 `lib`는 `app` 또는 특정 `features`를 알지 못합니다.
- feature가 다른 feature의 내부 파일을 직접 import하지 않습니다.
- 두 기능에 필요한 코드가 생기면 UI는 `components`, 기반 로직은 `lib`로 이동할 수 있는지 검토합니다. 앱 전역 shell과 layout UI도 `components`에 둘 수 있습니다.

## Feature 내부 확장

검수 문의 화면은 목록·상세·스냅샷 표시를 분리하고 `review-inquiry-view.ts`에서 순수 매핑과 URL 상태 계산을 관리합니다. 상세의 선점 재시도는 같은 멱등성 키를 사용하며, 최신 상태 확인은 이전 mutation 오류를 초기화한 뒤 상세와 목록을 갱신합니다. 조회 실패는 조회 오류로 안내합니다. 처리 결과 색상은 `Record<InquiryResolution, string>`으로 모든 값을 명시합니다. 검색 해석은 `SearchInterpretation`에서 한 번 계산하고 `ResolutionSummaryView`에 전달하며, 기록 없음과 계산 실패를 구분합니다. 파서의 `endTimeMs > startTimeMs`, `processingNo >= 1`, `resultRank >= 1` 검증은 DB CHECK 제약과 일치하므로 유지합니다. 순수 상태·파싱·렌더링은 단위 테스트로, 선점 복구·서버 재조회·페이지와 필터 URL 복원은 `e2e/review-inquiry.spec.ts`로 검증합니다.

기능이 커지면 필요한 디렉터리만 추가합니다. 다음 구조는 의무적인 초기 골격이 아닙니다.

```text
features/search/
├─ components/             검색 전용 UI
├─ api/                    검색 API 함수와 전송 타입
├─ hooks/                  검색 기능 전용 Hook
├─ types.ts                검색 기능에서 공유하는 타입
└─ utils.ts                검색 기능에서 공유하는 순수 함수
```

파일 하나로 충분하면 파일 하나를 유지합니다. 디렉터리 이름이 코드보다 구조를 더 복잡하게 만들기 시작할 때만 분리합니다.

## Server와 Client 경계

Server Component를 기본값으로 사용합니다.

```text
Server Component
├─ 각 보호 page의 세션·역할 확인 (`/auth/me`, no-store)
├─ route/searchParams, metadata와 정적인 뼈대
└─ Client Component
   ├─ TanStack Query 서버 상태 (현재 me·로그인·로그아웃 연결)
   ├─ 입력과 사용자 이벤트
   ├─ 브라우저 API
   └─ 일시적인 UI 상태
```

`'use client'`는 파일 아래의 전체 import tree를 클라이언트 경계로 만듭니다. 따라서 페이지 전체가 아니라 상호작용이 필요한 작은 경계에 둡니다. 브라우저에 전달되는 Props는 직렬화할 수 있어야 합니다.

## 상태 소유권

| 상태 종류          | 기본 위치                                            | 예시                                          |
| ------------------ | ---------------------------------------------------- | --------------------------------------------- |
| URL 상태           | route search params                                  | 검색어, 명시 필터, 페이지                     |
| Server 상태        | TanStack Query (페이지 접근 확인은 Server Component) | 현재 계정, 이후 검색 결과·처리 상태·문의 목록 |
| Local UI 상태      | 가장 가까운 Client Component                         | modal, 펼침 여부, 입력 중인 값                |
| Form 상태          | form 경계                                            | validation 오류, 제출 중 상태                 |
| Global client 상태 | 당분간 없음                                          | 필요가 생기면 재검토                          |

URL로 표현할 수 있는 상태를 전역 store에 중복 저장하지 않습니다. 서버에서 받은 데이터를 여러 상태 계층에 복사하면 어느 값이 최신인지 불명확해지므로 하나의 소유 위치를 유지합니다.

## API와 데이터 흐름

```text
route 또는 feature
        ↓
기능별 API 함수
        ↓
lib/api/client.ts
        ↓
NEXT_PUBLIC_API_BASE_URL
        ↓
Backend API
```

- `src/lib/env.ts`가 공개 환경변수의 기본값과 형식을 검증합니다.
- `src/lib/api/client.ts`가 기본 경로 보존, URLSearchParams 결합, JSON·FormData 요청, header와 HTTP 상태 검사를 담당합니다. 기본 주소는 로컬 HTTP(S) 절대 URL 또는 nginx를 통한 동일 오리진 `/api/v1`을 지원합니다. 브라우저가 백엔드를 호출하며 Next.js 프록시는 두지 않습니다.
- 기능별 API 함수가 endpoint, request·response 타입과 기능별 오류 변환을 담당합니다.
- UI는 HTTP 세부사항보다 `loading`, `empty`, `degraded`, `error` 같은 사용자 상태를 다룹니다.

공통 API client는 백엔드 `ApiResponse.java`의 `isSuccess/code/message/data` envelope를 해석합니다. `fetchJson<T>`의 `T`는 envelope 안의 `data` 타입입니다. 성공 시 `data`만 반환하며 `null`, 빈 배열, degraded 상태를 바꾸지 않습니다. `data` 생략 및 HTTP 204·205는 `undefined`를 반환하므로 데이터 없는 호출은 `fetchJson<void>`를 사용합니다. 다른 2xx 빈 본문·비정상 JSON·envelope 누락은 성공으로 처리하지 않습니다.

HTTP·업무 실패·네트워크·본문 수신 실패·비정상 응답·취소는 `ApiClientError`로 정규화합니다. `kind`, HTTP `status`(응답 전 실패는 0), 사용자용 `message`, stable `code`, 선택적 `requestId`를 제공합니다. 원래 응답의 `path/data`와 예외는 직렬화되지 않는 `diagnostics` 접근자로 분리하며 UI에 전달하거나 출력하지 않습니다. signal은 호출자가 전달하고 자동 재시도는 하지 않습니다. 공통 client는 `credentials: include`와 `redirect: error`를 강제하고 브라우저 변경 요청에 CSRF 헤더를 추가합니다.

멱등성 키는 `src/lib/api/idempotency.ts`의 `createIdempotencyKey()`가 불투명한 UUID로 생성합니다. 기능별 제출 경계가 입력과 키를 함께 소유하여 같은 논리적 요청의 재시도에는 기존 키를, 새 제출에는 새 키를 사용합니다. `fetchJson`은 `idempotencyKey` 옵션을 `Idempotency-Key` 헤더로 전달하고 조회 요청의 키는 전송 전에 거부합니다. 공통 client는 키를 자동 생성하거나 변경 요청을 자동 재시도하지 않습니다.

`components/api-error-notice.tsx`는 오류 객체를 받아 한국어 메시지·오류 코드·요청 ID와 후속 안내만 `role="alert"`로 표시합니다. form의 `aria-describedby`에 연결할 수 있는 `id`를 지원합니다. 정상 한국어 서버 메시지를 우선하며, 현재 백엔드의 고정 영어 메시지는 `code/message`가 정확히 일치하는 6개 조합만 번역합니다. 메시지 누락·타입 오류·한국어 안내 계약 위반(내부 경로·HTML·JSON·예외 trace 등)에는 일반 한국어 안내를 사용합니다. 임의 JSON 응답의 `message`나 일반 `Error.message`는 표시하지 않습니다.

**현재 연동 차이:** FRD v3.1 §6.3은 한국어 오류와 요청 식별자를 요구하지만 백엔드 `CommonErrorCode`는 영어이고 `ApiResponse`에는 요청 ID가 없습니다. FE는 본문의 `requestId`, 없으면 `X-Request-ID` 응답 헤더를 읽도록 준비했으며 미제공 시 `제공되지 않음`으로 표시합니다. ID를 임의 생성하지 않습니다. 백엔드의 한국어 메시지·요청 ID 생성과 교차 오리진 호출 시 해당 헤더의 CORS 노출은 별도 연동 작업입니다. 공통 client와 오류 UI는 로그인·로그아웃·세션 조회에 연결되어 있습니다.

## 인증·인가와 세션 수명

[ADR 0002](decisions/0002-backend-session-auth.md)를 적용합니다. 인증의 정본은 Spring Security 세션이며 브라우저가 HttpOnly `JSESSIONID`를 보냅니다. 프론트에서 해독하거나 새 세션을 만들지 않습니다.

- 보호된 각 page는 server-only `currentMember`/`requireMember`로 `/auth/me`를 확인합니다. 내부 주소는 `API_INTERNAL_BASE_URL`, 브라우저 주소는 `NEXT_PUBLIC_API_BASE_URL`입니다. 서버는 JSESSIONID만 전달하고 React `cache`는 한 렌더 요청 안에서만 중복 조회를 줄입니다. 영속·공유 캐시는 사용하지 않습니다.
- 브라우저는 `['auth', 'me']` query를 서버 snapshot으로 시작하고 mount·창 복귀·재접속 시 재검증합니다. 백그라운드 재확인 중에는 현재 내용과 local/form 상태를 유지하고, 실패하거나 계정·역할 변경을 확인한 경우에만 상호작용을 차단합니다. 계정·역할이 바뀌면 기존 캐시와 화면을 폐기하고 다시 접근 검사합니다.
- 로그인은 안내 role을 서버로 보내지 않습니다. 성공 DTO와 `/auth/me`의 쿠키 유지 확인 뒤 허용된 내부 복귀 주소 또는 실제 역할의 기본 화면으로 이동합니다. 비밀번호는 DOM 입력 동안만 유지하고 완료 시 비웁니다. mutation variables에도 넣지 않습니다.
- 첫 변경 요청 전 `/auth/csrf`를 호출합니다. 동시 준비는 하나의 요청으로 합치며 각 변경 요청 직전에 현재 `XSRF-TOKEN`을 헤더로 읽습니다. GET/HEAD/OPTIONS에는 추가하지 않습니다. 403 후 다음 수동 시도는 토큰을 새로 준비합니다.
- `COMM_401`만 만료로 처리해 query를 취소·폐기하고 재로그인으로 이동합니다. `MEMBER_401_001`은 로그인 폼 오류입니다. 403은 권한·CSRF 공통 오류로 안내하고 로그아웃하거나 mutation을 자동 재실행하지 않습니다. 서버 장애는 로그인 실패로 위장하지 않고 재시도 UI를 표시합니다.
- Server Component가 로그인으로 redirect한 경우에도 로그인 화면 진입 시 기존 사용자 query cache를 폐기합니다. 초안은 인증 만료 정책에 따라 보존합니다.
- 서버 로그아웃 성공 후 query cache와 앱 소유 `npick:{memberId}:…` 초안을 삭제하고 전체 문서 이동으로 Router Cache를 폐기합니다. 다른 탭에 BroadcastChannel로 로그아웃/계정 변경을 알립니다. 브라우저 뒤로가기의 bfcache 복원 시 새로고침하여 재검증합니다. BroadcastChannel이 없는 환경은 창 복귀·다음 요청의 세션 확인이 보완합니다.
- 만료 시 현재 계정 초안은 보존하고 다른 계정으로 들어오면 이전 계정 초안을 삭제합니다. 이 구현은 초안 정리 규칙만 제공하며 실제 기능별 sessionStorage 백업은 아직 연결하지 않았습니다.

## Route 경계

| Route             | 사용자            | 책임                                        |
| ----------------- | ----------------- | ------------------------------------------- |
| `/landing`        | 공통              | 브랜드 인트로·역할 선택                     |
| `/login`          | 공통              | 역할별 ID·비밀번호 입력                     |
| `/search`         | EDITOR / REVIEWER | 검색어 입력과 검색·문의 기록                |
| `/search/results` | EDITOR / REVIEWER | 결과·필터·근거 확인, Preview, 이상해요 제출 |
| `/review`         | reviewer          | 문의 검수, 영상 등록·처리 현황과 재시도     |

상세 상태와 인수 조건은 저장소의 [`../../docs/frd.md`](../../docs/frd.md)를 기준으로 합니다. route별 `loading.tsx`, `error.tsx`, `not-found.tsx`는 실제 상태 요구가 생길 때 추가합니다.

## 접근성과 오류 상태

FRD의 접근성 기본 계약을 모든 화면에 적용합니다.

- 키보드만으로 주요 작업을 수행할 수 있어야 합니다.
- interactive element에는 visible focus, accessible name과 논리적 tab order가 있어야 합니다.
- 상태, confidence와 오류를 색상만으로 전달하지 않습니다.
- form label과 오류를 연결합니다.
- 비동기 상태 변경은 live region으로 알립니다.
- 긴 한국어 기관명과 OCR 문자열은 줄바꿈과 전체값 확인을 지원합니다.
- `prefers-reduced-motion`을 존중합니다.

## 변경 원칙

- 새 feature는 먼저 `features/<feature>`에 배치합니다.
- 실제 재사용이 확인된 뒤에만 `components` 또는 `lib`로 공통화합니다.
- 계층 책임이나 의존 방향을 바꾸는 결정은 ADR로 남깁니다.
- 라이브러리 추가가 데이터 흐름이나 상태 소유권을 바꾸면 ADR을 먼저 승인합니다.
- 구조를 바꾼 MR은 이 문서의 구조와 의존 방향을 함께 갱신합니다.

## 미확정 사항

다음 항목은 요구사항과 구현 시점에 결정합니다.

- 기능별 query key·무효화·polling 정책의 실제 endpoint 연동 (인증은 ADR 0002로 확정)
- form과 schema validation 도구
- 기능별 data 스키마, 백엔드 한국어 오류·요청 ID 제공 계약
- 단위·컴포넌트·E2E 테스트 도구
- 공통 UI primitive와 디자인 토큰
- feature의 외부 공개 API와 barrel export 정책

팀 전체에 영향을 주는 선택은 [`decisions/`](decisions/)에 ADR로 기록합니다.
