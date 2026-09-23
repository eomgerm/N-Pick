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
  selectClipFilter,
  clipFilterUpdates,
  lastCheckedAt,
} = await import('./clip-processing-view.ts');
const { QueryClient, QueryObserver } = await import('@tanstack/react-query');

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

// 영상 목록과 같은 queryKey·placeholderData 로 결과를 받아 화면에 보일 확인 시각을 쌓는다.
function observeClipList() {
  const client = new QueryClient();
  const pendingResponses = [];
  const listOptions = (filter, page) => ({
    queryKey: ['processing-clips', filter, false, page, 10],
    queryFn: () => new Promise((resolve) => pendingResponses.push(() => resolve({ page }))),
    placeholderData: (previous, previousQuery) =>
      previousQuery?.queryKey[1] === filter ? previous : undefined,
  });
  const observer = new QueryObserver(client, listOptions('all', 1));
  const tick = () => new Promise((resolve) => setTimeout(resolve, 0));
  const shown = [];
  let checkedAt = 0;
  const unsubscribe = observer.subscribe((result) => {
    checkedAt = lastCheckedAt(checkedAt, result.dataUpdatedAt);
    shown.push(checkedAt);
  });
  return {
    shown,
    move: async (filter, page) => {
      observer.setOptions(listOptions(filter, page));
      await tick();
    },
    respond: async () => {
      await tick();
      pendingResponses.shift()();
      await tick();
    },
    latestUpdatedAt: () => observer.getCurrentResult().dataUpdatedAt,
    close: () => {
      unsubscribe();
      client.clear();
    },
  };
}

function shownAfterFirstCheck(shown) {
  return shown.slice(shown.findIndex((checkedAt) => checkedAt > 0));
}

test('같은 필터에서 쪽을 넘겨도 새 응답 전까지 직전 확인 시각을 보여 준다', async () => {
  const list = observeClipList();
  await list.respond();
  await list.move('all', 2);
  await list.respond();
  list.close();
  assert.equal(shownAfterFirstCheck(list.shown).includes(0), false);
  assert.equal(list.shown.at(-1), list.latestUpdatedAt());
});

test('필터를 바꿔도 새 응답 전까지 직전 확인 시각을 보여 준다', async () => {
  const list = observeClipList();
  await list.respond();
  await list.move('processing', 1);
  await list.respond();
  list.close();
  assert.equal(shownAfterFirstCheck(list.shown).includes(0), false);
  assert.equal(list.shown.at(-1), list.latestUpdatedAt());
});

test('새 응답이 오면 확인 시각을 새 값으로 바꾼다', () => {
  assert.equal(lastCheckedAt(1_000, 2_000), 2_000);
});

test('첫 응답 전에는 확인 시각이 없다', () => {
  assert.equal(lastCheckedAt(0, 0), 0);
});
