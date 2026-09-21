import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { registerHooks } from 'node:module';
import test from 'node:test';
import { createElement } from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import ts from 'typescript';

registerHooks({
  resolve(specifier, context, nextResolve) {
    return nextResolve(
      specifier.startsWith('@/lib/')
        ? new URL(`../lib/${specifier.slice('@/lib/'.length)}.ts`, import.meta.url).href
        : specifier,
      context,
    );
  },
  load(url, context, nextLoad) {
    if (url === new URL('./api-error-notice.tsx', import.meta.url).href) {
      return {
        format: 'module',
        source: ts.transpileModule(readFileSync(new URL(url), 'utf8'), {
          compilerOptions: { module: ts.ModuleKind.ESNext, jsx: ts.JsxEmit.ReactJSX },
        }).outputText,
        shortCircuit: true,
      };
    }
    return nextLoad(url, context);
  },
});

const { ApiErrorNotice } = await import('./api-error-notice.tsx');
const { fetchJson } = await import('../lib/api/client.ts');

test('실제 client 오류는 한국어 안내만 렌더링하고 코드·요청 ID는 오류 객체에 유지한다', async (context) => {
  const payload = {
    isSuccess: false,
    code: 'COMM_400',
    message: '입력한 날짜를 확인해 주세요.',
    requestId: 'req-123',
    path: '/srv/private/file',
    data: { password: 'secret-password', exception: 'InternalException' },
    stack: 'internal-stack',
  };
  context.mock.method(globalThis, 'fetch', async () => Response.json(payload, { status: 400 }));
  let error;
  try {
    await fetchJson('/clips');
  } catch (caught) {
    error = caught;
  }
  assert.ok(error);
  const html = renderToStaticMarkup(createElement(ApiErrorNotice, { error, id: 'form-error' }));
  for (const value of ['입력한 날짜를 확인해 주세요.']) {
    assert.ok(html.includes(value));
  }
  assert.match(html, /role="alert"/);
  assert.match(html, /id="form-error"/);
  assert.match(html, /aria-atomic="true"/);
  assert.doesNotMatch(html, /COMM_400|req-123|오류 코드|요청 ID/);
  assert.equal(error.code, 'COMM_400');
  assert.equal(error.requestId, 'req-123');
  assert.doesNotMatch(html, /private|secret-password|InternalException|internal-stack|isSuccess/);
});

test('일반 예외와 임의 객체는 일반 한국어 안내만 표시한다', () => {
  for (const error of [
    new Error('secret internal exception'),
    { message: '<script>secret</script>' },
    null,
  ]) {
    const html = renderToStaticMarkup(createElement(ApiErrorNotice, { error }));
    assert.match(html, /서버 응답을 확인할 수 없습니다/);
    assert.doesNotMatch(html, /CLIENT_INVALID_RESPONSE|제공되지 않음|요청 ID/);
    assert.doesNotMatch(html, /secret|<script>/);
  }
});
