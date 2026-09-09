import assert from 'node:assert/strict';
import { registerHooks } from 'node:module';
import test from 'node:test';

registerHooks({
  resolve(specifier, context, nextResolve) {
    return nextResolve(
      specifier.startsWith('@/')
        ? new URL(`../../${specifier.slice('@/'.length)}.ts`, import.meta.url).href
        : specifier,
      context,
    );
  },
});

const { ApiClientError } = await import('../../lib/api/error.ts');
const { countSnapshotEntries, displayClipTitle, getClaimRecovery, getFilterFacts, uniqueTagNames } =
  await import('./review-inquiry-view.ts');

test('제목 없는 영상 fallback과 중복 없는 태그명만 화면 값으로 만든다', () => {
  assert.equal(displayClipTitle(null), '제목 없는 영상');
  assert.equal(displayClipTitle('   '), '제목 없는 영상');
  assert.equal(displayClipTitle(' 저녁 뉴스 '), '저녁 뉴스');
  assert.deepEqual(
    uniqueTagNames([
      { tagName: '서울역', source: 'ocr' },
      { tagName: ' 서울역 ', source: 'asr' },
      { tagName: '귀성길', source: 'user_input' },
      { tagName: ' ' },
    ]),
    ['서울역', '귀성길'],
  );
});

test('명시 필터는 JSON 원문 대신 읽을 수 있는 항목으로 변환한다', () => {
  assert.deepEqual(getFilterFacts('{"broadcast_date":"2026-10-29","location":"서울"}'), [
    { label: '방송일', value: '2026-10-29' },
    { label: '장소', value: '서울' },
  ]);
  assert.deepEqual(getFilterFacts('{}'), []);
  assert.equal(getFilterFacts('not-json'), null);
});

test('과거 결과·규칙·제외 기록은 원문 노출 없이 건수만 센다', () => {
  assert.equal(countSnapshotEntries('[{"rule_id":1},{"rule_id":2}]'), 2);
  assert.equal(countSnapshotEntries('{"score":1,"match":{},"guard":{}}'), 3);
  assert.equal(countSnapshotEntries(null), 0);
  assert.equal(countSnapshotEntries('not-json'), null);
});

test('선점 충돌·권한·연결 실패마다 성공 대신 다음 행동을 안내한다', () => {
  assert.deepEqual(
    getClaimRecovery(
      new ApiClientError('api', 409, {
        code: 'FEEDBACK_409_001',
        message: '이미 검수가 시작된 문의입니다.',
      }),
    ).action,
    'refresh',
  );
  assert.equal(getClaimRecovery(new ApiClientError('http', 403)).action, 'back');
  assert.equal(getClaimRecovery(new ApiClientError('network', 0)).action, 'retry');
  assert.equal(getClaimRecovery(new Error('unknown')).action, 'refresh');
});
