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

const { canReleaseClaim, getReleaseErrorMessage } = await import('./review-claim-release-view.ts');

function inquiry(status, reviewerLoginId) {
  return { status, history: { reviewerLoginId } };
}

test('검수 취소는 검수 중인 문의의 담당자 본인에게만 보인다', () => {
  assert.equal(canReleaseClaim(inquiry('reviewing', 'kim'), 'kim'), true);
  assert.equal(canReleaseClaim(inquiry('reviewing', 'lee'), 'kim'), false);
  assert.equal(canReleaseClaim(inquiry('reviewing', null), 'kim'), false);
  assert.equal(canReleaseClaim(inquiry('open', 'kim'), 'kim'), false);
  assert.equal(canReleaseClaim(inquiry('closed', 'kim'), 'kim'), false);
  // 로그인 ID 를 알 수 없으면 담당자 정보가 비어 있어도 일치로 보지 않는다.
  assert.equal(canReleaseClaim(inquiry('reviewing', ''), ''), false);
});

test('검수 취소 오류는 HTTP 상태별 안내로 바꾸고 나머지는 서버 문구에 맡긴다', () => {
  assert.match(getReleaseErrorMessage(new ApiClientError('api', 403)), /담당자만/);
  assert.match(getReleaseErrorMessage(new ApiClientError('api', 409)), /검수 중인 상태가 아닙니다/);
  assert.match(getReleaseErrorMessage(new ApiClientError('api', 404)), /더 이상 존재하지 않습니다/);
  assert.equal(getReleaseErrorMessage(new ApiClientError('api', 500)), undefined);
  assert.equal(getReleaseErrorMessage(new ApiClientError('network', 0)), undefined);
  assert.equal(getReleaseErrorMessage(new Error('boom')), undefined);
});
