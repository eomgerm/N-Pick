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

function renderCard(
  result,
  isSelected = false,
  position = result.rank,
  inquiryState = 'ready',
  inquiryUnavailableReason,
) {
  return renderToStaticMarkup(
    createElement(SearchResultCard, {
      result,
      position,
      isSelected,
      inquiryState,
      inquiryUnavailableReason,
      onInquiry() {},
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

test('결과 카드는 Preview와 분리된 단일 이상해요 버튼을 제공한다', () => {
  const html = renderCard(results[0]);

  assert.equal((html.match(/이상해요/g) ?? []).length, 2);
  assert.equal((html.match(/cardInquiryButton/g) ?? []).length, 1);
  assert.match(html, /data-state="ready"/);
  assert.match(html, /aria-label="1위 설 연휴 첫날, 서울역 귀성 인파 이상해요"/);
  assert.match(html, /aria-label="1위 설 연휴 첫날, 서울역 귀성 인파 Preview 열기"/);
});

test('문의 접수·진행·불가 상태는 문구와 비활성 이유로 구분한다', () => {
  const submittingHtml = renderCard(results[0], false, 1, 'submitting');
  const submittedHtml = renderCard(results[0], false, 1, 'submitted');
  const unavailableHtml = renderCard(
    results[0],
    false,
    1,
    'unavailable',
    '검색 기록을 저장하지 못해 이 결과에서는 문의할 수 없습니다.',
  );

  assert.match(submittingHtml, /data-state="submitting"/);
  assert.match(submittingHtml, /aria-busy="true"/);
  assert.ok(submittingHtml.includes('접수 중'));
  assert.match(submittedHtml, /data-state="submitted"/);
  assert.ok(submittedHtml.includes('접수됨'));
  assert.match(unavailableHtml, /data-state="unavailable"/);
  assert.match(unavailableHtml, /aria-describedby="inquiry-unavailable-1"/);
  assert.match(unavailableHtml, /id="inquiry-unavailable-1"/);
  assert.ok(unavailableHtml.includes('검색 기록을 저장하지 못해'));
  assert.match(unavailableHtml, /disabled=""/);
});
