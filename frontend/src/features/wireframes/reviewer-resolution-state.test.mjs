import assert from 'node:assert/strict';
import test from 'node:test';

import { inquiries } from './reviewer-inquiries.ts';
import { displayEndDate, getResolutionSummary } from './reviewer-resolution-state.ts';

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
