import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { registerHooks } from 'node:module';
import test from 'node:test';

import ts from 'typescript';

registerHooks({
  resolve(specifier, context, nextResolve) {
    const stubs = {
      'next/navigation':
        'export function redirect(location) { throw Object.assign(new Error("redirect"), { location }); }',
      '@/features/wireframes/landing-shell': 'export function LandingShell() { return "landing"; }',
      '@/lib/auth/server':
        'export async function currentMember() { return globalThis.testMember ?? null; }',
    };
    if (specifier in stubs) {
      return {
        url: `data:text/javascript,${encodeURIComponent(stubs[specifier])}`,
        shortCircuit: true,
      };
    }
    return nextResolve(
      specifier.startsWith('@/lib/')
        ? new URL(`../../lib/${specifier.slice('@/lib/'.length)}.ts`, import.meta.url).href
        : specifier,
      context,
    );
  },
  load(url, context, nextLoad) {
    if (url === new URL('./page.tsx', import.meta.url).href) {
      return {
        format: 'module',
        source: ts.transpileModule(readFileSync(new URL(url), 'utf8'), {
          compilerOptions: { module: ts.ModuleKind.ESNext, jsx: ts.JsxEmit.ReactJSX },
        }).outputText,
        shortCircuit: true,
      };
    }
    return nextLoad(url, context);
  },
});

const { default: LandingPage } = await import('./page.tsx');

test('로그인하지 않은 사용자는 역할 선택 랜딩을 본다', async () => {
  globalThis.testMember = null;
  const page = await LandingPage();
  assert.equal(page.type.name, 'LandingShell');
});

test('로그인한 사용자는 실제 역할의 기본 화면으로 이동한다', async () => {
  for (const [role, location] of [
    ['EDITOR', '/search'],
    ['REVIEWER', '/review'],
  ]) {
    globalThis.testMember = { memberId: '1', loginId: 'tester', role };
    await assert.rejects(LandingPage(), { location });
  }
});
