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

test('결과 카드는 핵심 장면 정보와 일치 근거·검증 상태를 함께 렌더한다', () => {
  const result = results[0];
  const html = renderCard(result);

  for (const value of [
    '1위',
    result.title,
    '00:42 – 00:49',
    `일치도 ${result.score}%`,
    ...result.matchedKeywords,
    '일치 근거',
    result.matchEvidence.field,
    result.matchEvidence.value,
    result.matchEvidence.source,
    '검증됨',
    '촬영일 검증됨',
  ]) {
    assert.ok(html.includes(value));
  }
  assert.equal((html.match(/00:42 – 00:49/g) ?? []).length, 1);
  assert.ok(!html.includes('장면 구간'));
  for (const value of [
    result.displayName,
    result.broadcastDate,
    result.filmedDate,
    result.shotType,
    result.sceneType,
  ]) {
    assert.ok(!html.includes(value));
  }
  assert.match(html, /<button [^>]*type="button"/);
  assert.match(html, /aria-haspopup="dialog"/);
  assert.match(html, /aria-expanded="false"/);
  assert.match(html, new RegExp(`aria-label="${result.imageLabel}"`));
});

test('촬영일 값이 없으면 카드에서 미상 상태를 텍스트로 구분한다', () => {
  const html = renderCard({ ...results[0], broadcastDate: null, filmedDate: null }, true);
  assert.ok(html.includes('촬영일 미상'));
  assert.match(html, /aria-expanded="true"/);
});

test('정렬된 목록의 화면 순번을 원본 정확도 순위와 분리해 표시한다', () => {
  const html = renderCard(results[3], false, 1);
  assert.match(html, /검색 결과 1번째/);
  assert.match(html, /aria-label="1위 톨게이트로 이어지는 귀성 차량 행렬/);
});

test('긴 백엔드 메타데이터도 카드 DOM에 노출하지 않는다', () => {
  const longValue = '긴한국어표시값'.repeat(20);
  const html = renderCard({
    ...results[0],
    displayName: longValue,
    shotType: longValue,
    sceneType: longValue,
  });
  assert.ok(!html.includes(longValue));
});
