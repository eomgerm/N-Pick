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

test('상세는 fallback 우선·제외 수와 사유 모두 첫 페이지 값으로 병합된다', () => {
  // 제외 수는 페이지마다 전체 후보 pool 기준으로 다시 세므로 합산하면 부풀려진다(S15P21A501-280 #1).
  // 첫 페이지 값(2)이 대표값이고 합산(5)이 아니다. 사유도 같은 첫 페이지 기준이라 ['규칙A']다.
  const merged = mergeSearchResultDetails([
    { resolverStatus: 'succeeded', excludedCount: 2, exclusionReasons: ['규칙A'] },
    { resolverStatus: 'fallback', excludedCount: 3, exclusionReasons: ['규칙A', '규칙B'] },
  ]);
  assert.deepEqual(merged, {
    resolverStatus: 'fallback',
    excludedCount: 2,
    exclusionReasons: ['규칙A'],
  });
});

test('첫 페이지 제외 0건이면 뒤 페이지에 사유가 있어도 「0건+사유」 모순을 만들지 않는다', () => {
  // 수는 첫 페이지·사유는 합집합으로 두면 excludedCount 0 인데 exclusionReasons 가 차 §5.1
  // 불변식(수 0이면 사유도 빔)이 깨진다. 둘 다 첫 페이지 기준으로 좁혀 모순을 없앤다.
  const merged = mergeSearchResultDetails([
    { resolverStatus: 'succeeded', excludedCount: 0, exclusionReasons: [] },
    {
      resolverStatus: 'succeeded',
      excludedCount: 2,
      exclusionReasons: ['승인된 장면 제외 규칙에 해당'],
    },
  ]);
  assert.deepEqual(merged, {
    resolverStatus: 'succeeded',
    excludedCount: 0,
    exclusionReasons: [],
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
