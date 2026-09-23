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

const { seedChips, EDITABLE_AXES } = await import('./interpretation-edit.ts');
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
