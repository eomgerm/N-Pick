import assert from 'node:assert/strict';
import { existsSync, readFileSync } from 'node:fs';
import { registerHooks } from 'node:module';
import { fileURLToPath } from 'node:url';
import test from 'node:test';
import { createElement } from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import ts from 'typescript';

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

const { SuccessToast } = await import('./success-toast.tsx');

const render = (props) => renderToStaticMarkup(createElement(SuccessToast, props));

test('성공 문구가 있으면 토스트 카드와 라이브 영역을 함께 렌더한다', () => {
  const html = render({ message: '판정을 저장했습니다.' });
  assert.match(html, /role="status"/);
  assert.match(html, /aria-live="polite"/);
  assert.match(html, /판정을 저장했습니다\./);
  assert.match(html, /알림 닫기/);
});

test('빈 문구여도 라이브 영역 요소는 유지하고 카드만 감춘다', () => {
  const html = render({ message: '' });
  // 스크린리더가 이후 안내를 announce 하려면 role="status" 요소가 미리 존재해야 한다.
  assert.match(html, /role="status"/);
  assert.match(html, /aria-live="polite"/);
  // 표시할 안내가 없으므로 닫기 버튼(카드)은 나오지 않는다.
  assert.doesNotMatch(html, /알림 닫기/);
});
