export type DegradedReason = 'resolver-fallback' | 'dense-unavailable' | 'snapshot-save-failed';

export type SearchExecutionPresentation =
  | {
      readonly status: 'succeeded';
      readonly degradedReasons: readonly [];
      readonly hasAppliedReviewRule: boolean;
    }
  | {
      readonly status: 'degraded';
      readonly degradedReasons: readonly [DegradedReason, ...DegradedReason[]];
      readonly hasAppliedReviewRule: boolean;
    };

export interface DegradedReasonNotice {
  reason: DegradedReason;
  title: string;
  description: string;
}

const degradedReasonNotices: Record<DegradedReason, Omit<DegradedReasonNotice, 'reason'>> = {
  'resolver-fallback': {
    title: '검색어 해석 일부 누락',
    description: '검색어 해석을 사용할 수 없어 기본 단어 검색으로 결과를 제공했어요.',
  },
  'dense-unavailable': {
    title: '의미 검색 일부 누락',
    description: '의미 기반 검색을 사용할 수 없어 단어 검색과 사용 가능한 정보로 찾았어요.',
  },
  'snapshot-save-failed': {
    title: '검색 기록 저장 실패',
    description: '결과는 확인할 수 있지만 이 검색에서는 문의와 후속 교정을 시작할 수 없어요.',
  },
};

const degradedReasonOrder = Object.keys(degradedReasonNotices) as DegradedReason[];

export const successfulSearchExecution: SearchExecutionPresentation = Object.freeze({
  status: 'succeeded',
  degradedReasons: Object.freeze([]) as readonly [],
  hasAppliedReviewRule: false,
});

export function getDegradedReasonNotices(
  reasons: readonly DegradedReason[],
): DegradedReasonNotice[] {
  const uniqueReasons = new Set(reasons);

  return degradedReasonOrder
    .filter((reason) => uniqueReasons.has(reason))
    .map((reason) => ({ reason, ...degradedReasonNotices[reason] }));
}

export function canCreateInquiry(execution: SearchExecutionPresentation) {
  return (
    execution.status !== 'degraded' || !execution.degradedReasons.includes('snapshot-save-failed')
  );
}

export function getSearchExecutionAnnouncement(execution: SearchExecutionPresentation) {
  const reasonNotices = getDegradedReasonNotices(execution.degradedReasons);
  const statusMessage =
    execution.status === 'degraded'
      ? `일부 기능 누락: ${reasonNotices.map(({ title }) => title).join(', ')}`
      : '정상 검색';

  return execution.hasAppliedReviewRule ? `${statusMessage}. 검수 규칙 적용` : statusMessage;
}

export function getDemoSearchExecution(state?: string): SearchExecutionPresentation {
  switch (state) {
    case 'degraded-resolver':
      return {
        status: 'degraded',
        degradedReasons: ['resolver-fallback'],
        hasAppliedReviewRule: false,
      };
    case 'degraded-dense':
      return {
        status: 'degraded',
        degradedReasons: ['dense-unavailable'],
        hasAppliedReviewRule: false,
      };
    case 'degraded-snapshot':
      return {
        status: 'degraded',
        degradedReasons: ['snapshot-save-failed'],
        hasAppliedReviewRule: false,
      };
    case 'review-rule':
      return {
        ...successfulSearchExecution,
        hasAppliedReviewRule: true,
      };
    default:
      return successfulSearchExecution;
  }
}
