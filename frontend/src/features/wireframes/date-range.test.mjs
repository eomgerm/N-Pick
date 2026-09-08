import assert from 'node:assert/strict';
import test from 'node:test';

import {
  emptyDateRange,
  matchesDateRange,
  readDateRange,
  selectRangeDate,
  validateDateRange,
} from './date-range.ts';

test('기간은 양 끝을 포함하며 시작일과 종료일이 같아도 유효하다', () => {
  const range = { from: '2026-09-07', to: '2026-09-07' };
  assert.equal(validateDateRange(range), '');
  assert.equal(matchesDateRange('2026.09.07', range), true);
  assert.equal(matchesDateRange('2026.09.06', range), false);
  assert.equal(matchesDateRange('2026.09.08', range), false);
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

test('정보 없음과 미검증 날짜는 필터 범위 밖이어도 제외하지 않는다', () => {
  const range = { from: '2026-01-01', to: '2026-12-31' };
  assert.equal(matchesDateRange('미상', range), true);
  assert.equal(matchesDateRange('2025.01.01', range, false), true);
  assert.equal(matchesDateRange('2025.01.01', range, true), false);
});
