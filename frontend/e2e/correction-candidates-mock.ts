import type { Page } from '@playwright/test';

// 대기 교정 후보 조회(GET correction-candidates) 목 (S15P21A501-317). 화면은 후보를 바꿀 때마다 이 조회를
// 다시 읽어 서버 목록을 정본으로 삼으므로, 후보를 만드는 목은 여기 state 에도 같이 반영해야 한다.
export interface CandidateState {
  tags: Array<{
    evidenceId: string;
    taggingId: string;
    action: string;
    scope: string;
    tagType: string;
    matchValue: string;
    displayName: string;
  }>;
  parsePatches: unknown[];
  sceneExcludes: Array<{ searchRuleId: string; targetSceneId: string }>;
}

export async function mockCorrectionCandidates(page: Page, feedbackId = '41') {
  const state: CandidateState = { tags: [], parsePatches: [], sceneExcludes: [] };
  await page.route(`**/api/v1/review/inquiries/${feedbackId}/correction-candidates`, (route) =>
    route.fulfill({
      json: { isSuccess: true, code: 'COMM_200', message: '요청에 성공했습니다.', data: state },
    }),
  );
  return state;
}

interface TagOperation {
  action: string;
  scope: string;
  tagType: string;
  matchValue: string;
  displayName: string;
}

/** POST 로 만든 근거를 대기 후보에 넣는다. taggingId 는 현재 태그 REJECT 면 그 태그의 id 를 넘긴다. */
export function addPendingTags(
  state: CandidateState,
  operations: TagOperation[],
  evidenceIds: string[],
  currentTaggingIds: Record<string, string> = {},
) {
  operations.forEach((operation, index) => {
    const evidenceId = evidenceIds[index];
    const taggingId = currentTaggingIds[operation.matchValue] ?? `9${evidenceId}`;
    state.tags.push({ ...operation, evidenceId, taggingId });
  });
}

export function removePendingTag(state: CandidateState, url: string) {
  const evidenceId = new URL(url).pathname.split('/').pop();
  state.tags = state.tags.filter((tag) => tag.evidenceId !== evidenceId);
}
