import assert from 'node:assert/strict';
import test from 'node:test';

import { inquiries } from './reviewer-inquiries.ts';
import { getProgressOverview } from './reviewer-progress-state.ts';

const inquiryItems = inquiries.map((item) => ({
  ...item,
  status: item.initialStatus,
  stage: '검수 중',
}));
const videoItems = ['queued', 'running', 'failed', 'succeeded'].map((status) => ({
  id: status,
  title: status,
  fileName: `${status}.mp4`,
  status,
  stage: status,
  completedSteps: status === 'succeeded' ? 4 : 0,
  totalSteps: 4,
  description: '',
  canOpen: true,
}));

test('문의 처리 중은 검수 중인 문의만 표시하고 전체 진행 건수를 집계한다', () => {
  const summary = getProgressOverview(inquiryItems, videoItems);
  assert.equal(summary.inquiries.total, 23);
  assert.equal(summary.inquiries.completed, 8);
  assert.equal(summary.inquiries.pending, 11);
  assert.equal(summary.inquiries.active.length, 4);
  assert.ok(summary.inquiries.active.every((item) => item.status === 'reviewing'));
});

test('영상 등록 목록은 완료를 제외하고 대기·진행·실패를 구분한다', () => {
  const { videos } = getProgressOverview(inquiryItems, videoItems);
  assert.equal(videos.total, 4);
  assert.equal(videos.completed, 1);
  assert.equal(videos.queued, 1);
  assert.equal(videos.running, 1);
  assert.equal(videos.failed, 1);
  assert.deepEqual(
    videos.active.map((item) => item.id),
    ['queued', 'running', 'failed'],
  );
});

test('문의 완료 상태 변경은 진행 목록과 완료 건수에 즉시 반영된다', () => {
  const activeId = inquiryItems.find((item) => item.status === 'reviewing').id;
  for (const status of ['resolved', 'dismissed', 'deferred']) {
    const updated = inquiryItems.map((item) => (item.id === activeId ? { ...item, status } : item));
    const summary = getProgressOverview(updated, videoItems);
    assert.equal(summary.inquiries.active.length, 3);
    assert.equal(summary.inquiries.completed, 9);
  }
  assert.equal(getProgressOverview(inquiryItems, videoItems).inquiries.active.length, 4);
});

test('빈 데이터는 빈 진행 목록과 0건 요약을 제공한다', () => {
  const summary = getProgressOverview([], []);
  assert.equal(summary.inquiries.total, 0);
  assert.equal(summary.videos.total, 0);
  assert.deepEqual(summary.inquiries.active, []);
  assert.deepEqual(summary.videos.active, []);
});
