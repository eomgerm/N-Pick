import assert from 'node:assert/strict';
import test from 'node:test';

import {
  countParseRuleDrafts,
  draftLimitStatus,
  MAX_PARSE_RULE_DRAFTS,
} from './parse-rule-draft-limit.ts';

test('교정 수는 규칙 수에 입력 중인 새 빈 칩만 더한다', () => {
  const chips = [
    { id: 'a', axis: 'locations', value: '서울역' },
    { id: 'new-1', axis: 'locations', value: '부산', isNew: true },
    { id: 'new-2', axis: 'locations', value: '', isNew: true },
    { id: 'new-3', axis: 'entities', value: '  ', isNew: true },
  ];
  assert.equal(countParseRuleDrafts(3, chips), 5);
  assert.equal(countParseRuleDrafts(0, []), 0);
});

test('상한 아래에서는 개수만 보이고 알림 문구는 비어 있다', () => {
  assert.equal(MAX_PARSE_RULE_DRAFTS, 10);
  assert.deepEqual(draftLimitStatus(3), { label: '3/10', isAtLimit: false, message: '' });
  assert.equal(draftLimitStatus(9).isAtLimit, false);
});

test('상한에 닿으면 추가를 막고, 넘으면 줄일 개수를 안내한다', () => {
  const full = draftLimitStatus(10);
  assert.equal(full.label, '10/10');
  assert.equal(full.isAtLimit, true);
  assert.match(full.message, /10개/);

  const over = draftLimitStatus(12);
  assert.equal(over.label, '12/10');
  assert.equal(over.isAtLimit, true);
  assert.match(over.message, /2개 줄여/);
});
