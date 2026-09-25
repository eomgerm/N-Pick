import { expect, test, type Page, type Route } from '@playwright/test';

// S15P21A501-317: 새로고침 뒤 대기 교정 후보 복원, 태그 유형 기본값 없음, 검증에 적용된 후보 수.

const parsedQueryJson = JSON.stringify({
  schema_version: 'resolution-v1',
  intent: 'scene_search',
  date_windows: [],
  incident_names: [{ value: '추석', origin: 'explicit_query', query_span: null, confidence: 1 }],
  entities: [],
  locations: [],
  expanded_terms: ['귀성 차량'],
  confidence: 0.92,
});

function inquiry() {
  return {
    feedbackId: '41',
    status: 'REVIEWING',
    resolution: 'correction',
    resolutionNote: null,
    createdAt: '2026-09-09T01:00:00Z',
    queryText: '귀성길 정체',
    sceneId: '31',
    scene: {
      sceneId: '31',
      clipId: '21',
      clipTitle: '저녁 뉴스',
      startTimeMs: 42000,
      endTimeMs: 49000,
      pipelineRunId: '11',
      processingNo: 1,
    },
    hasComment: false,
    comment: null,
    resultRank: 1,
    resultExplainJson: '{"score":0.8}',
    execution: {
      queryText: '귀성길 정체',
      explicitFiltersJson: '{}',
      parsedQueryJson,
      resolverOutputJson: null,
      appliedRulesJson: '[]',
      appliedExcludesJson: '[]',
    },
    evidence: [
      {
        taggingId: '51',
        tagType: 'location',
        matchValue: '서울역',
        tagName: '서울역',
        sources: ['ocr'],
        verifiedState: 'VERIFIED',
        scope: 'SCENE',
      },
    ],
    history: {
      reviewedById: '2',
      reviewerName: 'E2E 검수자',
      reviewerLoginId: 'e2e-reviewer',
      reviewStartedAt: '2026-09-09T02:00:00Z',
      verifiedByExecutionId: null,
    },
  };
}

interface TagCandidate {
  evidenceId: string;
  taggingId: string;
  action: 'APPROVE' | 'REJECT' | 'WITHDRAW';
  scope: 'SCENE' | 'CLIP';
  tagType: string;
  matchValue: string;
  displayName: string;
}

interface ServerState {
  tags: TagCandidate[];
  parsePatches: unknown[];
  sceneExcludes: Array<{ searchRuleId: string; targetSceneId: string }>;
}

async function success(route: Route, data?: unknown) {
  await route.fulfill({
    json: { isSuccess: true, code: 'COMM_200', message: '요청에 성공했습니다.', data },
  });
}

// 대기 후보를 기억하는 목 서버. POST 는 자연 키가 같으면 기존 근거를 돌려주고, DELETE 는 근거를 지운다.
async function mockServer(page: Page, state: ServerState) {
  const calls: string[] = [];
  let nextEvidenceId = 71;
  await page.context().addCookies([
    { name: 'JSESSIONID', value: 'e2e-reviewer', url: 'http://127.0.0.1:3116' },
    { name: 'XSRF-TOKEN', value: 'review-csrf', url: 'http://127.0.0.1:3116' },
  ]);
  await page.route('**/api/v1/auth/csrf', (route) => success(route));
  await page.route('**/api/v1/review/inquiries/41', (route) => success(route, inquiry()));
  await page.route('**/api/v1/review/inquiries/41/correction-candidates', (route) =>
    success(route, structuredClone(state)),
  );
  await page.route('**/api/v1/review/inquiries/41/tag-correction-candidate', async (route) => {
    const body = route.request().postDataJSON() as {
      operations: Array<Omit<TagCandidate, 'evidenceId' | 'taggingId'>>;
    };
    calls.push(`POST ${JSON.stringify(body.operations)}`);
    let newlyCreated = 0;
    const evidenceIds = body.operations.map((operation) => {
      const sameTag = (tag: TagCandidate) =>
        tag.scope === operation.scope &&
        tag.tagType === operation.tagType &&
        tag.matchValue === operation.matchValue;
      const existing = state.tags.find((tag) => sameTag(tag) && tag.action === operation.action);
      if (existing) return existing.evidenceId;
      // 태깅마다 대기 판단은 하나만 — 반대 판단은 서버가 지운다.
      const taggingId =
        state.tags.find(sameTag)?.taggingId ??
        (operation.matchValue === '서울역' ? '51' : `9${nextEvidenceId}`);
      state.tags = state.tags.filter((tag) => !sameTag(tag));
      const evidenceId = String(nextEvidenceId++);
      state.tags.push({ ...operation, evidenceId, taggingId });
      newlyCreated += 1;
      return evidenceId;
    });
    await success(route, {
      feedbackId: '41',
      created: evidenceIds.length,
      newlyCreated,
      evidenceIds,
    });
  });
  await page.route('**/api/v1/review/inquiries/41/tag-correction-candidate/*', async (route) => {
    const evidenceId = new URL(route.request().url()).pathname.split('/').pop();
    calls.push(`${route.request().method()} ${evidenceId}`);
    state.tags = state.tags.filter((tag) => tag.evidenceId !== evidenceId);
    await success(route);
  });
  await page.route('**/api/v1/review/inquiries/41/scene-exclude-candidate', async (route) => {
    calls.push(`${route.request().method()} scene-exclude`);
    if (route.request().method() === 'DELETE') {
      state.sceneExcludes = [];
      await success(route);
      return;
    }
    state.sceneExcludes = [{ searchRuleId: '90', targetSceneId: '31' }];
    await success(route, { searchRuleId: '90', feedbackId: '41', active: false });
  });
  await page.route('**/api/v1/review/inquiries/41/parse-patch-candidate', async (route) => {
    calls.push(`${route.request().method()} parse-patch`);
    if (route.request().method() === 'DELETE') {
      state.parsePatches = [];
      await success(route);
      return;
    }
    const body = route.request().postDataJSON() as Record<string, unknown>;
    const searchRuleId = String(200 + state.parsePatches.length);
    state.parsePatches.push({ searchRuleId, ...body, replacesRuleId: null });
    await success(route, { searchRuleId, feedbackId: '41', active: false });
  });
  await page.route('**/api/v1/review/inquiries/41/verify', async (route) => {
    calls.push('POST verify');
    await success(route, {
      execution_id: '900',
      entered_scenes: [],
      dropped_scenes: [],
      verification_rule_set: ['80'],
    });
  });
  return calls;
}

function restoredState(): ServerState {
  return {
    tags: [
      {
        evidenceId: '61',
        taggingId: '501',
        action: 'APPROVE',
        scope: 'SCENE',
        tagType: 'location',
        matchValue: '부산',
        displayName: '부산',
      },
      {
        evidenceId: '62',
        taggingId: '51',
        action: 'REJECT',
        scope: 'SCENE',
        tagType: 'location',
        matchValue: '서울역',
        displayName: '서울역',
      },
    ],
    parsePatches: [],
    sceneExcludes: [{ searchRuleId: '90', targetSceneId: '31' }],
  };
}

test('새로고침하면 대기 후보로 추가·삭제 태그와 장면 제외 상태가 복원된다', async ({ page }) => {
  await mockServer(page, restoredState());
  await page.goto('/review?inquiry=41');

  for (let round = 0; round < 2; round += 1) {
    await expect(page.getByRole('button', { name: '‘부산’ 추가 취소' })).toBeVisible();
    await expect(page.getByRole('button', { name: '‘서울역’ 삭제 취소' })).toBeVisible();
    await expect(page.getByRole('button', { name: '‘서울역’ 삭제 후보' })).toHaveCount(0);
    await expect(page.getByRole('button', { name: '제외 취소', exact: true })).toBeVisible();
    await page.reload();
  }
});

test('복원한 추가 태그를 취소하면 그 근거만 DELETE 한다', async ({ page }) => {
  const calls = await mockServer(page, restoredState());
  await page.goto('/review?inquiry=41');

  const cancel = page.getByRole('button', { name: '‘부산’ 추가 취소' });
  await cancel.click();
  await expect(cancel).toHaveCount(0);
  await expect(page.getByRole('button', { name: '‘서울역’ 삭제 취소' })).toBeVisible();
  expect(calls).toEqual(['DELETE 61']);

  await page.reload();
  await expect(page.getByRole('button', { name: '‘서울역’ 삭제 취소' })).toBeVisible();
  await expect(cancel).toHaveCount(0);
});

test('새 태그 초안은 유형 없이 시작하고, 고른 유형의 검색 효과를 보여 준다', async ({ page }) => {
  const calls = await mockServer(page, restoredState());
  await page.goto('/review?inquiry=41');
  await expect(page.getByRole('button', { name: '‘부산’ 추가 취소' })).toBeVisible();

  await page.getByRole('button', { name: '+ 이 장면', exact: true }).click();
  const type = page.getByRole('combobox', { name: '태그 유형', exact: true });
  const confirm = page.getByRole('button', { name: '태그 추가 확정', exact: true });
  await expect(type).toHaveValue('');
  await expect(confirm).toBeDisabled();
  await expect(page.getByText('태그 유형을 먼저 선택해 주세요.')).toBeVisible();

  await type.selectOption('keyword');
  await expect(page.getByText('이 유형은 현재 검색 결과에 영향을 주지 않습니다.')).toBeVisible();
  await expect(confirm).toBeEnabled();

  // 이미 대기 중인 후보와 같은 변경안이면 서버가 기존 근거를 돌려준다 — 칩이 두 번 생기지 않는다.
  await type.selectOption('location');
  await expect(page.getByText('검색어와 일치하면 이 장면이 검색 결과에 반영됩니다.')).toBeVisible();
  await page.getByRole('textbox', { name: '태그 값', exact: true }).fill('부산');
  await confirm.click();
  await expect(type).toHaveCount(0);
  await expect(page.getByRole('button', { name: '‘부산’ 추가 취소' })).toHaveCount(1);
  expect(calls).toHaveLength(1);
  expect(calls[0]).toContain('"tagType":"location"');
});

test('삭제 후보인 태그를 다시 추가하면 서버 목록대로 삭제 후보가 사라진다', async ({ page }) => {
  await mockServer(page, restoredState());
  await page.goto('/review?inquiry=41');
  await expect(page.getByRole('button', { name: '‘서울역’ 삭제 취소' })).toBeVisible();

  await page.getByRole('button', { name: '+ 이 장면', exact: true }).click();
  await page.getByRole('combobox', { name: '태그 유형', exact: true }).selectOption('location');
  await page.getByRole('textbox', { name: '태그 값', exact: true }).fill('서울역');
  await page.getByRole('button', { name: '태그 추가 확정', exact: true }).click();

  await expect(page.getByRole('button', { name: '‘서울역’ 삭제 취소' })).toHaveCount(0);
  await expect(page.getByRole('button', { name: '‘서울역’ 삭제 후보' })).toBeVisible();
  await expect(page.getByRole('button', { name: '‘서울역’ 추가 취소' })).toHaveCount(1);
});

test('검증이 끝나면 이번 검증에 적용된 후보 수와 영향 없는 태그 안내를 보여 준다', async ({
  page,
}) => {
  const state: ServerState = {
    tags: [
      {
        evidenceId: '61',
        taggingId: '501',
        action: 'APPROVE',
        scope: 'SCENE',
        tagType: 'keyword',
        matchValue: '정체',
        displayName: '정체',
      },
    ],
    parsePatches: [
      {
        searchRuleId: '80',
        condition: { version: 'parse-rule/v1' },
        patch: { version: 'parse-rule/v1', ops: [] },
        replacesRuleId: null,
      },
    ],
    sceneExcludes: [],
  };
  const calls = await mockServer(page, state);
  await page.goto('/review?inquiry=41');
  await expect(page.getByRole('button', { name: '‘정체’ 추가 취소' })).toBeVisible();

  await page.getByRole('button', { name: '이 장면 제외', exact: true }).click();
  await expect(page.getByRole('button', { name: '제외 취소', exact: true })).toBeVisible();

  await page.getByRole('button', { name: '후보 검증', exact: true }).click();
  await expect(
    page.getByText('이번 검증에 적용된 후보: 태그 1 · 해석 규칙 1 · 장면 제외 1'),
  ).toBeVisible();
  await expect(page.getByText(/태그 후보가 모두 검색 결과에 영향을 주지 않는 유형/)).toBeVisible();
  expect(calls).toEqual(['POST scene-exclude', 'POST verify']);
});

test('개입 해제 후보는 삭제 후보와 따로 모아 보여 주고 취소하면 그 근거를 지운다', async ({
  page,
}) => {
  const state = restoredState();
  state.tags.push({
    evidenceId: '63',
    taggingId: '502',
    action: 'WITHDRAW',
    scope: 'CLIP',
    tagType: 'person',
    matchValue: '홍길동',
    displayName: '홍길동',
  });
  const calls = await mockServer(page, state);
  await page.goto('/review?inquiry=41');

  await expect(page.getByRole('heading', { name: '개입 해제 후보', exact: true })).toBeVisible();
  const cancel = page.getByRole('button', { name: '‘홍길동’ 개입 해제 취소' });
  await expect(cancel).toBeVisible();
  await expect(page.getByRole('button', { name: '‘서울역’ 삭제 취소' })).toBeVisible();

  await cancel.click();
  await expect(cancel).toHaveCount(0);
  await expect(page.getByRole('heading', { name: '개입 해제 후보', exact: true })).toHaveCount(0);
  expect(calls).toEqual(['DELETE 63']);
});

test('검증 재검색 중에는 태그·장면 제외 후보를 바꾸지 못한다', async ({ page }) => {
  await mockServer(page, restoredState());
  let releaseVerify: () => void = () => {};
  const verifyHeld = new Promise<void>((resolve) => {
    releaseVerify = resolve;
  });
  await page.route('**/api/v1/review/inquiries/41/verify', async (route) => {
    await verifyHeld;
    await success(route, {
      execution_id: '900',
      entered_scenes: [],
      dropped_scenes: [],
      verification_rule_set: [],
    });
  });
  await page.goto('/review?inquiry=41');
  const addScene = page.getByRole('button', { name: '+ 이 장면', exact: true });
  const cancelAdded = page.getByRole('button', { name: '‘부산’ 추가 취소' });
  const sceneToggle = page.getByRole('button', { name: '제외 취소', exact: true });
  await expect(cancelAdded).toBeEnabled();
  await expect(sceneToggle).toBeEnabled();

  await page.getByRole('button', { name: '후보 검증', exact: true }).click();
  await expect(addScene).toBeDisabled();
  await expect(cancelAdded).toBeDisabled();
  await expect(page.getByRole('button', { name: '‘서울역’ 삭제 취소' })).toBeDisabled();
  await expect(sceneToggle).toBeDisabled();

  releaseVerify();
  await expect(
    page.getByText('이번 검증에 적용된 후보: 태그 2 · 해석 규칙 0 · 장면 제외 1'),
  ).toBeVisible();
  await expect(addScene).toBeEnabled();
  await expect(sceneToggle).toBeEnabled();
});

test('대기 후보를 불러오지 못하면 장면 제외 토글을 잠그고 다시 불러오기를 안내한다', async ({
  page,
}) => {
  await mockServer(page, restoredState());
  let failing = true;
  await page.route('**/api/v1/review/inquiries/41/correction-candidates', async (route) => {
    if (failing) {
      await route.fulfill({
        status: 500,
        json: { isSuccess: false, code: 'COMM_500', message: '서버 오류가 발생했습니다.' },
      });
      return;
    }
    await success(route, restoredState());
  });
  await page.goto('/review?inquiry=41');

  await expect(page.getByText('저장해 둔 장면 제외 후보를 불러오지 못했습니다.')).toBeVisible();
  await expect(page.getByText('저장해 둔 태그 후보를 불러오지 못했습니다.')).toBeVisible();
  await expect(page.getByRole('button', { name: '이 장면 제외', exact: true })).toBeDisabled();
  await expect(page.getByRole('button', { name: '+ 이 장면', exact: true })).toBeDisabled();
  await expect(page.getByRole('button', { name: '+ 영상 전체', exact: true })).toBeDisabled();
  await expect(page.getByRole('button', { name: '‘서울역’ 삭제 후보' })).toBeDisabled();

  failing = false;
  await page.getByRole('button', { name: '다시 불러오기' }).first().click();
  await expect(page.getByRole('button', { name: '제외 취소', exact: true })).toBeEnabled();
  await expect(page.getByRole('button', { name: '‘부산’ 추가 취소' })).toBeEnabled();
  await expect(page.getByRole('button', { name: '+ 이 장면', exact: true })).toBeEnabled();
  await expect(page.getByText('저장해 둔 장면 제외 후보를 불러오지 못했습니다.')).toHaveCount(0);
  await expect(page.getByText('저장해 둔 태그 후보를 불러오지 못했습니다.')).toHaveCount(0);
});

test('대기 후보 첫 조회가 끝나기 전에는 태그·장면 제외 조작을 잠근다', async ({ page }) => {
  await mockServer(page, restoredState());
  let releaseGet: () => void = () => {};
  const getHeld = new Promise<void>((resolve) => {
    releaseGet = resolve;
  });
  await page.route('**/api/v1/review/inquiries/41/correction-candidates', async (route) => {
    await getHeld;
    await success(route, restoredState());
  });
  await page.goto('/review?inquiry=41');

  const addScene = page.getByRole('button', { name: '+ 이 장면', exact: true });
  await expect(addScene).toBeDisabled();
  await expect(page.getByRole('button', { name: '+ 영상 전체', exact: true })).toBeDisabled();
  await expect(page.getByRole('button', { name: '‘서울역’ 삭제 후보' })).toBeDisabled();
  await expect(page.getByRole('button', { name: '이 장면 제외', exact: true })).toBeDisabled();

  releaseGet();
  await expect(addScene).toBeEnabled();
  await expect(page.getByRole('button', { name: '‘서울역’ 삭제 취소' })).toBeEnabled();
  await expect(page.getByRole('button', { name: '제외 취소', exact: true })).toBeEnabled();
});

test('검증 재검색 중에는 해석 교정을 담거나 칩을 고치지 못한다', async ({ page }) => {
  await mockServer(page, restoredState());
  let releaseVerify: () => void = () => {};
  const verifyHeld = new Promise<void>((resolve) => {
    releaseVerify = resolve;
  });
  await page.route('**/api/v1/review/inquiries/41/verify', async (route) => {
    await verifyHeld;
    await success(route, {
      execution_id: '900',
      entered_scenes: [],
      dropped_scenes: [],
      verification_rule_set: [],
    });
  });
  await page.goto('/review?inquiry=41');

  await page.getByRole('button', { name: "'추석' 삭제", exact: true }).click();
  const save = page.getByRole('button', { name: '교정 담기', exact: true });
  await expect(save).toBeEnabled();

  await page.getByRole('button', { name: '후보 검증', exact: true }).click();
  await expect(save).toBeDisabled();
  await expect(page.getByRole('button', { name: "'귀성 차량' 삭제", exact: true })).toBeDisabled();

  releaseVerify();
  await expect(page.getByText(/이번 검증에 적용된 후보/)).toBeVisible();
  await expect(save).toBeEnabled();
});

function previousParsePatch() {
  return {
    searchRuleId: '6602',
    condition: { syntax_version: 'parse-rule/v1', all: [] },
    patch: { syntax_version: 'parse-rule/v1', operations: [] },
    replacesRuleId: null,
  };
}

test('새로고침 뒤 이전 해석 교정이 있으면 알리고, 편집만으로는 폐기하지 않고 담을 때 먼저 폐기한다', async ({
  page,
}) => {
  const state = restoredState();
  state.parsePatches = [previousParsePatch()];
  const calls = await mockServer(page, state);
  await page.goto('/review?inquiry=41');

  const notice = page.getByText(
    '이전에 담은 해석 교정 1건이 있어요. 편집해서 다시 담으면 이전 교정은 폐기됩니다.',
  );
  await expect(notice).toBeVisible();

  await page.getByRole('button', { name: "'추석' 삭제", exact: true }).click();
  const save = page.getByRole('button', { name: '교정 담기', exact: true });
  await expect(save).toBeEnabled();
  // 편집만으로는 폐기하지 않는다 — 떠나도 이전에 담은 교정이 서버에 남는다.
  await expect(
    page.getByText(
      '편집한 내용은 아직 담지 않았어요. 교정 담기를 누르면 이전 교정을 폐기하고 새로 담습니다.',
    ),
  ).toBeVisible();
  await expect(notice).toBeVisible();
  expect(calls).toEqual([]);
  expect(state.parsePatches).toEqual([previousParsePatch()]);

  await save.click();
  await expect(page.getByText('1개 교정을 담았어요.', { exact: false })).toBeVisible();
  expect(calls).toEqual(['DELETE parse-patch', 'POST parse-patch']);
  expect(state.parsePatches).toHaveLength(1);
});

test('담을 때 이전 교정 폐기가 실패하면 저장하지 않고, 다시 누르면 폐기부터 반복한다', async ({
  page,
}) => {
  const state = restoredState();
  state.parsePatches = [previousParsePatch()];
  const calls = await mockServer(page, state);
  let failDelete = true;
  await page.route('**/api/v1/review/inquiries/41/parse-patch-candidate', async (route) => {
    if (route.request().method() === 'DELETE' && failDelete) {
      failDelete = false;
      calls.push('DELETE parse-patch (failed)');
      await route.fulfill({
        status: 409,
        json: { isSuccess: false, code: 'SRCH_409_201', message: '요청을 처리할 수 없습니다.' },
      });
      return;
    }
    await route.fallback();
  });
  await page.goto('/review?inquiry=41');

  await page.getByRole('button', { name: "'추석' 삭제", exact: true }).click();
  const save = page.getByRole('button', { name: '교정 담기', exact: true });
  await save.click();
  await expect(page.getByRole('alert').filter({ hasText: /\S/ })).toBeVisible();
  expect(calls).toEqual(['DELETE parse-patch (failed)']);
  expect(state.parsePatches).toEqual([previousParsePatch()]);

  await save.click();
  await expect(page.getByText('1개 교정을 담았어요.', { exact: false })).toBeVisible();
  expect(calls).toEqual(['DELETE parse-patch (failed)', 'DELETE parse-patch', 'POST parse-patch']);
  expect(state.parsePatches).toHaveLength(1);
  expect(state.parsePatches).not.toContainEqual(previousParsePatch());
});

test('이전 교정 폐기 버튼은 서버 후보를 폐기하고 알림을 거둔다', async ({ page }) => {
  const state = restoredState();
  state.parsePatches = [previousParsePatch()];
  const calls = await mockServer(page, state);
  await page.goto('/review?inquiry=41');

  const notice = page.getByText(/이전에 담은 해석 교정 1건이 있어요/);
  await expect(notice).toBeVisible();
  await page.getByRole('button', { name: '이전 교정 폐기', exact: true }).click();
  await expect(notice).toHaveCount(0);
  expect(calls).toEqual(['DELETE parse-patch']);
});
