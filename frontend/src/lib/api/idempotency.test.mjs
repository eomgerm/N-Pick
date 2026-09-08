import assert from 'node:assert/strict';
import test from 'node:test';

import { createIdempotencyKey } from './idempotency.ts';

test('새 제출마다 서로 다른 UUID v4 멱등성 키를 생성한다', () => {
  const first = createIdempotencyKey();
  const second = createIdempotencyKey();
  const uuidV4 = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;

  assert.match(first, uuidV4);
  assert.match(second, uuidV4);
  assert.notEqual(first, second);
});

test('사용자·리소스·요청 데이터 없이 Web Crypto의 불투명한 키를 그대로 반환한다', (context) => {
  const expected = '3023c5b3-de02-4f7e-b2fa-eac87465f447';
  const randomUUID = context.mock.method(globalThis.crypto, 'randomUUID', () => expected);

  assert.equal(createIdempotencyKey(), expected);
  assert.equal(randomUUID.mock.callCount(), 1);
  assert.deepEqual(randomUUID.mock.calls[0].arguments, []);
});
