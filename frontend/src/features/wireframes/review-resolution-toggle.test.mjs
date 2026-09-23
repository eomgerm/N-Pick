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

const { isCorrectionMode, resolutionModeFromChecked, resolutionModeFromValue } =
  await import('./review-resolution-toggle-mode.ts');

test('resolutionModeFromChecked maps switch state to resolution string', () => {
  assert.equal(resolutionModeFromChecked(true), 'correction');
  assert.equal(resolutionModeFromChecked(false), 'no_action');
});

test('isCorrectionMode reports which sections should be visible', () => {
  assert.equal(isCorrectionMode('correction'), true);
  assert.equal(isCorrectionMode('no_action'), false);
});

test('resolutionModeFromValue derives initial toggle state from a loaded inquiry', () => {
  assert.equal(resolutionModeFromValue(null), 'no_action');
  assert.equal(resolutionModeFromValue('no_action'), 'no_action');
  assert.equal(resolutionModeFromValue('correction'), 'correction');
  // legacy pre-migration values still map to the correction side of the toggle
  assert.equal(resolutionModeFromValue('tag_correction'), 'correction');
  assert.equal(resolutionModeFromValue('patch_parse'), 'correction');
  assert.equal(resolutionModeFromValue('exclude_scene'), 'correction');
  assert.equal(resolutionModeFromValue('deferred'), 'no_action');
});
