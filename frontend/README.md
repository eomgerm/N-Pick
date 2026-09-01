# NewsCut Frontend

NewsCut의 Next.js 프론트엔드 프로젝트입니다. 패키지 매니저는 npm만 사용합니다.

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

## 환경변수

`.env.example`을 `.env.local`로 복사한 뒤 로컬 환경에 맞게 수정합니다.

- `NEXT_PUBLIC_API_BASE_URL`: 백엔드 API 기본 주소. 기본값은 `http://127.0.0.1:8080/api/v1`입니다.
- `NEXT_PUBLIC_APP_MODE`: 공개 가능한 앱 실행 모드. 기본값은 `demo`입니다.

`NEXT_PUBLIC_*` 값은 브라우저에 공개되며 빌드 시점에 번들에 포함될 수 있습니다. API 키, DB 접속 정보, 원본 미디어 경로와 같은 비밀값을 넣지 마세요. 실제 `.env.local` 파일은 Git에서 제외됩니다.

## 디렉터리

- `src/app`: App Router의 route와 화면 조합
- `src/components`: 여러 기능에서 공유하는 UI
- `src/features`: 기능 단위 UI와 로직
- `src/lib/api`: HTTP 접근 코드
- `src/lib/env.ts`: 공개 환경변수 읽기와 검증
- `public`: 정적 파일

검색·검수 화면과 실제 API 연동, 인증, 상태관리, 자동화 테스트는 후속 작업 범위입니다.
