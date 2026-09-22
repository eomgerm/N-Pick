import { CircleAlert, LoaderCircle, SearchX } from 'lucide-react';

import { type DateRange, formatDateRange } from '@/features/wireframes/date-range';
import styles from '@/features/wireframes/shinhan-search.module.css';
import {
  getResolverLabel,
  SearchExclusionDetails,
  type SearchResultDetails,
} from '@/features/wireframes/search-result-details';

interface SearchResultStateProps {
  state: 'empty' | 'failed' | 'loading';
  query: string;
  broadcastRange: DateRange;
  filmingRange: DateRange;
  details?: SearchResultDetails;
  /** 서버 왕복이 아닌 이유로 실패했을 때 그 설명. 있으면 다시 시도가 같은 결과를 낸다. */
  reason?: string;
  /** 실패 시 이 화면이 검색어·기간을 알고 있는지. true면 「입력한 검색어와 기간은 유지돼요」를 말하지 않는다. */
  conditionsUnknown?: boolean;
  onReset: () => void;
  onRetry: () => void;
}

export function SearchResultState({
  state,
  query,
  broadcastRange,
  filmingRange,
  details,
  reason,
  conditionsUnknown,
  onReset,
  onRetry,
}: SearchResultStateProps) {
  const isFailed = state === 'failed';
  const isRetryable = isFailed && !reason;
  const isLoading = state === 'loading';
  const Icon = isLoading ? LoaderCircle : isFailed ? CircleAlert : SearchX;
  return (
    <div
      aria-busy={isLoading}
      className={styles.statePanel}
      data-state={state}
      role={isFailed ? 'alert' : 'status'}
    >
      <div className={styles.stateSummary}>
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
          {isLoading ? (
            '검색어와 선택한 기간을 확인하고 있어요. 잠시만 기다려 주세요.'
          ) : reason && isFailed ? (
            // 「기간은 유지돼요」를 붙이지 않는다. 쓸 수 없는 기간은 접혀서 아래 조건에
            // 「전체 기간」으로 나오므로 유지됐다는 말과 화면이 어긋난다.
            reason
          ) : isFailed && conditionsUnknown ? (
            // 검색 기록 스냅샷처럼 서버 응답이 와야 검색어·기간을 알 수 있는 화면은, 그 응답
            // 자체가 실패하면 조건도 모른다 — 「유지돼요」라 하면 거짓이다.
            <>
              일시적인 연결 문제로 이 기록을 불러오지 못했어요.
              <br />이 화면에서는 검색어와 기간을 확인할 수 없어요.
            </>
          ) : isFailed ? (
            <>
              일시적인 연결 문제로 검색을 완료하지 못했어요.
              <br />
              입력한 검색어와 기간은 유지돼요.
            </>
          ) : (
            '이번 검색에서 반환된 장면은 0개예요. 적용 조건과 검색 상태를 확인해 주세요.'
          )}
        </p>
        {isLoading ? (
          <div aria-hidden="true" className={styles.loadingBars}>
            <span />
            <span />
            <span />
          </div>
        ) : null}
      </div>
      {!isLoading ? (
        <div className={styles.stateDetails}>
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
              <dd>{isFailed ? '확인하지 못함' : getResolverLabel(details?.resolverStatus)}</dd>
            </div>
            {!isFailed ? <SearchExclusionDetails details={details} /> : null}
          </dl>
          <div className={styles.stateActions}>
            <button
              className={styles.primaryButton}
              onClick={isRetryable ? onRetry : onReset}
              type="button"
            >
              {isRetryable ? '같은 조건으로 다시 시도' : '기간 초기화하고 다시 검색'}
            </button>
          </div>
        </div>
      ) : null}
    </div>
  );
}
