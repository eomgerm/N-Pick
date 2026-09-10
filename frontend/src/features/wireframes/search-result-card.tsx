import { Play } from 'lucide-react';

import {
  formatTimestamp,
  getVerificationStatusLabel,
  getKeyframeTimes,
  type SearchResult,
} from '@/features/wireframes/demo-scenes';
import styles from '@/features/wireframes/wireframe.module.css';

interface SearchResultCardProps {
  result: SearchResult;
  position: number;
  isSelected: boolean;
  onSelect: (resultId: number) => void;
}

export function SearchResultCard({
  result,
  position,
  isSelected,
  onSelect,
}: SearchResultCardProps) {
  const keyframeTimes = getKeyframeTimes(result);
  const filmingStatus = result.filmedDate ? result.filmingState : 'unknown';

  return (
    <article className={`${styles.resultCard} ${isSelected ? styles.selectedCard : ''}`}>
      <div
        aria-label={result.imageLabel}
        className={`${styles.thumbnail} ${result.imageClass}`}
        role="img"
      >
        <span aria-label={`검색 결과 ${position}번째`} className={styles.rank}>
          {position}
        </span>
        <span aria-hidden="true" className={styles.playButton}>
          <Play fill="currentColor" />
        </span>
        <span className={styles.timecode}>
          {formatTimestamp(result.sceneStart)} – {formatTimestamp(result.sceneEnd)}
        </span>
        <span aria-hidden="true" className={styles.keyframePreview}>
          {keyframeTimes.map((keyframeTime) => (
            <span
              className={`${styles.keyframe} ${result.imageClass}`}
              key={`${result.id}-${keyframeTime}`}
            >
              <span className={styles.keyframeTime}>{keyframeTime}</span>
            </span>
          ))}
        </span>
      </div>

      <div className={styles.cardBody}>
        <div className={styles.cardTopline}>
          <span className={styles.score}>일치도 {result.score}%</span>
          <h3 className={styles.cardTitle}>{result.title}</h3>
        </div>

        <div className={styles.matchedKeywords}>
          <span>키워드</span>
          {result.matchedKeywords.map((keyword) => (
            <span className={styles.keywordChip} key={keyword}>
              {keyword}
            </span>
          ))}
        </div>

        <section aria-label="일치 근거" className={styles.matchEvidence}>
          <div className={styles.matchEvidenceHeading}>
            <strong>일치 근거</strong>
            <span className={styles.statusBadge} data-status={result.matchEvidence.status}>
              {getVerificationStatusLabel(result.matchEvidence.status)}
            </span>
          </div>
          <dl className={styles.matchEvidenceDetails}>
            <div>
              <dt>필드</dt>
              <dd>{result.matchEvidence.field}</dd>
            </div>
            <div>
              <dt>값</dt>
              <dd>{result.matchEvidence.value}</dd>
            </div>
            <div>
              <dt>출처</dt>
              <dd>{result.matchEvidence.source}</dd>
            </div>
          </dl>
        </section>

        <div className={styles.informationStatus} aria-label="정보 상태">
          <span className={styles.statusBadge} data-status={filmingStatus}>
            촬영일 {getVerificationStatusLabel(filmingStatus)}
          </span>
        </div>
      </div>
      <button
        aria-expanded={isSelected}
        aria-haspopup="dialog"
        aria-label={`${position}위 ${result.title} Preview 열기`}
        className={styles.cardSelectButton}
        onClick={() => onSelect(result.id)}
        type="button"
      />
    </article>
  );
}
