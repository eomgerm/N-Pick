import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { registerHooks } from 'node:module';
import test from 'node:test';
import ts from 'typescript';

registerHooks({
  load(url, context, nextLoad) {
    if (url === new URL('./search-execution-status.ts', import.meta.url).href) {
      return {
        format: 'module',
        source: ts.transpileModule(readFileSync(new URL(url), 'utf8'), {
          compilerOptions: { module: ts.ModuleKind.ESNext },
        }).outputText,
        shortCircuit: true,
      };
    }
    return nextLoad(url, context);
  },
});

const {
  canCreateInquiry,
  getDegradedReasonNotices,
  getDemoSearchExecution,
  getSearchExecutionAnnouncement,
} = await import('./search-execution-status.ts');

test('degraded mock 상태를 검색 실행 표시 모델로 변환한다', () => {
  const cases = [
    ['degraded-resolver', 'resolver-fallback'],
    ['degraded-dense', 'dense-unavailable'],
    ['degraded-snapshot', 'snapshot-save-failed'],
  ];

  for (const [state, reason] of cases) {
    assert.deepEqual(getDemoSearchExecution(state), {
      status: 'degraded',
      degradedReasons: [reason],
      hasAppliedReviewRule: false,
    });
  }

  assert.deepEqual(getDemoSearchExecution('review-rule'), {
    status: 'succeeded',
    degradedReasons: [],
    hasAppliedReviewRule: true,
  });
});

test('복수 degraded 사유를 고정 순서로 중복 없이 표시한다', () => {
  const notices = getDegradedReasonNotices([
    'snapshot-save-failed',
    'resolver-fallback',
    'dense-unavailable',
    'resolver-fallback',
  ]);

  assert.deepEqual(
    notices.map(({ reason }) => reason),
    ['resolver-fallback', 'dense-unavailable', 'snapshot-save-failed'],
  );
  assert.equal(new Set(notices.map(({ title }) => title)).size, 3);
  assert.doesNotMatch(
    notices.map(({ description, title }) => `${title} ${description}`).join(' '),
    /BM25|JSON|오류 코드|데이터베이스/i,
  );
});

test('snapshot 저장 실패에서만 문의를 막는다', () => {
  assert.equal(canCreateInquiry(getDemoSearchExecution()), true);
  assert.equal(canCreateInquiry(getDemoSearchExecution('degraded-resolver')), true);
  assert.equal(canCreateInquiry(getDemoSearchExecution('degraded-dense')), true);
  assert.equal(canCreateInquiry(getDemoSearchExecution('degraded-snapshot')), false);
});

test('검색 상태 안내에 degraded 사유와 검수 규칙 적용을 함께 포함한다', () => {
  const execution = {
    status: 'degraded',
    degradedReasons: ['dense-unavailable'],
    hasAppliedReviewRule: true,
  };

  assert.equal(
    getSearchExecutionAnnouncement(execution),
    '일부 기능 누락: 의미 검색 일부 누락. 검수 규칙 적용',
  );
});
