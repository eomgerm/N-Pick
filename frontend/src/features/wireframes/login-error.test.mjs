import assert from 'node:assert/strict';
import { registerHooks } from 'node:module';
import test from 'node:test';

registerHooks({
  resolve(specifier, context, nextResolve) {
    return nextResolve(
      specifier.startsWith('@/lib/')
        ? new URL(`../../lib/${specifier.slice('@/lib/'.length)}.ts`, import.meta.url).href
        : specifier,
      context,
    );
  },
});

const { ApiClientError } = await import('../../lib/api/error.ts');
const { getLoginErrorPresentation } = await import('./login-error.ts');

test('로그인 401은 입력 수정 대기 상태로 변환한다', () => {
  assert.deepEqual(
    getLoginErrorPresentation(new ApiClientError('http', 401, { code: 'MEMBER_401_001' })),
    { kind: 'invalid-credentials' },
  );
});

test('로그인 403·500 오류를 상태별 팝업 문구로 변환한다', () => {
  const cases = [
    [
      new ApiClientError('http', 403, { code: 'COMM_403' }),
      '로그인 요청의 보안 정보가 만료되었습니다. 비밀번호를 다시 입력하고 시도해 주세요.',
    ],
    [
      new ApiClientError('http', 500, { code: 'COMM_500' }),
      '서버 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.',
    ],
  ];

  for (const [error, message] of cases) {
    const presentation = getLoginErrorPresentation(error);
    assert.equal(presentation.kind, 'dialog');
    assert.equal(presentation.content.message, message);
  }
});

test('400·네트워크 오류와 위조 객체는 인라인으로 처리한다', () => {
  for (const error of [
    new ApiClientError('http', 400, { code: 'COMM_400_001' }),
    new ApiClientError('network', 0),
    { status: 500, code: 'COMM_500' },
  ]) {
    assert.deepEqual(getLoginErrorPresentation(error), { kind: 'inline' });
  }
});
