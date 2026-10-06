# N-Pick

이 저장소에서 작업하는 모든 에이전트가 따르는 규칙 정본입니다.

## 커밋 / 브랜치 규칙

lefthook이 자동 검사합니다. 전체 규칙: [.github/CONTRIBUTING.md](.github/CONTRIBUTING.md)

```
커밋:   <:gitmoji:> <type>(<scope>): <설명> (#이슈 번호)
        :sparkles: feat(fe): 로그인 페이지 UI 구현 (#123)

브랜치: [<플랫폼>/]<type>/<설명-kebab>-<이슈 번호>
        fe/feat/login-page-123
```

- scope/플랫폼: `fe` `be` `ai` `infra` (커밋은 필수, 브랜치는 선택)
- type 15종과 이모지 매핑, 검사 로직 단일 소스: `scripts/git-rules.cjs`
- 브랜치 이름에 이슈 번호가 있으면 커밋 메시지에 자동으로 붙으므로 직접 쓰지 않아도 됩니다.
- 클론 직후 1회: `npm install`

## 아키텍처 정본

시스템 전체 구조는 C4 다이어그램 3종이 정본입니다. 기술 표기가 문서 간에 어긋나면 [02 Container](docs/architecture/02-container.md)의 *요소* 표를 따릅니다.

| 레벨 | 문서 | 범위 |
| --- | --- | --- |
| L1 Context | [docs/architecture/01-context.md](docs/architecture/01-context.md) | 사용자와 시스템 경계, 경계 밖으로 나가는 데이터 |
| L2 Container | [docs/architecture/02-container.md](docs/architecture/02-container.md) | 배포 단위와 통신 프로토콜, **기술 스택 정본** |
| Deployment | [docs/architecture/03-deployment.md](docs/architecture/03-deployment.md) | P0 노드 배치 (EC2 + RunPod GPU 파드) |

요청·응답 스키마와 오류 코드는 C4 문서가 아니라 [docs/contracts/](docs/contracts/README.md)가 정본입니다.

## 하위 규약

| 범위 | 문서 |
| --- | --- |
| `frontend/` (Next.js) | [frontend/AGENTS.md](frontend/AGENTS.md) → 설계 정본 [frontend/docs/architecture.md](frontend/docs/architecture.md) |
| `backend/` (Spring Boot) | [backend/AGENTS.md](backend/AGENTS.md) → 설계 정본 [backend/docs/ddd-package-architecture.md](backend/docs/ddd-package-architecture.md) |
| `ai/` (Python Pipeline Worker) | [ai/AGENTS.md](ai/AGENTS.md) → 처리 정본 [docs/frd.md](docs/frd.md) §5 |

해당 디렉터리에서 작업하기 전에 그 문서를 먼저 읽는다.

## 문서 템플릿

문서를 새로 만들 때는 반드시 해당 템플릿을 읽고 그 구조 그대로 채운다. 임의로 섹션을 추가/삭제하지 않는다.

| 산출물 | 템플릿 | 저장 위치 |
| --- | --- | --- |
| PRD | (팀 제공 예정) | `docs/prd/<기능명>.md` |
| FRD | (팀 제공 예정) | `docs/frd/<기능명>.md` |
| Jira 이슈 | `docs/templates/jira-issue.md` | Jira (프로젝트 `S15P21A501`) |
| GitHub 이슈 | `.github/ISSUE_TEMPLATE/feature.md`, `bug.md` | GitHub가 "New issue"에서 선택지로 제공 |
| GitHub PR | `.github/pull_request_template.md`, `.github/PULL_REQUEST_TEMPLATE/fix.md` | 기본 템플릿은 자동 적용, fix는 `?template=fix.md` |

## 기능 개발 절차

기능 구현을 요청받으면 코드를 쓰기 전에:

1. `docs/prd/`와 `docs/frd/`에서 해당 기능 문서를 찾아 읽는다.
2. 문서가 없으면 사용자에게 알리고 확인을 받는다. 임의로 만들지 않는다.
3. 구현은 FRD의 인수 조건(AC)을 기준으로 한다. AC에 없는 기능은 임의로 추가하지 않는다.
4. FRD와 코드가 어긋나면 코드를 고치지 말고 먼저 어긋난 지점을 보고한다.
