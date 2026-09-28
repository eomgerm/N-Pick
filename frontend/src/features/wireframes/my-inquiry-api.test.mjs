import assert from 'node:assert/strict';
import { registerHooks } from 'node:module';
import test from 'node:test';
import { ApiClientError } from '../../lib/api/error.ts';

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

const { getMyInquiries, getMyInquiry, parseMyInquiryPage, parseMyInquiryDetail } =
  await import('./my-inquiry-api.ts');

const item = {
  feedback_id: '9007199254740993',
  search_execution_id: '9701',
  search_result_id: '9802',
  created_at: '2026-09-11T03:00:00Z',
  updated_at: '2026-09-11T03:10:00Z',
  query_text: '서울역 귀성길',
  comment: null,
  status: 'OPEN',
  resolution: null,
  scene: {
    scene_id: '9302',
    clip_id: '9101',
    clip_title: null,
    start_time_ms: 49000,
    end_time_ms: 55000,
  },
};
const page = {
  items: [item],
  page: 0,
  size: 10,
  total_elements: 1,
  total_pages: 1,
  has_next: false,
};
const detail = {
  ...item,
  explicit_filters: {},
  resolution_note: null,
  review_started_at: null,
  closed_at: null,
  snapshot_status: 'unavailable',
  result_snapshot: null,
};
const availableDetail = {
  ...detail,
  snapshot_status: 'available',
  result_snapshot: {
    search_result_id: '9802',
    scene_id: '9302',
    rank: 2,
    explain: { display: { display_name: 'KBC 뉴스9', scene_description: '서울역 인파' }, score: 2 },
  },
};

test('내 문의의 큰 문자열 ID와 null은 보존하고 서버 상태를 변환한다', () => {
  const result = parseMyInquiryPage(page);
  assert.equal(result.items[0].feedbackId, item.feedback_id);
  assert.equal(result.items[0].status, 'open');
  assert.equal(result.items[0].comment, null);
  assert.equal(result.items[0].scene.clipTitle, null);
  assert.equal(result.items[0].resolution, null);
  assert.equal(result.totalElements, 1);
  assert.equal(result.hasNext, false);
  for (const [status, expected] of [
    ['REVIEWING', 'reviewing'],
    ['CLOSED', 'closed'],
  ]) {
    assert.equal(
      parseMyInquiryDetail({ ...detail, status, resolution: 'deferred' }).status,
      expected,
    );
  }
});

test('기록 없음과 빈 목록을 성공으로 읽되 예시 값으로 채우지 않는다', () => {
  const result = parseMyInquiryDetail(detail);
  assert.equal(result.resultSnapshot, null);
  assert.equal(result.snapshotStatus, 'unavailable');
  assert.equal(result.resolutionNote, null);
  assert.deepEqual(result.explicitFilters, {});
  assert.deepEqual(
    parseMyInquiryPage({ ...page, items: [], total_elements: 0, total_pages: 0 }).items,
    [],
  );
});

test('available 스냅샷은 result_snapshot의 ID·순위·explain을 보존한다', () => {
  const result = parseMyInquiryDetail(availableDetail);
  assert.equal(result.snapshotStatus, 'available');
  assert.equal(result.resultSnapshot.searchResultId, '9802');
  assert.equal(result.resultSnapshot.sceneId, '9302');
  assert.equal(result.resultSnapshot.rank, 2);
  assert.equal(result.resultSnapshot.explain.display.display_name, 'KBC 뉴스9');
});

test('display_name이 null(제목 없는 영상)·빈 문자열이어도 available이며 원값을 보존한다', () => {
  // 생산자(-59)는 nullable clip.title을 그대로 기록한다. null은 유효한 과거 값이므로 오류로 바꾸지 않고,
  // 표시용 대체 문구(제목 없는 영상)는 표현 계층이 정한다.
  for (const displayName of [null, '']) {
    const raw = {
      ...availableDetail.result_snapshot,
      explain: { display: { display_name: displayName } },
    };
    const result = parseMyInquiryDetail({ ...availableDetail, result_snapshot: raw });
    assert.equal(result.snapshotStatus, 'available');
    assert.equal(result.resultSnapshot.explain.display.display_name, displayName);
  }
});

test('알 수 없는 snapshot_status와 available의 잘못된 result_snapshot은 응답 오류로 처리한다', () => {
  assert.throws(
    () => parseMyInquiryDetail({ ...detail, snapshot_status: 'partial' }),
    ApiClientError,
  );
  assert.throws(
    () => parseMyInquiryDetail({ ...availableDetail, result_snapshot: null }),
    ApiClientError,
  );
  for (const badSnap of [
    { ...availableDetail.result_snapshot, explain: undefined },
    { ...availableDetail.result_snapshot, search_result_id: 42 },
    { ...availableDetail.result_snapshot, scene_id: '0' },
    { ...availableDetail.result_snapshot, rank: 0 },
    { ...availableDetail.result_snapshot, explain: null },
    // BE 불변식 대칭: available인데 display_name이 생산자 타입(문자열·null) 이탈이거나 display 블록·키가 없으면 계약 이탈
    { ...availableDetail.result_snapshot, explain: { display: { display_name: 42 } } },
    { ...availableDetail.result_snapshot, explain: { display: {} } },
    { ...availableDetail.result_snapshot, explain: { score: 2 } },
  ])
    assert.throws(
      () => parseMyInquiryDetail({ ...availableDetail, result_snapshot: badSnap }),
      ApiClientError,
    );
});

test('숫자 ID, 잘못된 상태·구간·페이지·누락 필드는 응답 오류로 처리한다', () => {
  for (const invalid of [
    { ...item, feedback_id: 42 },
    { ...item, status: 'PENDING' },
    { ...item, resolution: 'unknown' },
    { ...item, comment: undefined },
    { ...item, created_at: 'bad date' },
    { ...item, scene: { ...item.scene, end_time_ms: item.scene.start_time_ms } },
  ])
    assert.throws(() => parseMyInquiryPage({ ...page, items: [invalid] }), ApiClientError);
  for (const invalid of [
    { ...page, has_next: true },
    { ...page, total_pages: 2 },
    { ...page, page: -1 },
    { ...page, size: 0 },
    { ...page, items: null },
  ])
    assert.throws(() => parseMyInquiryPage(invalid), ApiClientError);
  assert.throws(() => parseMyInquiryDetail({ ...detail, explicit_filters: [] }), ApiClientError);
  assert.throws(() => parseMyInquiryDetail({ ...detail, result_snapshot: {} }), ApiClientError);
});

test('목록·상세는 본인 조회 endpoint에 페이지와 취소 signal만 전달한다', async (context) => {
  const fetch = context.mock.method(globalThis, 'fetch', async (url) =>
    Response.json({
      isSuccess: true,
      code: 'COMM_200',
      message: '성공',
      data: url.includes('?') ? { ...page, page: 1, total_elements: 11, total_pages: 2 } : detail,
    }),
  );
  const signal = new AbortController().signal;
  await getMyInquiries(1, signal);
  await getMyInquiry(item.feedback_id, signal);
  const [listUrl, init] = fetch.mock.calls[0].arguments;
  assert.equal(listUrl, 'http://127.0.0.1:8080/api/v1/inquiries?page=1&size=10');
  assert.equal(init.credentials, 'include');
  assert.strictEqual(init.signal, signal);
  assert.equal(
    fetch.mock.calls[1].arguments[0],
    `http://127.0.0.1:8080/api/v1/inquiries/${item.feedback_id}`,
  );
});

test('다른 문의 ID 응답과 404 오류를 성공 데이터로 사용하지 않는다', async (context) => {
  context.mock.method(globalThis, 'fetch', async () =>
    Response.json({
      isSuccess: true,
      code: 'COMM_200',
      message: '성공',
      data: { ...detail, feedback_id: '42' },
    }),
  );
  await assert.rejects(() => getMyInquiry(item.feedback_id), ApiClientError);
  context.mock.restoreAll();
  context.mock.method(globalThis, 'fetch', async () =>
    Response.json(
      {
        isSuccess: false,
        code: 'FEEDBACK_404_002',
        message: '문의를 찾을 수 없습니다.',
      },
      { status: 404 },
    ),
  );
  await assert.rejects(
    () => getMyInquiry(item.feedback_id),
    (error) => error.status === 404,
  );
});
