import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';

import {
  validateVideoContent,
  validateSubtitleContent,
  validateScriptContent,
} from './registration-files.ts';

function atom(type, payload = Buffer.alloc(4)) {
  const header = Buffer.alloc(8);
  header.writeUInt32BE(8 + payload.length);
  header.write(type, 4);
  return Buffer.concat([header, payload]);
}

test('확장자와 MIME을 위장한 텍스트·이미지·잘린 영상 헤더를 거부한다', async () => {
  for (const bytes of [
    Buffer.from('일반 텍스트'),
    Buffer.from('GIF89a'),
    atom('ftyp', Buffer.from('avif\0\0\0\0avif')),
    atom('ftyp', Buffer.from('isom\0\0\0\0isom')),
    Buffer.from([0, 0, 0, 80, 102, 116, 121, 112, 105, 115, 111, 109]),
  ]) {
    assert.notEqual(
      await validateVideoContent(new File([bytes], '위장.mp4', { type: 'video/mp4' })),
      '',
    );
  }
});

test('실제 MP4와 ftyp 유무가 다른 MOV의 컨테이너 헤더를 허용한다', async () => {
  const mp4 = await readFile(new URL('../../../e2e/preview-fixture.mp4', import.meta.url));
  assert.equal(await validateVideoContent(new File([mp4], '영상.mp4')), '');
  const movie = Buffer.concat([atom('wide'), atom('mdat'), atom('moov')]);
  assert.equal(await validateVideoContent(new File([movie], '영상.mov')), '');
  const ftyp = atom('ftyp', Buffer.from('qt  \0\0\0\0qt  '));
  assert.equal(await validateVideoContent(new File([ftyp, movie], '영상.MOV')), '');
});

test('자막은 UTF-8 내용과 확장자에 맞는 SRT·VTT·승인 JSON 구조를 검사한다', async () => {
  const valid = {
    srt: '\uFEFF1\r\n00:00:00,000 --> 00:00:01,000\r\n뉴스\r\n',
    vtt: 'WEBVTT\n\nNOTE 설명\n\n자막 ID\n00:00.000 --> 00:01.000 align:start\n뉴스',
    json: JSON.stringify({
      schemaVersion: 'npick.subtitle/v1',
      segments: [{ s: 0, e: 1000, t: '뉴스' }],
    }),
  };
  for (const [extension, content] of Object.entries(valid)) {
    assert.equal(await validateSubtitleContent(new File([content], `자막.${extension}`)), '');
    assert.notEqual(
      await validateSubtitleContent(new File(['대본 텍스트'], `위장.${extension}`)),
      '',
    );
  }
  for (const [name, content] of [
    ['위장.srt', valid.vtt],
    ['위장.vtt', valid.srt],
    ['잘린.vtt', 'WEBVTT\n\n'],
    ['잘못된.json', '{}'],
    ['잘못된.json', '{"schemaVersion":"npick.subtitle/v1","segments":[]}'],
    ['잘못된.srt', '1\n00:00:01,000 --> 00:00:00,000\n뉴스'],
    ['바이너리.srt', new Uint8Array([0xff, 0xfe, 0])],
  ])
    assert.notEqual(await validateSubtitleContent(new File([content], name)), '');
});

test('TXT는 UTF-8 텍스트를 허용하고 이름을 바꾼 바이너리·PDF·잘못된 인코딩을 거부한다', async () => {
  assert.equal(
    await validateScriptContent(new File(['\uFEFF한글 대본\n\t취재 내용 😀'], '대본.txt')),
    '',
  );
  for (const content of [
    new Uint8Array([0x50, 0x4b, 0x03, 0x04, 0, 0]),
    new Uint8Array([0xff, 0xfe, 0x41, 0]),
    '%PDF-1.7\n문서',
    '{\\rtf1 문서}',
    '내용\0바이너리',
    '   \n\t',
  ])
    assert.notEqual(await validateScriptContent(new File([content], '위장.txt')), '');
});

test('파일 읽기에 실패하면 한국어 오류를 반환한다', async () => {
  const file = new File(['news'], '영상.mp4');
  file.slice = () => {
    throw new Error('unreadable');
  };
  assert.notEqual(await validateVideoContent(file), '');
});

test('자막 사이 여러 빈 줄과 TXT 청크 경계의 UTF-8 문자를 보존한다', async () => {
  const cue = '1\n00:00:00,000 --> 00:00:01,000\n뉴스';
  assert.equal(await validateSubtitleContent(new File([`\n${cue}\n\n\n${cue}`], '자막.srt')), '');
  assert.equal(
    await validateSubtitleContent(
      new File([`WEBVTT\n\n\n00:00.000 --> 00:01.000\n뉴스`], '자막.vtt'),
    ),
    '',
  );
  assert.equal(
    await validateScriptContent(new File(['a'.repeat(65535), '한글😀'], '대본.txt')),
    '',
  );
  assert.notEqual(
    await validateScriptContent(new File(['a'.repeat(65535), new Uint8Array([0xed])], '대본.txt')),
    '',
  );
});
