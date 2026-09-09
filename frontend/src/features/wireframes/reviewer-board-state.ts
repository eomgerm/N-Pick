export type ReviewStatus = 'pending' | 'reviewing' | 'resolved' | 'dismissed' | 'deferred';
export type BoardStatus = 'all' | 'pending' | 'reviewing' | 'completed';
export type BoardSort = 'time' | 'requester' | 'topic';

export interface ReviewBoardItem {
  id: string;
  sceneTitle: string;
  comment: string;
  query: string;
  requester: string;
  topic: string;
  daysAgo: number;
  status: ReviewStatus;
  timecode: string;
  thumbnail: 'station' | 'weather' | 'square';
  isDegraded: boolean;
}

export function getBoardStatus(status: ReviewStatus): Exclude<BoardStatus, 'all'> {
  return status === 'pending' || status === 'reviewing' ? status : 'completed';
}

export function selectBoardPage(items: ReviewBoardItem[], params: URLSearchParams) {
  const query = (params.get('q') ?? '').trim().normalize('NFKC').toLocaleLowerCase('ko');
  const rawStatus = params.get('status');
  const status: BoardStatus =
    rawStatus === 'pending' || rawStatus === 'reviewing' || rawStatus === 'completed'
      ? rawStatus
      : 'all';
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
    pending: matched.filter((item) => getBoardStatus(item.status) === 'pending').length,
    reviewing: matched.filter((item) => getBoardStatus(item.status) === 'reviewing').length,
    completed: matched.filter((item) => getBoardStatus(item.status) === 'completed').length,
  };
  const filtered = matched.filter(
    (item) => status === 'all' || getBoardStatus(item.status) === status,
  );
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
  const query = params.toString();
  return `${pathname}${query ? `?${query}` : ''}`;
}

export function getReviewTabUrl(
  pathname: string,
  currentParams: string,
  tab: 'inquiries' | 'processing',
) {
  return getReviewUrl(pathname, currentParams, {
    view: tab === 'processing' ? 'processing' : null,
    tab: null,
    clip: null,
    inquiry: null,
  });
}
