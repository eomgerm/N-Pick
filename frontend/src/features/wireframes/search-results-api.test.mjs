import assert from 'node:assert/strict';
import { existsSync } from 'node:fs';
import { registerHooks } from 'node:module';
import { fileURLToPath } from 'node:url';
import test from 'node:test';

const sourceRoot = new URL('../../', import.meta.url);

registerHooks({
  resolve(specifier, context, nextResolve) {
    if (specifier.startsWith('@/')) {
      const moduleUrl = new URL(specifier.slice(2), sourceRoot);
      for (const extension of ['.ts', '.tsx']) {
        const candidate = new URL(`${moduleUrl.href}${extension}`);
        if (existsSync(fileURLToPath(candidate))) {
          return { url: candidate.href, shortCircuit: true };
        }
      }
    }
    return nextResolve(specifier, context);
  },
});

const { mergeSearchExecutions, mergeSearchResultDetails } = await import('./search-results-api.ts');

const succeeded = { status: 'succeeded', degradedReasons: [], hasAppliedReviewRule: false };

test('모든 페이지가 정상이면 병합 실행도 정상이다', () => {
  assert.deepEqual(mergeSearchExecutions([succeeded, succeeded]), {
    status: 'succeeded',
    degradedReasons: [],
    hasAppliedReviewRule: false,
  });
});

test('이후 페이지의 열화도 병합 실행에 드러난다', () => {
  const merged = mergeSearchExecutions([
    succeeded,
    { status: 'degraded', degradedReasons: ['snapshot-save-failed'], hasAppliedReviewRule: false },
  ]);
  assert.equal(merged.status, 'degraded');
  assert.ok(merged.degradedReasons.includes('snapshot-save-failed'));
});

test('페이지별 열화 사유는 합집합, 검수 규칙 적용은 논리합이다', () => {
  const merged = mergeSearchExecutions([
    { status: 'degraded', degradedReasons: ['resolver-fallback'], hasAppliedReviewRule: false },
    { status: 'degraded', degradedReasons: ['dense-unavailable'], hasAppliedReviewRule: true },
    { status: 'degraded', degradedReasons: ['resolver-fallback'], hasAppliedReviewRule: false },
  ]);
  assert.deepEqual([...merged.degradedReasons].sort(), ['dense-unavailable', 'resolver-fallback']);
  assert.equal(merged.hasAppliedReviewRule, true);
});

test('상세는 fallback 우선·제외 수 합산·사유 합집합으로 병합된다', () => {
  const merged = mergeSearchResultDetails([
    { resolverStatus: 'succeeded', excludedCount: 2, exclusionReasons: ['규칙A'] },
    { resolverStatus: 'fallback', excludedCount: 3, exclusionReasons: ['규칙A', '규칙B'] },
  ]);
  assert.deepEqual(merged, {
    resolverStatus: 'fallback',
    excludedCount: 5,
    exclusionReasons: ['규칙A', '규칙B'],
  });
});

test('빈 페이지 목록은 정상·0으로 병합된다', () => {
  assert.deepEqual(mergeSearchExecutions([]), {
    status: 'succeeded',
    degradedReasons: [],
    hasAppliedReviewRule: false,
  });
  assert.deepEqual(mergeSearchResultDetails([]), {
    resolverStatus: 'succeeded',
    excludedCount: 0,
    exclusionReasons: [],
  });
});
