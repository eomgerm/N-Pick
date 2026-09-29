import assert from 'node:assert/strict';
import { registerHooks } from 'node:module';
import test from 'node:test';

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
const {
  CLIP_FILTERS,
  clipFilterCounts,
  clipFilterStatuses,
  defaultProcessingStage,
  processingStageResultMode,
  processingStageResultTitle,
  selectClipFilter,
  clipFilterUpdates,
} = await import('./clip-processing-view.ts');

const runCounts = { queued: 2, running: 1, failed: 3, succeeded: 8, no_run: 4 };

test('칩 건수는 다섯 상태를 네 묶음으로 접고 합이 전체와 같다', () => {
  const counts = clipFilterCounts(runCounts);
  assert.equal(counts.processing, 3);
  assert.equal(counts.attention, 7);
  assert.equal(counts.done, 8);
  assert.equal(counts.all, 18);
  assert.equal(counts.processing + counts.attention + counts.done, counts.all);
});

test('칩은 묶은 상태를 서버가 아는 값으로 되돌려 준다', () => {
  assert.deepEqual(clipFilterStatuses('processing'), ['queued', 'running']);
  assert.deepEqual(clipFilterStatuses('attention'), ['failed', 'no_run']);
  assert.deepEqual(clipFilterStatuses('done'), ['succeeded']);
  assert.deepEqual(clipFilterStatuses('all'), []);
  const declared = CLIP_FILTERS.flatMap((filter) => filter.statuses);
  assert.deepEqual(declared.toSorted(), Object.keys(runCounts).toSorted());
});

test('모르는 status 파라미터는 전체로 떨어진다', () => {
  assert.equal(selectClipFilter('attention'), 'attention');
  assert.equal(selectClipFilter(null), 'all');
  assert.equal(selectClipFilter(''), 'all');
  assert.equal(selectClipFilter('uploads'), 'all');
  assert.equal(selectClipFilter('queued'), 'all');
});

test('칩을 바꾸면 페이지는 1로 돌아가고 전체는 파라미터를 지운다', () => {
  assert.deepEqual(clipFilterUpdates('attention'), {
    clipStatus: 'attention',
    progressPage: null,
  });
  assert.deepEqual(clipFilterUpdates('all'), { clipStatus: null, progressPage: null });
});

test('칩 키는 문의 화면의 status 와 겹치지 않는다', () => {
  // 같은 /review URL 을 문의 화면이 status=open|reviewing|closed 로 쓴다.
  for (const filter of CLIP_FILTERS) {
    assert.equal('status' in clipFilterUpdates(filter.value), false);
  }
  for (const inquiryToken of ['open', 'reviewing', 'closed']) {
    assert.equal(selectClipFilter(inquiryToken), 'all');
  }
});

test('파이프라인은 실패, 진행 중, 첫 단계 순으로 기본 선택한다', () => {
  const stages = [
    { name: 'scene_detection', status: 'succeeded' },
    { name: 'frame_extraction', status: 'running' },
    { name: 'ocr', status: 'failed' },
  ];
  assert.equal(defaultProcessingStage(stages), 'ocr');
  assert.equal(defaultProcessingStage(stages.slice(0, 2)), 'frame_extraction');
  assert.equal(defaultProcessingStage([]), 'scene_detection');
});

test('성공한 파이프라인 단계는 해당 산출물 이름으로 연결한다', () => {
  assert.equal(processingStageResultTitle('scene_detection'), '나눈 장면');
  assert.equal(processingStageResultTitle('frame_extraction'), '추출한 대표 화면');
  assert.equal(processingStageResultTitle('ocr'), '읽어낸 화면 글자');
  assert.equal(processingStageResultTitle('transcript_selection'), '선택한 대사 출처');
  assert.equal(processingStageResultTitle('asr'), '음성 인식 결과');
  assert.equal(processingStageResultTitle('scene_transcript_mapping'), '연결한 장면 대사');
  assert.equal(processingStageResultTitle('vlm_metadata'), '생성한 영상 설명');
  assert.equal(processingStageResultTitle('entity_extraction'), '추출한 검색 태그');
  assert.equal(processingStageResultTitle('text_embedding'), '검색 표현 생성 결과');
  assert.equal(processingStageResultTitle('indexing'), '검색 반영 결과');
});

test('확실한 장면 산출물이 없는 단계는 완료 여부만 표시한다', () => {
  for (const stage of ['asr', 'text_embedding', 'indexing']) {
    assert.equal(processingStageResultMode(stage), 'completion');
  }
  for (const stage of [
    'scene_detection',
    'frame_extraction',
    'ocr',
    'transcript_selection',
    'scene_transcript_mapping',
    'vlm_metadata',
    'entity_extraction',
  ]) {
    assert.equal(processingStageResultMode(stage), 'scene');
  }
});

const { processingRunProgress } = await import('./clip-processing-view.ts');
const stage = (name, status) => ({ name, status });

test('진행 요약은 성공·생략을 완료로 세고 진행 중 단계를 알려 준다', () => {
  const progress = processingRunProgress('running', [
    stage('scene_detection', 'succeeded'),
    stage('frame_extraction', 'succeeded'),
    stage('ocr', 'running'),
    stage('asr', 'skipped'),
  ]);
  assert.equal(progress.total, 10);
  assert.equal(progress.done, 3);
  assert.equal(progress.state, 'running');
  assert.equal(progress.currentName, 'ocr');
  assert.match(progress.label, /^3\/10 완료 · 지금 .+ 진행 중$/);
});

test('실행이 대기 중이고 진행 중 단계가 없으면 작업자 대기를 알린다', () => {
  const progress = processingRunProgress('queued', [stage('scene_detection', 'pending')]);
  assert.equal(progress.state, 'waiting');
  assert.equal(progress.currentName, null);
  assert.equal(progress.label, '0/10 완료 · 처리 서버가 작업을 가져가길 기다리는 중');
});

test('실행 중이지만 단계 사이면 다음 단계 준비로 표시한다', () => {
  const progress = processingRunProgress('running', [stage('scene_detection', 'succeeded')]);
  assert.equal(progress.state, 'waiting');
  assert.equal(progress.label, '1/10 완료 · 다음 단계를 준비하는 중');
});

test('실패한 단계가 있으면 진행 중보다 실패를 먼저 알린다', () => {
  const progress = processingRunProgress('failed', [
    stage('scene_detection', 'succeeded'),
    stage('frame_extraction', 'failed'),
  ]);
  assert.equal(progress.state, 'failed');
  assert.equal(progress.currentName, 'frame_extraction');
  assert.match(progress.label, /^1\/10 완료 · .+ 단계에서 멈춤$/);
});

test('완료된 실행은 완료 수만 보여 준다', () => {
  const stages = [
    'scene_detection',
    'frame_extraction',
    'ocr',
    'transcript_selection',
    'asr',
    'scene_transcript_mapping',
    'vlm_metadata',
    'entity_extraction',
    'text_embedding',
    'indexing',
  ].map((name) => stage(name, name === 'asr' ? 'skipped' : 'succeeded'));
  const progress = processingRunProgress('succeeded', stages);
  assert.equal(progress.state, 'done');
  assert.equal(progress.label, '10/10 완료');
});

test('처리 기록이 없으면 요약을 만들지 않는다', () => {
  assert.equal(processingRunProgress(null, []), null);
  // 실행은 있어도 단계 기록을 못 받았으면 0단계 완료로 꾸미지 않는다.
  assert.equal(processingRunProgress('failed', []), null);
});

test('실패한 실행에 실패 단계 기록이 없으면 중단으로 표시한다', () => {
  const progress = processingRunProgress('failed', [stage('scene_detection', 'succeeded')]);
  assert.equal(progress.state, 'failed');
  assert.equal(progress.label, '1/10 완료 · 처리가 중단됨');
});
