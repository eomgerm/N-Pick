import assert from 'node:assert/strict';
import test from 'node:test';
import {
  validateSearchQuery,
  validateInquiryComment,
  rejectOversizedPaste,
} from './input-validation.ts';

test('검색어는 trim 후 2~500자이며 UTF-16 길이 기준을 유지한다', () => {
  for (const query of ['', '  ', ' 비 ', '가'.repeat(501)])
    assert.notEqual(validateSearchQuery(query), '');
  for (const query of ['지진', '  화재  ', '가'.repeat(500), '😀'.repeat(250)])
    assert.equal(validateSearchQuery(query), '');
  assert.notEqual(validateSearchQuery('😀'.repeat(251)), '');
});

test('선택 문의 내용은 2000자까지 허용하며 초과값을 자르지 않는다', () => {
  assert.equal(validateInquiryComment(''), '');
  assert.equal(validateInquiryComment('가'.repeat(2000)), '');
  assert.notEqual(validateInquiryComment('가'.repeat(2001)), '');
});

test('붙여넣기는 선택 범위 교체를 계산하고 초과한 전체 붙여넣기를 거부한다', () => {
  for (const [start, end, text, rejected] of [
    [0, 0, '가', true],
    [0, 2, '새값', false],
    [0, 2, '새로운값', true],
  ]) {
    let prevented = false;
    let announced = false;
    rejectOversizedPaste(
      {
        currentTarget: { value: '가'.repeat(500), selectionStart: start, selectionEnd: end },
        clipboardData: { getData: () => text },
        preventDefault: () => {
          prevented = true;
        },
      },
      500,
      () => {
        announced = true;
      },
    );
    assert.equal(prevented, rejected);
    assert.equal(announced, rejected);
  }
});
