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

const interpretationEdit = await import('./interpretation-edit.ts');

test('추가 적용 기준은 명시값을 자동 선택하고 추론값도 선택 후보로 남긴다', () => {
  const options = interpretationEdit.additionGuardOptions([
    {
      id: 'entities#0',
      axis: 'entities',
      value: '한국도로공사',
      type: 'organization',
      origin: 'inferred',
    },
    {
      id: 'locations#0',
      axis: 'locations',
      value: '경부고속도로',
      type: 'location',
      origin: 'explicit_query',
    },
    { id: 'expanded_terms#0', axis: 'expanded_terms', value: '교통 정체' },
  ]);

  assert.deepEqual(
    options.map(({ axis, value, isExplicit }) => ({ axis, value, isExplicit })),
    [
      { axis: 'entities', value: '한국도로공사', isExplicit: false },
      { axis: 'locations', value: '경부고속도로', isExplicit: true },
      { axis: 'expanded_terms', value: '교통 정체', isExplicit: false },
    ],
  );
  assert.deepEqual(interpretationEdit.defaultAdditionGuard(options), options[1]);
});

test('추가 적용 기준은 빈 값·새 칩·중복 조건을 후보에서 제외한다', () => {
  const options = interpretationEdit.additionGuardOptions([
    { id: 'incident_names#0', axis: 'incident_names', value: '  ' },
    { id: 'new-1', axis: 'locations', value: '서울', isNew: true },
    { id: 'expanded_terms#0', axis: 'expanded_terms', value: '교통 정체' },
    { id: 'expanded_terms#1', axis: 'expanded_terms', value: '교통 정체' },
  ]);

  assert.deepEqual(
    options.map(({ axis, value }) => ({ axis, value })),
    [{ axis: 'expanded_terms', value: '교통 정체' }],
  );
  assert.equal(interpretationEdit.defaultAdditionGuard(options), null);
});

test('추가 적용 기준은 원본 값을 그대로 보존하고 같은 조건이면 명시값을 우선한다', () => {
  const options = interpretationEdit.additionGuardOptions([
    {
      id: 'entities#0',
      axis: 'entities',
      value: '한국도로공사',
      origin: 'inferred',
    },
    {
      id: 'entities#1',
      axis: 'entities',
      value: '한국도로공사',
      origin: 'explicit_query',
    },
    { id: 'expanded_terms#0', axis: 'expanded_terms', value: ' 정체 ' },
  ]);

  assert.deepEqual(options, [
    {
      id: 'entities#1',
      axis: 'entities',
      value: '한국도로공사',
      isExplicit: true,
    },
    {
      id: 'expanded_terms#0',
      axis: 'expanded_terms',
      value: ' 정체 ',
      isExplicit: false,
    },
  ]);
});

test('100자를 넘는 원본 값은 적용 기준 후보에서 빼고 별도 차단 사유로 분류한다', () => {
  assert.equal(typeof interpretationEdit.hasOversizedAdditionGuard, 'function');
  const longValue = '가'.repeat(101);
  const chips = [{ id: 'expanded_terms#0', axis: 'expanded_terms', value: longValue }];

  assert.deepEqual(interpretationEdit.additionGuardOptions(chips), []);
  assert.equal(interpretationEdit.hasOversizedAdditionGuard(chips), true);
  assert.equal(
    interpretationEdit.hasOversizedAdditionGuard([
      { id: 'expanded_terms#0', axis: 'expanded_terms', value: '가'.repeat(100) },
    ]),
    false,
  );
});
