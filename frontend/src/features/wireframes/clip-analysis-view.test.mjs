import assert from 'node:assert/strict';
import { registerHooks } from 'node:module';
import test from 'node:test';

registerHooks({
  resolve(specifier, context, nextResolve) {
    return nextResolve(
      specifier.startsWith('@/')
        ? new URL(`../../${specifier.slice(2)}.ts`, import.meta.url).href
        : specifier,
      context,
    );
  },
});

const {
  formatSceneInterval,
  tagScopeLabel,
  tagTypeLabel,
  tagVerificationLabel,
  transcriptSourceLabel,
} = await import('./clip-analysis-view.ts');

test('장면 구간은 시·분·초 경계를 같은 형식으로 표시한다', () => {
  assert.equal(formatSceneInterval(0, 4_200), '00:00–00:04');
  assert.equal(formatSceneInterval(3_725_000, 3_730_000), '1:02:05–1:02:10');
});

test('대사 출처와 태그 판정은 사용자가 이해할 수 있는 말로 표시한다', () => {
  assert.equal(transcriptSourceLabel('provided'), '제공 자막');
  assert.equal(transcriptSourceLabel('asr'), '음성 인식');
  assert.equal(transcriptSourceLabel(null), '출처 미확인');
  assert.equal(tagTypeLabel('person'), '인물');
  assert.equal(tagTypeLabel('scene_type'), '장면 유형');
  assert.equal(tagScopeLabel('clip'), '영상 전체');
  assert.equal(tagScopeLabel('scene'), '이 장면');
  assert.equal(tagVerificationLabel('reviewer_verified'), '검수 확인');
  assert.equal(tagVerificationLabel('verified'), '근거 확인');
  assert.equal(tagVerificationLabel('unverified'), '자동 분석');
});
