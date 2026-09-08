import assert from 'node:assert/strict';
import test from 'node:test';

import { getBoardStatus, getReviewUrl, selectBoardPage } from './reviewer-board-state.ts';

const items = Array.from({ length: 23 }, (_, index) => ({
  id: `inquiry-${index}`,
  sceneTitle: index % 2 ? '서울역 귀성객' : '부산 태풍 자료화면',
  comment: index === 0 ? '날짜가 달라요' : '장소를 확인해 주세요',
  query: '뉴스 장면',
  requester: ['최유진', '김서연', '박지민'][index % 3],
  topic: ['사회', '교통', '날씨'][index % 3],
  daysAgo: index,
  status: ['pending', 'reviewing', 'dismissed', 'deferred', 'resolved'][index % 5],
  timecode: '00:42–00:49',
  thumbnail: 'station',
  isDegraded: false,
}));

test('상세 진입·복귀는 검색·필터·정렬·페이지 조건을 유지한다', () => {
  const original = 'q=서울역&status=pending&sort=requester&page=2';
  const detail = getReviewUrl('/review/shinhan', original, { inquiry: 'INQ-1042' });
  const restored = getReviewUrl('/review/shinhan', detail.split('?')[1], { inquiry: null });
  assert.equal(
    new URL(`https://example.test${restored}`).searchParams.toString(),
    new URLSearchParams(original).toString(),
  );
  const filtered = getReviewUrl('/review/shinhan', original, { status: 'completed', page: null });
  const next = new URL(`https://example.test${filtered}`).searchParams;
  assert.equal(next.get('status'), 'completed');
  assert.equal(next.get('q'), '서울역');
  assert.equal(next.get('page'), null);
});

test('23 문의를 10, 10, 3개로 중복 없이 페이지네이션한다', () => {
  const pages = [1, 2, 3].map((page) =>
    selectBoardPage(items, new URLSearchParams({ page: String(page) })),
  );
  assert.deepEqual(
    pages.map(({ rows }) => rows.length),
    [10, 10, 3],
  );
  assert.equal(new Set(pages.flatMap(({ rows }) => rows.map(({ id }) => id))).size, 23);
  assert.deepEqual(
    pages[2].rows.map(({ daysAgo }) => daysAgo),
    [20, 21, 22],
  );
});

test('잘못된 페이지를 보정하고 빈 결과는 0건으로 표시한다', () => {
  for (const page of ['-1', '0', 'NaN', '2.5', 'Infinity']) {
    assert.equal(selectBoardPage(items, new URLSearchParams({ page })).page, 1);
  }
  assert.equal(selectBoardPage(items, new URLSearchParams('page=999')).page, 3);
  const empty = selectBoardPage(items, new URLSearchParams('q=존재하지않는문의&page=3'));
  assert.equal(empty.total, 0);
  assert.equal(empty.rows.length, 0);
  assert.equal(empty.page, 1);
});

test('제목·문의 내용·문의자·주제에 검색을 적용한다', () => {
  for (const q of ['서울역', '날짜가 달라요', '김서연', '사회']) {
    const result = selectBoardPage(items, new URLSearchParams({ q }));
    assert.ok(result.total > 0);
    assert.ok(
      result.rows.every((item) =>
        [item.sceneTitle, item.comment, item.requester, item.topic].join(' ').includes(q),
      ),
    );
  }
  assert.equal(selectBoardPage(items, new URLSearchParams('q=%20%20')).total, 23);
});

test('검색과 상태 필터를 결합하고 완료에는 모든 종료 상태를 포함한다', () => {
  const result = selectBoardPage(items, new URLSearchParams('q=태풍&status=completed'));
  assert.ok(result.rows.length > 0);
  assert.ok(
    result.rows.every(
      (item) =>
        item.sceneTitle.includes('태풍') &&
        ['resolved', 'dismissed', 'deferred'].includes(item.status),
    ),
  );
  assert.equal(
    result.counts.all,
    result.counts.pending + result.counts.reviewing + result.counts.completed,
  );
  assert.deepEqual(['resolved', 'dismissed', 'deferred'].map(getBoardStatus), [
    'completed',
    'completed',
    'completed',
  ]);
});

test('문의자와 주제 가나다순 정렬은 시간순을 보조 기준으로 사용한다', () => {
  for (const sort of ['requester', 'topic']) {
    const rows = selectBoardPage(items, new URLSearchParams({ sort })).rows;
    for (let index = 1; index < rows.length; index++) {
      const previous = rows[index - 1];
      const current = rows[index];
      const order = previous[sort].localeCompare(current[sort], 'ko');
      assert.ok(order <= 0);
      if (order === 0) assert.ok(previous.daysAgo <= current.daysAgo);
    }
  }
  assert.deepEqual(
    items.map(({ daysAgo }) => daysAgo),
    Array.from({ length: 23 }, (_, index) => index),
  );
});
