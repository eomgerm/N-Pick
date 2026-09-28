import assert from 'node:assert/strict';
import { registerHooks } from 'node:module';
import test from 'node:test';

registerHooks({
  resolve(specifier, context, nextResolve) {
    return nextResolve(
      specifier.startsWith('@/')
        ? new URL(`../../${specifier.slice(2)}.ts`, import.meta.url).href
        : specifier,
      context,
    );
  },
});

const { formatVideoDetails } = await import('./registration-files.ts');

const size = 1.2 * 1024 * 1024 * 1024;

test('메타데이터를 읽기 전에는 용량만 표시한다', () => {
  assert.equal(formatVideoDetails({ size }), '1.2 GB');
});

test('길이와 해상도를 읽으면 용량 뒤에 이어 표시한다', () => {
  assert.equal(
    formatVideoDetails({ size, duration: 201.7, width: 1920, height: 1080 }),
    '1.2 GB · 03:21 · 1920×1080',
  );
});

test('1시간 이상 영상은 시간 단위까지 표시한다', () => {
  assert.equal(formatVideoDetails({ size, duration: 3725 }), '1.2 GB · 1:02:05');
});

test('브라우저가 길이나 해상도를 알려주지 않으면 그 항목을 뺀다', () => {
  // 재생 불가 코덱이나 스트리밍 컨테이너는 duration 을 NaN·Infinity 로, 크기를 0 으로 준다.
  assert.equal(formatVideoDetails({ size, duration: Number.NaN, width: 0, height: 0 }), '1.2 GB');
  assert.equal(
    formatVideoDetails({ size, duration: Infinity, width: 640, height: 360 }),
    '1.2 GB · 640×360',
  );
});
