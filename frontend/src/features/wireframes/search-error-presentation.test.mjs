import assert from 'node:assert/strict';
import { existsSync } from 'node:fs';
import { registerHooks } from 'node:module';
import { fileURLToPath } from 'node:url';
import test from 'node:test';

const sourceRoot = new URL('../../', import.meta.url);

registerHooks({
  resolve(specifier, context, nextResolve) {
    if (specifier.startsWith('@/')) {
      const moduleUrl = new URL(specifier.slice(2), sourceRoot);
      for (const extension of ['.ts', '.tsx']) {
        const candidate = new URL(`${moduleUrl.href}${extension}`);
        if (existsSync(fileURLToPath(candidate))) {
          return { url: candidate.href, shortCircuit: true };
        }
      }
    }
    return nextResolve(specifier, context);
  },
});

const { ApiClientError } = await import('../../lib/api/error.ts');
const { presentSearchError } = await import('./search-error-presentation.ts');

test('검색어를 정규화할 수 없으면 서버 문장 대신 사용자 행동을 설명한다', () => {
  const error = new ApiClientError('http', 400, {
    code: 'SRCH_400_101',
    message: '검색어에서 검색할 수 있는 단어를 찾지 못했다',
  });

  assert.deepEqual(presentSearchError(error), {
    message: '입력한 내용만으로는 검색하기 어려워요.',
    followUp: '인물, 장소, 사건처럼 찾으려는 대상을 포함하면 검색할 수 있어요.',
  });
  assert.doesNotMatch(JSON.stringify(presentSearchError(error)), /찾지 못했다/);
});

test('재시도 가능한 검색 오류는 내부 구성 요소를 노출하지 않는다', () => {
  const error = new ApiClientError('http', 503, {
    code: 'SRCH_503_012',
    message: '기본 단어 검색을 수행하지 못했다',
  });

  assert.deepEqual(presentSearchError(error), {
    message: '검색 서비스를 잠시 이용하기 어려워요.',
    followUp: '잠시 후 같은 조건으로 다시 검색할 수 있어요.',
  });
});

test('알 수 없는 서버 문구도 검색 알림에 그대로 노출하지 않는다', () => {
  const error = new ApiClientError('http', 500, {
    code: 'SRCH_500_999',
    message: '내부 검색 인덱스가 고장났다',
  });

  const presentation = presentSearchError(error);
  assert.equal(presentation.message, '검색 서비스를 잠시 이용하기 어려워요.');
  assert.doesNotMatch(JSON.stringify(presentation), /내부 검색 인덱스|고장났다/);
});

test('네트워크 오류는 연결 상태에 맞는 사용자 문구를 제공한다', () => {
  const error = new ApiClientError('network', 0);

  assert.deepEqual(presentSearchError(error), {
    message: '검색 서버에 연결되지 않았어요.',
    followUp: '네트워크 연결이 복구되면 다시 검색할 수 있어요.',
  });
});

test('응답 형식을 확인할 수 없을 때 사용자 입력 문제로 안내하지 않는다', () => {
  const error = new ApiClientError('invalid-response', 200);

  assert.deepEqual(presentSearchError(error), {
    message: '검색 서비스를 잠시 이용하기 어려워요.',
    followUp: '잠시 후 같은 조건으로 다시 검색할 수 있어요.',
  });
});
