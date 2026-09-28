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

const { createInquirySubmission, isSameInquiryRequest, parseInquiryResponse, submitInquiry } =
  await import('./inquiry-api.ts');

test('2000자를 넘는 문의는 API에 전송하지 않는다', async (context) => {
  const fetch = context.mock.method(globalThis, 'fetch', () => {
    throw new Error('must not send');
  });
  const submission = createInquirySubmission('123', '가'.repeat(2001), () => 'test-key');
  await assert.rejects(submitInquiry(submission), /2,000자/);
  assert.equal(fetch.mock.callCount(), 0);
});

test('문의 요청은 저장된 결과 ID, trim한 선택 설명과 멱등성 키를 전송한다', async (context) => {
  const fetch = context.mock.method(globalThis, 'fetch', async () =>
    Response.json({
      isSuccess: true,
      code: 'COMM_200',
      message: '문의가 접수되었습니다.',
      data: { feedbackId: 42, status: 'OPEN' },
    }),
  );
  const signal = new AbortController().signal;

  const result = await submitInquiry(
    createInquirySubmission('987', '  장면 설명이 맞지 않습니다.  ', () => 'inquiry-key'),
    signal,
  );

  assert.deepEqual(result, { inquiryId: '42', status: 'open' });
  assert.equal(fetch.mock.callCount(), 1);
  const [url, init] = fetch.mock.calls[0].arguments;
  assert.equal(url, 'http://127.0.0.1:8080/api/v1/search/results/987/inquiries');
  assert.equal(init.method, 'POST');
  assert.equal(init.body, JSON.stringify({ comment: '장면 설명이 맞지 않습니다.' }));
  assert.equal(init.headers.get('idempotency-key'), 'inquiry-key');
  assert.strictEqual(init.signal, signal);
});

test('설명 없이도 빈 JSON 객체로 문의를 접수한다', async (context) => {
  const fetch = context.mock.method(globalThis, 'fetch', async () =>
    Response.json({
      isSuccess: true,
      code: 'COMM_200',
      message: '문의가 접수되었습니다.',
      data: { feedbackId: '43', status: 'OPEN' },
    }),
  );

  await submitInquiry(createInquirySubmission('1', '   ', () => 'empty-comment-key'));

  assert.equal(fetch.mock.calls[0].arguments[1].body, '{}');
});

test('같은 논리 요청은 frozen snapshot과 key를 재사용하고 설명 변경은 새 요청이다', () => {
  const first = createInquirySubmission('7', '  같은 설명  ', () => 'key-one');
  const retry = first;
  const edited = createInquirySubmission('7', '수정한 설명', () => 'key-two');

  assert.equal(Object.isFrozen(first.snapshot), true);
  assert.equal(isSameInquiryRequest(retry, '7', '같은 설명'), true);
  assert.equal(isSameInquiryRequest(retry, '7', '수정한 설명'), false);
  assert.equal(retry.key, 'key-one');
  assert.equal(edited.key, 'key-two');
  assert.notStrictEqual(edited.snapshot, first.snapshot);
});

test('실패 후 수동 재시도는 같은 body와 Idempotency-Key를 다시 사용한다', async (context) => {
  let attempt = 0;
  const fetch = context.mock.method(globalThis, 'fetch', async () => {
    attempt += 1;
    if (attempt === 1) throw new TypeError('network unavailable');
    return Response.json({
      isSuccess: true,
      code: 'COMM_200',
      message: '문의가 접수되었습니다.',
      data: { feedbackId: 44, status: 'OPEN' },
    });
  });
  const submission = createInquirySubmission('9', '같은 설명', () => 'retry-key');

  await assert.rejects(
    () => submitInquiry(submission),
    (error) => error instanceof ApiClientError && error.kind === 'network',
  );
  await submitInquiry(submission);

  assert.equal(fetch.mock.callCount(), 2);
  const [, firstInit] = fetch.mock.calls[0].arguments;
  const [, retryInit] = fetch.mock.calls[1].arguments;
  assert.equal(firstInit.body, retryInit.body);
  assert.equal(firstInit.headers.get('idempotency-key'), 'retry-key');
  assert.equal(retryInit.headers.get('idempotency-key'), 'retry-key');
});

for (const [status, normalizedStatus] of [
  ['REVIEWING', 'reviewing'],
  ['CLOSED', 'closed'],
]) {
  test(`응답 유실 후 재시도는 기존 문의의 ${status} 상태를 성공으로 보존한다`, async (context) => {
    let attempt = 0;
    const fetch = context.mock.method(globalThis, 'fetch', async () => {
      if (++attempt === 1) throw new TypeError('response lost after submission');
      return Response.json({
        isSuccess: true,
        code: 'COMM_200',
        message: '요청에 성공했습니다.',
        data: { feedbackId: '9223372036854775807', status },
      });
    });
    const submission = createInquirySubmission('987', '같은 설명', () => 'retry-key');

    await assert.rejects(
      () => submitInquiry(submission),
      (error) => error instanceof ApiClientError && error.kind === 'network',
    );
    assert.deepEqual(await submitInquiry(submission), {
      inquiryId: '9223372036854775807',
      status: normalizedStatus,
    });
    assert.equal(fetch.mock.callCount(), 2);
    const [first, retry] = fetch.mock.calls.map(({ arguments: [, init] }) => init);
    assert.equal(first.body, retry.body);
    assert.equal(first.headers.get('idempotency-key'), 'retry-key');
    assert.equal(retry.headers.get('idempotency-key'), 'retry-key');
  });
}

test('문의 대상 ID는 양의 10진 문자열만 허용한다', () => {
  for (const resultId of ['', '0', '-1', '1.5', ' 1', '1/other']) {
    assert.throws(() => createInquirySubmission(resultId, '', () => 'key'), TypeError);
  }
});

test('성공 응답은 유효한 feedbackId와 알려진 문의 상태를 모두 요구한다', () => {
  assert.deepEqual(parseInquiryResponse({ feedbackId: '9223372036854775807', status: 'OPEN' }), {
    inquiryId: '9223372036854775807',
    status: 'open',
  });

  for (const response of [
    null,
    {},
    { feedbackId: 0, status: 'OPEN' },
    { feedbackId: -1, status: 'OPEN' },
    { feedbackId: '1.5', status: 'OPEN' },
    { feedbackId: Number.MAX_SAFE_INTEGER + 1, status: 'OPEN' },
    { feedbackId: '1', status: 'UNKNOWN' },
    { feedbackId: '1', status: 'open' },
    { feedbackId: '1', status: null },
    { feedbackId: '1' },
    { feedbackId: '1', status: {} },
    { feedbackId: '1', status: ['OPEN'] },
    { feedbackId: '0', status: 'REVIEWING' },
    { feedbackId: Number.MAX_SAFE_INTEGER + 1, status: 'CLOSED' },
  ]) {
    assert.throws(() => parseInquiryResponse(response), ApiClientError);
  }
});

test('본문 없는 2xx 응답도 접수 성공으로 표시하지 않는다', async (context) => {
  context.mock.method(globalThis, 'fetch', async () => new Response(null, { status: 204 }));

  await assert.rejects(
    () => submitInquiry(createInquirySubmission('5', '', () => 'key')),
    (error) => error instanceof ApiClientError && error.kind === 'invalid-response',
  );
});
