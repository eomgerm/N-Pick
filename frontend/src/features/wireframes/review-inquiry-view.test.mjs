import assert from 'node:assert/strict';
import { registerHooks } from 'node:module';
import test from 'node:test';

registerHooks({
  resolve(specifier, context, nextResolve) {
    if (specifier.endsWith('.module.css')) {
      return { url: 'data:text/javascript,export default {}', shortCircuit: true };
    }
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

test('당시 결과 표시값은 검색 화면과 같은 한국어 어휘와 시간 형식으로 표시한다', () => {
  const snapshot = JSON.stringify({
    guard: { exclusion_reason: null },
    match: {
      matched_keywords: ['국회'],
      match_evidence: [
        {
          field: 'caption',
          value: '국회 로고가 보이는 배경',
          source: 'scene_caption',
          verification_status: 'unverified',
        },
      ],
    },
    score: { base_score: 1.2 },
    display: {
      display_name: '당시 KBC 뉴스9',
      scene_type: '국회 현장',
      shot_type: 'interview',
      broadcast_date: { value: '2022-04-02', verification_status: 'unverified' },
      filmed_date: { value: null, verification_status: 'unknown' },
      scene_description: '국회 로고가 보이는 배경 앞에서 마스크를 쓴 남성',
      start_time_ms: 0,
      end_time_ms: 9610,
      resolver_output: '/srv/private',
    },
  });

  assert.deepEqual(getSnapshotFacts(snapshot), [
    { label: '일치 근거 · 일치 근거 1 · 항목', value: '장면 설명' },
    { label: '일치 근거 · 일치 근거 1 · 값', value: '국회 로고가 보이는 배경' },
    { label: '일치 근거 · 일치 근거 1 · 출처', value: 'AI 장면 설명' },
    { label: '일치 근거 · 일치 근거 1 · 검증 상태', value: '자동 인식' },
    { label: '클립 제목', value: '당시 KBC 뉴스9' },
    { label: '장면 유형', value: '국회 현장' },
    { label: '샷 유형', value: '인터뷰' },
    { label: '방송일', value: '2022-04-02 · 자동 인식' },
    { label: '촬영일', value: '미상 · 미상' },
    { label: '장면 설명', value: '국회 로고가 보이는 배경 앞에서 마스크를 쓴 남성' },
    { label: '구간', value: '00:00 – 00:09' },
  ]);
});

test('표시값 전용 렌더러는 알 수 없는 값과 임의 키를 원문으로 노출하지 않는다', () => {
  assert.deepEqual(
    getSnapshotFacts(
      JSON.stringify({
        display: {
          display_name: '/srv/private',
          scene_type: '<script>alert(1)</script>',
          scene_description: 'Bearer secret',
          shot_type: 'legacy',
          broadcast_date: { value: null, verification_status: 'legacy' },
          private_path: '/srv/private',
        },
      }),
    ),
    [
      { label: '샷 유형', value: '정보 없음' },
      { label: '방송일', value: '미상 · 정보 없음' },
    ],
  );
  assert.equal(getSnapshotFacts('{"display":{"private_path":"/srv/private"}}'), null);
});

test('근거 출처와 검수자 판단은 계약 어휘 전체를 한국어로 옮기고 어휘 밖 값은 감춘다', () => {
  assert.deepEqual(
    ['user_input', 'original_metadata', 'cc', 'ocr', 'asr', 'vlm', 'rule', 'reviewer_feedback'].map(
      evidenceLabel,
    ),
    [
      '사용자 입력',
      '영상 원본 정보',
      '방송 자막',
      '화면 글자 인식',
      '음성 인식',
      'AI 화면 분석',
      '텍스트 자동 추출',
      '아카이빙 팀 피드백',
    ],
  );
  assert.equal(evidenceLabel('rejected'), '반려됨');
  assert.equal(evidenceLabel('withdrawn'), '개입 해제');
  assert.equal(evidenceLabel('CLIP'), '클립');
  assert.equal(evidenceLabel('legacy_source'), '정보 없음');
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
