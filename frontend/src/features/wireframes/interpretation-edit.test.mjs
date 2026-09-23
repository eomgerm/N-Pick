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
  date_windows: [{ field: 'broadcast_date', start: '2025-09-01', end_exclusive: '2025-10-11', origin: 'explicit_filter' }],
  incident_names: [{ value: '추석', origin: 'explicit_query', query_span: null, confidence: 1 }],
  entities: [{ type: 'organization', value: '한국도로공사', origin: 'inferred', query_span: null, confidence: 0.72 }],
  locations: [{ type: 'location', value: '경부고속도로', origin: 'explicit_query', query_span: null, confidence: 1 }],
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
  assert.deepEqual(edits.sort((a, b) => a.kind.localeCompare(b.kind)), [
    { kind: 'add', axis: 'expanded_terms', value: '나들이', type: undefined },
    { kind: 'edit', axis: 'entities', from: '한국도로공사', to: '도로공사', type: 'organization' },
    { kind: 'move', from: 'locations', to: 'entities', value: '경부고속도로', type: 'location' },
    { kind: 'remove', axis: 'incident_names', value: '추석', type: undefined },
  ]);
});

test('변화 없으면 빈 배열', () => {
  assert.deepEqual(deriveEdits(ORIG, ORIG.map((c) => ({ ...c }))), []);
});
