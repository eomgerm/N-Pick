# Frontend Architecture

이 문서는 N-Pick 프론트엔드의 구조, 책임 경계와 데이터 흐름을 설명합니다. 반드시 지켜야 하는 작업 규칙은 [`../AGENTS.md`](../AGENTS.md), 설치와 실행 방법은 [`../README.md`](../README.md)를 기준으로 합니다.

## 목표

- Next.js App Router의 route 계층을 얇게 유지합니다.
- 기능 전용 코드와 여러 기능에서 공유하는 코드를 구분합니다.
- Server Component를 기본으로 사용해 불필요한 클라이언트 JavaScript를 줄입니다.
- API, 환경변수와 UI의 경계를 분리합니다.
- 요구사항이 생기기 전에 디렉터리, 상태와 추상화를 미리 만들지 않습니다.

## 현재 구조

```text
src/
├─ app/                    Next.js route와 화면 조합
│  ├─ layout.tsx           HTML 뼈대, Metadata, 전역 CSS
│  ├─ page.tsx             랜딩 기본 버전으로 이동
│  ├─ landing/            신한 디자인의 역할 선택 랜딩
│  ├─ login/              역할별 로그인 화면 (현재 로컬 데모)
│  ├─ search/             편집자 검색 입력
│  │  └─ results/         검색 결과와 URL 필터
│  ├─ review/             문의·영상 등록·처리 현황
│  └─ globals.css          전역 토큰과 전역 스타일
├─ components/             여러 기능에서 공유하는 UI
│  ├─ app-shell.tsx
│  └─ api-error-notice.tsx  한국어 오류·코드·요청 ID 공통 표시
├─ features/               기능 단위 UI와 로직
│  ├─ search/
│  │  └─ search-placeholder.tsx
│  └─ wireframes/
│     ├─ landing-shell.tsx        브랜드 인트로와 역할 선택
│     ├─ landing-cube.tsx         랜딩 전용 Three.js 유리 큐브 Canvas와 모션 경계
│     ├─ landing.module.css       신한 랜딩과 글자 합치기 인트로
│     ├─ login-shell.tsx          역할별 ID·비밀번호 입력과 로컬 화면 이동
│     ├─ search-entry-shell.tsx   편집자 검색 입력과 결과 진입
│     ├─ search-history.tsx       검색·문의 예시 기록과 부분 노출 시트
│     ├─ search-history.module.css 기록 목록·상태 칩·펼침 레이아웃
│     ├─ demo-scenes.ts          검색 결과·기록이 공유하는 예시 구간과 시간 표시
│     ├─ scene-dialogs.tsx        구간 영상·문의 공통 팝업과 상태별 조회·수정
│     ├─ entry-chrome.tsx         로그인·검색 공통 헤더와 푸터
│     ├─ entry.module.css         신한 로그인·검색 반응형 레이아웃
│     ├─ reviewer-shell.tsx    처리 상태·문의 검수 로컬 상호작용
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
│     ├─ video-registration.module.css 영상 등록의 테마·반응형 레이아웃
│     ├─ registration-files.ts 영상·첨부 파일 선택 검증과 중복 첨부 방지
│     ├─ reviewer.module.css   검수자 화면의 테마와 반응형 레이아웃
│     ├─ wireframe-shell.tsx   검색 결과 UI와 로컬 상호작용
│     ├─ wireframe-themes.ts   신한 단일 테마 타입과 route 검증
│     └─ wireframe.module.css  신한 토큰과 반응형 레이아웃
└─ lib/                    프레임워크·인프라 성격의 공통 코드
   ├─ routes.ts            화면 경로 상수
   ├─ env.ts               공개 환경변수 읽기와 검증
   └─ api/
      ├─ client.ts         공통 응답 envelope 해석과 HTTP client
      └─ error.ts          안전한 ApiClientError와 개발용 진단 정보 분리
```

현재 와이어프레임 UI를 제품 화면으로 사용하며 디자인은 신한(`shinhan`)을 유지합니다. `/`는 `/landing`으로 이동하고, 역할 카드는 `/login?role=editor|reviewer`로 연결합니다. 로그인 후 편집자는 `/search`, 검수자는 `/review`로 이동하며 검색 결과는 `/search/results`에서 표시합니다. 화면 경로 상수는 `src/lib/routes.ts`가 소유합니다. 기존 다섯 테마의 `/landing/[theme]`, `/login/[theme]`, `/search/[theme]`, `/wireframes/[theme]`, `/review/[theme]` 주소는 `next.config.ts`의 307 redirect로 새 화면에 연결하고 query를 보존합니다. 알 수 없는 테마는 404로 처리합니다. 디자인과 로컬 상태 동작을 재사용하는 단계로, 로그인 자격 증명은 저장·전송하지 않으며 실제 인증과 검색·문의·등록·검수 API는 백엔드 명세 확정 후 연결합니다.

랜딩의 Three.js 유리 큐브는 `landing-shell.tsx`의 Client Component 경계에서 `next/dynamic({ ssr: false })`로 지연 로드합니다. Three.js physical material과 rounded box geometry로 굴절·두께·분산·무지갯빛 테두리를 표현하며, 투명 Canvas texture의 N-Pick 타이포그래피를 큐브 뒤에 배치합니다. `Need? Pick!`의 글자 폭을 줄여 `N-Pick`으로 합치는 CSS 인트로 뒤에 큐브와 역할 카드가 나타납니다. 인트로는 WebGL 준비 여부와 무관하게 끝나고 CSS 유리 큐브는 로딩·WebGL 실패 fallback으로 유지합니다. WebGL render loop와 CSS animation은 `prefers-reduced-motion`에서 정지하고 Canvas의 DPR을 제한합니다.

편집자 검색 입력 화면 하단에는 `이전 검색 기록`과 `문의 기록` 시트를 일부만 노출합니다. 제목 버튼으로 한 시트씩 위로 펼치고 접을 수 있으며 Escape로도 접습니다. 기존 검색 결과의 뉴스 썸네일을 재사용하고 구간·내용·경과일을 표시합니다. 검색 기록 항목은 검색 결과와 동일한 `ScenePreviewDialog`를 열고, 문의 기록 항목은 `이상해요` 접수와 같은 `InquiryDialog`에서 당시 검색어·구간·문의 내용을 보여 줍니다. 문의의 `pending/reviewing/resolved` 예시 상태는 `대기/처리 중/완료` 칩으로 표현합니다. 이번 와이어프레임 요청에 따라 대기는 문의 설명만 수정·저장할 수 있고, 처리 중과 완료는 읽기 전용이며 완료에는 처리 내용도 표시합니다. native dialog로 배경 조작을 막고 키보드 포커스를 가두며 Escape·닫기로 복귀합니다. 기록에서 새로 접수하거나 수정한 문의는 `SearchHistory` 메모리에서 유지하며 새로고침·페이지 이동 시 초기화됩니다. 실제 검색·문의 이력 API나 제품 FRD의 문의 수정 계약은 연결하지 않은 디자인 시안입니다.

검수자는 `/review`에서 인사말과 문의 건수, 10개 단위 문의 게시판을 먼저 봅니다. 검색은 영상 제목·문의 내용·원문 query·문의자·주제를 대상으로 합니다. 상태는 전체·대기·처리중·완료로 필터링하며 완료는 `resolved/dismissed/deferred`를 포함하고 상세에서 실제 종료 사유를 구분합니다. 시간 최신순과 문의자·주제 가나다순을 지원합니다. `q/status/sort/page/inquiry/view/tab/clip`는 Next.js 라우터로 URL에 보존하며, 상세 진입과 복귀 중 검수 상태는 `ReviewerShell`이 유지합니다. 페이지는 `useSearchParams`를 위한 Suspense 경계를 제공합니다. 현재 고정 데모 검수자 이름은 나현우이며 문의 데이터와 변경 상태는 실제 API나 새로고침 후 영속 저장에 연결되지 않습니다.

영상 등록은 `/review?view=upload`에서 제공하며 문의 목록과 영상 처리 화면에서 진입할 수 있습니다. 영상 1개와 선택 첨부(TXT·SRT·VTT)를 클릭 또는 드래그로 고르고, 파일명·용량 확인과 삭제·교체가 가능합니다. 영상 형식은 이번 디자인 요청에 따라 원본·방영본 선택을 제공하고 방영일은 선택 입력입니다. 이는 실제 제품의 `FR-ING-002`(broadcast 고정)와 구분되는 디자인 시안입니다. 등록 시 파일 본문을 읽거나 전송하지 않고 이름·용량·형식·방영일·첨부 이름만 `ReviewerShell`의 메모리에 추가하여 `영상 등록 중` 탭에 `등록 대기`로 표시합니다. 취소 시 form을 비우고 기존 목록 조건으로 돌아갑니다. 새로고침 시 로컬 등록 데이터는 초기화됩니다. 실제 `/clips` API, 사용권 검증, media decode와 pipeline enqueue는 연결하지 않습니다.

처리 현황은 `view=processing`에서 `문의 처리 중` 탭을 먼저 보여주고, `tab=uploads`로 `영상 등록 중` 탭을 엽니다. 문의는 `reviewing`만 진행 목록에 포함하며, 전체 완료 건수에는 `resolved/dismissed/deferred`를 포함합니다. 영상은 최신 작업의 대기·진행·실패를 표시하고, 실제 완료 단계 수로 진행 막대를 계산합니다. 새로 등록한 데모 영상은 0단계 대기로 추가합니다. 문의 선택과 영상 `상세 보기`는 기존 조치·재시도 화면으로 연결하며 복귀 시 선택 탭을 유지합니다. 상태·건수·진행률은 시간 경과로 임의 증가하지 않습니다.

검수 상세는 문의자의 설명, 당시 검색 조건, 영상에서 확인한 내용과 검색 해석 요약을 보여줍니다. 개발용 JSON·로그는 화면에 노출하지 않고, 검색 해석 수정은 사건·인물·기관·장소·관련 검색어와 날짜 입력으로 구성합니다. 전체 snapshot과 수정하지 않은 근거 정보는 내부에 유지하며, 문의자가 명시한 날짜 필터는 변경하지 않습니다. 종료일은 화면에서 해당 날짜를 포함하는 기간으로 표시하고 내부의 배타적 종료일로 변환합니다. 검수 시작 후에는 당시 결과와 같은 검색어·필터로 다시 검색한 결과를 먼저 비교하고, 수정안 작성 → 수정 후 결과 확인 → 완료 순서로 진행합니다. 수정 전 재검색과 수정 후 검증은 별도 상태로 유지하여 최초 재검색만으로 완료되지 않게 합니다. 이전 단계로 돌아가도 작성 내용을 유지하며, 입력 변경이나 수정 전 재검색을 다시 실행하면 수정 후 확인을 무효화합니다. 각 문의의 진행 단계와 비교 결과는 목록 이동 중 메모리에 보존하고, 단계 전환 시 해당 영역으로 키보드 포커스를 옮깁니다. 수정 전 재검색은 당시 결과와 같은 고정 예시 목록, 수정 후 검증은 기존 변경 후 예시 목록을 사용하며 실제 검색 API가 연결되지 않았음을 표시합니다. 담당팀 확인 요청은 문의에 포함된 장면·근거를 읽기 쉬운 이름으로 선택하며, 정보가 아직 수정되지 않았음을 완료 내역에도 표시합니다. 영상 처리 단계와 실패 사유 역시 사용자가 이해할 수 있는 작업 설명으로 표현합니다.

문의 상세의 선택된 장면은 `ReviewerScenePreview`에서 해당 문의의 제목·시작/종료 시각·확인 근거를 공통 `ScenePreviewDialog`에 전달합니다. 문의 생성 버튼은 검수자 팝업에서 제공하지 않으며, 닫기·Escape 후 장면 카드로 포커스를 돌려주고 검수 진행 상태를 유지합니다. 문의별 키로 팝업 상태를 분리해 다른 문의나 목록으로 이동하면 팝업을 닫습니다. 실제 원본 영상은 연결 전으로 기존 재생·일시정지·구간 재생 UI 데모를 사용하고 팝업에 이를 명시합니다. 원본 길이·파일명·날짜가 없는 문의에는 값을 만들어 넣지 않습니다.

다른 디자인 시스템의 전용 스타일과 화면 분기는 제거하고 신한(`shinhan`) 구현만 유지합니다. 신한 검색 결과는 방송일·촬영일별 Date Range Picker를 제공하며, 달력의 시작일·종료일 선택과 직접 입력, 취소·초기화, 키보드 방향 이동을 지원합니다. 선택한 기간은 양 끝 날짜를 포함하고 `broadcastFrom/broadcastTo/filmingFrom/filmingTo` URL에 보존합니다. 예시 데이터에 날짜 필터를 적용하되 미상·미검증 촬영일은 검증된 충돌로 제외하지 않습니다. 기간 밖 예시 데이터는 0건 화면으로 연결되며 조건·검색 해석·제외 건수와 기간 초기화를 제공합니다. `state=empty`와 `state=failed`는 디자인 확인용 상태 URL이며, 실패 화면의 재시도는 검색어·기간을 보존하고 실패 시연 상태를 해제합니다. 실제 검색 API는 연결하지 않습니다.

신한 Preview는 진입 시 로딩 안내와 비활성 재생 제어를 보여준 뒤 예시 준비 화면으로 전환합니다. `/search/results?preview=loading`은 로딩 상태를 유지해 디자인을 확인하는 주소입니다. 실제 미디어 로딩 성공을 뜻하지 않으며 화면 안에 데모임을 안내합니다. 신한 처리 현황에는 `tab=completed`의 `등록 완료` 탭이 추가됩니다. 고정 완료 예시의 장면 수·완료 시각·누락 정보·단계·검색 이동을 확인할 수 있고, 새로 등록한 데모 영상도 같은 처리 상세에서 파일 정보와 0단계 대기를 확인할 수 있습니다. 신규 영상은 시간이 지났다는 이유로 완료 처리하지 않습니다. 새로고침 시 로컬 등록 메모리는 초기화됩니다. 처리 상태 변환은 `registration-processing.ts`, 날짜 검증은 `date-range.ts`가 담당합니다.

## 계층별 책임

| 계층         | 책임                                              | 포함하지 않는 것                        |
| ------------ | ------------------------------------------------- | --------------------------------------- |
| `app`        | route, layout, metadata, 페이지 수준 조합         | 재사용 가능한 기능 로직, 공통 HTTP 처리 |
| `features`   | 특정 사용자 기능의 UI, 상태, API 함수, Hook       | 다른 기능의 내부 코드, 전역 기반 코드   |
| `components` | 여러 기능에서 공유하거나 앱 전역에 사용하는 UI    | 특정 기능의 업무 규칙, API 호출         |
| `lib`        | 환경변수, HTTP client와 프레임워크 독립 기반 코드 | 화면 표현과 기능별 상태                 |
| `public`     | 브라우저에 그대로 제공하는 정적 파일              | 빌드가 필요한 소스 파일                 |

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
├─ 초기 데이터 조회
├─ route와 layout 조합
└─ Client Component
   ├─ 입력과 사용자 이벤트
   ├─ 브라우저 API
   └─ 일시적인 UI 상태
```

`'use client'`는 파일 아래의 전체 import tree를 클라이언트 경계로 만듭니다. 따라서 페이지 전체가 아니라 상호작용이 필요한 작은 경계에 둡니다. 브라우저에 전달되는 Props는 직렬화할 수 있어야 합니다.

## 상태 소유권

| 상태 종류          | 기본 위치                            | 예시                                  |
| ------------------ | ------------------------------------ | ------------------------------------- |
| URL 상태           | route search params                  | 검색어, 명시 필터, 페이지             |
| Server 상태        | Server Component 또는 서버 상태 도구 | 검색 결과, 처리 상태, 문의 목록       |
| Local UI 상태      | 가장 가까운 Client Component         | modal, 펼침 여부, 입력 중인 값        |
| Form 상태          | form 경계                            | validation 오류, 제출 중 상태         |
| Global client 상태 | 도입 근거가 있는 전역 store          | 서로 떨어진 기능이 공유하는 편집 세션 |

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

HTTP·업무 실패·네트워크·본문 수신 실패·비정상 응답·취소는 `ApiClientError`로 정규화합니다. `kind`, HTTP `status`(응답 전 실패는 0), 사용자용 `message`, stable `code`, 선택적 `requestId`를 제공합니다. 원래 응답의 `path/data`와 예외는 직렬화되지 않는 `diagnostics` 접근자로 분리하며 UI에 전달하거나 출력하지 않습니다. 인증 옵션과 signal은 호출자가 전달하고 자동 재시도는 하지 않습니다.

`components/api-error-notice.tsx`는 오류 객체를 받아 한국어 메시지·오류 코드·요청 ID와 후속 안내만 `role="alert"`로 표시합니다. form의 `aria-describedby`에 연결할 수 있는 `id`를 지원합니다. 정상 한국어 서버 메시지를 우선하며, 현재 백엔드의 고정 영어 메시지는 `code/message`가 정확히 일치하는 6개 조합만 번역합니다. 메시지 누락·타입 오류·한국어 안내 계약 위반(내부 경로·HTML·JSON·예외 trace 등)에는 일반 한국어 안내를 사용합니다. 임의 JSON 응답의 `message`나 일반 `Error.message`는 표시하지 않습니다.

**현재 연동 차이:** FRD v3.1 §6.3은 한국어 오류와 요청 식별자를 요구하지만 백엔드 `CommonErrorCode`는 영어이고 `ApiResponse`에는 요청 ID가 없습니다. FE는 본문의 `requestId`, 없으면 `X-Request-ID` 응답 헤더를 읽도록 준비했으며 미제공 시 `제공되지 않음`으로 표시합니다. ID를 임의 생성하지 않습니다. 백엔드의 한국어 메시지·요청 ID 생성과 교차 오리진 호출 시 해당 헤더의 CORS 노출은 별도 연동 작업입니다. 화면은 아직 데모이며 공통 client와 오류 UI의 실제 기능 연결은 endpoint·인증 명세 확정 후 진행합니다.

## Route 경계

| Route             | 사용자   | 책임                                        |
| ----------------- | -------- | ------------------------------------------- |
| `/landing`        | 공통     | 브랜드 인트로·역할 선택                     |
| `/login`          | 공통     | 역할별 ID·비밀번호 입력                     |
| `/search`         | editor   | 검색어 입력과 검색·문의 기록                |
| `/search/results` | editor   | 결과·필터·근거 확인, Preview, 이상해요 제출 |
| `/review`         | reviewer | 문의 검수, 영상 등록·처리 현황과 재시도     |

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

- 인증과 역할별 접근 제어 방식
- 서버 상태 관리와 cache 라이브러리
- form과 schema validation 도구
- 기능별 data 스키마, 백엔드 한국어 오류·요청 ID 제공 계약
- 단위·컴포넌트·E2E 테스트 도구
- 공통 UI primitive와 디자인 토큰
- feature의 외부 공개 API와 barrel export 정책

팀 전체에 영향을 주는 선택은 [`decisions/`](decisions/)에 ADR로 기록합니다.
