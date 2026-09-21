import assert from 'node:assert/strict';
import test from 'node:test';
import { ApiClientError, readUserMessage } from './error.ts';

test('한국어가 섞인 진단 문자열도 사용자 메시지로 사용하지 않는다', () => {
  for (const message of [
    'validation_error',
    '처리 실패: validation_error',
    '오류 COMM_400_001 발생',
    '요청 requestId: abc',
    '자막 오류: STAGE_TIMEOUT',
    '입력 오류: /srv/private',
    '오류 <div>상세</div>',
  ]) {
    assert.equal(readUserMessage(message), undefined);
    assert.doesNotMatch(
      new ApiClientError('api', 400, { message }).message,
      /validation_error|COMM_400_001|requestId|STAGE_TIMEOUT|private|<div>/,
    );
  }
  assert.equal(
    readUserMessage('MP4 또는 MOV 파일을 선택해 주세요.'),
    'MP4 또는 MOV 파일을 선택해 주세요.',
  );
});

test('메시지 속 요청 ID는 숨겨도 오류 객체의 진단 필드는 유지한다', () => {
  const error = new ApiClientError('api', 400, {
    code: 'COMM_400_001',
    requestId: 'req-123',
    message: '실패한 요청: req-123',
  });
  assert.doesNotMatch(error.message, /req-123/);
  assert.equal(error.requestId, 'req-123');
  assert.equal(error.code, 'COMM_400_001');
});
