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
const { uploadPhaseView } = await import('./registration-upload-phase.ts');

test('전송 진행 전에는 0%로 전송 준비 단계를 보여 준다', () => {
  assert.deepEqual(uploadPhaseView(null, 2048), {
    phase: 'sending',
    percent: 0,
    detail: '0% · 0 B / 2.0 KB',
  });
});

test('전송 중에는 보낸 양과 전체 크기로 퍼센트를 내림해 보여 준다', () => {
  const view = uploadPhaseView({ loaded: 1024 * 1024 * 210, total: 1024 * 1024 * 500 }, 1);
  assert.equal(view.phase, 'sending');
  assert.equal(view.percent, 42);
  assert.equal(view.detail, '42% · 210.0 MB / 500.0 MB');
});

test('전체 크기를 모르면 파일 크기를 분모로 쓴다', () => {
  const view = uploadPhaseView({ loaded: 512, total: 0 }, 2048);
  assert.equal(view.percent, 25);
});

test('끝까지 보내기 전에는 100%로 표시하지 않는다', () => {
  const view = uploadPhaseView({ loaded: 999, total: 1000 }, 1000);
  assert.equal(view.phase, 'sending');
  assert.equal(view.percent, 99);
});

test('전송이 끝나면 서버 확인 단계로 넘어간다', () => {
  assert.deepEqual(uploadPhaseView({ loaded: 1000, total: 1000 }, 1000), {
    phase: 'verifying',
    percent: 100,
    detail: '영상 형식과 중복 여부를 확인하고 있어요.',
  });
});
