import assert from 'node:assert/strict';
import test from 'node:test';

import {
  mergeAttachments,
  validateAttachmentFiles,
  validateVideoFiles,
} from './registration-files.ts';

const video = new File(['demo video bytes'], '뉴스.mp4', { type: 'video/mp4' });
const script = new File(['뉴스 대본'], '대본.txt', { type: 'text/plain', lastModified: 1 });
const caption = new File(['WEBVTT\n\n00:00.000 --> 00:01.000\n뉴스'], '자막.vtt', {
  lastModified: 2,
});

test('영상은 한 개만 허용하고 빈 파일과 영상이 아닌 파일은 거절한다', () => {
  assert.notEqual(validateVideoFiles([]), '');
  assert.notEqual(validateVideoFiles([video, video]), '');
  assert.notEqual(validateVideoFiles([new File([], 'empty.mp4')]), '');
  assert.notEqual(validateVideoFiles([script]), '');
  assert.equal(validateVideoFiles([video]), '');
});

test('브라우저가 MIME을 생략한 영상과 대문자 확장자를 선택할 수 있다', () => {
  assert.equal(validateVideoFiles([new File(['video'], '뉴스.MOV')]), '');
  assert.equal(
    validateVideoFiles([new File(['video'], '뉴스.mxf', { type: 'application/octet-stream' })]),
    '',
  );
});

test('선택 첨부는 TXT·SRT·VTT를 허용하고 혼합 선택과 빈 파일은 검증한다', () => {
  assert.equal(validateAttachmentFiles([]), '');
  assert.equal(validateAttachmentFiles([script, caption, new File(['caption'], '자막.SRT')]), '');
  assert.notEqual(validateAttachmentFiles([script, video]), '');
  assert.notEqual(validateAttachmentFiles([new File([], '빈대본.txt')]), '');
});

test('첨부 재선택은 중복을 추가하지 않고 기존 목록과 다른 파일은 보존한다', () => {
  const current = [script];
  const changedScript = new File(['수정된 대본'], '대본.txt', { lastModified: 3 });
  assert.deepEqual(mergeAttachments(current, [script, caption, caption, changedScript]), [
    script,
    caption,
    changedScript,
  ]);
  assert.deepEqual(current, [script]);
});
