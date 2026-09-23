import assert from 'node:assert/strict';
import { existsSync, readFileSync } from 'node:fs';
import { registerHooks } from 'node:module';
import { fileURLToPath } from 'node:url';
import test from 'node:test';
import { createElement } from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
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
    createElement(
      QueryClientProvider,
      { client: new QueryClient() },
      createElement(WireframeShell, {
        initialQuery: '명절 교통',
        initialParams: { ...params, ...(state ? { state } : {}), ...(preview ? { preview } : {}) },
        theme: 'shinhan',
        ...props,
      }),
    ),
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
  }
});

test('검수 규칙 demo는 정상 결과와 적용 사실만 함께 표시한다', () => {
  const html = renderShell({ state: 'review-rule' });

  assert.equal((html.match(/class="resultCard/g) ?? []).length, 10);
  // 문의(신고)는 카드 썸네일마다 버튼으로 붙는다(Preview 를 열지 않아도 바로 문의).
  assert.equal((html.match(/class="cardInquiryButton"/g) ?? []).length, 10);
  assert.ok(html.includes('정상 검색'));
  assert.ok(html.includes('검수 규칙 적용'));
  assert.doesNotMatch(html, /rule[_ -]?id|condition|JSON|오류 코드/i);
});

test('Preview 를 열면 근거와 함께 문의 진입점도 유지된다', () => {
  // 문의는 카드 썸네일에서 바로 열 수 있지만, Preview 로 근거를 확인한 뒤에도 문의할 수
  // 있어야 한다 (S15P21A501-294). onInquiry 가 붙으면 근거 라벨도 '검색 근거'가 된다.
  const html = renderShell({ preview: 'loading' });

  // Preview 안에 문의 버튼이 붙는다(활성/불가 여부는 결과의 저장 상태에 달렸고 여기선 진입점
  // 존재만 본다 — 활성/불가 텍스트 분기는 scene-dialogs.test.mjs 가 덮는다).
  assert.ok(html.includes('class="previewReportButton"'));
  // onInquiry 가 붙으면 근거 섹션 라벨이 '검색 근거'가 된다(없으면 '확인 근거').
  assert.ok(html.includes('검색 근거'));
});

test('snapshot 실패 결과 카드의 문의 버튼은 사유와 함께 비활성이다', () => {
  const html = renderShell({ state: 'degraded-snapshot' });

  assert.ok(html.includes('class="cardInquiryButton"'));
  assert.ok(html.includes('aria-disabled="true"'));
  assert.ok(html.includes('aria-describedby="inquiry-reason-'));
  assert.ok(html.includes('data-state="unavailable"'));
  assert.ok(html.includes('검색 기록을 저장하지 못해 이 결과에서는 문의할 수 없습니다.'));
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

  assert.match(
    html,
    /기간 설정: 방송일 2026\.09\.01 – 2026\.09\.03 · 촬영일 2026\.08\.28 – 2026\.08\.29/,
  );
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

test('기간을 버린 실패는 연결 문제로 안내하지 않고 기간 초기화로 유도한다', () => {
  // S15P21A501-227. 한쪽만 온 기간을 조용히 버리고 필터 없이 검색하던 자리다. 검색을 세우는
  // 것만으로는 부족하고, 왜 세웠는지가 화면 세 곳(배지·패널·live region)에 같은 말로 남아야
  // 한다 — 서버에 가 보지도 않았으므로 연결 문제로 적으면 거짓이다.
  const html = renderShell(
    { broadcastFrom: '2026-09-01' },
    {
      api: {
        state: 'failed',
        error: null,
        failureReason: '시작일과 종료일을 모두 선택해 주세요.',
        retry() {},
      },
    },
  );
  assert.match(html, /시작일과 종료일을 모두 선택해 주세요\./);
  assert.doesNotMatch(html, /일시적인 연결 문제|검색 연결 실패|검색에 실패했습니다\./);
  assert.match(html, /기간 초기화하고 다시 검색/);
  assert.doesNotMatch(html, /같은 조건으로 다시 시도/);
});

test('사유 없는 검색 실패는 기존 연결 안내와 재시도를 유지한다', () => {
  const html = renderShell({}, { api: { state: 'failed', error: null, retry() {} } });
  assert.match(html, /일시적인 연결 문제/);
  assert.match(html, /같은 조건으로 다시 시도/);
});

test('조건을 모르는 실패(검색 기록 스냅샷 조회 실패)는 검색어·기간이 유지된다고 말하지 않는다', () => {
  const html = renderShell(
    {},
    { api: { state: 'failed', error: null, conditionsUnknown: true, retry() {} } },
  );
  assert.match(html, /이 화면에서는 검색어와 기간을 확인할 수 없어요\./);
  assert.doesNotMatch(html, /입력한 검색어와 기간은 유지돼요\./);
});

test('historyBadge가 있으면 결과 그리드 위에 한 줄 배지로 뜬다', () => {
  const html = renderShell({ state: 'empty' }, { historyBadge: '2026-09-15 검색 기록' });
  assert.match(html, /2026-09-15 검색 기록/);
});

test('검색 기록 모드는 배지와 재검색 고지를 보여주고 제외 감사정보는 싣지 않는다', () => {
  // 다시 검색하면 당시가 아닌 새 결과가 나온다는 것을 고지한다. 제외 수 같은 감사 정보는
  // 일반 사용자에게 혼란이라 결과 화면에 싣지 않는다 (S15P21A501-262).
  const html = renderShell(
    {},
    {
      historyBadge: '2026-09-15 검색 기록',
      showHistoryNotice: true,
      api: {
        state: 'ready',
        presentation: {
          results: [],
          execution: { status: 'succeeded', degradedReasons: [], hasAppliedReviewRule: false },
          details: {
            resolverStatus: 'succeeded',
            excludedCount: 2,
            exclusionReasons: ['승인된 장면 제외 규칙에 해당'],
          },
        },
        error: null,
        retry() {},
      },
    },
  );
  // 기록 컨텍스트 블록만 검사한다. 결과 0개 상태 패널의 「제외된 결과」는 라이브 검색에도 있는
  // 기존 동작이라 이 티켓 범위가 아니다.
  const historyContext = html.match(/<div class="historyContext"[\s\S]*?<\/div>/)?.[0] ?? '';
  assert.match(historyContext, /2026-09-15 검색 기록/);
  assert.match(historyContext, /다시 검색하면 지금 기준으로 새로 찾은 결과/);
  assert.doesNotMatch(historyContext, /제외된 결과/);
});

test('스냅샷 없는 기록은 배지만 뜨고 「저장된 당시 결과예요」 고지는 빠진다', () => {
  // unavailable 기록도 날짜(배지)는 사실이라 뜨지만, 당시 결과가 없어 재검색 고지를 내면
  // 바로 아래 실패 안내(「당시 결과 기록이 없어…」)와 모순된다 (S15P21A501-262).
  const html = renderShell(
    {},
    {
      historyBadge: '2026-08-01 검색 기록',
      showHistoryNotice: false,
      api: {
        state: 'failed',
        error: null,
        failureReason: '이 검색의 당시 결과 기록이 없어 결과를 표시할 수 없어요.',
        retry() {},
      },
    },
  );
  const historyContext = html.match(/<div class="historyContext"[\s\S]*?<\/div>/)?.[0] ?? '';
  assert.match(historyContext, /2026-08-01 검색 기록/);
  assert.doesNotMatch(historyContext, /저장된 당시 결과예요/);
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
    }),
  );
  assert.match(html, /aria-busy="true"/);
  assert.doesNotMatch(html, /제외된 결과|정상 완료/);
});
