// 실행: node --test scripts/
const test = require('node:test');
const assert = require('node:assert/strict');
const { validateMessage, validateBranch } = require('./git-rules.cjs');

test('커밋 메시지: 이슈 번호 형식만 통과한다', () => {
  assert.equal(validateMessage(':sparkles: feat(fe): 로그인 페이지 UI 구현 (#123)'), null);
  assert.notEqual(validateMessage(':sparkles: feat(fe): 로그인 페이지 UI 구현 (S15P21A501-123)'), null);
  assert.notEqual(validateMessage(':sparkles: feat(fe): 로그인 페이지 UI 구현'), null);
  assert.notEqual(validateMessage(':bug: feat(fe): 로그인 페이지 UI 구현 (#123)'), null);
});

test('브랜치: 이슈 번호로 끝나야 통과한다', () => {
  assert.equal(validateBranch('fe/feat/login-page-123'), null);
  assert.equal(validateBranch('feat/login-page-123'), null);
  assert.notEqual(validateBranch('fe/feat/login-page-S15P21A501-123'), null);
  assert.notEqual(validateBranch('feat/login-page'), null);
});

test('브랜치: 보호 브랜치와 release/* 는 검사하지 않는다', () => {
  for (const branch of ['main', 'dev', 'release/1.0']) assert.equal(validateBranch(branch), null);
});
