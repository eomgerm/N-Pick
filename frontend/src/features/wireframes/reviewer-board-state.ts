import type { InquiryStatus } from '@/features/wireframes/inquiry-state';

export type BoardStatus = 'all' | InquiryStatus;
export type BoardSort = 'time' | 'requester' | 'topic';

export interface ReviewBoardItem {
  id: string;
  sceneTitle: string;
  comment: string;
  query: string;
  requester: string;
  topic: string;
  daysAgo: number;
  status: InquiryStatus;
  timecode: string;
  thumbnail: 'station' | 'weather' | 'square';
  isDegraded: boolean;
}

export function selectBoardPage(items: ReviewBoardItem[], params: URLSearchParams) {
  const query = (params.get('q') ?? '').trim().normalize('NFKC').toLocaleLowerCase('ko');
  const rawStatus = params.get('status');
  const status: BoardStatus =
    rawStatus === 'open' || rawStatus === 'reviewing' || rawStatus === 'closed' ? rawStatus : 'all';
  const rawSort = params.get('sort');
  const sort: BoardSort = rawSort === 'requester' || rawSort === 'topic' ? rawSort : 'time';
  const matched = items.filter((item) => {
    const text = [item.sceneTitle, item.comment, item.query, item.requester, item.topic]
      .join(' ')
      .normalize('NFKC')
      .toLocaleLowerCase('ko');
    return text.includes(query);
  });
  const counts = {
    all: matched.length,
    open: matched.filter((item) => item.status === 'open').length,
    reviewing: matched.filter((item) => item.status === 'reviewing').length,
    closed: matched.filter((item) => item.status === 'closed').length,
  };
  const filtered = matched.filter((item) => status === 'all' || item.status === status);
  filtered.sort((a, b) => {
    const labelOrder = sort === 'time' ? 0 : a[sort].localeCompare(b[sort], 'ko');
    return labelOrder || a.daysAgo - b.daysAgo || a.id.localeCompare(b.id);
  });
  const pageCount = Math.max(1, Math.ceil(filtered.length / 10));
  const rawPage = Number(params.get('page') ?? '1');
  const page = Math.min(pageCount, Number.isSafeInteger(rawPage) && rawPage > 0 ? rawPage : 1);
  return {
    query,
    status,
    sort,
    counts,
    page,
    pageCount,
    total: filtered.length,
    rows: filtered.slice((page - 1) * 10, page * 10),
  };
}

export function getReviewUrl(
  pathname: string,
  currentParams: string,
  updates: Record<string, string | null>,
) {
  const params = new URLSearchParams(currentParams);
  for (const [key, value] of Object.entries(updates)) {
    if (value === null || value === '') params.delete(key);
    else params.set(key, value);
  }
  // 빈 /review 는 개요다. 문의 화면에서 마지막 조건을 지워도 문의 화면에 머물게 view 를 남긴다.
  const query = params.toString() || 'view=inquiries';
  return `${pathname}?${query}`;
}

export type ReviewTab = 'overview' | 'inquiries' | 'processing';
export type ReviewView = ReviewTab | 'upload';

/** 파라미터가 하나도 없을 때만 개요다. 기존 `?status=`·`?inquiry=` 같은 링크는 문의 화면을 연다. */
export function selectReviewView(params: URLSearchParams): ReviewView {
  const view = params.get('view');
  if (view === 'processing' || view === 'upload') return view;
  return params.size === 0 ? 'overview' : 'inquiries';
}

export function getReviewTabUrl(pathname: string, currentParams: string, tab: ReviewTab) {
  if (tab === 'overview') return pathname;
  return getReviewUrl(pathname, currentParams, {
    view: tab === 'processing' ? 'processing' : null,
    tab: null,
    clip: null,
    inquiry: null,
    progressPage: null,
  });
}
