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

const { seedChips, EDITABLE_AXES, deriveEdits } = await import('./interpretation-edit.ts');
const { parseResolution } = await import('./reviewer-resolution-state.ts');

const SAMPLE = JSON.stringify({
  schema_version: 'resolution-v1',
  intent: 'scene_search',
  date_windows: [
    {
      field: 'broadcast_date',
      start: '2025-09-01',
      end_exclusive: '2025-10-11',
      origin: 'explicit_filter',
    },
  ],
  incident_names: [{ value: '추석', origin: 'explicit_query', query_span: null, confidence: 1 }],
  entities: [
    {
      type: 'organization',
      value: '한국도로공사',
      origin: 'inferred',
      query_span: null,
      confidence: 0.72,
    },
  ],
  locations: [
    {
      type: 'location',
      value: '경부고속도로',
      origin: 'explicit_query',
      query_span: null,
      confidence: 1,
    },
  ],
  expanded_terms: ['귀성 차량', '고속도로 정체'],
  confidence: 0.92,
});

test('seedChips 는 편집 4축만 평탄화하고 type·origin 을 보존한다', () => {
  const chips = seedChips(parseResolution(SAMPLE));
  assert.equal(chips.length, 5);
  const loc = chips.find((c) => c.axis === 'locations');
  assert.deepEqual(
    { value: loc.value, type: loc.type, origin: loc.origin },
    { value: '경부고속도로', type: 'location', origin: 'explicit_query' },
  );
  const term = chips.find((c) => c.axis === 'expanded_terms');
  assert.equal(term.value, '귀성 차량');
  assert.equal(term.type, undefined);
  assert.ok(chips.every((c) => typeof c.id === 'string' && c.id.length > 0));
  assert.deepEqual(EDITABLE_AXES, ['incident_names', 'entities', 'locations', 'expanded_terms']);
});

const ORIG = [
  { id: 'incident_names#0', axis: 'incident_names', value: '추석' },
  { id: 'entities#0', axis: 'entities', value: '한국도로공사', type: 'organization' },
  { id: 'locations#0', axis: 'locations', value: '경부고속도로', type: 'location' },
  { id: 'expanded_terms#0', axis: 'expanded_terms', value: '귀성 차량' },
];

test('삭제/수정/이동/추가를 분류한다', () => {
  const current = [
    // locations#0 을 인물·기관으로 이동
    { id: 'locations#0', axis: 'entities', value: '경부고속도로', type: 'location' },
    // entities#0 값 수정
    { id: 'entities#0', axis: 'entities', value: '도로공사', type: 'organization' },
    // incident_names#0 삭제됨(빠짐)
    // 새 검색 의미어 추가
    { id: 'new-1', axis: 'expanded_terms', value: '나들이', isNew: true },
    { id: 'expanded_terms#0', axis: 'expanded_terms', value: '귀성 차량' },
  ];
  const edits = deriveEdits(ORIG, current);
  assert.deepEqual(
    edits.sort((a, b) => a.kind.localeCompare(b.kind)),
    [
      { kind: 'add', axis: 'expanded_terms', value: '나들이', type: undefined },
      {
        kind: 'edit',
        axis: 'entities',
        from: '한국도로공사',
        to: '도로공사',
        type: 'organization',
      },
      {
        kind: 'move',
        from: 'locations',
        to: 'entities',
        value: '경부고속도로',
        fromValue: '경부고속도로',
        type: 'location',
        fromType: 'location',
      },
      { kind: 'remove', axis: 'incident_names', value: '추석', type: undefined },
    ],
  );
});

test('이동 + 값 수정이 함께 일어나면 move 1건에 fromValue(옛값)/value(새값)가 함께 담긴다', () => {
  const current = [
    // locations#0 을 인물·기관으로 이동하면서 값도 함께 수정 (id 는 그대로)
    { id: 'locations#0', axis: 'entities', value: '경부고속도로 휴게소', type: 'location' },
    { id: 'entities#0', axis: 'entities', value: '한국도로공사', type: 'organization' },
    { id: 'incident_names#0', axis: 'incident_names', value: '추석' },
    { id: 'expanded_terms#0', axis: 'expanded_terms', value: '귀성 차량' },
  ];
  const edits = deriveEdits(ORIG, current);
  assert.deepEqual(edits, [
    {
      kind: 'move',
      from: 'locations',
      to: 'entities',
      value: '경부고속도로 휴게소',
      fromValue: '경부고속도로',
      type: 'location',
      fromType: 'location',
    },
  ]);
});

test('변화 없으면 빈 배열', () => {
  assert.deepEqual(
    deriveEdits(
      ORIG,
      ORIG.map((c) => ({ ...c })),
    ),
    [],
  );
});

const { deriveParseRules } = await import('./interpretation-edit.ts');

test('remove: 비TYPED 축은 type 없이, TYPED 축은 type 포함', () => {
  const [term] = deriveParseRules(
    [{ kind: 'remove', axis: 'expanded_terms', value: '나들이' }],
    null,
  );
  assert.deepEqual(term.condition.all, [
    { axis: 'expanded_terms', op: 'has_value', value: '나들이' },
  ]);
  assert.deepEqual(term.patch.operations, [
    { op: 'remove_item', axis: 'expanded_terms', value: '나들이' },
  ]);

  const [loc] = deriveParseRules(
    [{ kind: 'remove', axis: 'locations', value: '경부고속도로', type: 'location' }],
    null,
  );
  assert.deepEqual(loc.patch.operations, [
    { op: 'remove_item', axis: 'locations', value: '경부고속도로', type: 'location' },
  ]);
});

test('edit: has_value(옛값) 조건 + remove 옛값 + add 새값', () => {
  const [rule] = deriveParseRules(
    [
      {
        kind: 'edit',
        axis: 'entities',
        from: '한국도로공사',
        to: '도로공사',
        type: 'organization',
      },
    ],
    null,
  );
  assert.deepEqual(rule.condition.all, [
    { axis: 'entities', op: 'has_value', value: '한국도로공사' },
  ]);
  assert.deepEqual(rule.patch.operations, [
    { op: 'remove_item', axis: 'entities', value: '한국도로공사', type: 'organization' },
    { op: 'add_item', axis: 'entities', value: '도로공사', type: 'organization' },
  ]);
});

test('move 비TYPED→TYPED: add 에 기본 type 을 채운다', () => {
  const [rule] = deriveParseRules(
    [
      {
        kind: 'move',
        from: 'incident_names',
        to: 'locations',
        value: '경부고속도로',
        fromValue: '경부고속도로',
      },
    ],
    null,
  );
  assert.deepEqual(rule.condition.all, [
    { axis: 'incident_names', op: 'has_value', value: '경부고속도로' },
  ]);
  assert.deepEqual(rule.patch.operations, [
    { op: 'remove_item', axis: 'incident_names', value: '경부고속도로' },
    { op: 'add_item', axis: 'locations', value: '경부고속도로', type: 'location' },
  ]);
});

test('move + 수정: 조건/remove 는 옛값(fromValue)·원본 type(fromType), add 는 새값(value)·목적지 type 기준', () => {
  const [rule] = deriveParseRules(
    [
      {
        kind: 'move',
        from: 'locations',
        to: 'entities',
        value: '경부고속도로 휴게소',
        fromValue: '경부고속도로',
        type: 'organization',
        fromType: 'location',
      },
    ],
    null,
  );
  assert.deepEqual(rule.condition.all, [
    { axis: 'locations', op: 'has_value', value: '경부고속도로' },
  ]);
  assert.deepEqual(rule.patch.operations, [
    { op: 'remove_item', axis: 'locations', value: '경부고속도로', type: 'location' },
    { op: 'add_item', axis: 'entities', value: '경부고속도로 휴게소', type: 'organization' },
  ]);
});

const { validateParseRuleBody } = await import('./review-parse-rule-api.ts');

test('move locations→entities(typed→typed, 드롭 핸들러 재현): remove 는 원본 type 을 키로 쓰고 검증을 통과한다', () => {
  // onRowDrop 은 옮겨진 칩의 type 을 목적지 기본 type 으로 덮어쓴다 (FIX 3) — 그 결과를 그대로 재현한다.
  const current = ORIG.map((chip) =>
    chip.id === 'locations#0' ? { ...chip, axis: 'entities', type: 'organization' } : { ...chip },
  );
  const edits = deriveEdits(ORIG, current);
  assert.deepEqual(edits, [
    {
      kind: 'move',
      from: 'locations',
      to: 'entities',
      value: '경부고속도로',
      fromValue: '경부고속도로',
      type: 'organization',
      fromType: 'location',
    },
  ]);

  const [rule] = deriveParseRules(edits, null);
  assert.deepEqual(rule.patch.operations, [
    { op: 'remove_item', axis: 'locations', value: '경부고속도로', type: 'location' },
    { op: 'add_item', axis: 'entities', value: '경부고속도로', type: 'organization' },
  ]);
  assert.equal(validateParseRuleBody(rule), null);
});

test('move entities→incident_names(typed→비TYPED, 드롭 핸들러 재현): remove 는 원본 type 유지, add 는 type 없이 검증을 통과한다', () => {
  const current = ORIG.map((chip) =>
    chip.id === 'entities#0' ? { ...chip, axis: 'incident_names', type: undefined } : { ...chip },
  );
  const edits = deriveEdits(ORIG, current);
  assert.deepEqual(edits, [
    {
      kind: 'move',
      from: 'entities',
      to: 'incident_names',
      value: '한국도로공사',
      fromValue: '한국도로공사',
      type: undefined,
      fromType: 'organization',
    },
  ]);

  const [rule] = deriveParseRules(edits, null);
  assert.deepEqual(rule.patch.operations, [
    { op: 'remove_item', axis: 'entities', value: '한국도로공사', type: 'organization' },
    { op: 'add_item', axis: 'incident_names', value: '한국도로공사' },
  ]);
  assert.equal(validateParseRuleBody(rule), null);
});

test('add: guard 없으면 규칙 없음, 있으면 guard 조건', () => {
  assert.deepEqual(
    deriveParseRules([{ kind: 'add', axis: 'expanded_terms', value: '나들이' }], null),
    [],
  );
  const [rule] = deriveParseRules([{ kind: 'add', axis: 'expanded_terms', value: '나들이' }], {
    axis: 'incident_names',
    value: '추석',
  });
  assert.deepEqual(rule.condition.all, [
    { axis: 'incident_names', op: 'has_value', value: '추석' },
  ]);
  assert.deepEqual(rule.patch.operations, [
    { op: 'add_item', axis: 'expanded_terms', value: '나들이' },
  ]);
});

const { describeEdits } = await import('./interpretation-edit.ts');

test('편집을 사람 문장으로 요약한다', () => {
  const out = describeEdits([
    {
      kind: 'move',
      from: 'locations',
      to: 'entities',
      value: '경부고속도로',
      fromValue: '경부고속도로',
    },
    { kind: 'add', axis: 'expanded_terms', value: '나들이' },
    { kind: 'remove', axis: 'incident_names', value: '추석' },
    { kind: 'edit', axis: 'entities', from: '한국도로공사', to: '도로공사' },
  ]);
  assert.deepEqual(out, [
    { key: '수정', text: '\u2018경부고속도로\u2019를 장소·시설에서 인물·기관으로' },
    { key: '추가', text: '검색 의미어에 \u2018나들이\u2019' },
    { key: '삭제', text: '사건명에서 \u2018추석\u2019 제거' },
    { key: '수정', text: '인물·기관 \u2018한국도로공사\u2019를 \u2018도로공사\u2019로' },
  ]);
});

test('편집 요약: 이동 + 수정이 함께면 이동 문장에 수정 사실을 덧붙인다', () => {
  const out = describeEdits([
    {
      kind: 'move',
      from: 'locations',
      to: 'entities',
      value: '경부고속도로 휴게소',
      fromValue: '경부고속도로',
    },
  ]);
  assert.deepEqual(out, [
    {
      key: '수정',
      text: '‘경부고속도로’를 장소·시설에서 인물·기관으로 (‘경부고속도로 휴게소’로 수정)',
    },
  ]);
});
