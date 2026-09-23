# Frontend Architecture

이 문서는 N-Pick 프론트엔드의 구조, 책임 경계와 데이터 흐름을 설명합니다. 반드시 지켜야 하는 작업 규칙은 [`../AGENTS.md`](../AGENTS.md), 설치와 실행 방법은 [`../README.md`](../README.md)를 기준으로 합니다. 로그인 글래스 UI의 시각 정본은 [`DESIGN.md`](design-system/DESIGN.md), 동작·상태·접근성 정본은 [`EXPERIENCE.md`](design-system/EXPERIENCE.md)입니다.

## 목표

- Next.js App Router의 route 계층을 얇게 유지합니다.
- 기능 전용 코드와 여러 기능에서 공유하는 코드를 구분합니다.
- Server Component를 기본으로 사용해 불필요한 클라이언트 JavaScript를 줄입니다.
- API, 환경변수와 UI의 경계를 분리합니다.
- 요구사항이 생기기 전에 디렉터리, 상태와 추상화를 미리 만들지 않습니다.

## 현재 구조

검색 결과의 0건 안내는 `wireframes/search-result-details.tsx`의 UI 입력 모델을 사용합니다. `WireframeShell`은 실제 검색 adapter가 전달한 실행·제외 정보를 받아 빈 결과에서도 degraded 상태를 유지합니다. `SearchResultState`는 실제 해석 상태와 제외 정보를 표시합니다. 사용자 결정에 따라 1~9건에는 별도 부족 안내 없이 기존 결과 개수와 카드만 표시합니다. 기록이 없는 수치는 0으로 바꾸지 않습니다. `search-results-api.ts`가 서버의 해석 상태·제외 건수·사유 코드를 화면 모델로 변환합니다.

검색 결과 상단은 입력 검색어와 서버의 해석 상태를 구분합니다. `query_resolution_status`를 기존 adapter와 `getResolverLabel`로 표시하며 검색 중·실패에는 이전 응답의 완료 상태를 표시하지 않습니다. 검색어를 분할한 단어를 해석 결과로 표시하지 않습니다. 실제 인물·장소·날짜 등의 해석 내용은 공개 검색 응답에 아직 없으므로, S15P21A501-59에서 FE용 필드·예시·fallback/null 규칙을 확정한 뒤 S15P21A501-167에서 연결해야 합니다. 과거 해석 복원은 S15P21A501-60의 저장 계약에 별도로 의존합니다.

```text
src/
├─ app/                    Next.js route와 화면 조합
│  ├─ layout.tsx           HTML 뼈대, Metadata, 전역 CSS와 상주 산 배경
│  ├─ page.tsx             랜딩 기본 버전으로 이동
│  ├─ landing/            영상 배경과 역할 선택 랜딩
│  ├─ login/              백엔드 세션 로그인 (역할 query는 안내용)
│  ├─ session/renew/      SSR access 만료 시 브라우저 refresh와 원래 화면 복귀
│  ├─ search/             편집자 검색 입력
│  │  └─ results/         검색 결과와 URL 필터
│  ├─ review/             문의·영상 등록·처리 현황
│  ├─ error.tsx            서버 조회 실패 안내·재시도
│  └─ globals.css          전역 토큰과 전역 스타일
├─ components/             여러 기능에서 공유하는 UI
│  ├─ app-backdrop.tsx      검색 결과·검수의 가독성 베일
│  ├─ mountain-backdrop.tsx 루트 상주 하늘·설산·전경과 로그인·검색 파라랙스
│  ├─ mountain-backdrop.module.css 레이어 마스크·깊이·모션 감소
│  ├─ app-shell.tsx        제품 공통 헤더·역할별 메뉴와 페이지 본문 조합
│  ├─ app-shell.module.css 검색 공통 고정 헤더·검색바 뒤 프로스티드 페이드
│  ├─ api-error-notice.tsx  한국어 오류·후속 안내 공통 표시
│  ├─ query-provider.tsx   TanStack Query와 인증 만료·탭 간 세션 변경 처리
│  ├─ session-boundary.tsx 서버 사용자 snapshot과 클라이언트 세션 재확인
│  ├─ session-renewal.tsx 브라우저 갱신·쿠키 확인·오류 재시도
│  └─ session-controls.tsx 현재 계정·검색 화면의 메뉴 드롭다운·로그아웃
├─ features/               기능 단위 UI와 로직
│  ├─ search/
│  │  ├─ inquiry-api.ts          문의 snapshot·응답 검증·접수 API
│  │  └─ search-placeholder.tsx
│  └─ wireframes/
│     ├─ landing-shell.tsx        영상 히어로와 역할 선택
│     ├─ use-landing-scroll-snap.ts 히어로·역할 선택 사이의 방향별 임계 스크롤
│     ├─ landing-cube.tsx         이전 유리 큐브 시안 (현재 랜딩에서 사용하지 않음)
│     ├─ landing.module.css       영상 히어로·워드마크·역할 카드
│     ├─ login-shell.tsx          로그인 form·mutation·오류와 안전한 복귀
│     ├─ search-entry-shell.tsx   편집자 검색 입력과 결과 진입
│     ├─ search-layout.tsx        검색 입력·결과의 공통 사이드바와 계정 메뉴 조합
│     ├─ search-navigation.ts     검색어·명시 날짜 필터의 결과 URL 생성
│     ├─ search-api-contract.ts   검색 요청 변환·응답 타입과 런타임 계약 검증
│     ├─ date-range.ts            날짜 범위 검증과 URL 복원
│     ├─ date-range-picker.tsx    방송일·촬영일 기간 선택 dialog
│     ├─ date-range-calendar.tsx  시작일·종료일 달력과 월·연도 탐색
│     ├─ search-history.tsx       내 검색·문의 기록 사이드바
│     ├─ my-search-history-api.ts 내 검색 목록·상세 API와 저장된 결과 검증
│     ├─ my-search-history.tsx    내 검색 목록·페이지 이동·오류 복구
│     ├─ search-history-result.tsx 당시 조건·결과·Preview 읽기 전용 조회
│     ├─ my-inquiry-api.ts        내 문의 목록·상세 API와 응답 검증
│     ├─ my-inquiry-history.tsx   내 문의 조회·페이지 이동·상세·오류 복구
│     ├─ search-history.module.css 기록 목록·상태 칩·펼침 레이아웃
│     ├─ demo-scenes.ts          예시 장면과 공통 장면 표시 모델
│     ├─ scene-dialogs.tsx        구간 영상·문의 공통 팝업과 상태별 조회
│     ├─ inquiry-api.ts          문의 제출 snapshot·응답 검증·접수 API
│     ├─ entry-chrome.tsx         로그인 헤더와 로그인·검색 공통 푸터
│     ├─ entry.module.css         신한 로그인·검색 반응형 레이아웃
│     ├─ reviewer-shell.tsx    처리 상태·문의 검수 로컬 상호작용
│     ├─ reviewer-layout.tsx   검수 공통 계정 헤더·문의/처리/등록 사이드바·어두운 배경
│     ├─ review-inquiry-workspace.tsx 문의 목록 조회·URL 보정·목록/상세 조합
│     ├─ review-inquiry-list.tsx 문의 목록·상태 필터·페이지 이동
│     ├─ review-inquiry-detail.tsx 문의 상세 조회·선점·오류 복구와 캐시 갱신
│     ├─ review-inquiry-resolution.tsx 담당자 판정 입력·사유 검증·저장과 캐시 갱신
│     ├─ review-inquiry-snapshots.tsx 당시 필터·검색 해석·결과 기록 표시
│     ├─ review-inquiry-view.ts 화면 매핑·목록 URL 상태·페이지 보정·처리 결과 색상
│     ├─ reviewer-scene-preview.tsx 문의 장면 카드와 공통 영상 팝업 연결
│     ├─ reviewer-progress.tsx 실제 API 기반 처리 요약·상태별 목록·페이지 이동
│     ├─ clip-processing-api.ts 영상 목록·상세 조회와 공개 응답 검증
│     ├─ clip-processing-view.ts 처리 상태 표시와 polling 조건
│     ├─ processing-clip-detail.tsx 영상 요약·원본 영상·처리 기록·대사 정보
│     ├─ processing-refresh-status.tsx 영상 목록·상세의 조회 안내와 마지막 성공 확인 시각
│     ├─ processing-pipeline.tsx 계약 순서의 10단계와 hover·키보드·터치 상세 조회
│     ├─ reviewer-progress-state.ts 탭 타입과 구 화면 단위 테스트용 집계
│     ├─ reviewer-progress.module.css 진행 목록의 테마·반응형 레이아웃
│     ├─ reviewer-board.tsx    검수자 문의 게시판·검색·필터·페이지네이션
│     ├─ reviewer-board-state.ts 목록 조건과 정렬·10개 단위 페이지 계산
│     ├─ reviewer-board.module.css 문의 게시판의 테마·반응형 레이아웃
│     ├─ reviewer-inquiries.ts 구 화면 단위 테스트용 문의 fixture (제품 화면 미사용)
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
      ├─ log.ts            모든 API 응답·실패의 콘솔 기록과 민감 필드 마스킹
      ├─ log-receiver.ts   브라우저 로그 검증·마스킹 후 서버 stdout/stderr 기록
      ├─ idempotency.ts    새 제출용 불투명한 UUID 멱등성 키 생성
      └─ error.ts          안전한 ApiClientError와 개발용 진단 정보 분리
```

현재 와이어프레임 UI를 제품 화면으로 사용하며 디자인은 신한(`shinhan`)을 유지합니다. `/`는 `/landing`으로 이동하고 역할 카드는 `/login?role=editor|reviewer`로 연결합니다. 로그인 후 실제 계정의 역할에 따라 편집자는 `/search`, 검수자는 `/review`로 이동합니다. 권한이 있는 내부 `returnTo`가 있으면 우선 복귀합니다. 검색 결과는 `/search/results`에서 표시합니다. 화면 경로 상수는 `src/lib/routes.ts`가 소유합니다. 기존 테마 주소는 `next.config.ts`의 307 redirect로 새 화면에 연결하고 query를 보존하며 알 수 없는 테마는 404로 처리합니다. 인증·영상 등록 POST·검색·문의 접수·검수 문의 목록·상세·선점·판정 API는 연결되어 있으며 처리 목록·상세 조회도 실제 API와 연결되어 있습니다. 비밀번호와 세션 토큰은 프론트 저장소에 저장하지 않습니다.

랜딩은 `landing-shell.tsx`에서 진입 즉시 앱 아이콘·두 줄 `N / PICK` 워드마크와 배경 영상이 있는 히어로를 보여 줍니다. `NEED? PICK!` 인트로와 대기·등장 애니메이션은 제거했으며, 스크립트 실행 전에도 로고와 영상 포스터·헤더·스크롤 안내가 보입니다. 워드마크에는 Black Han Sans를 적용하며, 배경 영상과 포스터는 `public/media/landing-hero*`, 앱 아이콘은 공통 `AppLogo`, 역할 카드 이미지는 `public/images/role-*.jpg`에서 제공합니다. 너비 1000px 초과·높이 720px 초과인 창에서는 스크롤 진행도에 따라 같은 로고가 좌하단에서 역할 선택 영역으로 이동하며 크기를 맞추고, 안내 문구와 역할 카드는 같은 진행도로 교차 페이드합니다. 역할 영역의 어두운 배경은 화면 전체 너비를 덮고 콘텐츠만 최대 1440px 안에 배치합니다. 너비 1000px 이하 또는 높이 720px 이하에서는 고정을 풀어 히어로와 역할 선택을 일반 문서 흐름에 놓고, 역할 영역에 같은 디자인의 정지 워드마크를 표시합니다. 너비 740px 이하에서는 카드를 한 열로 쌓으며 안내·설명·버튼·푸터를 생략하지 않고 필요한 높이만큼 스크롤합니다. 히어로·역할 선택 사이에서는 같은 방향의 실제 스크롤 변화량이 화면 높이의 10%(최소 64px·최대 120px)를 넘으면 해당 방향의 끝점으로 부드럽게 이동합니다. 방향 전환 시 누적량을 초기화하고 자동 이동 자체의 스크롤은 누적하지 않습니다. 역할 영역 내부의 긴 카드·푸터는 자유롭게 스크롤하며 상단 경계를 넘어 올라온 거리만 히어로 복귀 판단에 사용합니다. Scroll down 버튼도 같은 이동을 사용하고 모션 감소 설정에서는 즉시 도착합니다. 역스크롤 시 데스크톱 로고는 원위치로 돌아오며, 창 크기가 바뀌면 위치와 배치를 다시 계산하고 역할 선택 중 배치가 전환되면 해당 영역을 유지합니다. 카드는 기존 `/login?role=editor|reviewer`로 연결됩니다. `prefers-reduced-motion`에서는 영상 재생·CSS 애니메이션을 멈추고 로고 위치도 이동 애니메이션 없이 전환합니다. 영상 자동 재생이 허용되지 않으면 포스터를 유지합니다.

편집자 검색 화면의 왼쪽 사이드바에는 `이전 검색 기록`과 `문의 사항`을 표시합니다. 각 아이콘 버튼으로 펼치고 같은 버튼·닫기·배경·Escape로 접습니다. `SearchHistory`의 단일 `activePanel` 상태가 기간 선택기·검색 기록·문의 사항과 사이드바 확장을 함께 관리합니다. 기간 선택기는 이 상태를 props로 받아 접힌 사이드바에 팝업만 남지 않습니다. 적용·취소·초기화·닫기·Escape와 바깥 클릭·탐색 영역 밖으로 포커스 이탈 시 함께 접습니다. 다른 메뉴 안으로의 pointerdown·포커스 이동은 닫기로 처리하지 않고 click에서 패널을 전환하므로 오른쪽 끝을 눌러도 펼친 너비를 유지합니다. 사이드바 너비와 모서리, 라벨, 기록 패널은 양방향으로 전환하며 모션 감소 설정에서는 즉시 전환합니다. 닫힌 패널은 `inert`로 포커스와 클릭을 차단합니다. 기록 패널과 사이드바는 같은 높이·계정 버튼과 같은 밝은 frosted glass 표면을 사용하며 목록만 내부 스크롤하며 얇은 청회색 스크롤바를 사용합니다. 검색바도 미세 노이즈와 backdrop blur를 사용합니다. 검색 헤더는 좌측 로고 없이 좌상단에 기본 프로필 이미지를 표시하는 72×72 원형 버튼을 둡니다. 버튼의 오른쪽에는 전체 계정명·역할명·역할별 메뉴·빨간 로그아웃 버튼이 펼쳐지고 바깥 클릭·포커스 이탈·Escape로 닫힙니다. 검색 기록은 패널을 열 때 `GET /search/history?page=0&size=10`으로 본인의 실제 기록을 조회하고 서버 순서대로 표시합니다. 항목을 선택하면 `/search/results?historyId={searchExecutionId}`로 이동해 `GET /search/history/{searchExecutionId}`의 저장된 검색어·날짜 조건·결과·해석 상태를 검색 결과 화면에 복원하며 `POST /search`로 재검색하지 않습니다. 별도 검색 기록 상세 다이얼로그는 두지 않습니다. 저장된 결과 카드는 `SearchResultCard`, 장면 확인은 `ScenePreviewDialog`를 재사용합니다. 0건 결과와 복원 불가 기록을 구분하고, 과거 조건·근거의 null은 현재 데이터로 채우지 않습니다. 계정별 query key와 세션 쿠키를 사용하며 패널 재열기·페이지 이동·결과 화면 진입 시 재조회합니다. 검색 목록·결과의 로딩·빈 기록·오류·재시도를 구분합니다. 문의 사항은 패널을 열 때 `GET /inquiries?page=0&size=10`으로 본인의 실제 기록을 조회하며 패널 안에서 페이지를 이동합니다. 고정 문의·메모리 접수는 사용하지 않습니다. 항목을 선택하면 `GET /inquiries/{feedbackId}`로 최신 상세를 읽고 `InquiryDialog`에서 검색어·영상 제목·구간·설명·상태·처리 결과와 사유를 표시합니다. ID는 문자열을 유지하고 계정별 query key로 캐시를 구분합니다. 서버가 제공하지 않는 썸네일은 영상 아이콘으로, `snapshot_status=unavailable`은 상세 근거 미제공 안내로 표시하며 근거를 합성하지 않습니다. 목록·상세의 loading·empty·error와 수동 재시도를 구분하고 native dialog로 배경 조작을 막으며 Escape·닫기 후 선택한 항목으로 포커스를 돌립니다. 검색 결과에서 문의 접수에 성공하면 내 문의 캐시를 무효화하며, 패널과 상세를 다시 열 때도 서버에서 재조회합니다. 문의 설명 수정·교정 후보 편집 UI는 별도 작업입니다.

제품 화면 `/search`, `/search/results`, `/review`는 `AppShell`이 상단에 고정된 공통 계정 헤더와 화면 이동 메뉴를 제공합니다. `headerContent`로 검색 결과의 검색바 또는 검수 목록·처리 현황의 제목과 영상 등록 버튼을 계정 버튼과 같은 행에 배치합니다. 공통 헤더의 상단 여백은 32px이며 본문 spacer와 사이드바도 같은 기준으로 배치합니다. 검수 목록은 제목 오른쪽에 전체 문의 건수를 표시하고, 영상 처리 상세의 복귀 버튼과 영상 등록 화면의 제목도 계정 버튼과 같은 헤더 행에 둡니다. 검수 본문은 기본 바깥 패딩 `24px 32px 64px 124px`을 사용하며, 문의 목록·처리 현황과 공통 제목은 최대 1440px 안에서 반응형으로 표시합니다. 문의 목록은 상단 패딩을 공통 헤더 spacer와 사이드바 시작점의 차이로 계산하고, 패널의 최소 높이를 화면 높이에서 사이드바 상단과 하단 여백 64px을 뺀 값으로 맞춥니다. 빈 목록·로딩·오류 패널도 같은 높이 기준을 사용하며, 목록이 길어지면 내용에 따라 늘어납니다. 760px 이하에서는 모바일 좌우 패딩과 단일 열 배치를 유지하며 문의 목록의 하단 여백은 사이드바와 같은 64px을 사용합니다. `SessionBoundary`의 현재 계정으로 역할을 읽고 `SessionControls`가 계정·역할·로그아웃을 표시합니다. 편집기자에게는 장면 검색, 검수자에게는 장면 검색과 검수 메뉴를 제공합니다. 검수의 `ReviewerLayout`은 검색과 같은 유리 표면의 왼쪽 사이드바로 문의·처리·영상 등록을 연결하고, 검색 결과와 같은 어두운 배경 레이어를 사용합니다. 공개 랜딩·로그인과 각 page의 서버 접근 검사는 별도로 유지합니다.

검수자는 `/review`에서 Spring의 `GET /review/inquiries`를 통해 최근 접수 순 10개 단위 문의 목록과 전체 상태 건수를 봅니다. 상태는 전체·접수(`open`)·검수 중(`reviewing`)·종료(`closed`)로 서버 필터링하며 `status/page/inquiry`를 URL에 보존합니다. 상세는 `GET /review/inquiries/{feedbackId}`로 문의 당시 검색 실행·필터·근거·처리 이력을 읽고, 접수 상태에서는 `POST /review/inquiries/{feedbackId}/claim`으로 선점합니다. 선점 버튼은 요청 중 중복 입력을 막고 동일 재시도에 같은 멱등성 키를 사용하며 성공 후 목록과 상세를 다시 읽습니다. 선점 충돌·권한·연결 실패는 성공 상태로 바꾸지 않고 최신 상태 확인·목록 복귀·안전한 재시도 중 다음 행동을 안내합니다. 종료 여부와 `resolution`은 분리해 표시합니다. `no_action`과 `deferred`는 수정 완료 색상으로 표현하지 않습니다. 현재 백엔드 목록 계약에 검색·임의 정렬 조건이 없어 해당 조작은 제공하지 않습니다. 페이지는 `useSearchParams`를 위한 Suspense 경계를 제공하고 현재 계정은 `/auth/me`의 `loginId`를 사용합니다.

문의 목록과 상세의 장면 요약은 API의 `clipTitle`, `startTimeMs`, `endTimeMs`를 사용하며 제목이 없으면 `제목 없는 영상`으로 표시합니다. 썸네일은 합성하지 않습니다. 문의 상세의 `ReviewInquiryPreview`는 실제 `clipId`와 밀리초 구간을 공통 Preview에 전달하며 `/media/{clipId}`로 원본을 재생합니다. 상세는 문의 당시 검색 결과·필터·해석·적용 기록과 현재 태그를 분리합니다. 과거 JSON과 `resolverOutputJson`은 화면에 원문으로 노출하지 않으며, 현재 근거는 태그별 출처·검증 상태·장면/클립 범위를 함께 표시합니다. 당시 결과와 적용 기록은 허용된 항목을 읽기 전용 목록으로 표시하며, 미지원 형식은 기록 없음과 구분합니다. 미지원 JSON 구조는 BE snapshot 내부 계약 확정 후 확장해야 합니다. 선점 후 담당자를 로그인 계정으로 합성하지 않고 상세 재조회 응답만 표시합니다. 과거 검색 기록은 읽기 전용이고 현재 태그로 다시 계산해 덮어쓰지 않습니다.

선점한 검수자는 `PUT /review/inquiries/{feedbackId}/resolution`으로 판정과 최대 2,000자의 사유를 저장합니다. `no_action/deferred`는 사유가 필수이며 즉시 `closed`로 종료됩니다. `tag_correction/patch_parse/exclude_scene`는 판정만 저장하고 후속 교정·검증 API가 완료될 때까지 `reviewing`을 유지합니다. 프론트는 서버 응답 뒤 목록과 상세를 다시 읽어 상태를 확정하고 요청 중 중복 입력과 다른 검수자의 저장을 막습니다.

검색 결과의 문의 입력은 Preview의 `이상해요`에서만 열며 `POST /search/results/{resultId}/inquiries`로 선택 설명을 전송합니다. 화면 장면 ID와 서버의 저장된 결과 ID를 분리하고 `SearchResult.searchResultId`만 API 경로에 사용합니다. 데모 결과에는 서버 ID를 만들지 않으며 저장 ID가 없거나 snapshot 저장에 실패하면 이유와 함께 문의를 비활성화합니다. 실제 검색 응답의 저장 결과 ID를 Preview에서 문의 접수로 전달하며, 접수 상태도 이 ID로 구분해 재검색의 새 결과에 이전 접수 상태가 붙지 않도록 합니다. 제출은 trim한 설명과 frozen snapshot·UUID 멱등성 키를 보존하며 자동 재시도하지 않습니다. 실패 후 설명을 바꾸지 않은 수동 재시도는 같은 snapshot/key를 사용하고 입력 변경 시 새 요청으로 바꿉니다. 제출 중에는 입력·중복 제출·dialog 닫기를 잠급니다. 성공 응답의 양의 `feedbackId`와 `OPEN/REVIEWING/CLOSED`를 확인한 뒤 접수 확인과 현재 상태를 표시하며 접수 자체로 현재 결과를 숨기거나 즉시 개선하지 않습니다. 백엔드는 동일한 검색 결과·신고자의 기존 문의를 현재 상태로 반환할 수 있으므로 재전송 응답을 새 접수나 `open`으로 바꾸지 않습니다.

검수 화면 왼쪽 사이드바는 URL로 선택 상태를 계산합니다. 기본 `/review`는 문의, `view=processing`은 처리, `view=upload`는 영상 등록으로 표시합니다. 문의·처리 이동은 `getReviewTabUrl`로 상세 선택(`inquiry/clip`)과 처리 하위 `tab`을 지우고 목록 조건과 나머지 query를 유지합니다. 처리 하위 탭은 기존 `tab=uploads/completed`를 사용하며 생략하거나 알 수 없는 값이면 문의 처리 중을 표시합니다. 문의·처리 이동은 브라우저 이력에 남고 새로고침·뒤로가기·앞으로가기로 복원됩니다. 서버에 저장한 검수 상태는 이동 후 재조회하며 등록 취소는 문의 목록으로 돌아갑니다.

영상 등록은 `/review?view=upload`에서 제공하며 문의 목록과 영상 처리 화면에서 진입할 수 있습니다. 헤더 행에서 제목과 문의 목록 복귀 버튼을 양끝에 두고 등록 중에는 복귀 버튼도 잠급니다. 등록 페이지는 최대 너비와 자동 좌우 margin 없이 본문을 채우며, 폼은 사이드바와 같은 무테두리 프로스티드 그레인·반투명 배경·24px blur를 사용합니다. MP4/MOV 영상 1개, 선택 자막(SRT/VTT·승인 JSON, 10 MiB 이하) 1개, 선택 일반 대본(TXT) 1개를 클릭 또는 드래그로 고르고 파일명·용량 확인과 삭제·교체가 가능합니다. 방송분 `broadcast`과 자료 영상 `archive`, 선택 제목, 독립적인 방송일·촬영일, 이용 권한·외부 처리 확인을 입력합니다. 자료 영상은 방송일 입력을 숨기고 multipart에도 포함하지 않습니다. 일반 대본은 fatal UTF-8 검사 뒤 `script_text`로 읽으며 자막 파일의 서버 저장·처리는 선행 이슈가 소유합니다.

폼은 검증을 통과한 File 포함 snapshot과 UUID 멱등성 키를 함께 소유하고 `POST /api/v1/clips`를 실행합니다. 네트워크·취소·비정상 응답·5xx·처리 중/결과 확인 불가 오류의 수동 재시도는 같은 snapshot/key를 쓰되 매 시도마다 새 `FormData`를 만듭니다. 입력을 수정하거나 서버가 새 요청 키를 요구하면 snapshot/key를 폐기하며 mutation을 자동 재시도하지 않습니다. 대본 읽기부터 응답까지 폼과 `ReviewerShell` 상단 이동을 잠그고 실패 시 File을 포함한 입력을 유지합니다. 허용된 검증 필드의 안전한 문자열만 인라인 오류로 사용하고 나머지는 공통 API 오류 UI로 표시합니다.

성공 응답은 문자열 `clip_id`, `pipeline_run_id`와 `queued`만 인정하며, `outcome`으로 신규 등록(`created`)과 이미 있던 clip 의 반환(`duplicate_own`·`duplicate_other`)을 구분합니다. `outcome`이 없거나 아는 값이 아니면 신규 등록으로 읽어 이전 서버와 섞여 배포돼도 흐름이 끊기지 않습니다. 중복이면 `ReviewerShell`이 그 사실과 입력값이 저장되지 않았음을 알리고, 등록 결과 배너에 요청 당시의 파일명을 쓰지 않습니다 — 그 화면에 열리는 clip 은 다른 사람의 등록일 수 있어 요청 값과 서버 값을 섞으면 존재하지 않는 조합을 보여주게 됩니다. `ReviewerShell`은 영상 목록 캐시를 무효화하고 `view=processing&tab=uploads&clip=<clipId>`로 이동해 실제 상세 GET을 실행합니다. 새로고침에도 URL의 ID로 서버 기록을 조회하며, 처리 상태와 단계 정보를 등록 요청 메모리에서 만들지 않습니다. media decode, pipeline enqueue와 등록 결과의 영속성은 서버 책임입니다.

처리 현황은 `view=processing`에서 `문의 처리 중` 탭을 먼저 보여주고, `tab=uploads`와 `tab=completed`로 영상 상태를 나눕니다. 문의는 서버의 REVIEWING 목록, 종료 건수는 statusCounts.closed를 사용합니다. 영상은 최신 run의 대기·진행·실패·성공·기록 없음 상태를 표시합니다. 상세에서 돌아오면 선택한 탭과 progressPage를 유지합니다. 신규 등록의 단계 수와 상태는 상세 API 응답을 따르며 시간 경과로 임의 증가하지 않습니다.

영상 목록·상세의 갱신 안내는 기존 5초 polling 조건과 Query의 요청 상태를 따릅니다. 마지막 확인 시각은 성공한 조회의 `dataUpdatedAt`이며 처리 단계 변경 시각과 구분합니다. 조회 오류·완료·실패에서는 자동 확인 종료를 안내하고, 오프라인·백그라운드 화면에서는 일시 중지를 표시합니다. 등록 직후 실행 기록이 없는 상세는 등록 시각부터 1분 동안 기록을 자동 확인하며, 1분 경계에서 안내를 수동 새로고침으로 바꿉니다. 이 경계용 단발 타이머는 조회 간격을 바꾸거나 진행률을 만들지 않습니다. 요청·시각 안내는 live region 밖에 두어 5초마다 반복 낭독하지 않고, 실제 처리 상태 변경만 기존 상태 영역에서 알립니다. 갱신 중 원본 영상과 키보드 포커스는 유지합니다.

최신 처리 시도의 음성 인식 상태는 대사 기록의 알려진 `asr_status`를 우선하여 `대기 / 처리 중 / 완료 / 실패 / 생략`으로 표시합니다. 상태가 미확인이면 같은 `pipeline_run_id`의 `asr` 단계로만 보완하며, 근거가 없으면 `상태 정보 없음`으로 남깁니다. 대사 출처의 `record_status=unavailable`은 별도로 저장된 ASR 상태를 무효화하지 않습니다. 전체 실행의 성공·실패와 `asr_required`로 ASR 상태를 추정하지 않으며, 후보 구간의 실제 0건과 null, 현재 검색 제공 결과의 대사 출처는 기존대로 구분합니다.

문의 검수 상세는 일반 문의 목록과 처리 현황에서 같은 `InquiryDetail`을 사용합니다. 실제 문의·장면·당시 검색 snapshot·근거·담당 이력을 조회하고 claim·resolution 성공 시 관련 목록과 상세 캐시를 갱신합니다. 서버에 없는 후보 재검색·검증·확정 결과를 로컬에서 생성하지 않습니다. 이전 mock의 교정 편집·재시도 화면은 제품 경로에서 제거했으며 추가 API 범위는 웹 API 계약 §6.5와 §7에 기록합니다.

문의 상세의 선택된 장면은 `ReviewerScenePreview`에서 해당 문의의 제목·시작/종료 시각·확인 근거를 공통 `ScenePreviewDialog`에 전달합니다. 문의 생성 버튼은 검수자 팝업에서 제공하지 않으며, 닫기·Escape 후 장면 카드로 포커스를 돌려주고 검수 진행 상태를 유지합니다. 문의별 키로 팝업 상태를 분리해 다른 문의나 목록으로 이동하면 팝업을 닫습니다. 데모 문의의 `ReviewerScenePreview`에는 clip ID가 없으므로 재생 불가를 안내합니다. 실제 API 문의 상세는 `ReviewInquiryPreview`가 담당합니다. 원본 길이·파일명·날짜가 없는 문의에는 값을 만들어 넣지 않습니다.

다른 디자인 시스템의 전용 스타일과 화면 분기는 제거하고 신한(`shinhan`) 구현만 유지합니다. 신한 검색 입력과 결과 화면은 방송일·촬영일별 Date Range Picker를 제공하며, 좌측 시작일·우측 종료일을 독립적으로 선택하고 취소·초기화·키보드 방향 이동을 지원합니다. 각 달력 제목은 일 보기 → 월 선택 → 연도 선택(예: 2020 - 2030)으로 전환하고, 연도 → 월 → 날짜 순서로 돌아옵니다. 모바일에서도 좌우 배치를 유지합니다. 적용 전까지는 임시 값만 바뀌며, 역순 선택은 즉시 시작일·종료일을 재정렬하고 누락 기간은 적용을 막습니다. 날짜 범위는 둥근 시작일·종료일 배경 뒤로 연결하고 범위와 주 경계의 모서리를 살짝 둥글게 처리하며 최근 연도 칩과 월 이동 버튼에는 테두리를 두지 않습니다. 팝업은 미세 노이즈와 blur를 합친 frosted glass 표면을 사용하고, 초기화 버튼에는 밑줄을 두지 않습니다. 검색 사이드바는 `기간 설정` 메뉴 하나를 제공하고 펼치면 적용 중인 날짜 기준을 표시하며, 취소는 기존 표시를 유지하고 초기화는 선택한 기준의 기간만 즉시 지운 뒤 패널을 닫습니다. 팝업 헤더의 기준 선택 드롭다운에서 방송일·촬영일을 전환하며 각 기준의 초안을 보존하고, 적용 시 두 조건을 한 번에 갱신합니다. 기준 드롭다운은 채움색 없이 굵게 표시하며 최근 1·2·3년 버튼은 오른쪽에 10px 간격으로 배치합니다. 진입 화면의 입력 중 값은 form 가까이에 두고, 제출하면 `search-navigation.ts`가 검색어와 두 날짜를 `q/broadcastFrom/broadcastTo/filmingFrom/filmingTo`로 분리해 결과 URL을 만듭니다. 결과 화면도 같은 생성기를 사용하며 URL을 검색 요청 상태의 정본으로 읽습니다. 선택한 기간은 양 끝 날짜를 포함합니다. navigation 중에는 검색 입력·날짜·제출을 잠그고, 결과 화면은 이전 카드를 `검색 중` 상태로 바꿔 새 결과와 혼동되지 않게 합니다. `search-api-contract.ts`가 URL 상태를 [웹 API 계약](../../docs/contracts/web-api.md)의 요청 본문으로 변환하며, 백엔드가 필터링·정렬한 응답 순서를 클라이언트가 다시 필터링하거나 정렬하지 않습니다. `state=empty`와 `state=failed`는 디자인 확인용 상태 URL이며, 실패 화면의 재시도는 검색어·기간을 보존하고 실패 시연 상태를 해제합니다. 현재 표준 검색 결과 경로는 실제 검색 API를 호출합니다. 결과 그리드는 `SearchResultCard`가 제목·썸네일 우하단 장면 구간·키워드와 키워드 행 우측의 텍스트 검증 칩을 맡고, 칩의 hover·focus 툴팁에는 사용자용 근거 필드와 값만 표시합니다. 출처와 OCR·VLM 같은 기술명은 카드에서 숨깁니다. 결과 카드에는 백엔드 내부 점수를 퍼센트 일치도로 변환해 노출하지 않으며 검색 채널 선택과 클라이언트 재정렬 UI를 제공하지 않습니다. `WireframeShell`은 날짜 요청 상태와 선택한 Preview 상태를 소유하고, Preview는 전체 근거와 상태 모델을 유지합니다. 표시명·방송일·촬영일·샷 유형·장면 유형은 검색 API 응답 모델에 보존하되 결과 카드에는 노출하지 않습니다. 장면 유형은 Preview에도 노출하지 않습니다. `demo-scenes.ts`의 예시 장면은 디자인 시안과 단위 테스트에만 사용하며 실제 검색 기록에는 사용하지 않습니다. 자동 생성 근거에는 `verified/unverified`만 사용하고, 정보 부재는 `unknown`, 사람의 판단은 `rejected/withdrawn`으로 분리합니다.

### 검색 API FE 적용 규칙

검색 입력과 결과는 `SearchLayout`을 공유합니다. 이 레이아웃은 `AppShell`의 계정 드롭다운과 `SearchHistory`의 방송일·촬영일·검색 기록·문의 사이드바를 조합합니다. 검색 결과의 재검색 form은 `searchField` 슬롯으로 전달해 계정 버튼 오른쪽의 같은 행에 배치합니다. 공통 검색 헤더는 상단에 고정되며 본문에는 그 높이만큼 공간을 확보합니다. 검색바 뒤에는 별도 프로스티드 그레인·배경 흐림 레이어를 두고 아래로 갈수록 투명해지는 마스크를 적용합니다. 이 레이어는 포인터 이벤트를 받지 않고 계정 메뉴와 사이드바의 상호작용을 가리지 않습니다. 입력 화면은 필터 초안을, 결과 화면은 URL에서 읽은 필터와 재검색 콜백을 전달합니다. 결과의 별도 상세 필터 레일은 사용하지 않습니다. 결과 배경에는 `AppBackdrop`의 어두운 베일을 적용하며 검색 상태 패널은 기존 프로스티드 그레인 텍스처와 배경 흐림을 사용합니다.

요청·응답 JSON과 상태 불변식의 정본은 [웹 API 계약](../../docs/contracts/web-api.md#5-장면-검색-api--계약-확정-연결-대기)입니다. FE는 URL의 `q/broadcastFrom/broadcastTo/filmingFrom/filmingTo`를 계약의 `query/explicit_filters`로 변환하고 `search-api-contract.ts`에서 응답을 런타임 검증합니다. 백엔드가 정한 `data.results` 순서를 다시 필터링하거나 정렬하지 않습니다.

검색 실행의 정상·degraded 상태와 사람 검수 규칙 적용 여부는 개별 장면이 아니라 `WireframeShell`이 한 번 소유합니다. `SearchResultNotices`가 결과 상단과 Preview에 같은 누락 사유·검수 규칙·송출 전 확인 문구를 제공하며, `state=degraded-resolver`, `degraded-dense`, `degraded-snapshot`, `review-rule`은 실제 API 연결 전의 화면 검증용 상태입니다. snapshot 저장 실패 결과는 볼 수 있지만 저장된 검색 식별자가 필요한 문의는 이유와 함께 비활성화합니다. `SearchResults`는 공통 client로 POST /search를 호출하고 응답 검증 후 `presentSearchResponse`로 카드에 연결합니다. 검색 실패는 데모로 대체하지 않으며 같은 조건 재시도는 명시적으로 실행합니다. clip ID와 scene ID는 문자열로 보존하고 Preview에 실제 구간을 전달합니다. 표준 /search/results route는 실제 API를 사용하며 WireframeShell의 데모 입력은 기존 단위 테스트용으로만 유지합니다. 121번 문의 접수 API는 실제 검색 결과 Preview와 연결되어 있습니다.

신한 Preview의 `ScenePreviewPlayer`는 ID 기반 MP4를 HTML video로 직접 요청하고, 장면 시작점 seek 후 재생합니다. 기본적으로 장면 끝 이후에도 계속 재생하며 전체 위치·IN/OUT·구간 다시 재생을 제공합니다. `구간 반복`을 켜면 선택한 IN/OUT 구간을 반복하며 원본 끝과 OUT이 같아도 이어집니다. 반복 토글은 미디어를 다시 불러오지 않고 일시 정지를 유지하며, 미리보기를 다시 열거나 장면이 바뀌면 꺼짐으로 시작합니다. 미디어 요청은 세션 credentials와 브라우저 Range를 사용하고 JSON client를 통과하지 않습니다. 자동 재생 차단·로딩/이동 실패·구간 불일치를 안내하며 닫기와 장면 교체 시 이전 미디어를 정리합니다. ID 없는 기록 데모는 재생 불가로 표시합니다. 실제 검색 adapter는 `clipId`를 보존하고 `toScenePreviewMedia`로 밀리초를 초로 변환합니다. `preview=loading`의 타이머 기반 가짜 로딩은 제거했습니다. `/review?view=processing`은 `GET /review/inquiries?status=REVIEWING`과 `GET /clips`를 병렬 조회합니다. 문의의 `statusCounts`와 영상의 `run_counts`를 요약·탭 건수에 사용하며, 목록은 서버 필터와 10건 pagination을 사용합니다. 페이지는 기존 문의 게시판의 `page`와 분리한 `progressPage`로 URL에 보존하고 탭 변경 시 초기화합니다. 영상 등록 성공 시 실제 ID의 `GET /clips/{id}`로 이동하므로 새로고침에도 기록을 다시 조회합니다. 최신 처리 상태와 활성 검색 제공 여부를 분리하고 partial/unavailable/unsupported_version의 null 단계 수를 0이나 추정 진행률로 바꾸지 않습니다. 영상 목록은 전체 queued/running이 있을 때, 상세는 해당 run이 queued/running일 때 5초마다 갱신하며 terminal·오류에서 중단합니다. 문의 현황은 REVIEWING이 있을 때 15초마다 갱신합니다. 오류는 영역별로 표시하고 명시적으로 다시 조회할 수 있습니다. 상세는 단계·실패·자동 재시도 이력·대사 채택 기록을 표시하며 원본 영상은 세션 credentials를 포함한 `GET /media/{clipId}`로 재생합니다. 문의 상세·선점·판정은 기존 실제 API 컴포넌트를 재사용합니다. 수동 재처리, 장면 목록·썸네일, 제공되지 않는 파일 메타데이터는 합성하지 않습니다. 추가 BE 계약은 `docs/contracts/web-api.md` §6.5와 §7을 따릅니다. `registration-processing.ts`의 구 mock 변환은 제품 경로에서 사용하지 않습니다.

## 계층별 책임

| 계층         | 책임                                                | 포함하지 않는 것                        |
| ------------ | --------------------------------------------------- | --------------------------------------- |
| `app`        | route, layout, metadata, 페이지 수준 조합           | 재사용 가능한 기능 로직, 공통 HTTP 처리 |
| `features`   | 특정 사용자 기능의 UI, 상태, API 함수, Hook         | 다른 기능의 내부 코드, 전역 기반 코드   |
| `components` | 공유 UI, 앱 전역 세션 UI 경계와 인증 query·mutation | 특정 기능의 업무 규칙·API 호출          |
| `lib`        | 환경변수, HTTP client, 앱 전역 보안 기반 코드       | 화면 표현과 기능별 상태                 |
| `public`     | 브라우저에 그대로 제공하는 정적 파일                | 빌드가 필요한 소스 파일                 |

## 의존 방향

로그인·검색 입력의 `EntryFooter`에는 저작권 문구만 표시하며 배경 움직임 토글과 브라우저 저장 설정은 사용하지 않습니다. 과거 `npick:parallax-enabled` 저장값은 무시합니다. 배경 파라랙스는 기본 활성화하며 운영체제 모션 감소 설정을 우선합니다.

루트 `layout.tsx`에 `MountainBackdrop`을 한 번 배치하여 모든 화면이 같은 하늘·설산·숲과 호수 3개 레이어를 유지합니다. 이미지는 `public/images/login-mountains/`에 보관하며 SVG 능선 마스크로 합성합니다. 초기 HTML부터 레이어를 표시하고 원본 사진이나 별도 합성 사진으로 교체하지 않습니다. 로그인(`/login`)과 검색 입력(`/search`)에서만 포인터와 실제 문서 스크롤에 깊이 0.12/0.45/1로 반응하며 64px 여유 영역 안에서 이동량을 제한합니다. 다른 경로에서는 즉시 기본 위치로 정지하고 모션 이벤트와 프레임을 정리합니다. 모션 감소·숨긴 탭에서는 기존 정지 규칙을 유지합니다. `requestAnimationFrame`은 이동 중에만 실행합니다. 배경은 인증 상태를 소유하지 않습니다. 동작·폼·모바일 검증은 `e2e/login-mountain-backdrop.spec.ts`와 `e2e/search-experience.spec.ts`가 담당합니다.

랜딩은 루트의 정지 산 레이어 위에 기존 영상과 포스터를 표시합니다. 역할 카드의 같은 탭 로그인 이동은 산 이미지 3장과 마스크 2장의 디코딩을 기다리며, 그동안 랜딩 영상을 유지합니다. 루트의 준비 상태만 Context로 공유하고 대기 중인 역할과 이동은 `LandingShell`이 소유합니다. 자산 오류 또는 8초 응답 지연 시에는 준비 대기를 종료하여 로그인 진입을 막지 않으며, 불러온 레이어와 기본 배경색을 유지합니다. 검색 결과(`/search/results`)·문의와 처리 현황(`/review`)에서는 `AppBackdrop`이 기존 가독성 베일만 덮습니다. 별도 정지 사진·물방울·Three.js·Rapier는 불러오지 않습니다. 결과·검수의 정지 상태·모바일·검색 왕복 시 동일 DOM 유지는 `e2e/app-backdrop.spec.ts`, 랜딩 전환의 지연·실패·타임아웃과 스크립트 없는 초기 표시는 `e2e/login-mountain-backdrop.spec.ts`로 검증합니다. 로그인 성공 후 이전 계정의 캐시를 폐기하는 전체 문서 이동 정책은 유지합니다.

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

문의 상세의 공통 헤더는 `ReviewInquiryWorkspace`가 제목 `문의 상세`와 오른쪽 끝의 `문의 목록으로` 버튼을 소유합니다. 목록 복귀는 문의 ID만 URL에서 지우고 기존 상태·페이지 조건을 보존하며, 상세 조회 중·실패 상태에서도 헤더를 유지합니다. 본문의 별도 툴바와 중복 상세 표시는 사용하지 않습니다. 문의 상세는 `review-inquiry-detail.module.css`에서 반응형 2열 레이아웃과 진행 단계 표시를 관리합니다. 문의 내용·당시 검색어·장면 구간을 왼쪽에, 선점·검수 이력·판정 입력을 오른쪽에 배치하며 모바일은 한 열로 표시합니다. 후보 작성·검증과 당시 검색 기록·현재 태그는 별도 영역에 두고 기존 API와 캐시 갱신 동작을 유지합니다.

등록 파일 선택은 `registration-files.ts`에서 실제 내용을 검사합니다. 영상은 MP4/MOV 컨테이너 헤더만 부분 읽기하고, 자막은 UTF-8과 SRT/VTT/승인 JSON 구조를 검사합니다. TXT는 고유 헤더가 없어 청크 단위 UTF-8·바이너리 제어 문자·문서 헤더 검사로 형식 위장을 거부합니다. 실제 미디어 디코딩·영상 길이와 자막의 상세 정합 검사는 서버가 최종 수행합니다. 검사 중에는 제출을 막고 이전 파일의 늦은 검사 결과는 무시합니다. 선택한 영상의 정보는 별도 파일 행 대신 선택 영역 안에 표시합니다. 선택 전 중립색·점선에서 선택 후 초록색·실선·체크 표시로 바꾸며, 일반 동작은 파랑 계열이고 빨강·분홍 계열은 오류에만 사용합니다.

검색 날짜 선택기의 오늘은 `lib/seoul-date.ts`의 Asia/Seoul 날짜를 사용합니다(S15P21A501-287). 등록도 같은 날짜 계산을 사용하지만 방송일·촬영일의 상호 제약은 `registration-dates.ts`에 유지합니다. 검색 달력은 열기 이벤트에서 오늘을 주입하고 1950-01-01부터 오늘까지 날짜·월·연도와 키보드 이동을 제한합니다. `RegistrationDatePicker`는 검색의 `DateRangeCalendar`를 단일 날짜 모드로 재사용하고 `registrationDateBounds`의 최소·최대 날짜를 일·월·연도 탐색과 키보드 이동에 적용합니다. 등록 달력도 반투명 유리 표면·파란 선택 표시를 사용하며 body 포털에서 입력 주변의 화면 안에 배치합니다. 선택은 즉시 반영하고 날짜 지우기·Escape·바깥 클릭·포커스 이탈로 닫습니다. 스크롤·화면 크기 변경 시 입력 위치를 따라가고 입력이 화면을 벗어나면 닫습니다. 기본 date 입력은 YYYY-MM-DD 텍스트 입력과 달력 버튼으로 교체하고, 직접 입력한 날짜의 실제 존재 여부와 1950년 하한을 제출 전에 검사합니다. 잘못된 입력은 상대 날짜의 달력 범위에 사용하지 않습니다. 검색 달력의 비활성 버튼은 기본 커서를 사용하고 클릭 시 선택 불가 툴팁을 표시합니다. 공통 `Tooltip`은 검색 사이드바의 메뉴명(`기간 설정`, `이전 검색 기록`, `문의 사항`)과 선택 불가 안내를 화면 경계 안에 표시하며, 네이티브 title을 사용하지 않습니다. `SearchResults`는 hydration 이후 같은 상한으로 URL 범위를 검사하며, 잘못된 기간을 필터 없는 검색으로 보내지 않습니다. 기간의 구조 검증은 순수 함수로 유지하고 필요한 호출부에서 상한을 전달합니다.

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

`fetchJson`은 실제 전송한 호출마다 `[API]` 로그를 남깁니다. 브라우저는 Console에 표시하고 같은 오리진의 `POST /client-logs`에도 비동기 전달합니다. 이 Route Handler는 `lib/api/log-receiver.ts`에 처리를 위임하며, nginx의 `/api/` 백엔드 경로와 겹치지 않아 기존 프론트 upstream으로 연결됩니다. 수신부는 요청 오리진·JSON 형식·최대 24 KiB 크기·필드 형식을 검사하고 응답을 다시 마스킹합니다. 로그인 실패도 기록해야 하므로 세션 쿠키를 보내거나 요구하지 않습니다. 외부 텔레메트리 서비스는 사용하지 않습니다.

서버는 브라우저 수신 로그와 서버 측 API 호출을 `[API]` 접두사의 한 줄 JSON으로 stdout/stderr에 기록합니다. `source=browser|server`와 서버 기록 시각 `loggedAt`으로 출처를 구분하며, 브라우저 보고는 백엔드의 감사 기록이 아닙니다. 성공·취소는 info, 나머지 실패는 error이며 메서드·query를 제외한 경로·HTTP 상태·소요 시간(ms)·요청 ID·결과와 마스킹한 JSON 응답 snapshot을 기록합니다. 비밀번호·토큰·쿠키·경로·URL·검색어·대사·설명 등 민감 필드는 가리고, 문자열은 200자 및 중첩은 12단계로 제한합니다. 요청 본문·헤더, 비정상 JSON/HTML 원문, 원래 예외는 기록하지 않습니다. 전송 상한을 넘는 응답은 본문을 생략하고 메타데이터만 전달합니다. 로그 전달은 5초 제한의 keepalive 요청이며 실패 시 재시도하거나 원래 API 요청을 실패시키지 않습니다. 네트워크 단절이나 브라우저 종료 시 로그 전달은 보장하지 않습니다.

프론트 이미지를 재빌드·배포한 뒤 SSH로 접속한 서버에서 다음 명령으로 확인합니다. 브라우저 Console에서도 `[API]` 필터와 Info·Error 수준을 사용할 수 있습니다.

```bash
docker logs --follow --tail 100 npick-frontend 2>&1 | grep --line-buffered '\[API\]'
```

HTTP·업무 실패·네트워크·본문 수신 실패·비정상 응답·취소는 `ApiClientError`로 정규화합니다. `kind`, HTTP `status`(응답 전 실패는 0), 사용자용 `message`, stable `code`, 선택적 `requestId`를 제공합니다. 원래 응답의 `path/data`와 예외는 직렬화되지 않는 `diagnostics` 접근자로 분리하며 UI에 전달하거나 출력하지 않습니다. signal은 호출자가 전달하고 자동 재시도는 하지 않습니다. 공통 client는 `credentials: include`와 `redirect: error`를 강제하고 브라우저 변경 요청에 CSRF 헤더를 추가합니다.

멱등성 키는 `src/lib/api/idempotency.ts`의 `createIdempotencyKey()`가 불투명한 UUID로 생성합니다. 기능별 제출 경계가 입력과 키를 함께 소유하여 같은 논리적 요청의 재시도에는 기존 키를, 새 제출에는 새 키를 사용합니다. `fetchJson`은 `idempotencyKey` 옵션을 `Idempotency-Key` 헤더로 전달하고 조회 요청의 키는 전송 전에 거부합니다. 공통 client는 키를 자동 생성하거나 변경 요청을 자동 재시도하지 않습니다.

`components/api-error-notice.tsx`는 오류 객체를 받아 한국어 메시지와 후속 안내만 `role="alert"`로 표시합니다. 오류 코드·요청 ID는 화면에 표시하지 않고 `ApiClientError`와 기존 `[API]` 로그에 유지합니다. form의 `aria-describedby`에 연결할 수 있는 `id`를 지원합니다. 기능 계층이 변환한 사용자 문구와 후속 안내가 있으면 이를 우선합니다. 검색 오류 알림은 `search-error-presentation.ts`에서 stable `SRCH_*` 코드와 오류 종류를 사용자 관점의 존댓말 문구로 변환하며 서버의 내부 문장을 직접 표시하지 않습니다. 그 밖의 정상 한국어 서버 메시지는 우선하며, 현재 백엔드의 고정 영어 메시지는 `code/message`가 정확히 일치하는 6개 조합만 번역합니다. 메시지 누락·타입 오류·한국어 안내 계약 위반(내부 경로·HTML·JSON·예외 trace·진단 코드 등)에는 일반 한국어 안내를 사용합니다. 한국어와 `validation_error` 같은 진단 문자열이 섞여 있어도 원문을 표시하지 않습니다. 등록 시 미매핑 검증 오류는 일반 입력 안내, 자막 검증 오류는 자막 항목의 한국어 안내로 표시합니다. 처리 목록·상세·실패 시도 기록도 원시 오류 코드를 노출하지 않습니다. 임의 JSON 응답의 `message`나 일반 `Error.message`는 표시하지 않습니다.

**현재 연동 차이:** FRD §6.3의 API 오류 코드·요청 식별자 계약은 유지합니다. FE는 본문의 `requestId`, 없으면 `X-Request-ID` 응답 헤더를 읽어 진단용으로 보존하며, 미제공 ID를 임의 생성하지 않습니다. 백엔드의 한국어 메시지·요청 ID 생성과 교차 오리진 호출 시 해당 헤더의 CORS 노출은 별도 연동 작업입니다. 공통 client와 오류 UI는 로그인·로그아웃·세션 조회에 연결되어 있습니다.

S15P21A501-252·254·256에서 검색어는 trim 후 2~500자, 선택 문의 내용은 2,000자, 선택 제목은 50자로 제한합니다. 제목은 BE의 요청·도메인 검증에도 같은 상한을 적용하며 기존 제목·검색 기록과 DB 저장 길이(`varchar(500)`)는 유지합니다. `input-validation.ts`의 동일 검증을 검색 입력·재검색·URL 요청 생성에 적용하며, 문의도 제출 API 함수에서 상한을 확인합니다. 길이는 HTML `maxLength`·Java `String.length`와 같은 UTF-16 단위입니다. 초과 붙여넣기는 전체를 거부하고 안내하며 값을 임의로 잘라 제출하지 않습니다. 영상 MP4/MOV 10 GiB 제한을 유지하고 SRT/VTT·승인 JSON 자막은 10 MiB까지 선택할 수 있습니다. 자막 내용·인코딩·시간 구간과 실제 영상 검사는 서버가 담당합니다. 문의 상한의 BE 검증은 후속 작업이며 FE 제한은 API 직접 호출을 제한하지 않습니다. 파일 제한은 저장소 기본 설정 기준이므로 배포 설정과 함께 맞춰야 합니다. 서버 전체 요청 한도의 자막·대본 여유분 보완은 이번 변경에 포함하지 않으며, HTTP 413은 전체 첨부 크기를 줄이도록 안내합니다.

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
| `/landing`        | 공통              | 영상 히어로·역할 선택                       |
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
