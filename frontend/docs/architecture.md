# Frontend Architecture

이 문서는 NewsCut 프론트엔드의 구조, 책임 경계와 데이터 흐름을 설명합니다. 반드시 지켜야 하는 작업 규칙은 [`../AGENTS.md`](../AGENTS.md), 설치와 실행 방법은 [`../README.md`](../README.md)를 기준으로 합니다.

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
│  ├─ page.tsx             현재 루트 화면
│  └─ globals.css          전역 토큰과 전역 스타일
├─ components/             여러 기능에서 공유하는 UI
│  └─ app-shell.tsx
├─ features/               기능 단위 UI와 로직
│  └─ search/
│     └─ search-placeholder.tsx
└─ lib/                    프레임워크·인프라 성격의 공통 코드
   ├─ env.ts               공개 환경변수 읽기와 검증
   └─ api/
      └─ client.ts         공통 HTTP client
```

현재 구현된 route는 정적으로 생성되는 `/`뿐입니다. 제품 FRD가 정의한 `/search`와 `/review`는 아직 구현되지 않았습니다.

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
- `src/lib/api/client.ts`가 URL 결합, JSON 직렬화, header와 HTTP 상태 검사를 담당합니다.
- 기능별 API 함수가 endpoint, request·response 타입과 기능별 오류 변환을 담당합니다.
- UI는 HTTP 세부사항보다 `loading`, `empty`, `degraded`, `error` 같은 사용자 상태를 다룹니다.

현재 공통 API client는 준비되어 있지만 실제 화면에는 아직 연결되지 않았습니다.

## Route 경계

| Route     | 사용자   | 책임                                                           |
| --------- | -------- | -------------------------------------------------------------- |
| `/search` | editor   | query·필터 입력, 결과·경고·근거 확인, Preview, `이상해요` 제출 |
| `/review` | reviewer | 영상 처리·index 상태 확인, 실패·재시도, 문의 검수와 replay     |

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
- API 응답·오류 타입 표준
- 단위·컴포넌트·E2E 테스트 도구
- 공통 UI primitive와 디자인 토큰
- feature의 외부 공개 API와 barrel export 정책

팀 전체에 영향을 주는 선택은 [`decisions/`](decisions/)에 ADR로 기록합니다.
