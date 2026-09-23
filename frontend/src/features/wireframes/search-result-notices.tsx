import { AlertTriangle, CheckCircle2 } from 'lucide-react';

import {
  getDegradedReasonNotices,
  type SearchExecutionPresentation,
} from '@/features/wireframes/search-execution-status';
import styles from '@/features/wireframes/wireframe.module.css';

interface SearchResultNoticesProps {
  execution: SearchExecutionPresentation;
  variant: 'results' | 'preview';
}

export function SearchResultNotices({ execution, variant }: SearchResultNoticesProps) {
  const reasonNotices = getDegradedReasonNotices(execution.degradedReasons);

  if (execution.status !== 'degraded' && !execution.hasAppliedReviewRule) {
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
    </div>
  );
}
