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

function renderShell({ preview, state, ...params } = {}) {
  return renderToStaticMarkup(
    createElement(WireframeShell, {
      initialQuery: '명절 교통',
      initialParams: { ...params, ...(state ? { state } : {}), ...(preview ? { preview } : {}) },
      theme: 'shinhan',
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
  assert.ok(html.includes('정상 검색'));
  assert.ok(html.includes('검수 규칙 적용'));
  assert.doesNotMatch(html, /rule[_ -]?id|condition|JSON|오류 코드/i);
});

test('snapshot 실패 Preview는 통합 경로에서도 문의를 비활성화한다', () => {
  const html = renderShell({ preview: 'loading', state: 'degraded-snapshot' });

  assert.match(html, /data-state="unavailable"/);
  assert.ok(html.includes('문의 불가'));
  assert.match(html, /<dialog aria-describedby="[^"]+"/);
});

test('빈 결과 demo는 degraded 상태와 섞어 표시하지 않는다', () => {
  const html = renderShell({ state: 'empty' });

  assert.ok(html.includes('관련 장면 0개'));
  assert.ok(!html.includes('검색 기록 저장 실패'));
  assert.ok(!html.includes('일부 기능 누락'));
});
