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

const { createSearchResultsHref, isSameSearchDestination } = await import('./search-navigation.ts');

const emptyRange = { from: '', to: '' };

test('검색어를 정리하고 URL에서 안전하게 인코딩한다', () => {
  const href = createSearchResultsHref({
    query: '  명절 교통 & 서울역  ',
    broadcast: emptyRange,
    filming: emptyRange,
  });

  assert.equal(
    href,
    '/search/results?q=%EB%AA%85%EC%A0%88+%EA%B5%90%ED%86%B5+%26+%EC%84%9C%EC%9A%B8%EC%97%AD',
  );
});

test('방송일과 촬영일 범위를 서로 다른 URL 필드로 보존한다', () => {
  const href = createSearchResultsHref({
    query: '귀성길',
    broadcast: { from: '2026-09-01', to: '2026-09-03' },
    filming: { from: '2026-08-28', to: '2026-08-29' },
  });
  const url = new URL(href, 'https://npick.local');

  assert.deepEqual(Object.fromEntries(url.searchParams), {
    q: '귀성길',
    broadcastFrom: '2026-09-01',
    broadcastTo: '2026-09-03',
    filmingFrom: '2026-08-28',
    filmingTo: '2026-08-29',
  });
});

test('빈 검색어나 불완전하고 잘못된 날짜 범위는 이동 대상으로 만들지 않는다', () => {
  assert.equal(
    createSearchResultsHref({ query: '   ', broadcast: emptyRange, filming: emptyRange }),
    null,
  );

  for (const broadcast of [
    { from: '2026-09-01', to: '' },
    { from: '2026-09-03', to: '2026-09-01' },
    { from: '2026-02-30', to: '2026-03-01' },
  ]) {
    assert.equal(
      createSearchResultsHref({ query: '명절 교통', broadcast, filming: emptyRange }),
      null,
    );
  }
});

test('같은 검색 조건은 query 순서와 무관하게 같은 이동 대상으로 본다', () => {
  assert.equal(
    isSameSearchDestination(
      '/search/results?broadcastFrom=2026-09-01&q=%EA%B7%80%EC%84%B1%EA%B8%B8&broadcastTo=2026-09-03',
      '/search/results?q=%EA%B7%80%EC%84%B1%EA%B8%B8&broadcastFrom=2026-09-01&broadcastTo=2026-09-03',
    ),
    true,
  );
  assert.equal(
    isSameSearchDestination(
      '/search/results?q=%EA%B7%80%EC%84%B1%EA%B8%B8',
      '/search/results?q=서울역',
    ),
    false,
  );
});
