import assert from 'node:assert/strict';
import test from 'node:test';

const { getPageItems, parsePageJump, selectPageSize } = await import('./list-pagination.ts');

test('7쪽 이하는 모든 번호를 그대로 보인다', () => {
  assert.deepEqual(getPageItems(1, 1), [1]);
  assert.deepEqual(getPageItems(3, 7), [1, 2, 3, 4, 5, 6, 7]);
});

test('8쪽 이상은 첫·끝 번호와 현재 앞뒤만 남기고 7칸을 유지한다', () => {
  assert.deepEqual(getPageItems(1, 12), [1, 2, 3, 4, 5, 'ellipsis', 12]);
  assert.deepEqual(getPageItems(4, 12), [1, 2, 3, 4, 5, 'ellipsis', 12]);
  assert.deepEqual(getPageItems(5, 12), [1, 'ellipsis', 4, 5, 6, 'ellipsis', 12]);
  assert.deepEqual(getPageItems(9, 12), [1, 'ellipsis', 8, 9, 10, 11, 12]);
  assert.deepEqual(getPageItems(12, 12), [1, 'ellipsis', 8, 9, 10, 11, 12]);
});

test('번호 이동은 범위 안의 양의 정수만 받는다', () => {
  assert.deepEqual(parsePageJump('5', 12), { page: 5 });
  assert.deepEqual(parsePageJump(' 12 ', 12), { page: 12 });
  for (const input of ['', 'abc', '1.5', '0', '13', '-1', '1e1']) {
    assert.deepEqual(parsePageJump(input, 12), {
      error: '1~12 사이의 페이지 번호를 입력해 주세요.',
    });
  }
});

test('목록 표시 개수는 허용 값만 고르고 나머지는 10개로 둔다', () => {
  assert.equal(selectPageSize('20'), 20);
  assert.equal(selectPageSize('50'), 50);
  for (const value of [null, '', '30', 'abc', '100']) {
    assert.equal(selectPageSize(value), 10);
  }
});
