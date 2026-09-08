# N-Pick Frontend

N-Pick의 Next.js 프론트엔드 프로젝트입니다. 패키지 매니저는 npm만 사용합니다.

현재 와이어프레임 UI를 제품 화면으로 사용합니다. 로그인·로그아웃·세션 확인과 역할별 페이지 보호는 백엔드에 연결되어 있습니다. 검색·문의·영상 데이터와 조작은 아직 데모입니다.

## 개발 문서

- [프론트 작업 규칙](AGENTS.md): 반드시 지켜야 하는 코드·구조 규칙
- [프론트 아키텍처](docs/architecture.md): 디렉터리 책임, 의존 방향과 데이터 흐름
- [설계 결정 기록](docs/decisions/): 중요한 기술 선택과 그 배경
- [제품 FRD](../docs/frd.md): 화면 상태, 기능 요구사항과 인수 조건

## 기술 스택

| 구분            | 기술                                          |
| --------------- | --------------------------------------------- |
| Framework       | Next.js 16 App Router (Turbopack)             |
| UI              | React 19                                      |
| Language        | TypeScript 5.9                                |
| Styling         | Tailwind CSS 4, PostCSS                       |
| Lint            | ESLint 9, `eslint-config-next` flat config    |
| Format          | Prettier, `prettier-plugin-tailwindcss`       |
| Package manager | npm 11                                        |
| Runtime         | Node.js 24                                    |
| Server state    | TanStack Query v5 (현재 로그인·세션·로그아웃) |

정확한 버전은 `package.json`과 `package-lock.json`을 기준으로 합니다.

## 요구 환경

- Node.js 24.18.0 (`.nvmrc`)
- npm 11.16.0 (Node.js 24.18.0에 포함)

Node.js 엔진 범위를 벗어난 버전에서는 `.npmrc`의 `engine-strict=true` 설정으로 설치가 실패합니다.

## 설치 및 개발 서버

```bash
npm ci
cp .env.example .env.local
npm run dev
```

Windows PowerShell에서는 환경변수 파일을 다음과 같이 복사할 수 있습니다.

```powershell
Copy-Item .env.example .env.local
```

개발 서버는 `http://127.0.0.1:3000`에서 실행됩니다. `.env.local`을 만들지 않으면 초기 화면은 `.env.example`과 같은 기본값을 사용합니다.

## 프로덕션 실행

```bash
npm run build
npm run start
```

## 스크립트

| 명령                   | 설명                                    |
| ---------------------- | --------------------------------------- |
| `npm run dev`          | `127.0.0.1`에서 개발 서버 실행          |
| `npm run build`        | 프로덕션 번들 생성                      |
| `npm run start`        | `127.0.0.1`에서 프로덕션 서버 실행      |
| `npm run lint`         | ESLint 검사                             |
| `npm run typecheck`    | TypeScript 타입 검사                    |
| `npm test`             | API client와 기존 화면 상태 단위 테스트 |
| `npm run format`       | Prettier로 파일 정리                    |
| `npm run format:check` | Prettier 형식 검사                      |
| `npm run check`        | 형식, 린트, 타입, 빌드를 순서대로 검사  |

작업을 마치기 전에는 전체 품질 검사를 실행합니다.

```bash
npm run check
```

현재 Git hook은 브랜치명과 커밋 메시지만 검사합니다. 프론트엔드 lint와 format은 commit 시 자동 실행되지 않으므로 직접 `npm run check`를 실행해야 합니다.

검수 게시판의 검색·정렬·상태 필터·페이지 경계 테스트는 `node --test src/features/wireframes/reviewer-board-state.test.mjs`로 실행합니다.
영상 등록의 파일 선택 검증·중복 첨부 방지 테스트는 `node --test src/features/wireframes/registration-files.test.mjs`로 실행합니다.
처리 현황의 진행 목록·건수·완료 상태 반영 테스트는 `node --test src/features/wireframes/reviewer-progress-state.test.mjs`로 실행합니다.

## 아키텍처 개요

```text
src/
├─ app/          route와 화면 조합
├─ components/   여러 기능에서 공유하거나 앱 전역에 쓰는 UI
├─ features/     기능 단위 UI와 로직
└─ lib/          환경변수와 HTTP client 등 공통 기반 코드
```

`/landing`에서 역할을 선택하고 `/login?role=editor|reviewer`에서 로그인 화면을 엽니다. 편집자는 `/search`에서 검색어를 입력하고 `/search/results`에서 결과를 확인합니다. 검수자는 `/review`에서 문의·영상 등록·처리 현황을 확인합니다. 화면 링크의 정본은 `src/lib/routes.ts`입니다.

기존 테마 주소는 `next.config.ts`의 307 redirect로 새 주소에 연결하며 query를 보존합니다. 알 수 없는 과거 테마는 404로 처리합니다. 실제 로그인 계정의 역할이 접근 권한을 결정하며 `role` query는 안내용입니다. 검색은 두 역할 모두, `/review` 전체는 검수자만 접근합니다. 검색·문의·등록·검수 데이터는 아직 실제 서버에 저장되지 않습니다. 디렉터리 책임과 API 흐름은 [프론트 아키텍처](docs/architecture.md)를 기준으로 합니다.

## 환경변수

`.env.example`을 `.env.local`로 복사한 뒤 로컬 환경에 맞게 수정합니다.

- `NEXT_PUBLIC_API_BASE_URL`: 백엔드 API 기본 주소. 기본값은 `http://127.0.0.1:8080/api/v1`입니다.
- `API_INTERNAL_BASE_URL`: Next.js 서버의 세션 확인용 절대 주소. 로컬 기본값은 `http://127.0.0.1:8080/api/v1`, Docker Compose는 `http://backend:8080/api/v1`을 주입합니다. server-only 값이며 `NEXT_PUBLIC_` 접두사를 붙이지 않습니다.
- `NEXT_PUBLIC_APP_MODE`: 공개 가능한 앱 실행 모드. 기본값은 `demo`입니다.

`NEXT_PUBLIC_*` 값은 브라우저에 공개되며 빌드 시점에 번들에 포함될 수 있습니다. API 키, DB 접속 정보, 원본 미디어 경로와 같은 비밀값을 넣지 마세요. 실제 `.env.local` 파일은 Git에서 제외됩니다.

`src/lib/env.ts`는 HTTP(S) 절대 주소 또는 `/api/v1` 같은 루트 상대 경로를 허용하고, 인증 정보·query·fragment가 섞인 기본 주소는 거부합니다. nginx를 사용하는 배포에서는 `NEXT_PUBLIC_API_BASE_URL=/api/v1`로 브라우저와 같은 오리진을 사용합니다. Next.js 프록시는 추가하지 않습니다. 로컬에서 백엔드를 직접 호출하려면 서버의 CORS 허용 목록에 프론트 주소 `http://127.0.0.1:3000`을 포함해야 합니다.

기능별 API 함수는 `src/lib/api/client.ts`의 `fetchJson<T>(endpoint, options)`를 사용합니다. endpoint는 기본 주소 다음의 경로만 전달하며 `/api/v1`을 반복하지 않습니다. `query`에는 `URLSearchParams`, JSON 요청은 `body`에 객체, 파일 요청은 `FormData`를 전달합니다. multipart Content-Type과 boundary는 브라우저가 설정합니다. `signal`로 조회를 취소할 수 있습니다. 세션 쿠키는 항상 `credentials: include`, API redirect는 `error`로 처리합니다. 브라우저 변경 요청에는 CSRF 토큰이 자동으로 붙습니다.

로컬 인증 실행 시 주의사항:

- 프론트와 브라우저 API 주소의 호스트를 통일합니다. `localhost`와 `127.0.0.1`을 섞으면 세션·CSRF 쿠키를 공유할 수 없습니다.
- 직접 실행하는 백엔드는 CORS에 `http://127.0.0.1:3000`을 허용해야 합니다. 현재 local 기본값은 `http://localhost:3000`이므로 `CORS_ALLOWED_ORIGINS` 또는 local 프로필의 `LOCAL_CORS_ALLOWED_ORIGINS`를 실행 환경에 설정합니다.
- 백엔드는 현재 모든 프로필에서 `JSESSIONID`에 Secure를 설정합니다. 로컬 HTTP에서 쿠키가 유지되지 않으면 HTTPS를 사용하거나 **로컬 백엔드 실행에만** `SERVER_SERVLET_SESSION_COOKIE_SECURE=false`를 지정합니다. 운영 Secure/SameSite/HttpOnly 설정은 낮추지 않습니다.
- 로그인 성공 후 `/auth/me`로 쿠키 유지도 확인합니다. 프론트에 테스트 계정·비밀번호를 하드코딩하지 않으며 백엔드의 사전 등록 계정을 사용합니다.

`fetchJson<T>`는 백엔드 공통 envelope(`isSuccess/code/message/data`)의 성공 `data`만 반환합니다. 데이터가 생략된 성공 또는 HTTP 204·205는 `undefined`이므로 해당 호출은 `fetchJson<void>`를 사용합니다. 그 외 비정상·빈 응답은 `ApiClientError`로 처리하며 오류를 성공이나 빈 결과로 바꾸지 않습니다.

기능 계층에서 오류를 catch하여 `ApiErrorNotice`의 `error`에 전달하면 한국어 메시지·stable code·요청 ID가 표시됩니다. form 오류는 `id`를 지정하고 입력의 `aria-describedby`에서 참조합니다. `ApiClientError.kind`로 `http/api/network/invalid-response/aborted`를 구분하고, `status`는 실제 HTTP 상태(응답 전 실패는 0)입니다. 취소 안내 표시 여부와 재시도는 기능 계층이 결정하며 client는 자동 재시도하지 않습니다. 원래 응답과 예외는 `diagnostics`에만 보존되므로 UI에서 출력하거나 사용자 상태로 복사하지 않습니다.

정상 한국어 서버 메시지를 우선합니다. 현재 백엔드의 고정 영어 오류 6개는 정확한 코드·메시지 조합만 번역하며, 누락·비정상 사용자 메시지는 한국어 fallback으로 처리합니다. 요청 ID는 본문 `requestId` 또는 `X-Request-ID` 응답 헤더에서 읽고 없으면 `제공되지 않음`으로 표시합니다. 현재 백엔드는 요청 ID를 제공하지 않으므로 백엔드 계약 보완과 실제 화면 연동은 남아 있습니다. 자세한 계약은 [API와 데이터 흐름](docs/architecture.md#api와-데이터-흐름)을 참고합니다.

## 현재 구현 상태

- 공통 환경변수 검증, JSON·FormData 요청, query 결합과 API 오류 처리가 준비되어 있습니다.
- API client와 기존 화면 상태에 대한 단위 테스트는 `npm test`로 실행합니다.
- `/auth/csrf`, `/auth/login`, `/auth/me`, `/auth/logout`이 연결되어 있습니다. `demo` 모드여도 인증을 우회하지 않습니다.
- TanStack Query는 서버 상태만 관리합니다. 로그인 실패는 폼에 표시하고 `COMM_401`은 캐시 제거 후 로그인으로 이동합니다. 403은 자동 로그아웃·자동 저장 재시도를 하지 않습니다.
- 서버 로그아웃 성공 후 캐시와 앱 소유 `npick:{memberId}:…` sessionStorage 초안을 삭제합니다. 만료 시 초안은 보존하고 다른 계정 로그인 시 이전 계정 초안을 정리합니다. 실제 초안 저장 기능은 별도 구현 대상입니다.
- form·스키마·공통 UI 도구와 컴포넌트/E2E 테스트 도구의 최종 선택은 별도 결정 대상입니다. 현재 단위 테스트는 Node 내장 runner를 사용합니다.
- 팀 전체에 영향을 주는 새 기술 선택은 [ADR](docs/decisions/)로 기록합니다.

인증 검증: 단위 테스트는 `npm test`, 형식·린트·타입·프로덕션 빌드는 `npm run check`로 확인합니다. 백엔드 계약을 모사한 임시 서버에서 로그인 실패, 실제 역할에 따른 이동, 검수 경로 차단, 안전한 복귀, 403 로그아웃 실패, 성공 로그아웃과 탭 간 동기화를 브라우저로 확인했습니다. 이는 실제 Spring/DB 계정 연동 검증을 대신하지 않습니다. 실제 환경에서는 위 CORS·쿠키 설정 후 두 역할의 사전 등록 계정으로 동일 흐름을 확인해야 합니다.

## CI 상태

Jenkins의 Frontend stage는 아직 placeholder입니다. 현재는 저장소에 `frontend` 디렉터리가 있어도 실제 `npm ci` 또는 `npm run check`를 실행하지 않습니다. CI 연결 전까지 로컬의 `npm run check` 결과를 프론트 품질 기준으로 사용합니다.
