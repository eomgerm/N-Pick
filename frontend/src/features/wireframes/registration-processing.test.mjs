import assert from 'node:assert/strict';
import test from 'node:test';

import { completedDemoClip, getRegisteredClip } from './registration-processing.ts';
import { getProgressOverview } from './reviewer-progress-state.ts';

test('새 등록 상세는 입력 메타데이터를 보존하며 완료·검색 가능으로 표시하지 않는다', () => {
  const registration = {
    id: 'local-1',
    fileName: '시연.mp4',
    fileSize: 2048,
    attachments: ['시연.srt'],
    sourceType: 'broadcast',
    broadcastDate: '',
  };
  const clip = getRegisteredClip(registration);
  assert.equal(clip.id, registration.id);
  assert.deepEqual(clip.registration, registration);
  assert.equal(clip.latestRun, 'queued');
  assert.equal(clip.servingStatus, 'queued');
  assert.ok(clip.stages.every((stage) => stage.status === 'pending'));
  assert.equal(clip.retryable, false);
  assert.equal(clip.scenes, undefined);
});

test('완료 영상의 전체 구간이 처음부터 끝까지 시간순으로 연결된다', () => {
  const { scenes, sceneCount, totalSeconds } = completedDemoClip;
  assert.equal(scenes.length, sceneCount);
  assert.equal(new Set(scenes.map(({ id }) => id)).size, sceneCount);
  assert.equal(scenes[0].start, 0);
  assert.equal(scenes.at(-1).end, totalSeconds);
  for (const [index, scene] of scenes.entries()) {
    assert.ok(scene.end > scene.start);
    assert.ok(scene.title && scene.description);
    assert.equal(scene.start, index === 0 ? 0 : scenes[index - 1].end);
  }
});

test('완료 조회에는 성공한 영상만 들어가며 신규 대기 영상과 섞이지 않는다', () => {
  const videos = [
    { id: 'new', status: 'queued' },
    { id: 'failed', status: 'failed' },
    { id: completedDemoClip.id, status: completedDemoClip.latestRun },
  ];
  const { videos: overview } = getProgressOverview([], videos);
  assert.deepEqual(
    overview.completedItems.map(({ id }) => id),
    [completedDemoClip.id],
  );
  assert.equal(overview.completed, 1);
  assert.equal(overview.active.length, 2);
  assert.equal(completedDemoClip.servingStatus, 'ready');
  assert.ok(completedDemoClip.stages.every(({ status }) => status === 'succeeded'));
});
