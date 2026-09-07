import { CircleAlert, LoaderCircle, SearchX } from 'lucide-react';

import { type DateRange, formatDateRange } from '@/features/wireframes/date-range';
import styles from '@/features/wireframes/shinhan-search.module.css';

interface SearchResultStateProps {
  state: 'empty' | 'failed' | 'loading';
  query: string;
  broadcastRange: DateRange;
  filmingRange: DateRange;
  excludedCount: number;
  onReset: () => void;
  onRetry: () => void;
  onEditQuery: () => void;
}

export function SearchResultState({
  state,
  query,
  broadcastRange,
  filmingRange,
  excludedCount,
  onReset,
  onRetry,
  onEditQuery,
}: SearchResultStateProps) {
  const isFailed = state === 'failed';
  const isLoading = state === 'loading';
  const Icon = isLoading ? LoaderCircle : isFailed ? CircleAlert : SearchX;
  return (
    <div
      aria-busy={isLoading}
      className={styles.statePanel}
      data-state={state}
      role={isFailed ? 'alert' : 'status'}
    >
      <div className={styles.stateIcon}>
        <Icon aria-hidden="true" className={isLoading ? styles.spinner : undefined} />
      </div>
      <h3>
        {isLoading
          ? '필요한 장면을 찾고 있어요'
          : isFailed
            ? '검색 결과를 불러오지 못했어요'
            : '조건에 맞는 장면이 없어요'}
      </h3>
      <p>
        {isLoading
          ? '검색어와 선택한 기간을 확인하고 있어요. 잠시만 기다려 주세요.'
          : isFailed
            ? '일시적인 연결 문제로 검색을 완료하지 못했어요. 입력한 검색어와 기간은 그대로 유지돼요.'
            : '기간을 넓히거나 검색어를 조금 더 간단하게 바꿔 보세요.'}
      </p>
      {isLoading ? (
        <div aria-hidden="true" className={styles.loadingBars}>
          <span />
          <span />
          <span />
        </div>
      ) : (
        <>
          <dl className={styles.searchConditions}>
            <div>
              <dt>검색어</dt>
              <dd>{query}</dd>
            </div>
            <div>
              <dt>방송일</dt>
              <dd>{formatDateRange(broadcastRange)}</dd>
            </div>
            <div>
              <dt>촬영일</dt>
              <dd>{formatDateRange(filmingRange)}</dd>
            </div>
            <div>
              <dt>검색 해석</dt>
              <dd>{isFailed ? '확인하지 못함' : '정상 완료 · 예시 검색'}</dd>
            </div>
            {!isFailed ? (
              <div>
                <dt>조건으로 제외</dt>
                <dd>{excludedCount}개</dd>
              </div>
            ) : null}
          </dl>
          <div className={styles.stateActions}>
            <button
              className={styles.primaryButton}
              onClick={isFailed ? onRetry : onReset}
              type="button"
            >
              {isFailed ? '같은 조건으로 다시 시도' : '기간 초기화하고 다시 검색'}
            </button>
            <button onClick={onEditQuery} type="button">
              검색어 수정
            </button>
          </div>
        </>
      )}
    </div>
  );
}
