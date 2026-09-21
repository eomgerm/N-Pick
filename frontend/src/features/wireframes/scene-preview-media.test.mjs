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

const { formatMediaTime, formatSceneDuration } = await import('./scene-preview-media.ts');

test('구간 길이를 소수점 없는 초로 표시한다', () => {
  assert.equal(formatSceneDuration(12.345), '12초');
  assert.equal(formatSceneDuration(7), '7초');
});

test('1초 미만 구간은 0초로 표시한다', () => {
  assert.equal(formatSceneDuration(0.4), '0초');
  assert.equal(formatSceneDuration(0.999), '0초');
  assert.equal(formatSceneDuration(0), '0초');
});

test('음수 구간 길이는 0초로 묶는다', () => {
  assert.equal(formatSceneDuration(-3.2), '0초');
});

test('1시간 이상 구간도 초 단위 표기를 유지한다', () => {
  assert.equal(formatSceneDuration(3600), '3600초');
  assert.equal(formatSceneDuration(3700.9), '3700초');
});

test('시작·종료 시각과 같은 절삭 규칙을 쓴다', () => {
  for (const seconds of [0.999, 59.9, 3599.4, 3600.6]) {
    assert.equal(formatSceneDuration(seconds), `${Math.floor(seconds)}초`);
    assert.equal(formatMediaTime(seconds), formatMediaTime(Math.floor(seconds)));
  }
});

test('구간 시각의 소수 초를 버리고 경계값을 표기한다', () => {
  assert.equal(formatMediaTime(12.345), '00:12');
  assert.equal(formatMediaTime(0), '00:00');
  assert.equal(formatMediaTime(-1.5), '00:00');
  assert.equal(formatMediaTime(59.999), '00:59');
  assert.equal(formatMediaTime(3600), '1:00:00');
  assert.equal(formatMediaTime(3900.75), '1:05:00');
});
