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
const linkUrl = `data:text/javascript,${encodeURIComponent(
  'export default function Link({ children }) { return children; }',
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
    if (specifier === 'next/link') {
      return { url: linkUrl, shortCircuit: true };
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

const { SearchEntryShell } = await import('./search-entry-shell.tsx');

test('검색 진입 form은 검색어와 두 날짜 명시 필터를 제공한다', () => {
  const html = renderToStaticMarkup(createElement(SearchEntryShell, { theme: 'shinhan' }));

  assert.match(html, /aria-label="뉴스 장면 검색"/);
  assert.match(html, /<label[^>]*for="scene-search">뉴스 장면 검색어<\/label>/);
  assert.match(html, /aria-label="방송일 기간 선택: 전체 기간"/);
  assert.match(html, /aria-label="촬영일 기간 선택: 전체 기간"/);
  assert.match(html, /날짜 필터/);
  assert.match(html, /aria-live="polite"/);
  assert.match(html, /<button[^>]*disabled=""[^>]*type="submit"/);
});
