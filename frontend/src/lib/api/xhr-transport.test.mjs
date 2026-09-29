import assert from 'node:assert/strict';
import test from 'node:test';

import { sendWithUploadProgress } from './xhr-transport.ts';

class FakeXhr {
  static last;
  upload = {};
  headers = {};
  status = 0;
  responseText = '';
  responseURL = '';
  withCredentials = false;
  constructor() {
    FakeXhr.last = this;
  }
  open(method, url) {
    this.method = method;
    this.url = url;
  }
  setRequestHeader(name, value) {
    this.headers[name] = value;
  }
  getAllResponseHeaders() {
    return this.responseHeaders ?? '';
  }
  send(body) {
    this.body = body;
  }
  abort() {
    this.onabort?.();
  }
  respond(status, text, headers = '', responseURL = this.url) {
    this.status = status;
    this.responseText = text;
    this.responseHeaders = headers;
    this.responseURL = responseURL;
    this.onload?.();
  }
}

function withFakeXhr(context) {
  globalThis.XMLHttpRequest = FakeXhr;
  context.after(() => {
    delete globalThis.XMLHttpRequest;
  });
}

test('헤더·쿠키 전송과 업로드 진행률을 넘기고 응답을 Response로 돌려준다', async (context) => {
  withFakeXhr(context);
  const progress = [];
  const url = new URL('http://api.test/api/v1/clips');
  const pending = sendWithUploadProgress(
    url,
    {
      method: 'POST',
      body: 'payload',
      credentials: 'include',
      headers: new Headers({ 'Idempotency-Key': 'k1' }),
    },
    (event) => progress.push(event),
  );
  const xhr = FakeXhr.last;
  assert.equal(xhr.method, 'POST');
  assert.equal(xhr.url, url.toString());
  assert.equal(xhr.withCredentials, true);
  assert.equal(xhr.headers['idempotency-key'], 'k1');
  assert.equal(xhr.body, 'payload');
  xhr.upload.onprogress({ loaded: 5, total: 10, lengthComputable: true });
  xhr.respond(201, '{"ok":true}', 'x-request-id: req-1\r\ncontent-type: application/json');
  const response = await pending;
  assert.deepEqual(progress, [{ loaded: 5, total: 10 }]);
  assert.equal(response.status, 201);
  assert.equal(response.headers.get('x-request-id'), 'req-1');
  assert.equal(await response.text(), '{"ok":true}');
});

test('본문 없는 성공 상태는 본문 없이 돌려준다', async (context) => {
  withFakeXhr(context);
  const pending = sendWithUploadProgress(
    new URL('http://api.test/x'),
    { method: 'POST' },
    () => {},
  );
  FakeXhr.last.respond(204, '');
  assert.equal((await pending).status, 204);
});

test('옮겨진 응답은 성공으로 처리하지 않는다(전송 차단은 프록시 몫)', async (context) => {
  withFakeXhr(context);
  const pending = sendWithUploadProgress(
    new URL('http://api.test/x'),
    { method: 'POST' },
    () => {},
  );
  FakeXhr.last.respond(200, '{}', '', 'http://elsewhere.test/login');
  await assert.rejects(pending, TypeError);
});

test('네트워크 오류는 fetch처럼 TypeError로 거부한다', async (context) => {
  withFakeXhr(context);
  const pending = sendWithUploadProgress(
    new URL('http://api.test/x'),
    { method: 'POST' },
    () => {},
  );
  FakeXhr.last.onerror();
  await assert.rejects(pending, TypeError);
});

test('signal이 중단되면 요청을 취소하고 AbortError로 거부한다', async (context) => {
  withFakeXhr(context);
  const controller = new AbortController();
  const pending = sendWithUploadProgress(
    new URL('http://api.test/x'),
    { method: 'POST', signal: controller.signal },
    () => {},
  );
  controller.abort();
  await assert.rejects(pending, { name: 'AbortError' });
});
