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

const { canReleaseClaim, isCorrectionMutation, nextTrappedFocusIndex, releaseErrorMessages } =
  await import('./review-claim-release-view.ts');

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

test('검수 취소 오류는 코드로만 안내를 고르고 보안 계층 403 은 서버 문구에 맡긴다', () => {
  const message = (code) => releaseErrorMessages[new ApiClientError('api', 0, { code }).code];
  assert.match(message('FEEDBACK_403_002'), /담당자만/);
  assert.match(message('FEEDBACK_409_003'), /이미 검수가 취소되었거나/);
  assert.match(message('FEEDBACK_404_002'), /더 이상 존재하지 않습니다/);
  // 역할·CSRF 거부(COMM_403)를 담당자 문제로 안내하지 않는다.
  assert.equal(message('COMM_403'), undefined);
  assert.equal(message('FEEDBACK_409_001'), undefined);
});

test('교정·판정 요청 키가 같은 문의로 진행 중일 때만 검수 취소를 잠근다', () => {
  for (const key of [
    'parse-patch-save',
    'parse-patch-discard',
    'tag-candidate-change',
    'scene-exclude-change',
    'resolution-save',
    'verification-run',
    'confirm-correction',
  ]) {
    assert.equal(isCorrectionMutation([key, '41'], '41'), true, key);
    assert.equal(isCorrectionMutation([key, '42'], '41'), false, key);
  }
  assert.equal(isCorrectionMutation(['claim-release', '41'], '41'), false);
  assert.equal(isCorrectionMutation(undefined, '41'), false);
});

test('확인창 Tab 은 안의 버튼끼리 양 끝에서 돈다', () => {
  assert.equal(nextTrappedFocusIndex(0, 2, false), 1);
  assert.equal(nextTrappedFocusIndex(1, 2, false), 0);
  assert.equal(nextTrappedFocusIndex(0, 2, true), 1);
  assert.equal(nextTrappedFocusIndex(1, 2, true), 0);
  // 포커스가 창 밖에 있으면 방향에 맞는 끝으로 들여온다.
  assert.equal(nextTrappedFocusIndex(-1, 2, false), 0);
  assert.equal(nextTrappedFocusIndex(-1, 2, true), 1);
  assert.equal(nextTrappedFocusIndex(0, 1, false), 0);
  assert.equal(nextTrappedFocusIndex(-1, 0, false), -1);
});
