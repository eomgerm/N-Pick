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
const { getSnapshotFacts, evidenceLabel } = await import('./review-inquiry-view.ts');
const { selectInquiryPage, selectInquiryStatus, normalizeInquiryPage } =
  await import('./review-inquiry-view.ts');

test('목록 URL은 지원 상태와 양의 정수 페이지만 선택한다', () => {
  for (const status of ['open', 'reviewing', 'closed']) {
    assert.equal(selectInquiryStatus(status), status);
  }
  for (const value of [null, '', 'all', 'OPEN', 'unknown']) {
    assert.equal(selectInquiryStatus(value), undefined);
  }
  for (const value of [null, '', '0', '-1', '1.5', 'NaN', 'Infinity', '9007199254740992']) {
    assert.equal(selectInquiryPage(value), 1);
  }
  assert.equal(selectInquiryPage('2'), 2);
  assert.equal(selectInquiryPage('999'), 999);
});

test('서버 페이지 수로 범위 초과와 빈 목록을 보정하고 유효 페이지를 유지한다', () => {
  assert.equal(normalizeInquiryPage(99, 3), 3);
  assert.equal(normalizeInquiryPage(99, 0), 1);
  assert.equal(normalizeInquiryPage(1, 0), 1);
  assert.equal(normalizeInquiryPage(2, 3), 2);
  assert.equal(normalizeInquiryPage(3, 3), 3);
});

test('과거 근거와 적용 규칙의 내용을 보존하고 내부 필드는 노출하지 않는다', () => {
  const snapshot = JSON.stringify({
    score: 0.8,
    evidence: [{ tag_name: '서울역', reason: '화면 문자 일치' }],
    storage_key: '/srv/private',
    resolverOutput: 'secret',
  });
  assert.deepEqual(getSnapshotFacts(snapshot), [
    { label: '점수', value: '0.8' },
    { label: '근거 1 · 태그명', value: '서울역' },
    { label: '근거 1 · 이유', value: '화면 문자 일치' },
  ]);
  assert.deepEqual(
    getSnapshotFacts('[{"rule_id":"9","status":"applied","reason":"장소 조건 일치"}]'),
    [
      { label: '1 · 규칙 ID', value: '9' },
      { label: '1 · 처리 상태', value: 'applied' },
      { label: '1 · 이유', value: '장소 조건 일치' },
    ],
  );
  assert.equal(getSnapshotFacts('{"reason":"/srv/private"}'), null);
  assert.equal(getSnapshotFacts('invalid'), null);
  assert.equal(getSnapshotFacts('{"unknown":"secret"}'), null);
  assert.deepEqual(getSnapshotFacts('[]'), []);
  assert.equal(evidenceLabel('SCENE'), '장면');
  assert.equal(evidenceLabel('verified'), '검증됨');
  assert.equal(evidenceLabel(null), '기록 없음');
});

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
