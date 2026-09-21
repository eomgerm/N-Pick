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

const localFiles = {
  '@/features/wireframes/demo-scenes': './demo-scenes.ts',
  '@/features/wireframes/scene-hover-preview': './scene-hover-preview.tsx',
  '@/features/wireframes/scene-preview-media': './scene-preview-media.ts',
  '@/features/wireframes/scene-thumbnail': './scene-thumbnail.tsx',
  '@/lib/api/client': '../../lib/api/client.ts',
  '@/lib/api/error': '../../lib/api/error.ts',
  '@/lib/api/log': '../../lib/api/log.ts',
  '@/lib/auth/session-events': '../../lib/auth/session-events.ts',
  '@/lib/env': '../../lib/env.ts',
};

registerHooks({
  resolve(specifier, context, nextResolve) {
    if (specifier === '@/features/wireframes/wireframe.module.css') {
      return { url: cssModuleUrl, shortCircuit: true };
    }
    if (localFiles[specifier]) {
      return { url: new URL(localFiles[specifier], import.meta.url).href, shortCircuit: true };
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

const { SearchResultCard } = await import('./search-result-card.tsx');
const { results } = await import('./demo-scenes.ts');

function renderCard(result, isSelected = false, position = result.rank) {
  return renderToStaticMarkup(
    createElement(SearchResultCard, {
      result,
      position,
      isSelected,
      onSelect() {},
    }),
  );
}

test('결과 카드는 키워드 행 끝의 검증 칩과 사용자용 근거 툴팁을 렌더한다', () => {
  const result = results[0];
  const html = renderCard(result);

  for (const value of [
    '1위',
    result.title,
    '00:42 – 00:49',
    ...result.matchedKeywords,
    '검증됨',
    '화면 속 글자',
    '서울역, 설 연휴 귀성객',
  ]) {
    assert.ok(html.includes(value));
  }
  for (const value of [
    '일치 근거',
    '필드',
    '값',
    '출처',
    'OCR',
    'Keyframe OCR',
    '서울역 · 설 연휴 귀성객',
    '촬영일',
    `일치도 ${result.score}%`,
  ]) {
    assert.ok(!html.includes(value));
  }
  assert.equal((html.match(/statusBadge/g) ?? []).length, 1);
  assert.match(html, /aria-describedby="match-evidence-1"/);
  assert.match(html, /id="match-evidence-1" role="tooltip"/);
  assert.match(
    html,
    /<div class="matchedKeywords">[\s\S]*키워드[\s\S]*서울역[\s\S]*귀성객[\s\S]*<span class="evidenceTooltip">/,
  );
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

test('촬영일 값이 없어도 카드에는 근거 검증 칩만 표시한다', () => {
  const html = renderCard({ ...results[0], broadcastDate: null, filmedDate: null }, true);
  assert.ok(!html.includes('촬영일'));
  assert.ok(!html.includes('미상'));
  assert.ok(html.includes('검증됨'));
  assert.match(html, /aria-expanded="true"/);
});

test('서버 응답 목록의 화면 순번을 원본 순위와 분리해 표시한다', () => {
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

test('결과 카드에는 이상해요 버튼을 표시하지 않고 Preview 진입점만 제공한다', () => {
  const html = renderCard(results[0]);

  assert.ok(!html.includes('이상해요'));
  assert.ok(!html.includes('cardInquiryButton'));
  assert.match(html, /aria-label="1위 설 연휴 첫날, 서울역 귀성 인파 Preview 열기"/);
});

test('장면 ID가 있으면 데모 배경 대신 실제 대표 이미지 endpoint를 배선한다', () => {
  const html = renderCard({ ...results[0], sceneId: '21' });

  assert.ok(!html.includes('imageOne'));
  assert.ok(!html.includes(results[0].imageLabel));
  assert.match(html, /data-thumbnail-state="loading"/);
});

test('장면 ID가 없는 데모 결과는 기존 배경 표시를 그대로 쓴다', () => {
  const html = renderCard(results[0]);

  assert.match(html, new RegExp(`aria-label="${results[0].imageLabel}"`));
  assert.ok(html.includes('imageOne'));
  assert.ok(!html.includes('data-thumbnail-state'));
});

test('밀리초에서 온 소수 구간 시각도 카드 타임코드는 초 단위로만 표시한다', () => {
  const html = renderCard({ ...results[0], sceneStart: 12.345, sceneEnd: 20.6 });

  assert.ok(html.includes('00:12 – 00:20'));
  assert.ok(!html.includes('12.345'));
  assert.ok(!html.includes('20.6'));
});
