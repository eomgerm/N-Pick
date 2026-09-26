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
