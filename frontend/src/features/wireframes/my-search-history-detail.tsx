'use client';

import { useQuery } from '@tanstack/react-query';
import { AlertTriangle, ChevronDown, Clock3, Search, X } from 'lucide-react';
import { useState } from 'react';

import { ApiErrorNotice } from '@/components/api-error-notice';
import { useMember } from '@/components/session-boundary';
import { formatDateRange } from '@/features/wireframes/date-range';
import {
  getMySearchHistoryDetail,
  mySearchHistoryKeys,
} from '@/features/wireframes/my-search-history-api';
import { SceneDialog, ScenePreviewDialog } from '@/features/wireframes/scene-dialogs';
import { SearchResultCard } from '@/features/wireframes/search-result-card';
import {
  getResolverLabel,
  SearchExclusionDetails,
} from '@/features/wireframes/search-result-details';
import { getDegradedReasonNotices } from '@/features/wireframes/search-execution-status';
import { presentSearchResponse } from '@/features/wireframes/search-results-api';
import type { WireframeTheme } from '@/features/wireframes/wireframe-themes';
import styles from '@/features/wireframes/my-search-history-detail.module.css';

interface MySearchHistoryDetailProps {
  executionId: string;
  theme: WireframeTheme;
  onClose: () => void;
}

export function MySearchHistoryDetail({ executionId, theme, onClose }: MySearchHistoryDetailProps) {
  const { memberId } = useMember();
  const [selectedRank, setSelectedRank] = useState<number | null>(null);
  const detail = useQuery({
    queryKey: mySearchHistoryKeys.detail(memberId, executionId),
    queryFn: ({ signal }) => getMySearchHistoryDetail(executionId, signal),
    staleTime: 0,
  });
  const item = detail.data;
  const view = item?.searchSnapshot ? presentSearchResponse(item.searchSnapshot) : null;
  const selected = view?.results.find((result) => result.id === selectedRank);
  const notices = view ? getDegradedReasonNotices(view.execution.degradedReasons) : [];

  return (
    <>
      <SceneDialog
        theme={theme}
        className={styles.dialog}
        labelledBy="search-history-detail-title"
        describedBy="search-history-description"
        onClose={onClose}
      >
        <header className={styles.header}>
          <div className={styles.headingRow}>
            <h2 id="search-history-detail-title">검색 기록 상세</h2>
            <button
              aria-label="검색 기록 상세 닫기"
              className={styles.close}
              onClick={onClose}
              type="button"
            >
              <X aria-hidden="true" />
            </button>
          </div>
          {item ? (
            <>
              <p className={styles.query}>
                <Search aria-hidden="true" />
                <span>{item.queryText}</span>
              </p>
              <div className={styles.metadata}>
                <time dateTime={item.createdAt}>
                  <Clock3 aria-hidden="true" />
                  {new Date(item.createdAt).toLocaleString('ko-KR', { timeZone: 'Asia/Seoul' })}
                </time>
                <span className={styles.status} data-degraded={item.status === 'degraded'}>
                  {item.status === 'degraded' ? '일부 기능 누락' : '검색 완료'}
                </span>
              </div>
            </>
          ) : null}
          <p id="search-history-description" className={styles.description}>
            검색 당시 저장된 조건과 결과입니다.
          </p>
        </header>
        <div className={styles.body}>
          {detail.isPending ? (
            <p className={styles.empty} role="status">
              당시 검색 결과를 불러오는 중…
            </p>
          ) : null}
          {detail.isError ? (
            <div className={styles.error}>
              <ApiErrorNotice error={detail.error} />
              {item ? <p>마지막으로 확인한 기록입니다.</p> : null}
              <button
                className={styles.retry}
                disabled={detail.isFetching}
                onClick={() => void detail.refetch()}
                type="button"
              >
                검색 기록 상세 다시 시도
              </button>
            </div>
          ) : null}
          {item ? (
            <div className={styles.layout}>
              <aside className={styles.conditions} aria-label="당시 검색 조건">
                <h3>검색 조건</h3>
                {item.explicitFilters === null ? (
                  <p className={styles.conditionMissing}>당시 검색 조건을 확인할 수 없습니다.</p>
                ) : (
                  <dl className={styles.dates}>
                    <div>
                      <dt>방송일</dt>
                      <dd>
                        {formatDateRange(
                          item.explicitFilters.broadcast_date ?? { from: '', to: '' },
                        )}
                      </dd>
                    </div>
                    <div>
                      <dt>촬영일</dt>
                      <dd>
                        {formatDateRange(item.explicitFilters.filmed_date ?? { from: '', to: '' })}
                      </dd>
                    </div>
                  </dl>
                )}
                {view ? (
                  <details className={styles.processing}>
                    <summary>
                      검색 처리 정보 <ChevronDown aria-hidden="true" />
                    </summary>
                    <dl>
                      <div>
                        <dt>검색 해석</dt>
                        <dd>{getResolverLabel(view.details.resolverStatus)}</dd>
                      </div>
                      <SearchExclusionDetails details={view.details} />
                    </dl>
                  </details>
                ) : null}
              </aside>
              <section className={styles.results} aria-labelledby="history-results-title">
                <div className={styles.resultsHeading}>
                  <h3 id="history-results-title">
                    당시 검색 결과 {view ? <span>{view.results.length}건</span> : null}
                  </h3>
                  {view && view.results.length > 0 ? (
                    <p>장면을 선택하면 영상을 확인할 수 있습니다.</p>
                  ) : null}
                </div>
                {notices.length > 0 ? (
                  <section aria-label="검색 기능 누락 안내" className={styles.notice}>
                    <AlertTriangle aria-hidden="true" />
                    <ul>
                      {notices.map(({ reason, title, description }) => (
                        <li key={reason}>
                          <strong>{title}</strong>
                          <p>{description}</p>
                        </li>
                      ))}
                    </ul>
                  </section>
                ) : null}
                {view?.execution.hasAppliedReviewRule ? (
                  <p className={styles.reviewRule}>
                    검수 규칙 적용 · 아카이브 팀이 확인한 규칙을 반영한 결과입니다.
                  </p>
                ) : null}
                {view ? (
                  view.results.length === 0 ? (
                    <p className={styles.empty} role="status">
                      당시 검색 결과는 0건입니다.
                    </p>
                  ) : (
                    <div className={styles.cards}>
                      {view.results.map((result) => (
                        <SearchResultCard
                          key={result.id}
                          result={result}
                          position={result.rank ?? result.id}
                          idPrefix={`history-${executionId}-`}
                          isSelected={selectedRank === result.id}
                          onSelect={setSelectedRank}
                        />
                      ))}
                    </div>
                  )
                ) : (
                  <p className={styles.empty} role="status">
                    당시 검색 결과를 복원할 수 없습니다. 저장된 결과 기록이 없거나 불완전합니다.
                  </p>
                )}
              </section>
            </div>
          ) : null}
        </div>
        <footer className={styles.footer} aria-label="송출 전 확인 안내">
          <AlertTriangle aria-hidden="true" />
          <p>
            <strong>송출 전 최종 확인</strong> 내용·최신성·권리·사용 적합성을 확인하세요.
          </p>
        </footer>
      </SceneDialog>
      {selected && view ? (
        <ScenePreviewDialog
          result={selected}
          scenes={view.results}
          theme={theme}
          searchExecution={view.execution}
          contextLabel="이전 검색 결과"
          notice="검색 당시 저장된 결과와 근거입니다."
          onClose={() => setSelectedRank(null)}
        />
      ) : null}
    </>
  );
}
