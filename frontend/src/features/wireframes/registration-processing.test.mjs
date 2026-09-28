import assert from 'node:assert/strict';
import test from 'node:test';

import { completedDemoClip, getRegisteredClip } from './registration-processing.ts';

test('새 등록 상세는 입력 메타데이터를 보존하며 완료·검색 가능으로 표시하지 않는다', () => {
  const registration = {
    id: '398021840012345',
    pipelineRunId: '398021847361024',
    status: 'queued',
    title: '',
    fileName: '시연.mp4',
    fileSize: 2048,
    sourceType: 'broadcast',
    broadcastDate: '',
    filmedDate: '2026-09-07',
    subtitleFileName: '시연.srt',
    scriptFileName: '대본.txt',
  };
  const clip = getRegisteredClip(registration);
  assert.equal(clip.id, registration.id);
  assert.equal(clip.pipelineRunId, registration.pipelineRunId);
  assert.deepEqual(clip.registration, registration);
  assert.equal(clip.title, `표시 이름(파일명) · ${registration.fileName}`);
  assert.equal(clip.latestRun, 'queued');
  assert.equal(clip.servingStatus, 'queued');
  assert.ok(clip.stages.every((stage) => stage.status === 'pending'));
  assert.equal(clip.retryable, false);
  assert.equal(clip.scenes, undefined);
});

test('사용자가 입력한 제목은 표시 제목으로 사용하고 파일명 fallback과 구분한다', () => {
  const registration = {
    id: 'clip-2',
    pipelineRunId: 'run-2',
    status: 'queued',
    title: '사용자가 확인한 제목',
    fileName: '사실성미확인.mp4',
    fileSize: 2048,
    sourceType: 'archive',
    broadcastDate: '',
    filmedDate: '',
  };
  const clip = getRegisteredClip(registration);
  assert.equal(clip.title, registration.title);
  assert.equal(clip.fileName, registration.fileName);
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

test('완료 데모 클립은 모든 단계가 성공이고 검색 제공 상태다', () => {
  assert.equal(completedDemoClip.servingStatus, 'ready');
  assert.ok(completedDemoClip.stages.every(({ status }) => status === 'succeeded'));
});
