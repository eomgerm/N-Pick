import { Play } from 'lucide-react';

import {
  formatTimestamp,
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

function formatResultDate(value: string | null): string {
  return value ?? '미상';
}

export function SearchResultCard({
  result,
  position,
  isSelected,
  onSelect,
}: SearchResultCardProps) {
  const keyframeTimes = getKeyframeTimes(result);

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
          <div>
            <p className={styles.cardDisplayName}>{result.displayName}</p>
            <h3 className={styles.cardTitle}>{result.title}</h3>
          </div>
        </div>

        <dl className={styles.cardMetadata}>
          <div className={styles.cardMetadataItem}>
            <dt>방송일</dt>
            <dd>{formatResultDate(result.broadcastDate)}</dd>
          </div>
          <div className={styles.cardMetadataItem}>
            <dt>촬영일</dt>
            <dd>{formatResultDate(result.filmedDate)}</dd>
          </div>
          <div className={styles.cardMetadataItem}>
            <dt>샷 유형</dt>
            <dd>{result.shotType}</dd>
          </div>
          <div className={styles.cardMetadataItem}>
            <dt>장면 유형</dt>
            <dd>{result.sceneType}</dd>
          </div>
          <div className={`${styles.cardMetadataItem} ${styles.cardMetadataWide}`}>
            <dt>장면 구간</dt>
            <dd>
              {formatTimestamp(result.sceneStart)} – {formatTimestamp(result.sceneEnd)}
            </dd>
          </div>
        </dl>

        <div className={styles.matchedKeywords}>
          <span>키워드</span>
          {result.matchedKeywords.map((keyword) => (
            <span className={styles.keywordChip} key={keyword}>
              {keyword}
            </span>
          ))}
        </div>
      </div>
      <button
        aria-expanded={isSelected}
        aria-haspopup="dialog"
        aria-label={`${position}위 ${result.displayName}, ${result.title} Preview 열기`}
        className={styles.cardSelectButton}
        onClick={() => onSelect(result.id)}
        type="button"
      />
    </article>
  );
}
