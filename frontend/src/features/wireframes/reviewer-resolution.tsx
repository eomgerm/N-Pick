import { Sparkles } from 'lucide-react';

import styles from '@/features/wireframes/reviewer-resolution.module.css';
import { getResolutionSummary } from '@/features/wireframes/reviewer-resolution-state';

interface ResolutionSummaryProps {
  value: string;
  title?: string;
}

export function ResolutionSummary({ value, title }: ResolutionSummaryProps) {
  return <ResolutionSummaryView facts={getResolutionSummary(value)} title={title} />;
}

interface ResolutionSummaryViewProps {
  facts: ReturnType<typeof getResolutionSummary>;
  title?: string;
}

export function ResolutionSummaryView({
  facts,
  title = '검색어를 이렇게 이해했어요',
}: ResolutionSummaryViewProps) {
  return (
    <section className={styles.summary} aria-label={title}>
      <h3>
        <Sparkles aria-hidden="true" />
        {title}
      </h3>
      <p>검색을 돕기 위한 해석입니다. 실제 영상 정보와 다를 수 있어요.</p>
      <dl>
        {facts.map(({ label, value: text }) => (
          <div key={label}>
            <dt>{label}</dt>
            <dd>{text}</dd>
          </div>
        ))}
      </dl>
    </section>
  );
}
