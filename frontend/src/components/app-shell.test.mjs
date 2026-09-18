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
    if (specifier.endsWith('.module.css')) {
      return {
        url: 'data:text/javascript,export default new Proxy({}, {get: (_, key) => key});',
        shortCircuit: true,
      };
    }
    if (specifier === '@/components/mountain-backdrop') {
      return {
        url: 'data:text/javascript,export function MountainBackdrop() { return null; }',
        shortCircuit: true,
      };
    }
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

function renderShell(role, pathname, loginId = 'test-member', isInteractionLocked = false) {
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
          createElement(
            AppShell,
            { 'data-theme': 'shinhan', isInteractionLocked },
            createElement('main', null, '본문'),
          ),
        ),
      ),
    );
  } finally {
    client.clear();
  }
}

test('편집 기사 메뉴는 검색만 제공하며 계정과 역할을 실제 세션에서 표시한다', () => {
  const html = renderShell('EDITOR', '/search', '편집 기사-계정');
  assert.match(html, /href="\/search"/);
  assert.doesNotMatch(html, /href="\/review"/);
  assert.match(html, /편집 기사-계정/);
  assert.match(html, /편집 기사/);
  assert.match(html, /로그아웃/);
});

test('아카이브 팀이 검색 화면으로 이동해도 아카이브 팀 역할과 두 메뉴를 유지한다', () => {
  for (const pathname of ['/search', '/search/results', '/review']) {
    const html = renderShell('REVIEWER', pathname);
    assert.match(html, /href="\/search"/);
    assert.match(html, /href="\/review"/);
    assert.match(html, /아카이브 팀/);
    assert.doesNotMatch(html, /편집 기사/);
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

test('상호작용 잠금 중에는 공통 헤더 링크와 로그아웃도 비활성 상태를 노출한다', () => {
  const html = renderShell('REVIEWER', '/review', '아카이브 팀', true);
  assert.equal((html.match(/aria-disabled="true"/g) ?? []).length, 2);
  assert.match(html, /<button[^>]*disabled=""[^>]*>로그아웃<\/button>/);
});
