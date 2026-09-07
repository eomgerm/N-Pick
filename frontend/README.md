# N-Pick Frontend

N-Pick의 Next.js 프론트엔드 프로젝트입니다. 패키지 매니저는 npm만 사용합니다.

현재 와이어프레임 UI를 제품 화면으로 사용합니다. 화면 경로와 공통 API 호출 기반은 정리되어 있으며, 화면 데이터는 아직 데모입니다. 실제 인증과 기능별 API 연동은 백엔드 명세 확정 후 연결합니다.

## 개발 문서

- [프론트 작업 규칙](AGENTS.md): 반드시 지켜야 하는 코드·구조 규칙
- [프론트 아키텍처](docs/architecture.md): 디렉터리 책임, 의존 방향과 데이터 흐름
- [설계 결정 기록](docs/decisions/): 중요한 기술 선택과 그 배경
- [제품 FRD](../docs/frd.md): 화면 상태, 기능 요구사항과 인수 조건

## 기술 스택

| 구분            | 기술                                       |
| --------------- | ------------------------------------------ |
| Framework       | Next.js 16 App Router (Turbopack)          |
| UI              | React 19                                   |
| Language        | TypeScript 5.9                             |
| Styling         | Tailwind CSS 4, PostCSS                    |
| Lint            | ESLint 9, `eslint-config-next` flat config |
| Format          | Prettier, `prettier-plugin-tailwindcss`    |
| Package manager | npm 11                                     |
| Runtime         | Node.js 24                                 |

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

기존 신한·여기어때·원티드·지마켓·당근 테마 주소는 `next.config.ts`의 307 redirect로 새 주소에 연결하며 검색어·필터·역할 등 query를 보존합니다. 알 수 없는 과거 테마는 404로 처리합니다. 신한 UI와 기존 로컬 동작은 유지합니다. 로그인은 ID·비밀번호를 저장·전송하지 않는 화면 전환 데모이며, 검색·문의·등록·검수도 실제 서버에 저장되지 않습니다. 디렉터리 책임과 API 흐름은 [프론트 아키텍처](docs/architecture.md)를 기준으로 합니다.

## 환경변수

`.env.example`을 `.env.local`로 복사한 뒤 로컬 환경에 맞게 수정합니다.

- `NEXT_PUBLIC_API_BASE_URL`: 백엔드 API 기본 주소. 기본값은 `http://127.0.0.1:8080/api/v1`입니다.
- `NEXT_PUBLIC_APP_MODE`: 공개 가능한 앱 실행 모드. 기본값은 `demo`입니다.

`NEXT_PUBLIC_*` 값은 브라우저에 공개되며 빌드 시점에 번들에 포함될 수 있습니다. API 키, DB 접속 정보, 원본 미디어 경로와 같은 비밀값을 넣지 마세요. 실제 `.env.local` 파일은 Git에서 제외됩니다.

`src/lib/env.ts`는 HTTP(S) 절대 주소 또는 `/api/v1` 같은 루트 상대 경로를 허용하고, 인증 정보·query·fragment가 섞인 기본 주소는 거부합니다. nginx를 사용하는 배포에서는 `NEXT_PUBLIC_API_BASE_URL=/api/v1`로 브라우저와 같은 오리진을 사용합니다. Next.js 프록시는 추가하지 않습니다. 로컬에서 백엔드를 직접 호출하려면 서버의 CORS 허용 목록에 프론트 주소 `http://127.0.0.1:3000`을 포함해야 합니다.

기능별 API 함수는 `src/lib/api/client.ts`의 `fetchJson<T>(endpoint, options)`를 사용합니다. endpoint는 기본 주소 다음의 경로만 전달하며 `/api/v1`을 반복하지 않습니다. `query`에는 `URLSearchParams`, JSON 요청은 `body`에 객체, 파일 요청은 `FormData`를 전달합니다. multipart Content-Type과 boundary는 브라우저가 설정합니다. `signal`, 인증 header, `credentials` 등 fetch 옵션은 그대로 전달되며 인증 방식과 기능별 endpoint는 명세 확정 후 결정합니다.

`fetchJson<T>`는 백엔드 공통 envelope(`isSuccess/code/message/data`)의 성공 `data`만 반환합니다. 데이터가 생략된 성공 또는 HTTP 204·205는 `undefined`이므로 해당 호출은 `fetchJson<void>`를 사용합니다. 그 외 비정상·빈 응답은 `ApiClientError`로 처리하며 오류를 성공이나 빈 결과로 바꾸지 않습니다.

기능 계층에서 오류를 catch하여 `ApiErrorNotice`의 `error`에 전달하면 한국어 메시지·stable code·요청 ID가 표시됩니다. form 오류는 `id`를 지정하고 입력의 `aria-describedby`에서 참조합니다. `ApiClientError.kind`로 `http/api/network/invalid-response/aborted`를 구분하고, `status`는 실제 HTTP 상태(응답 전 실패는 0)입니다. 취소 안내 표시 여부와 재시도는 기능 계층이 결정하며 client는 자동 재시도하지 않습니다. 원래 응답과 예외는 `diagnostics`에만 보존되므로 UI에서 출력하거나 사용자 상태로 복사하지 않습니다.

정상 한국어 서버 메시지를 우선합니다. 현재 백엔드의 고정 영어 오류 6개는 정확한 코드·메시지 조합만 번역하며, 누락·비정상 사용자 메시지는 한국어 fallback으로 처리합니다. 요청 ID는 본문 `requestId` 또는 `X-Request-ID` 응답 헤더에서 읽고 없으면 `제공되지 않음`으로 표시합니다. 현재 백엔드는 요청 ID를 제공하지 않으므로 백엔드 계약 보완과 실제 화면 연동은 남아 있습니다. 자세한 계약은 [API와 데이터 흐름](docs/architecture.md#api와-데이터-흐름)을 참고합니다.

## 현재 구현 상태

- 공통 환경변수 검증, JSON·FormData 요청, query 결합과 API 오류 처리가 준비되어 있습니다.
- API client와 기존 화면 상태에 대한 단위 테스트는 `npm test`로 실행합니다.
- 실제 API 연동과 인증은 아직 구현되지 않았습니다. 현재 `demo` 모드 값은 서버 연동을 켜는 스위치가 아닙니다.
- 상태관리, form, 테스트와 UI 라이브러리 선택은 확정되지 않았습니다.
- 팀 전체에 영향을 주는 새 기술 선택은 [ADR](docs/decisions/)로 기록합니다.

## CI 상태

Jenkins의 Frontend stage는 아직 placeholder입니다. 현재는 저장소에 `frontend` 디렉터리가 있어도 실제 `npm ci` 또는 `npm run check`를 실행하지 않습니다. CI 연결 전까지 로컬의 `npm run check` 결과를 프론트 품질 기준으로 사용합니다.
