import assert from 'node:assert/strict';
import test from 'node:test';

import {
  MAX_VIDEO_SIZE_BYTES,
  MAX_SUBTITLE_SIZE_BYTES,
  validateScriptFiles,
  validateSubtitleFiles,
  validateVideoFiles,
} from './registration-files.ts';

const mp4 = new File(['demo video bytes'], '뉴스.mp4', { type: 'video/mp4' });
const mov = new File(['demo video bytes'], '뉴스.MOV', { type: 'video/quicktime' });
const script = new File(['뉴스 대본'], '대본.txt', { type: 'text/plain' });
const subtitle = new File(['WEBVTT\n\n00:00.000 --> 00:01.000\n뉴스'], '자막.vtt');

test('영상은 정확히 한 개의 MP4 또는 MOV만 허용한다', () => {
  assert.notEqual(validateVideoFiles([]), '');
  assert.notEqual(validateVideoFiles([mp4, mov]), '');
  assert.notEqual(validateVideoFiles([new File([], 'empty.mp4', { type: 'video/mp4' })]), '');
  assert.notEqual(validateVideoFiles([new File(['video'], '뉴스.mxf', { type: 'video/mxf' })]), '');
  assert.notEqual(validateVideoFiles([new File(['text'], '뉴스.mp4', { type: 'text/plain' })]), '');
  assert.equal(validateVideoFiles([mp4]), '');
  assert.equal(validateVideoFiles([mov]), '');
  assert.equal(validateVideoFiles([new File(['video'], '뉴스.mov')]), '');
  assert.equal(
    validateVideoFiles([new File(['video'], '뉴스.mov', { type: 'video/x-quicktime' })]),
    '',
  );
});

test('영상은 10 GiB까지 허용하고 이를 넘으면 거절한다', () => {
  assert.equal(
    validateVideoFiles([{ name: 'limit.mp4', size: MAX_VIDEO_SIZE_BYTES, type: 'video/mp4' }]),
    '',
  );
  assert.notEqual(
    validateVideoFiles([
      { name: 'too-large.mp4', size: MAX_VIDEO_SIZE_BYTES + 1, type: 'video/mp4' },
    ]),
    '',
  );
});

test('자막은 SRT/VTT 한 개, 일반 대본은 TXT 한 개로 분리해 검증한다', () => {
  assert.equal(validateSubtitleFiles([]), '');
  assert.equal(validateSubtitleFiles([subtitle]), '');
  assert.equal(validateSubtitleFiles([new File(['caption'], '자막.SRT')]), '');
  assert.notEqual(validateSubtitleFiles([subtitle, subtitle]), '');
  assert.notEqual(validateSubtitleFiles([script]), '');
  assert.notEqual(validateSubtitleFiles([new File([], '빈자막.srt')]), '');

  assert.equal(validateScriptFiles([]), '');
  assert.equal(validateScriptFiles([script]), '');
  assert.notEqual(validateScriptFiles([script, script]), '');
  assert.notEqual(validateScriptFiles([subtitle]), '');
  assert.notEqual(validateScriptFiles([new File([], '빈대본.txt')]), '');
  // 빈 파일과 UTF-8 디코드 실패가 같은 문구를 쓰면 무엇을 고쳐야 할지 알 수 없다 (S15P21A501-258).
  assert.match(validateScriptFiles([new File([], '빈대본.txt')]), /비어/);
  assert.match(validateSubtitleFiles([new File([], '빈자막.srt')]), /비어/);
});

test('자막은 10 MiB 경계를 검사하고 승인 JSON 형식은 서버 내용 검증으로 전달한다', () => {
  for (const name of ['자막.srt', '자막.VTT', '자막.json']) {
    assert.equal(validateSubtitleFiles([{ name, size: MAX_SUBTITLE_SIZE_BYTES, type: '' }]), '');
    assert.match(
      validateSubtitleFiles([{ name, size: MAX_SUBTITLE_SIZE_BYTES + 1, type: '' }]),
      /10 MiB/,
    );
  }
  assert.notEqual(
    validateSubtitleFiles([{ name: '자막.srt.exe', size: 1, type: 'text/plain' }]),
    '',
  );
});
