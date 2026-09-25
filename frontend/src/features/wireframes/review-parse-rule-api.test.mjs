import assert from 'node:assert/strict';
import { registerHooks } from 'node:module';
import test from 'node:test';

import { ApiClientError } from '../../lib/api/error.ts';

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

const { createParsePatchCandidate, parseRuleErrorMessage, validateParseRuleBody } =
  await import('./review-parse-rule-api.ts');

/** F-11 예: 공장명이 장소로만 해석되어 사건 검색을 놓친 경우. */
function draft(overrides = {}) {
  return {
    condition: {
      syntax_version: 'parse-rule/v1',
      resolution_schema_version: 'query-resolver/v2',
      all: [{ axis: 'locations', op: 'has_value', value: '○○공장' }],
      ...overrides.condition,
    },
    patch: {
      syntax_version: 'parse-rule/v1',
      operations: [
        { op: 'remove_item', axis: 'locations', type: 'facility', value: '○○공장' },
        {
          op: 'add_item',
          axis: 'incident_names',
          value_from: { axis: 'locations', type: 'facility', value: '○○공장' },
        },
      ],
      ...overrides.patch,
    },
    ...(overrides.replacesRuleId === undefined ? {} : { replacesRuleId: overrides.replacesRuleId }),
  };
}

function stubOk(context, requests, { status = 201, data } = {}) {
  context.mock.method(globalThis, 'fetch', async (input, init) => {
    requests.push({ input: String(input), init });
    return Response.json(
      {
        isSuccess: true,
        code: status === 201 ? 'COMM_201' : 'COMM_200',
        message: 'ok',
        data: data ?? { searchRuleId: '398021847361024', feedbackId: '41', active: false },
      },
      { status },
    );
  });
}

test('올바른 규칙 후보 본문은 통과시킨다', () => {
  assert.equal(validateParseRuleBody(draft()), null);
});

test('두 syntax_version 이 다르면 전송 전에 막는다', () => {
  const problem = validateParseRuleBody(
    draft({
      patch: { syntax_version: 'parse-rule/v2', operations: [{ op: 'unset', axis: 'intent' }] },
    }),
  );
  assert.match(problem ?? '', /문법/);
});

test('조건과 변경이 모두 비어 있으면 막는다', () => {
  assert.ok(validateParseRuleBody(draft({ condition: { all: [] } })));
  assert.ok(validateParseRuleBody(draft({ patch: { operations: [] } })));
});

test('스칼라 축과 목록 축의 연산을 서로 바꿔 쓸 수 없다', () => {
  assert.ok(
    validateParseRuleBody(draft({ condition: { all: [{ axis: 'intent', op: 'is_empty' }] } })),
  );
  assert.ok(
    validateParseRuleBody(
      draft({ condition: { all: [{ axis: 'locations', op: 'equals', value: '서울' }] } }),
    ),
  );
  assert.ok(
    validateParseRuleBody(
      draft({ patch: { operations: [{ op: 'set', axis: 'locations', value: '서울' }] } }),
    ),
  );
  assert.ok(
    validateParseRuleBody(
      draft({ patch: { operations: [{ op: 'add_item', axis: 'intent', value: 'scene_search' }] } }),
    ),
  );
});

test('연산이 쓰지 않는 피연산자를 적으면 막는다', () => {
  assert.ok(
    validateParseRuleBody(
      draft({ condition: { all: [{ axis: 'locations', op: 'is_empty', value: '서울' }] } }),
    ),
  );
  assert.ok(
    validateParseRuleBody(draft({ condition: { all: [{ axis: 'locations', op: 'has_value' }] } })),
  );
  assert.ok(
    validateParseRuleBody(
      draft({ condition: { all: [{ axis: 'incident_names', op: 'has_type', type: 'person' }] } }),
    ),
  );
});

test('value_from 은 add_item 에만 쓰고 value 와 함께 쓸 수 없다', () => {
  const valueFrom = { axis: 'locations', type: 'facility', value: '○○공장' };
  assert.ok(
    validateParseRuleBody(
      draft({
        patch: {
          operations: [{ op: 'remove_item', axis: 'incident_names', value_from: valueFrom }],
        },
      }),
    ),
  );
  assert.ok(
    validateParseRuleBody(
      draft({
        patch: {
          operations: [
            {
              op: 'add_item',
              axis: 'incident_names',
              value: '○○공장 사고',
              value_from: valueFrom,
            },
          ],
        },
      }),
    ),
  );
});

test('date_windows 항목은 값이 아니라 구간으로 가리킨다', () => {
  assert.ok(
    validateParseRuleBody(
      draft({
        patch: {
          operations: [
            {
              op: 'remove_item',
              axis: 'date_windows',
              type: 'broadcast_date',
              value: '2026-09-01',
            },
          ],
        },
      }),
    ),
  );
  assert.equal(
    validateParseRuleBody(
      draft({
        patch: {
          operations: [
            {
              op: 'remove_item',
              axis: 'date_windows',
              type: 'broadcast_date',
              start: '2026-09-01',
              end_exclusive: '2026-09-02',
            },
          ],
        },
      }),
    ),
    null,
  );
});

test('교체 대상 규칙 번호는 양의 정수 문자열만 받는다', () => {
  assert.ok(validateParseRuleBody(draft({ replacesRuleId: '92.01' })));
  assert.ok(validateParseRuleBody(draft({ replacesRuleId: '0' })));
  assert.equal(validateParseRuleBody(draft({ replacesRuleId: '9201' })), null);
});

test('계약대로 경로·메서드·멱등성 키를 보내고 빈 값 키는 아예 빼고 보낸다', async (context) => {
  const requests = [];
  stubOk(context, requests);

  await createParsePatchCandidate('41', draft(), 'parse-key-1');

  assert.ok(requests[0].input.endsWith('/api/v1/review/inquiries/41/parse-patch-candidate'));
  assert.equal(requests[0].init.method, 'POST');
  assert.equal(new Headers(requests[0].init.headers).get('Idempotency-Key'), 'parse-key-1');
  const sent = JSON.parse(requests[0].init.body);
  assert.deepEqual(Object.keys(sent), ['condition', 'patch']);
  assert.deepEqual(Object.keys(sent.condition), [
    'syntax_version',
    'resolution_schema_version',
    'all',
  ]);
  assert.deepEqual(Object.keys(sent.patch), ['syntax_version', 'operations']);
  assert.deepEqual(sent.condition.all[0], { axis: 'locations', op: 'has_value', value: '○○공장' });
  assert.deepEqual(sent.patch.operations[1], {
    op: 'add_item',
    axis: 'incident_names',
    value_from: { axis: 'locations', type: 'facility', value: '○○공장' },
  });
});

test('교체 대상을 적으면 그때만 replacesRuleId 를 보낸다', async (context) => {
  const requests = [];
  stubOk(context, requests);

  await createParsePatchCandidate('41', draft({ replacesRuleId: '9201' }), 'parse-key-2');

  assert.equal(JSON.parse(requests[0].init.body).replacesRuleId, '9201');
});

test('신규 201 과 멱등 재생 200 을 모두 성공으로 읽고 TSID 를 문자열로 보존한다', async (context) => {
  for (const status of [201, 200]) {
    const mock = context.mock.method(globalThis, 'fetch', async () =>
      Response.json(
        {
          isSuccess: true,
          code: 'COMM_200',
          message: 'ok',
          data: { searchRuleId: 398021847361024, feedbackId: 41, active: false },
        },
        { status },
      ),
    );
    assert.deepEqual(await createParsePatchCandidate('41', draft(), 'parse-key-3'), {
      searchRuleId: '398021847361024',
      feedbackId: '41',
      active: false,
    });
    mock.mock.restore();
  }
});

test('검증에 걸린 본문은 요청을 보내지 않는다', async (context) => {
  const requests = [];
  stubOk(context, requests);

  await assert.rejects(
    () => createParsePatchCandidate('41', draft({ condition: { all: [] } }), 'parse-key-4'),
    ApiClientError,
  );
  assert.equal(requests.length, 0);
});

test('알 수 없는 응답 모양은 성공으로 읽지 않는다', async (context) => {
  const requests = [];
  stubOk(context, requests, { data: { searchRuleId: '0', feedbackId: '41', active: false } });

  await assert.rejects(
    () => createParsePatchCandidate('41', draft(), 'parse-key-5'),
    ApiClientError,
  );
});

test('교정 후보 오류 코드는 검수자가 읽을 한국어 문구로 바꾼다', () => {
  for (const code of [
    'SRCH_400_201',
    'SRCH_400_202',
    'SRCH_403_201',
    'SRCH_403_202',
    'SRCH_404_201',
    'SRCH_409_201',
    'SRCH_409_202',
    'SRCH_409_203',
    'SRCH_409_204',
    'SRCH_409_205',
  ]) {
    const message = parseRuleErrorMessage(new ApiClientError('api', 400, { code }));
    assert.ok(message && /[가-힣]/.test(message), code);
  }
  assert.match(
    parseRuleErrorMessage(new ApiClientError('api', 409, { code: 'SRCH_409_205' })),
    /폐기/,
  );
  assert.equal(parseRuleErrorMessage(new ApiClientError('api', 500, { code: 'COMM_500' })), null);
  assert.equal(parseRuleErrorMessage(new Error('boom')), null);
});
