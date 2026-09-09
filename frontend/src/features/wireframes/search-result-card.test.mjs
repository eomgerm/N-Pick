import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { registerHooks } from 'node:module';
import test from 'node:test';
import { createElement } from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import ts from 'typescript';

const cssModuleUrl = `data:text/javascript,${encodeURIComponent(
  'export default new Proxy({}, { get: (_, key) => String(key) });',
)}`;

registerHooks({
  resolve(specifier, context, nextResolve) {
    if (specifier === '@/features/wireframes/wireframe.module.css') {
      return { url: cssModuleUrl, shortCircuit: true };
    }
    if (specifier === '@/features/wireframes/demo-scenes') {
      return { url: new URL('./demo-scenes.ts', import.meta.url).href, shortCircuit: true };
    }
    return nextResolve(specifier, context);
  },
  load(url, context, nextLoad) {
    if (url === new URL('./search-result-card.tsx', import.meta.url).href) {
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

const { SearchResultCard } = await import('./search-result-card.tsx');
const { results } = await import('./demo-scenes.ts');

function renderCard(result, isSelected = false, position = result.rank) {
  return renderToStaticMarkup(
    createElement(SearchResultCard, { result, position, isSelected, onSelect() {} }),
  );
}

test('결과 카드는 순위와 필수 장면 메타데이터를 모두 렌더한다', () => {
  const result = results[0];
  const html = renderCard(result);

  for (const value of [
    '1위',
    result.displayName,
    result.title,
    result.broadcastDate,
    result.filmedDate,
    result.shotType,
    result.sceneType,
    '00:42 – 00:49',
  ]) {
    assert.ok(html.includes(value));
  }
  assert.match(html, /<button [^>]*type="button"/);
  assert.match(html, /aria-haspopup="dialog"/);
  assert.match(html, /aria-expanded="false"/);
  assert.match(html, new RegExp(`aria-label="${result.imageLabel}"`));
});

test('날짜가 없으면 각 날짜를 미상으로 표시하고 선택 상태를 노출한다', () => {
  const html = renderCard({ ...results[0], broadcastDate: null, filmedDate: null }, true);
  assert.equal((html.match(/미상/g) ?? []).length, 2);
  assert.match(html, /aria-expanded="true"/);
});

test('정렬된 목록의 화면 순번을 원본 정확도 순위와 분리해 표시한다', () => {
  const html = renderCard(results[3], false, 1);
  assert.match(html, /검색 결과 1번째/);
  assert.match(html, /aria-label="1위 KBC 뉴스9 · 귀성길 현장/);
});

test('긴 표시명과 유형 문자열을 DOM에서 생략하지 않는다', () => {
  const longValue = '긴한국어표시값'.repeat(20);
  const html = renderCard({
    ...results[0],
    displayName: longValue,
    shotType: longValue,
    sceneType: longValue,
  });
  assert.ok((html.match(new RegExp(longValue, 'g')) ?? []).length >= 3);
});
