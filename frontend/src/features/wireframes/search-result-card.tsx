'use client';

import { useState } from 'react';
import { Play } from 'lucide-react';

import { getVerificationStatusLabel, type SearchResult } from '@/features/wireframes/demo-scenes';
import { SceneHoverPreview } from '@/features/wireframes/scene-hover-preview';
import { formatMediaTime, getSceneThumbnailUrl } from '@/features/wireframes/scene-preview-media';
import { SceneThumbnail } from '@/features/wireframes/scene-thumbnail';
import styles from '@/features/wireframes/wireframe.module.css';

interface SearchResultCardProps {
  idPrefix?: string;
  result: SearchResult;
  position: number;
  isSelected: boolean;
  onSelect: (resultId: number) => void;
}

function getEvidenceFieldLabel(field: SearchResult['matchEvidence']['field']) {
  return field === '화면 속 글자 (OCR)' ? '화면 속 글자' : field;
}

function getEvidenceValueLabel(value: string) {
  return value.replaceAll(' · ', ', ');
}

export function SearchResultCard({
  idPrefix = '',
  result,
  position,
  isSelected,
  onSelect,
}: SearchResultCardProps) {
  // Hover and focus are tracked apart so that moving the mouse off a focused card,
  // or tabbing out of a hovered one, leaves the other reason to preview standing.
  const [isHovering, setIsHovering] = useState(false);
  const [isFocused, setIsFocused] = useState(false);
  const evidenceTooltipId = `${idPrefix}match-evidence-${result.id}`;
  // 실제 장면은 대표 이미지를 endpoint에서 받고, 장면 ID가 없는 데모 화면만 배경 이미지를 쓴다.
  const thumbnailUrl = getSceneThumbnailUrl(result.sceneId);

  return (
    <article
      className={`${styles.resultCard} ${isSelected ? styles.selectedCard : ''}`}
      onBlur={(event) => {
        if (!event.currentTarget.contains(event.relatedTarget)) setIsFocused(false);
      }}
      onFocus={() => setIsFocused(true)}
      onPointerEnter={() => setIsHovering(true)}
      onPointerLeave={() => setIsHovering(false)}
    >
      <div
        aria-label={thumbnailUrl ? undefined : result.imageLabel}
        className={`${styles.thumbnail} ${thumbnailUrl ? '' : (result.imageClass ?? '')}`}
        role={thumbnailUrl ? undefined : 'img'}
      >
        {thumbnailUrl ? (
          <SceneThumbnail alt={`${result.title} 대표 이미지`} src={thumbnailUrl} />
        ) : null}
        <span aria-label={`검색 결과 ${position}번째`} className={styles.rank}>
          {position}
        </span>
        <span aria-hidden="true" className={styles.playButton}>
          <Play fill="currentColor" />
          <span>장면 보기</span>
        </span>
        <span className={styles.timecode}>
          {formatMediaTime(result.sceneStart)} – {formatMediaTime(result.sceneEnd)}
        </span>
        {isHovering || isFocused ? (
          <SceneHoverPreview
            clipId={result.clipId}
            sceneEnd={result.sceneEnd}
            sceneStart={result.sceneStart}
          />
        ) : null}
      </div>

      <div className={styles.cardBody}>
        <h3 className={styles.cardTitle}>{result.title}</h3>

        <div className={styles.matchedKeywords}>
          <span className={styles.keywordList}>
            <span>키워드</span>
            {result.matchedKeywords.map((keyword) => (
              <span className={styles.keywordChip} key={keyword}>
                {keyword}
              </span>
            ))}
          </span>
          <span className={styles.evidenceTooltip}>
            <span
              aria-describedby={evidenceTooltipId}
              className={`${styles.statusBadge} ${styles.evidenceTooltipTrigger}`}
              data-status={result.matchEvidence.status}
              tabIndex={0}
            >
              {getVerificationStatusLabel(result.matchEvidence.status)}
            </span>
            <span className={styles.evidenceTooltipContent} id={evidenceTooltipId} role="tooltip">
              <strong>{getEvidenceFieldLabel(result.matchEvidence.field)}</strong>
              <span>{getEvidenceValueLabel(result.matchEvidence.value)}</span>
            </span>
          </span>
        </div>
      </div>
      <button
        aria-expanded={isSelected}
        aria-haspopup="dialog"
        aria-label={`${position}위 ${result.title} Preview 열기`}
        className={styles.cardSelectButton}
        onClick={() => onSelect(result.id)}
        title={`${result.title}\n키워드: ${result.matchedKeywords.join(', ')}`}
        type="button"
      />
    </article>
  );
}
