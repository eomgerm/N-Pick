import assert from 'node:assert/strict';
import { existsSync, readFileSync } from 'node:fs';
import { registerHooks } from 'node:module';
import test from 'node:test';
import { createElement } from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import ts from 'typescript';

registerHooks({
  resolve(specifier, context, nextResolve) {
    if (specifier.endsWith('.module.css')) {
      return { url: 'data:text/javascript,export default {}', shortCircuit: true };
    }
    if (specifier.startsWith('@/')) {
      const base = new URL(`../../${specifier.slice(2)}`, import.meta.url);
      const url = ['.ts', '.tsx']
        .map((extension) => new URL(`${base.href}${extension}`))
        .find((candidate) => existsSync(candidate));
      if (url) return nextResolve(url.href, context);
    }
    return nextResolve(specifier, context);
  },
  load(url, context, nextLoad) {
    if (url.endsWith('.tsx')) {
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

const { InquiryResolutionForm } = await import('./review-inquiry-resolution.tsx');

function renderResolution(resolution) {
  const client = new QueryClient();
  const inquiry = {
    feedbackId: '41',
    sceneId: '31',
    resolution,
    resolutionNote: null,
    history: { reviewerLoginId: 'reviewer' },
  };

  return renderToStaticMarkup(
    createElement(
      QueryClientProvider,
      { client },
      createElement(InquiryResolutionForm, {
        inquiry,
        memberLoginId: 'reviewer',
      }),
    ),
  );
}

test('교정 판정 안에서 장면 제외 후보를 바로 작성할 수 있다', () => {
  const html = renderResolution('correction');

  assert.match(html, /처리 판정/);
  assert.match(html, /이 장면 검색에서 제외/);
  assert.match(html, />이 장면 제외</);
});

test('오류없음 판정에는 장면 제외 후보를 표시하지 않는다', () => {
  assert.doesNotMatch(renderResolution('no_action'), /이 장면 검색에서 제외/);
});
