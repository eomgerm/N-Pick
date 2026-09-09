import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { registerHooks } from 'node:module';
import test from 'node:test';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { createElement } from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import ts from 'typescript';

registerHooks({
  resolve(specifier, context, nextResolve) {
    if (specifier === 'next/navigation') {
      return {
        url: 'data:text/javascript,export function usePathname() { return globalThis.testPathname; }',
        shortCircuit: true,
      };
    }
    if (specifier === 'next/link') specifier = 'next/link.js';
    if (specifier.startsWith('@/')) {
      const extension = specifier.startsWith('@/components/') ? 'tsx' : 'ts';
      specifier = new URL(`../${specifier.slice(2)}.${extension}`, import.meta.url).href;
    }
    return nextResolve(specifier, context, nextResolve);
  },
  load(url, context, nextLoad) {
    if (url.startsWith(new URL('./', import.meta.url).href) && url.endsWith('.tsx')) {
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

const { AppShell } = await import('./app-shell.tsx');
const { SessionBoundary } = await import('./session-boundary.tsx');

function renderShell(role, pathname, loginId = 'test-member') {
  globalThis.testPathname = pathname;
  const client = new QueryClient();
  try {
    return renderToStaticMarkup(
      createElement(
        QueryClientProvider,
        { client },
        createElement(
          SessionBoundary,
          { member: { memberId: '1', role, loginId } },
          createElement(AppShell, { 'data-theme': 'shinhan' }, createElement('main', null, '본문')),
        ),
      ),
    );
  } finally {
    client.clear();
  }
}

test('편집기자 메뉴는 검색만 제공하며 계정과 역할을 실제 세션에서 표시한다', () => {
  const html = renderShell('EDITOR', '/search', '편집자-계정');
  assert.match(html, /href="\/search"/);
  assert.doesNotMatch(html, /href="\/review"/);
  assert.match(html, /편집자-계정/);
  assert.match(html, /편집기자/);
  assert.match(html, /로그아웃/);
});

test('검수자가 검색 화면으로 이동해도 검수자 역할과 두 메뉴를 유지한다', () => {
  for (const pathname of ['/search', '/search/results', '/review']) {
    const html = renderShell('REVIEWER', pathname);
    assert.match(html, /href="\/search"/);
    assert.match(html, /href="\/review"/);
    assert.match(html, /검수자/);
    assert.doesNotMatch(html, /편집기자/);
    const currentLink = html.match(/<a\b[^>]*aria-current="page"[^>]*>/g);
    assert.equal(currentLink.length, 1);
    assert.ok(currentLink[0].includes(`href="${pathname === '/review' ? '/review' : '/search'}"`));
  }
});

test('공통 헤더와 페이지 본문은 한 번씩 렌더링하며 긴 계정명 전체를 보존한다', () => {
  const loginId = '긴계정명'.repeat(30);
  const html = renderShell('EDITOR', '/search', loginId);
  assert.equal((html.match(/<header\b/g) ?? []).length, 1);
  assert.equal((html.match(/<main\b/g) ?? []).length, 1);
  assert.match(html, /aria-label="주요 메뉴"/);
  assert.match(html, /data-theme="shinhan"/);
  assert.ok(html.includes(loginId));
  assert.match(html, /본문/);
});
