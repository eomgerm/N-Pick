import assert from 'node:assert/strict';
import test from 'node:test';

import {
  emptyDateRange,
  readDateRange,
  readDateRangeError,
  selectRangeDate,
  validateDateRange,
} from './date-range.ts';

test('시작일과 종료일이 같아도 유효하다', () => {
  const range = { from: '2026-09-07', to: '2026-09-07' };
  assert.equal(validateDateRange(range), '');
});

test('역순 선택은 정렬하고 완성된 기간의 다음 클릭은 새 기간을 시작한다', () => {
  const first = selectRangeDate(emptyDateRange, '2026-09-07');
  const range = selectRangeDate(first, '2026-08-31');
  assert.deepEqual(range, { from: '2026-08-31', to: '2026-09-07' });
  assert.deepEqual(selectRangeDate(range, '2026-10-01'), { from: '2026-10-01', to: '' });
});

test('잘못된 날짜·역순 입력·한쪽 누락은 거부하고 URL 입력도 검증한다', () => {
  for (const range of [
    { from: '2025-02-29', to: '2025-03-01' },
    { from: '2026-04-31', to: '2026-05-01' },
    { from: '2026-09-08', to: '2026-09-07' },
    { from: '2026-09-07', to: '' },
  ]) {
    assert.notEqual(validateDateRange(range), '');
    assert.deepEqual(readDateRange(range.from, range.to), emptyDateRange);
  }
  assert.equal(validateDateRange({ from: '2024-02-29', to: '2024-03-01' }), '');
  assert.equal(validateDateRange(emptyDateRange), '');
});

test('URL이 실어 온 기간을 버렸으면 그 이유가 남는다', () => {
  // readDateRange 가 불완전한 기간을 emptyDateRange 로 접는 것은 화면을 계속 쓰게 하려는 것이지
  // 조건이 없었다는 뜻이 아니다. 이유를 함께 읽지 않으면 방송일·촬영일을 건 링크가 필터 없는
  // 검색으로 조용히 돌아간다 — 계약 §5 는 한쪽만 온 기간을 SRCH_400_003 으로 막는다.
  for (const [from, to] of [
    ['2026-09-01', undefined],
    [undefined, '2026-09-03'],
  ]) {
    assert.deepEqual(readDateRange(from, to), emptyDateRange);
    assert.equal(readDateRangeError(from, to), '시작일과 종료일을 모두 선택해 주세요.');
  }
  assert.equal(
    readDateRangeError('2026-09-08', '2026-09-07'),
    '종료일은 시작일과 같거나 이후여야 해요.',
  );
  assert.equal(readDateRangeError('2026-09-01', '2026-09-03'), '');
  assert.equal(readDateRangeError(undefined, undefined), '');
});
