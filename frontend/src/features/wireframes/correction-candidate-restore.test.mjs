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

const { appendAddedTag, restoreTagCandidates } = await import('./correction-candidate-restore.ts');

function tag(evidenceId, taggingId, action, extra = {}) {
  return {
    evidenceId,
    taggingId,
    action,
    scope: 'SCENE',
    tagType: 'location',
    matchValue: '서울',
    displayName: '서울',
    ...extra,
  };
}

test('APPROVE 는 추가 칩, 현재 태그의 REJECT 는 삭제 후보로 되돌린다', () => {
  const restored = restoreTagCandidates(
    [
      tag('61', '501', 'APPROVE', { scope: 'CLIP', tagType: 'person', displayName: '홍길동' }),
      tag('62', '51', 'REJECT'),
      tag('63', '51', 'REJECT'),
    ],
    [{ taggingId: '51' }],
  );
  assert.deepEqual(restored.added, [
    { id: 'candidate-61', scope: 'CLIP', tagType: 'person', value: '홍길동', evidenceIds: ['61'] },
  ]);
  assert.deepEqual([...restored.removed], [['51', ['62', '63']]]);
  assert.deepEqual(restored.other, []);
});

test('WITHDRAW 와 현재 태그에 없는 REJECT 는 기타 후보로 남긴다', () => {
  const restored = restoreTagCandidates(
    [tag('71', '51', 'WITHDRAW'), tag('72', '999', 'REJECT', { displayName: '부산' })],
    [{ taggingId: '51' }],
  );
  assert.deepEqual(restored.added, []);
  assert.equal(restored.removed.size, 0);
  assert.deepEqual(
    restored.other.map(({ id, action, value, evidenceIds }) => ({
      id,
      action,
      value,
      evidenceIds,
    })),
    [
      { id: 'candidate-71', action: 'WITHDRAW', value: '서울', evidenceIds: ['71'] },
      { id: 'candidate-72', action: 'REJECT', value: '부산', evidenceIds: ['72'] },
    ],
  );
});

test('이미 보이는 근거로만 된 추가는 다시 붙이지 않는다', () => {
  const current = [
    { id: 'candidate-61', scope: 'SCENE', tagType: 'location', value: '서울', evidenceIds: ['61'] },
  ];
  const duplicate = { ...current[0], id: 'draft-1' };
  assert.deepEqual(appendAddedTag(current, duplicate), current);
  const fresh = { ...current[0], id: 'draft-2', value: '부산', evidenceIds: ['62'] };
  assert.deepEqual(appendAddedTag(current, fresh), [...current, fresh]);
  const repeated = { ...fresh, evidenceIds: ['62', '62'] };
  assert.deepEqual(appendAddedTag(current, repeated), [...current, fresh]);
});
