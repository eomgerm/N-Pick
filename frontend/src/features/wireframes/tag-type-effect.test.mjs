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

const { reviewTagTypes } = await import('./review-inquiry-api.ts');
const {
  allWithoutSearchEffect,
  tagTypeEffect,
  tagTypeEffectGroups,
  tagTypeEffectHint,
  tagTypeLabels,
} = await import('./tag-type-effect.ts');

test('모든 태그 유형은 정확히 한 효과 그룹에 속하고 이름이 있다', () => {
  const grouped = tagTypeEffectGroups.flatMap((group) => group.types);
  assert.deepEqual([...grouped].sort(), [...reviewTagTypes].sort());
  assert.equal(new Set(grouped).size, grouped.length);
  for (const tagType of reviewTagTypes) assert.ok(tagTypeLabels[tagType]);
});

test('유형별 검색 효과는 검색 채널이 실제로 읽는 범위와 같다', () => {
  for (const tagType of ['event', 'person', 'organization', 'location', 'facility', 'scene_type']) {
    assert.equal(tagTypeEffect(tagType), 'search');
  }
  assert.equal(tagTypeEffect('broadcast_date'), 'date-filter');
  assert.equal(tagTypeEffect('filmed_date'), 'date-filter');
  assert.equal(tagTypeEffect('season'), 'rank-minor');
  assert.equal(tagTypeEffect('weather'), 'rank-minor');
  assert.equal(tagTypeEffect('keyword'), 'none');
  assert.equal(tagTypeEffectHint('keyword'), '이 유형은 현재 검색 결과에 영향을 주지 않습니다.');
});

test('태그가 있고 모두 영향 없는 유형일 때만 영향 없음으로 본다', () => {
  assert.equal(allWithoutSearchEffect([]), false);
  assert.equal(allWithoutSearchEffect(['keyword', 'keyword']), true);
  assert.equal(allWithoutSearchEffect(['keyword', 'season']), false);
  assert.equal(allWithoutSearchEffect(['location']), false);
});
