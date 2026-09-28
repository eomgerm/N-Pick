import assert from 'node:assert/strict';
import { registerHooks } from 'node:module';
import test from 'node:test';

registerHooks({
  resolve(specifier, context, nextResolve) {
    return nextResolve(
      specifier.startsWith('@/')
        ? new URL(`../../${specifier.slice('@/'.length)}.ts`, import.meta.url).href
        : specifier,
      context,
    );
  },
});

const { resolveInquiryResultTitle } = await import('./my-inquiry-view.ts');

const snapshot = (displayName) => ({
  searchResultId: '9802',
  sceneId: '9302',
  rank: 2,
  explain: { display: { display_name: displayName }, score: 2 },
});

test('available 스냅샷은 당시 기록된 표시명을 그대로 쓴다', () => {
  assert.equal(resolveInquiryResultTitle(snapshot('KBC 뉴스9'), '지금 붙은 제목'), 'KBC 뉴스9');
});

test('available인데 당시 제목이 없었으면 현재 제목으로 재구성하지 않고 대체 문구를 쓴다', () => {
  // 재생성 금지(FRD §7.2, 리뷰 R3): null·빈 문자열은 "당시 제목 없음"이므로 현재 clipTitle을 붙이면 안 된다.
  for (const displayName of [null, '']) {
    assert.equal(
      resolveInquiryResultTitle(snapshot(displayName), '지금 붙은 제목'),
      '제목 없는 영상',
    );
  }
});

test('unavailable(스냅샷 없음)은 현재 장면 제목으로 대체한다', () => {
  assert.equal(resolveInquiryResultTitle(null, '현재 장면 제목'), '현재 장면 제목');
  assert.equal(resolveInquiryResultTitle(null, null), '제목 없는 영상');
});
