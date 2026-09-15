import assert from 'node:assert/strict';
import { existsSync, readFileSync } from 'node:fs';
import { registerHooks } from 'node:module';
import { fileURLToPath } from 'node:url';
import test from 'node:test';
import { createElement } from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import ts from 'typescript';

const cssModuleUrl = `data:text/javascript,${encodeURIComponent(
  'export default new Proxy({}, { get: (_, key) => String(key) });',
)}`;
const appShellUrl = `data:text/javascript,${encodeURIComponent(
  'export function AppShell({ children }) { return children; }',
)}`;
const navigationUrl = `data:text/javascript,${encodeURIComponent(
  'export function useRouter() { return { push() {} }; }',
)}`;
const sourceRoot = new URL('../../', import.meta.url);

registerHooks({
  resolve(specifier, context, nextResolve) {
    if (specifier.endsWith('.module.css')) {
      return { url: cssModuleUrl, shortCircuit: true };
    }
    if (specifier === '@/components/app-shell') {
      return { url: appShellUrl, shortCircuit: true };
    }
    if (specifier === 'next/navigation') {
      return { url: navigationUrl, shortCircuit: true };
    }
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
  load(url, context, nextLoad) {
    if (url.endsWith('.ts') || url.endsWith('.tsx')) {
      return {
        format: 'module',
        source: ts.transpileModule(readFileSync(new URL(url), 'utf8'), {
          compilerOptions: { module: ts.ModuleKind.ESNext, jsx: ts.JsxEmit.ReactJSX },
        }).outputText,
        shortCircuit: true,
      };
    }
    return nextLoad(url, context);
  },
});

const { WireframeShell } = await import('./wireframe-shell.tsx');

function renderShell({ preview, state, ...params } = {}, props = {}) {
  return renderToStaticMarkup(
    createElement(WireframeShell, {
      initialQuery: '명절 교통',
      initialParams: { ...params, ...(state ? { state } : {}), ...(preview ? { preview } : {}) },
      theme: 'shinhan',
      ...props,
    }),
  );
}

test('degraded demo 세 종류는 결과 10건을 유지하며 각각의 상태를 알린다', () => {
  const cases = [
    ['degraded-resolver', '검색어 해석 일부 누락'],
    ['degraded-dense', '의미 검색 일부 누락'],
    ['degraded-snapshot', '검색 기록 저장 실패'],
  ];

  for (const [state, reason] of cases) {
    const html = renderShell({ preview: 'loading', state });
    assert.equal((html.match(/class="resultCard/g) ?? []).length, 10);
    assert.ok((html.match(new RegExp(reason, 'g')) ?? []).length >= 2);
    assert.ok(html.includes('일부 기능 누락'));
    assert.ok(html.includes('명절 교통 검색 결과 10개'));
    assert.ok((html.match(/송출 전 최종 확인/g) ?? []).length >= 2);
  }
});

test('검수 규칙 demo는 정상 결과와 적용 사실만 함께 표시한다', () => {
  const html = renderShell({ state: 'review-rule' });

  assert.equal((html.match(/class="resultCard/g) ?? []).length, 10);
  assert.equal((html.match(/class="cardInquiryButton"/g) ?? []).length, 0);
  assert.ok(!html.includes('이상해요'));
  assert.ok(html.includes('정상 검색'));
  assert.ok(html.includes('검수 규칙 적용'));
  assert.doesNotMatch(html, /rule[_ -]?id|condition|JSON|오류 코드/i);
});

test('snapshot 실패 Preview는 통합 경로에서도 문의를 비활성화한다', () => {
  const html = renderShell({ preview: 'loading', state: 'degraded-snapshot' });

  assert.match(html, /data-state="unavailable"/);
  assert.equal((html.match(/data-state="unavailable"/g) ?? []).length, 1);
  assert.ok(html.includes('문의 불가'));
  assert.ok(!html.includes('id="inquiry-unavailable-'));
  assert.match(html, /<dialog aria-describedby="[^"]+"/);
});

test('정상 빈 결과는 임의 degraded 경고를 만들지 않는다', () => {
  const html = renderShell({ state: 'empty' });

  assert.ok(html.includes('관련 장면 0개'));
  assert.ok(!html.includes('검색 기록 저장 실패'));
  assert.ok(!html.includes('일부 기능 누락'));
});

test('결과 URL의 방송일과 촬영일 범위를 각각 복원한다', () => {
  const html = renderShell({
    broadcastFrom: '2026-09-01',
    broadcastTo: '2026-09-03',
    filmingFrom: '2026-08-28',
    filmingTo: '2026-08-29',
  });

  assert.match(html, /방송일 기간 선택: 2026\.09\.01 – 2026\.09\.03/);
  assert.match(html, /촬영일 기간 선택: 2026\.08\.28 – 2026\.08\.29/);
});

test('빈 결과도 실제 degraded 경고와 resolver 상태를 보존한다', () => {
  for (const reason of ['resolver-fallback', 'dense-unavailable', 'snapshot-save-failed']) {
    const html = renderShell(
      { state: 'empty' },
      {
        execution: { status: 'degraded', degradedReasons: [reason], hasAppliedReviewRule: false },
      },
    );
    assert.match(html, /관련 장면 0개/);
    assert.match(html, /일부 기능 누락/);
    assert.doesNotMatch(html, /정상 완료/);
    assert.match(
      html,
      reason === 'resolver-fallback'
        ? /해석을 사용할 수 없어 기본 단어 검색으로 전환/
        : /해석 상태가 제공되지 않았어요/,
    );
  }
});

test('검색 실패에는 빈 결과의 제외 정보와 부족 안내를 표시하지 않는다', () => {
  const html = renderShell(
    { state: 'failed' },
    {
      resultDetails: { excludedCount: 7 },
    },
  );
  assert.match(html, /검색을 완료하지 못했어요/);
  assert.doesNotMatch(html, /제외된 결과|검색 결과 부족 안내|후보 부족/);
});

const { SearchResultState } = await import('./search-result-state.tsx');

test('빈 결과의 적용 조건과 제외 수 0·미제공·유효하지 않은 값을 구분한다', () => {
  for (const excludedCount of [0, 4, undefined, null, -1, 1.5, NaN]) {
    const html = renderShell(
      { state: 'empty', broadcastFrom: '2026-09-01', broadcastTo: '2026-09-11' },
      {
        resultDetails: {
          excludedCount,
          resolverStatus: 'succeeded',
          exclusionReasons: ['명시 날짜 충돌'],
        },
      },
    );
    assert.match(html, /2026\.09\.01/);
    assert.match(html, /2026\.09\.11/);
    assert.match(html, /명시 날짜 충돌/);
    assert.match(html, /정상 완료/);
    if (excludedCount === 0 || excludedCount === 4) {
      assert.ok(html.includes(`<dd>${excludedCount}건</dd>`));
    } else {
      assert.match(html, /제외 정보가 제공되지 않았어요/);
    }
  }
});

test('검색 중에는 이전 실행의 해석·제외 정보를 노출하지 않는다', () => {
  const html = renderToStaticMarkup(
    createElement(SearchResultState, {
      state: 'loading',
      query: '교통',
      broadcastRange: { from: '', to: '' },
      filmingRange: { from: '', to: '' },
      details: { excludedCount: 4, resolverStatus: 'succeeded' },
      onReset() {},
      onRetry() {},
      onEditQuery() {},
    }),
  );
  assert.match(html, /aria-busy="true"/);
  assert.doesNotMatch(html, /제외된 결과|정상 완료/);
});
