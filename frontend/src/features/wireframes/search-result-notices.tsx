import { AlertTriangle, CheckCircle2 } from 'lucide-react';

import {
  getDegradedReasonNotices,
  type SearchExecutionPresentation,
} from '@/features/wireframes/search-execution-status';
import styles from '@/features/wireframes/wireframe.module.css';

interface SearchResultNoticesProps {
  execution: SearchExecutionPresentation;
  variant: 'results' | 'preview';
  showSafetyNotice?: boolean;
}

export function SearchResultNotices({
  execution,
  variant,
  showSafetyNotice = true,
}: SearchResultNoticesProps) {
  const reasonNotices = getDegradedReasonNotices(execution.degradedReasons);

  if (execution.status !== 'degraded' && !execution.hasAppliedReviewRule && !showSafetyNotice) {
    return null;
  }

  return (
    <div className={styles.searchResultNotices} data-variant={variant}>
      {execution.status === 'degraded' ? (
        <section aria-label="검색 기능 누락 안내" className={styles.degradedNotice}>
          <div className={styles.degradedNoticeHeading}>
            <AlertTriangle aria-hidden="true" />
            <div>
              <span>일부 기능 누락</span>
              <strong>검색 결과를 확인하기 전에 아래 내용을 확인하세요.</strong>
            </div>
          </div>
          <ul className={styles.degradedReasonList}>
            {reasonNotices.map(({ description, reason, title }) => (
              <li key={reason}>
                <strong>{title}</strong>
                <span>{description}</span>
              </li>
            ))}
          </ul>
        </section>
      ) : null}

      {execution.hasAppliedReviewRule ? (
        <div className={styles.reviewRuleNotice}>
          <CheckCircle2 aria-hidden="true" />
          <div>
            <strong>검수 규칙 적용</strong>
            <span>아카이브 팀이 확인한 규칙을 이 검색에 반영했어요.</span>
          </div>
        </div>
      ) : null}

      {showSafetyNotice ? <SearchResultSafetyNotice variant={variant} /> : null}
    </div>
  );
}

export function SearchResultSafetyNotice({ variant }: Pick<SearchResultNoticesProps, 'variant'>) {
  return (
    <aside
      aria-label="송출 전 확인 안내"
      className={styles.resultSafetyNotice}
      data-variant={variant}
    >
      <AlertTriangle aria-hidden="true" />
      <p>
        <strong>송출 전 최종 확인</strong>
        내용·최신성·권리·사용 적합성을 확인하세요.
      </p>
    </aside>
  );
}
