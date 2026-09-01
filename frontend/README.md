# NewsCut Frontend

NewsCut의 Next.js 프론트엔드 프로젝트입니다. 패키지 매니저는 npm만 사용합니다.

현재 프로젝트는 App Router 기반의 화면 조합, 기능 단위 디렉터리, 공통 UI, API·환경변수 계층을 분리한 초기 구조입니다. 검색·검수 화면과 실제 API 연동, 인증, 상태관리, 자동화 테스트는 후속 작업 범위입니다.

## 개발 문서

- [프론트 작업 규칙](AGENTS.md): 반드시 지켜야 하는 코드·구조 규칙
- [프론트 아키텍처](docs/architecture.md): 디렉터리 책임, 의존 방향과 데이터 흐름
- [설계 결정 기록](docs/decisions/): 중요한 기술 선택과 그 배경
- [제품 FRD](../docs/frd.md): 화면 상태, 기능 요구사항과 인수 조건

## 기술 스택

| 구분            | 기술                                                |
| --------------- | --------------------------------------------------- |
| Framework       | Next.js 15 App Router                               |
| UI              | React 19                                            |
| Language        | TypeScript 5.9                                      |
| Styling         | Tailwind CSS 4, PostCSS                             |
| Lint            | ESLint 9, `next/core-web-vitals`, `next/typescript` |
| Format          | Prettier, `prettier-plugin-tailwindcss`             |
| Package manager | npm 11                                              |
| Runtime         | Node.js 24                                          |

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

| 명령                   | 설명                                   |
| ---------------------- | -------------------------------------- |
| `npm run dev`          | `127.0.0.1`에서 개발 서버 실행         |
| `npm run build`        | 프로덕션 번들 생성                     |
| `npm run start`        | `127.0.0.1`에서 프로덕션 서버 실행     |
| `npm run lint`         | ESLint 검사                            |
| `npm run typecheck`    | TypeScript 타입 검사                   |
| `npm run format`       | Prettier로 파일 정리                   |
| `npm run format:check` | Prettier 형식 검사                     |
| `npm run check`        | 형식, 린트, 타입, 빌드를 순서대로 검사 |

작업을 마치기 전에는 전체 품질 검사를 실행합니다.

```bash
npm run check
```

현재 Git hook은 브랜치명과 커밋 메시지만 검사합니다. 프론트엔드 lint와 format은 commit 시 자동 실행되지 않으므로 직접 `npm run check`를 실행해야 합니다.

## 아키텍처 개요

```text
src/
├─ app/          route와 화면 조합
├─ components/   여러 기능에서 공유하거나 앱 전역에 쓰는 UI
├─ features/     기능 단위 UI와 로직
└─ lib/          환경변수와 HTTP client 등 공통 기반 코드
```

현재 구현된 화면은 정적으로 생성되는 루트 `/`뿐입니다. 제품 FRD가 정의한 `/search`와 `/review`는 아직 구현되지 않았습니다. 디렉터리 책임, 의존 방향, 상태 소유권과 API 흐름은 [프론트 아키텍처](docs/architecture.md)를 기준으로 합니다.

## 환경변수

`.env.example`을 `.env.local`로 복사한 뒤 로컬 환경에 맞게 수정합니다.

- `NEXT_PUBLIC_API_BASE_URL`: 백엔드 API 기본 주소. 기본값은 `http://127.0.0.1:8080/api/v1`입니다.
- `NEXT_PUBLIC_APP_MODE`: 공개 가능한 앱 실행 모드. 기본값은 `demo`입니다.

`NEXT_PUBLIC_*` 값은 브라우저에 공개되며 빌드 시점에 번들에 포함될 수 있습니다. API 키, DB 접속 정보, 원본 미디어 경로와 같은 비밀값을 넣지 마세요. 실제 `.env.local` 파일은 Git에서 제외됩니다.

`src/lib/env.ts`는 API 주소가 HTTP 또는 HTTPS 절대 URL인지 확인하고 앱 모드가 비어 있지 않은지 검증합니다. 환경변수 접근은 각 컴포넌트에 흩어놓지 않고 이 모듈을 통해 처리합니다.

## 현재 구현 상태

- 공통 환경변수 검증과 JSON HTTP client가 준비되어 있습니다.
- 실제 API 연동, 인증, 상태관리와 자동화 테스트는 아직 구현되지 않았습니다.
- 상태관리, form, 테스트와 UI 라이브러리 선택은 확정되지 않았습니다.
- 팀 전체에 영향을 주는 새 기술 선택은 [ADR](docs/decisions/)로 기록합니다.

## CI 상태

Jenkins의 Frontend stage는 아직 placeholder입니다. 현재는 저장소에 `frontend` 디렉터리가 있어도 실제 `npm ci` 또는 `npm run check`를 실행하지 않습니다. CI 연결 전까지 로컬의 `npm run check` 결과를 프론트 품질 기준으로 사용합니다.
