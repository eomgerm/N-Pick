import assert from 'node:assert/strict';
import { registerHooks } from 'node:module';
import test from 'node:test';
import { createElement } from 'react';
import { renderToStaticMarkup } from 'react-dom/server';

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
const { CorrectionEditorArea } = await import('./review-correction-editor-area.ts');

test('resolutionModeFromChecked maps switch state to resolution string', () => {
  assert.equal(resolutionModeFromChecked(true), 'correction');
  assert.equal(resolutionModeFromChecked(false), 'no_action');
});

test('isCorrectionMode reports which sections should be visible', () => {
  assert.equal(isCorrectionMode('correction'), true);
  assert.equal(isCorrectionMode('no_action'), false);
});

test('switch transition immediately changes the rendered correction editor area', () => {
  let mode = resolutionModeFromChecked(false);
  const render = () =>
    renderToStaticMarkup(
      createElement(
        CorrectionEditorArea,
        { mode },
        createElement('div', { 'data-testid': 'correction-editor' }, '교정 편집'),
      ),
    );

  assert.doesNotMatch(render(), /data-testid="correction-editor"/);
  mode = resolutionModeFromChecked(true);
  assert.match(render(), /data-testid="correction-editor"/);
  mode = resolutionModeFromChecked(false);
  assert.doesNotMatch(render(), /data-testid="correction-editor"/);
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
