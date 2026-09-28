# 협업 규칙 (Gitmoji 기반)

커밋 메시지, 브랜치, 이슈, Pull Request 규칙 문서입니다.
커밋 메시지와 브랜치 이름은 Git hook(lefthook)으로 **자동 검사**됩니다. 규칙을 어기면 커밋이 막히고 고치는 방법이 출력됩니다.

## 0. 최초 1회 설정

클론 받은 뒤 아래 명령을 한 번 실행하면 훅이 활성화됩니다.

```bash
npm install
```

## 1. 커밋 메시지 규칙

### 형식

```
<:gitmoji:> <type>(<scope>): <설명> (#이슈 번호)
```

```
:sparkles: feat(fe): 로그인 페이지 UI 구현 (#123)
:bug: fix(be): 회원가입 시 이메일 중복 체크 오류 수정 (#56)
:wrench: chore(infra): Jenkins 파이프라인 설정 추가 (#4)
```

- 이모지는 **shortcode**(`:sparkles:`)로 씁니다. GitHub가 자동으로 이모지로 렌더링해줍니다.
- **scope는 필수**이며 `fe`, `be`, `ai`, `infra` 중 하나입니다.
- **type은 반드시 소문자**로 씁니다 (`docs:` O, `Docs:` X)
- 설명은 한글/영문 모두 가능, **1~60자**
- 이슈 번호는 항상 맨 뒤에 괄호로 붙입니다 (`#123` 은 GitHub가 이슈 링크로 연결해줍니다)
- shortcode와 type이 짝이 맞지 않으면(`:bug: feat(fe):`) 커밋이 막힙니다.
- `npx gitmoji -c` 로 이모지를 골라 대화형 커밋을 할 수 있습니다.
  - 이모지를 고른 뒤 title 입력란에 `feat(fe): 로그인 페이지 UI 구현` 처럼 **type과 scope까지 함께** 입력하세요.
  - gitmoji-cli의 scope 프롬프트는 `(fe):` 만 붙이고 type을 빼기 때문에 `.gitmojirc.json`에서 `scopePrompt: false`로 꺼두었습니다.
  - 마찬가지로 title 첫 글자를 대문자로 바꾸는 동작도 `capitalizeTitle: false`로 꺼두었습니다 (소문자 type 규칙과 충돌).

### 이슈 번호 자동 완성

브랜치 이름(`type/설명-이슈번호`)에 이미 이슈 번호가 들어있으므로, 커밋 메시지에는 직접 안 써도 됩니다.

```bash
# fe/feat/login-page-123 브랜치에서
git commit -m ":sparkles: feat(fe): 로그인 페이지 UI 구현"
# -> ":sparkles: feat(fe): 로그인 페이지 UI 구현 (#123)" 으로 자동 완성
```

- `main`/`dev`처럼 이슈 번호가 없는 보호 브랜치에서는 동작하지 않으므로 직접 적어야 합니다.
- 이미 같은 이슈 번호가 있으면(`--amend` 등) 중복으로 붙이지 않습니다.
- 자동 완성이 실패해도 최종적으로 `commit-msg` 검사가 이슈 번호 유무를 다시 확인합니다.

### Gitmoji ↔ type 매핑

| Shortcode | 렌더링 | Type | 의미 |
|---|---|---|---|
| `:sparkles:` | ✨ | feat | 새로운 기능 추가 |
| `:bug:` | 🐛 | fix | 버그 수정 |
| `:memo:` | 📝 | docs | 문서 추가/수정 |
| `:lipstick:` | 💄 | style | 로직 변경 없는 스타일/포맷팅 |
| `:recycle:` | ♻️ | refactor | 리팩토링 (기능 변화 없음) |
| `:white_check_mark:` | ✅ | test | 테스트 코드 추가/수정 |
| `:wrench:` | 🔧 | chore | 설정, 빌드, 패키지 매니저 등 |
| `:zap:` | ⚡️ | perf | 성능 개선 |
| `:fire:` | 🔥 | remove | 코드/파일 삭제 |
| `:ambulance:` | 🚑 | hotfix | 긴급 수정 |
| `:rocket:` | 🚀 | deploy | 배포 관련 |
| `:twisted_rightwards_arrows:` | 🔀 | merge | 브랜치 병합 |
| `:rewind:` | ⏪ | revert | 이전 커밋으로 되돌리기 |
| `:tada:` | 🎉 | init | 프로젝트/기능 최초 세팅 |
| `:art:` | 🎨 | design | UI/디자인 작업 |

> 타입을 추가/변경하려면 `scripts/git-rules.cjs` 상단의 `TYPES` 만 고치고 이 표를 갱신하면 됩니다.

`Merge ...`, `Revert ...`, `fixup!`, `squash!` 로 시작하는 자동 생성 메시지는 검사에서 제외됩니다.

## 2. 브랜치 네이밍 규칙

### 형식

```
[<플랫폼>/]<커밋타입>/<설명(kebab-case, 영문 또는 한글)>-<이슈 번호>
```

```
feat/login-page-123
fix/signup-email-validation-56
ai/feat/model-serving-8
fe/fix/버그-수정-45
infra/chore/jenkins-pipeline-4
```

- **플랫폼**(선택): `fe`, `be`, `ai`, `infra` 중 하나. 구분이 필요할 때만 붙이고 없어도 됩니다.
- **커밋타입**(필수): 커밋 메시지와 완전히 동일한 목록 → 위 매핑 표 참고. 임의의 단어는 허용되지 않습니다.
- 설명은 소문자 영문/숫자/한글/하이픈만 씁니다.
- 이슈 번호는 항상 맨 뒤에 `-` 로 이어 붙입니다 (`#` 은 셸에서 주석으로 해석되므로 브랜치 이름에는 쓰지 않습니다)
- `main`, `master`, `develop`, `dev`, `dev-be`, `dev-fe`, `dev-ai`, `release/*` 는 검사에서 제외됩니다.
- 브랜치와 커밋이 같은 타입 목록(`scripts/git-rules.cjs`의 `TYPES`)을 참조합니다.

## 3. 이슈 / Pull Request 규칙

- **이슈**: 작업 전에 이슈를 먼저 만들고, 그 번호로 브랜치를 만듭니다. "New issue"에서 템플릿을 고릅니다.
  - 기능 구현 → [feature.md](ISSUE_TEMPLATE/feature.md)
  - 버그 제보 → [bug.md](ISSUE_TEMPLATE/bug.md)
- **머지 대상 브랜치**: 기능/버그 브랜치는 `dev`로 PR을 올립니다. `main`은 배포 시점에만 머지합니다.
- **제목 형식**: 커밋 메시지와 동일하게 `<:gitmoji:> <type>(<scope>): <설명> (#이슈 번호)`
- **템플릿**: GitHub PR에는 템플릿 선택 드롭다운이 없습니다.
  - 신규 기능 → [pull_request_template.md](pull_request_template.md) (PR 생성 시 자동 적용)
  - 버그 수정 → [fix.md](PULL_REQUEST_TEMPLATE/fix.md) (PR 생성 URL 끝에 `?template=fix.md` 를 붙입니다)
- **리뷰어**: 최소 1인 이상 지정, 승인(approve) 후 머지
- **머지 방식**: 일반 머지 커밋, 머지 후 소스 브랜치 삭제. Squash 머지는 쓰지 않습니다.
- **연결된 이슈**: "관련 이슈"에 이슈 번호(`#123`)를 반드시 작성

## 4. 자동 검사 구조

실행 순서: `prepare-commit-msg` → `commit-msg`

| 훅 | 실행 명령 | 역할 |
|---|---|---|
| `prepare-commit-msg` | `git-rules.cjs branch` | 브랜치 이름 검사 |
| `prepare-commit-msg` | `git-rules.cjs prepare` | 브랜치에서 이슈 번호를 추출해 메시지 끝에 부착 |
| `commit-msg` | `git-rules.cjs verify` | 커밋 메시지 형식 최종 검사 |

- 설정 파일: [`lefthook.yml`](../lefthook.yml)
- 검사 로직 + 타입 목록 단일 소스: `scripts/git-rules.cjs`

> 브랜치 검사를 `pre-commit`이 아닌 `prepare-commit-msg`에 둔 이유: lefthook은 스테이징된 파일이 없으면 `pre-commit` 커맨드를 건너뛰는데, 브랜치 이름은 파일과 무관하게 항상 검사해야 하기 때문입니다.

### 훅을 일시적으로 끄기

```bash
LEFTHOOK=0 git commit -m "..."
```
