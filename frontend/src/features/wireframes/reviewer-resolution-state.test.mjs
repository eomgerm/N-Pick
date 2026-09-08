import assert from 'node:assert/strict';
import test from 'node:test';

import { inquiries } from './reviewer-inquiries.ts';
import {
  displayEndDate,
  getResolutionSummary,
  hasValidResolutionDates,
  normalizeResolution,
  parseResolution,
  updateResolutionDate,
  updateResolutionTerms,
} from './reviewer-resolution-state.ts';

test('검색 요약은 개발용 필드 대신 항목과 종료일을 포함한 기간을 보여준다', () => {
  const summary = getResolutionSummary(inquiries[0].initialResolution);
  assert.equal(summary[0].value, '방송일 2025.09.01 ~ 2025.10.10');
  assert.equal(summary.find((item) => item.label === '장소').value, '경부고속도로');
  assert.doesNotMatch(
    JSON.stringify(summary),
    /schema_version|query_span|confidence|explicit_query/,
  );
  assert.equal(displayEndDate('2024-03-01'), '2024-02-29');
});

test('읽기 쉬운 입력을 수정해도 전체 검색 해석과 기존 근거는 보존한다', () => {
  const original = inquiries[0].initialResolution;
  const before = parseResolution(original);
  const edited = parseResolution(
    normalizeResolution(updateResolutionTerms(original, 'locations', '경부고속도로, 부산 ')),
  );
  assert.deepEqual(edited.locations[0], before.locations[0]);
  assert.equal(edited.locations[1].value, '부산');
  assert.equal(edited.locations[1].query_span, null);
  assert.equal(edited.locations[1].origin, 'inferred');
  for (const key of Object.keys(before).filter((key) => key !== 'locations')) {
    assert.deepEqual(edited[key], before[key]);
  }
  assert.equal(inquiries[0].initialResolution, original);
});

test('입력 중 공백과 쉼표를 보존하고 저장할 때 빈 항목만 정리한다', () => {
  const value = updateResolutionTerms(
    inquiries[0].initialResolution,
    'expanded_terms',
    ' 귀성 차량, , 정체,',
  );
  assert.equal(parseResolution(value).expanded_terms.join(','), ' 귀성 차량, , 정체,');
  assert.deepEqual(parseResolution(normalizeResolution(value)).expanded_terms, [
    '귀성 차량',
    '정체',
  ]);
});

test('인물과 기관은 구분해서 수정하며 기존 기관 정보를 유지한다', () => {
  const original = inquiries[0].initialResolution;
  const added = updateResolutionTerms(original, 'entities', '홍길동', 'person');
  const entities = parseResolution(added).entities;
  assert.deepEqual(entities[0], parseResolution(original).entities[0]);
  assert.equal(entities[1].type, 'person');
  assert.equal(entities[1].value, '홍길동');
  assert.deepEqual(
    parseResolution(updateResolutionTerms(added, 'entities', '', 'person')).entities,
    [entities[0]],
  );
});

test('문의자가 지정한 날짜는 그대로 유지하며 새 기간은 양 끝을 검증한다', () => {
  const original = inquiries[0].initialResolution;
  assert.equal(updateResolutionDate(original, 'broadcast_date', 'start', '2024-01-01'), original);
  const start = updateResolutionDate(original, 'filming_date', 'start', '2024-02-29');
  assert.equal(hasValidResolutionDates(start), false);
  const valid = updateResolutionDate(start, 'filming_date', 'end_exclusive', '2024-02-29');
  assert.equal(hasValidResolutionDates(valid), true);
  assert.equal(parseResolution(valid).date_windows[1].end_exclusive, '2024-03-01');
  assert.equal(
    hasValidResolutionDates(
      updateResolutionDate(valid, 'filming_date', 'end_exclusive', '2024-02-28'),
    ),
    false,
  );
  const cleared = updateResolutionDate(
    updateResolutionDate(valid, 'filming_date', 'start', ''),
    'filming_date',
    'end_exclusive',
    '',
  );
  assert.deepEqual(parseResolution(cleared).date_windows, parseResolution(original).date_windows);
});
