#!/usr/bin/env node
// 커밋 메시지/브랜치 이름 규칙 검사와 지라 키 자동 부착.
// lefthook.yml에서 서브커맨드로 호출한다.
//
//   node scripts/git-rules.cjs branch                 현재 브랜치 이름 검사
//   node scripts/git-rules.cjs prepare <파일> <소스>   브랜치의 지라 키를 메시지 끝에 부착
//   node scripts/git-rules.cjs verify  <파일>          커밋 메시지 형식 검사
//
// 규칙 문서: .gitlab/CONTRIBUTING.md

const fs = require('fs');
const { execSync } = require('child_process');

// ── 규칙 정의 (여기만 고치면 커밋/브랜치 양쪽에 반영된다) ──────────────

// 타입 -> gitmoji shortcode (GitLab/GitHub가 렌더링해준다)
const TYPES = {
  feat: ':sparkles:',
  fix: ':bug:',
  docs: ':memo:',
  style: ':lipstick:',
  refactor: ':recycle:',
  test: ':white_check_mark:',
  chore: ':wrench:',
  perf: ':zap:',
  remove: ':fire:',
  hotfix: ':ambulance:',
  deploy: ':rocket:',
  merge: ':twisted_rightwards_arrows:',
  revert: ':rewind:',
  init: ':tada:',
  design: ':art:',
};

// 플랫폼 스코프. 커밋 메시지에서는 필수, 브랜치 이름에서는 선택.
const SCOPES = ['fe', 'be', 'ai', 'infra'];

// 지라 키가 없는 통합 브랜치는 이름 검사에서 제외한다.
const PROTECTED_BRANCHES = ['main', 'master', 'develop', 'dev', 'dev-be', 'dev-fe', 'dev-ai'];

// git이 자동 생성하는 커밋 메시지는 형식 검사에서 제외한다.
const SKIP_PATTERNS = [/^Merge /, /^Revert /, /^fixup!/, /^squash!/];

const typeNames = Object.keys(TYPES).join('|');

// <:shortcode:> <type>(<scope>): <설명 1~60자> (지라 키)
const MSG_PATTERN = new RegExp(
  String.raw`^(:[a-z0-9_+-]+:) (${typeNames})\((${SCOPES.join('|')})\): .{1,60} \([A-Z][A-Z0-9]*-\d+\)$`
);

// [<플랫폼>/]<type>/<설명 kebab-case>-<지라 키>
const BRANCH_PATTERN = new RegExp(
  String.raw`^(?:(?:${SCOPES.join('|')})/)?(?:${typeNames})/[a-z0-9가-힣-]+-[A-Z][A-Z0-9]*-\d+$`
);

const ISSUE_KEY_PATTERN = /([A-Z][A-Z0-9]*-\d+)$/;

// ── 검사 (통과하면 null, 위반하면 사유 문자열) ────────────────────────

function validateMessage(firstLine) {
  if (SKIP_PATTERNS.some((p) => p.test(firstLine))) return null;

  const matched = MSG_PATTERN.exec(firstLine);
  if (!matched) return '형식이 규칙과 다릅니다.';

  const [, shortcode, type] = matched;
  if (TYPES[type] !== shortcode) {
    return `타입 "${type}" 에는 ${TYPES[type]} 를 써야 합니다 (입력한 이모지: ${shortcode}).`;
  }
  return null;
}

function validateBranch(branch) {
  if (PROTECTED_BRANCHES.includes(branch) || branch.startsWith('release/')) return null;
  return BRANCH_PATTERN.test(branch) ? null : '형식이 규칙과 다릅니다.';
}

// ── 서브커맨드 ───────────────────────────────────────────────────────

// detached HEAD 등 브랜치가 없으면 null
function currentBranch() {
  try {
    return execSync('git symbolic-ref --short HEAD', { encoding: 'utf8' }).trim();
  } catch {
    return null;
  }
}

const firstLineOf = (file) => fs.readFileSync(file, 'utf8').split('\n')[0].trim();

const COMMANDS = {
  branch() {
    const branch = currentBranch();
    if (branch === null) return;

    const reason = validateBranch(branch);
    if (!reason) return;

    fail(`
브랜치 이름 규칙 위반: "${branch}" — ${reason}

  형식: [<플랫폼>/]<타입>/<설명(kebab-case, 영문 또는 한글)>-<지라 키>
  예시: feat/login-page-S15P11A105-123
        ai/fix/버그-수정-S15P11A105-45

  허용 플랫폼(선택): ${SCOPES.join(', ')}
  허용 타입: ${Object.keys(TYPES).join(', ')}
  검사 제외: ${PROTECTED_BRANCHES.join(', ')}, release/*

  브랜치 이름 변경: git branch -m <새이름>
  전체 규칙: .gitlab/CONTRIBUTING.md
`);
  },

  // 브랜치 이름 끝의 지라 키를 커밋 메시지 첫 줄 끝에 붙인다.
  // 예: fe/feat/login-S15P11A105-123 에서 ":sparkles: feat(fe): 로그인" 만 써도
  //     ":sparkles: feat(fe): 로그인 (S15P11A105-123)" 이 된다.
  prepare(msgFile, commitSource) {
    // merge/squash는 git이 완성된 메시지를 넣어주므로 건드리지 않는다
    if (['merge', 'squash'].includes(commitSource)) return;

    const branch = currentBranch();
    if (branch === null) return;

    const matched = branch.match(ISSUE_KEY_PATTERN);
    if (!matched) return; // main/develop 등 지라 키가 없는 브랜치

    const issueKey = matched[1];
    const lines = fs.readFileSync(msgFile, 'utf8').split('\n');
    const firstLine = lines[0];

    if (firstLine.includes(issueKey)) return; // --amend 등 중복 방지
    if (firstLine.trim() === '' || firstLine.trim().startsWith('#')) return; // 아직 안 쓴 상태

    lines[0] = `${firstLine} (${issueKey})`;
    fs.writeFileSync(msgFile, lines.join('\n'));
  },

  verify(msgFile) {
    const firstLine = firstLineOf(msgFile);
    const reason = validateMessage(firstLine);
    if (!reason) return;

    const table = Object.entries(TYPES).map(([type, code]) => `${code} ${type}`).join('  ');
    fail(`
커밋 메시지 규칙 위반: ${reason}

  입력: ${firstLine}

  형식: <:gitmoji:> <type>(<scope>): <설명> (지라 키)
  예시: :sparkles: feat(fe): 로그인 페이지 UI 구현 (S15P11A105-123)

  설명은 1~60자, 지라 키는 항상 맨 뒤 괄호에 붙입니다.
  허용 스코프: ${SCOPES.join(', ')}
  허용 타입:
    ${table}

  npx gitmoji -c 로 이모지를 골라 커밋할 수 있습니다.
  전체 규칙: .gitlab/CONTRIBUTING.md
`);
  },
};

function fail(message) {
  console.error(message);
  process.exit(1);
}

module.exports = { TYPES, SCOPES, validateMessage, validateBranch };

if (require.main === module) {
  const [command, ...args] = process.argv.slice(2);
  const handler = COMMANDS[command];
  if (!handler) {
    console.error(`알 수 없는 명령: ${command} (사용 가능: ${Object.keys(COMMANDS).join(', ')})`);
    process.exit(2);
  }
  handler(...args);
}
