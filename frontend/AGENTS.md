# Frontend 작업 규칙

이 문서는 `frontend/`에서 작업할 때 적용하는 규칙 정본입니다. 저장소 루트의 [`AGENTS.md`](../AGENTS.md)를 함께 따릅니다. 구조와 설계 배경은 [`docs/architecture.md`](docs/architecture.md), 중요한 기술 선택은 [`docs/decisions/`](docs/decisions/)에서 확인합니다.

## 작업 전 확인

- 기능을 구현하기 전에 루트 `AGENTS.md`의 기능 개발 절차에 따라 관련 PRD와 FRD를 읽습니다.
- FRD 인수 조건에 없는 동작을 임의로 추가하지 않습니다.
- 새 라이브러리나 전역 구조가 필요하면 기존 코드만으로 해결할 수 없는지 먼저 확인합니다.

## TypeScript와 import

- 애플리케이션 코드는 TypeScript로 작성하고 `strict` 모드를 유지합니다.
- 값으로 사용하지 않는 타입은 `import type`으로 가져옵니다.
- `src` 내부 모듈은 `@/*` 별칭으로 import합니다.
- 애플리케이션 코드의 `process.env` 직접 접근은 환경변수 경계 모듈 안에서만 합니다. 공개 환경변수는 `src/lib/env.ts`에서 관리하고, 서버 비밀값이 필요하면 client에서 import할 수 없는 별도 server-only 모듈을 만듭니다.

## 이름과 export

- 컴포넌트 파일은 `kebab-case.tsx`, 컴포넌트는 `PascalCase`를 사용합니다.
- 컴포넌트 Props는 `컴포넌트명 + Props`로 이름 짓습니다.
- Hook은 `use*`, 이벤트 Props는 `on*`, 내부 이벤트 handler는 `handle*`로 시작합니다.
- boolean은 의미에 맞게 `is*`, `has*`, `can*`을 우선합니다.
- 일반 컴포넌트와 유틸리티는 named export를 사용합니다.
- Next.js 파일 규약인 `page.tsx`, `layout.tsx`는 default export를 사용합니다.

## 컴포넌트 경계

- Server Component를 기본으로 사용합니다.
- 상태, effect, 브라우저 API, 사용자 이벤트가 필요한 최소 경계에만 `'use client'`를 선언합니다.
- `src/app`은 route, layout, 페이지 조합을 담당하고 기능 로직을 직접 소유하지 않습니다.
- 기능 전용 UI와 로직은 `src/features/<feature>`에 둡니다.
- 둘 이상의 기능에서 실제로 재사용하거나 앱 전역 layout에 사용하는 UI만 `src/components`에 둡니다.
- 기능 간 직접 import는 만들지 않습니다. 공통 UI는 `components`, 공통 기반 코드는 `lib`로 올립니다.
- 필요하지 않은 디렉터리와 추상화는 미리 만들지 않습니다.

## 상태 소유권

- 공유하거나 다시 열어야 하는 검색어, 필터, 페이지 정보는 URL 상태를 우선합니다.
- 한 컴포넌트 트리 안에서만 쓰는 UI 상태는 가장 가까운 컴포넌트에 둡니다.
- API에서 가져온 데이터는 서버 상태로 취급하며 동일 데이터를 전역 상태에 복제하지 않습니다.
- 전역 상태는 서로 떨어진 기능이 같은 클라이언트 상태를 공유해야 할 때만 도입합니다.
- 상태관리 라이브러리는 구체적인 요구사항과 대안을 ADR로 기록한 뒤 추가합니다.

## API와 오류 처리

- 기능별 API 함수는 공통 `src/lib/api/client.ts`를 사용합니다.
- request와 response 타입은 기능 경계 가까이에 두고 UI 컴포넌트 안에서 즉석 정의하지 않습니다.
- HTTP 오류를 사용자가 이해할 수 있는 상태로 바꾸는 책임은 해당 기능 계층에 둡니다.
- loading, empty, degraded, error 상태를 정상 데이터와 구분합니다.
- 비밀값을 `NEXT_PUBLIC_*` 환경변수에 넣지 않습니다.

## 스타일과 접근성

- Tailwind CSS utility class를 우선 사용합니다.
- `globals.css`에는 전역 토큰, reset, `html`·`body` 수준의 스타일만 둡니다.
- 키보드 조작, visible focus, accessible name과 논리적 tab order를 보장합니다.
- 상태와 오류를 색상만으로 표현하지 않습니다.
- form label과 오류를 연결하고 비동기 상태 변경은 live region으로 알립니다.
- `prefers-reduced-motion`을 존중합니다.

## 완료 기준

- 작업 범위에 맞는 테스트를 추가하거나, 테스트 도구가 없는 경우 검증 방법을 MR에 기록합니다.
- `npm run check`를 통과합니다.
- 구조나 기술 선택을 변경했다면 `README.md`, `docs/architecture.md` 또는 ADR 중 해당 정본을 함께 갱신합니다.
