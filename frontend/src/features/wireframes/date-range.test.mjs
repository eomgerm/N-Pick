import assert from 'node:assert/strict';
import test from 'node:test';

import {
  createRecentYearRange,
  emptyDateRange,
  readDateRange,
  selectRangeDate,
  validateDateRange,
} from './date-range.ts';

test('최근 연도 프리셋은 기준일과 윤년 경계를 보존한다', () => {
  assert.deepEqual(createRecentYearRange(1, '2026-09-21'), {
    from: '2025-09-21',
    to: '2026-09-21',
  });
  assert.deepEqual(createRecentYearRange(2, '2026-09-21'), {
    from: '2024-09-21',
    to: '2026-09-21',
  });
  assert.deepEqual(createRecentYearRange(3, '2024-02-29'), {
    from: '2021-02-28',
    to: '2024-02-29',
  });
});

test('시작일과 종료일이 같아도 유효하다', () => {
  const range = { from: '2026-09-07', to: '2026-09-07' };
  assert.equal(validateDateRange(range), '');
});

test('1950년 1월 1일부터 허용하고 이전 날짜는 URL에서도 거부한다', () => {
  assert.equal(validateDateRange({ from: '1950-01-01', to: '1950-01-01' }), '');
  const error = '1950년 1월 1일 이전 날짜는 선택할 수 없습니다.';
  for (const to of ['1949-12-31', '1950-01-01']) {
    assert.equal(validateDateRange({ from: '1949-12-31', to }), error);
    assert.deepEqual(readDateRange('1949-12-31', to), { range: emptyDateRange, error });
  }
});

test('오늘까지 허용하고 미래 날짜는 URL에서도 조건을 버린 이유와 함께 거부한다', () => {
  const today = '2026-09-22';
  assert.equal(validateDateRange({ from: today, to: today }, today), '');
  assert.equal(validateDateRange(emptyDateRange, today), '');
  for (const [from, to] of [
    ['2026-09-21', '2026-09-23'],
    ['2026-09-23', '2026-09-24'],
  ]) {
    const error = '시작일과 종료일은 오늘 이후 날짜로 선택할 수 없습니다.';
    assert.equal(validateDateRange({ from, to }, today), error);
    assert.deepEqual(readDateRange(from, to, today), { range: emptyDateRange, error });
  }
  assert.equal(
    validateDateRange({ from: today, to: '2026-09-21' }, today),
    '종료일은 시작일과 같거나 이후여야 해요.',
  );
  assert.equal(
    validateDateRange({ from: today, to: '' }, today),
    '시작일과 종료일을 모두 선택해 주세요.',
  );
  assert.equal(
    validateDateRange({ from: '2026-02-30', to: today }, today),
    '올바른 날짜를 입력해 주세요.',
  );
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
    assert.deepEqual(readDateRange(range.from, range.to).range, emptyDateRange);
  }
  assert.equal(validateDateRange({ from: '2024-02-29', to: '2024-03-01' }), '');
  assert.equal(validateDateRange(emptyDateRange), '');
});

test('URL이 실어 온 기간을 버렸으면 그 이유가 함께 나온다', () => {
  // 쓸 수 없는 기간을 접는 것은 기간 선택기를 계속 열어 두기 위해서지 조건이 없었다는 뜻이
  // 아니다. 이유를 따로 읽게 두면 방송일·촬영일을 건 링크가 필터 없는 검색으로 조용히
  // 돌아간다 — 계약 §5 는 한쪽만 온 기간을 SRCH_400_003 으로 막는다.
  for (const [from, to, expected] of [
    ['2026-09-01', undefined, '시작일과 종료일을 모두 선택해 주세요.'],
    [undefined, '2026-09-03', '시작일과 종료일을 모두 선택해 주세요.'],
    ['2026-09-08', '2026-09-07', '종료일은 시작일과 같거나 이후여야 해요.'],
    ['2026-13-01', '2026-13-05', '올바른 날짜를 입력해 주세요.'],
  ]) {
    assert.deepEqual(readDateRange(from, to), { range: emptyDateRange, error: expected });
  }
  assert.deepEqual(readDateRange('2026-09-01', '2026-09-03'), {
    range: { from: '2026-09-01', to: '2026-09-03' },
    error: '',
  });
  assert.deepEqual(readDateRange(undefined, undefined), { range: emptyDateRange, error: '' });
});
