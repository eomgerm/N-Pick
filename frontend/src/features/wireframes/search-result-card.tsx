'use client';

import { useState } from 'react';
import { AlertTriangle, Check, Flag, Play } from 'lucide-react';

import {
  getKeywordOriginLabel,
  getVerificationStatusLabel,
  type SearchResult,
} from '@/features/wireframes/demo-scenes';
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
  // 문의(신고)를 카드에서 바로 연다. 없으면(데모 등) 버튼을 그리지 않는다.
  onInquiry?: (resultId: number) => void;
  isInquirySubmitted?: boolean;
  // 값이 있으면 문의 불가 사유. 버튼을 비활성으로 두고 사유를 안내한다.
  inquiryUnavailableReason?: string;
}

function getEvidenceFieldLabel(field: SearchResult['matchEvidence']['field']) {
  return field === '화면 속 글자 (OCR)' ? '화면 속 글자' : field;
}

/** hover 로만 보이는 요약에도 화면과 같은 구분을 싣는다. 칩에서 라벨을 읽은 사람과 다른 사실을 보게 두지 않는다. */
function formatKeywordSummary(keywords: SearchResult['matchedKeywords']) {
  return keywords
    .map(({ keyword, origin }) => {
      const originLabel = getKeywordOriginLabel(origin);
      return originLabel === null ? keyword : `${keyword}(${originLabel})`;
    })
    .join(', ');
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
  onInquiry,
  isInquirySubmitted = false,
  inquiryUnavailableReason,
}: SearchResultCardProps) {
  // Hover and focus are tracked apart so that moving the mouse off a focused card,
  // or tabbing out of a hovered one, leaves the other reason to preview standing.
  const [isHovering, setIsHovering] = useState(false);
  const [isFocused, setIsFocused] = useState(false);
  const evidenceTooltipId = `${idPrefix}match-evidence-${result.id}`;
  const inquiryReasonId = `${idPrefix}inquiry-reason-${result.id}`;
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
        {onInquiry ? (
          <span className={styles.cardInquiry}>
            <button
              aria-describedby={inquiryUnavailableReason ? inquiryReasonId : undefined}
              aria-disabled={inquiryUnavailableReason ? true : undefined}
              aria-label={`${result.title} 문의하기`}
              className={styles.cardInquiryButton}
              data-state={
                isInquirySubmitted
                  ? 'submitted'
                  : inquiryUnavailableReason
                    ? 'unavailable'
                    : 'ready'
              }
              disabled={isInquirySubmitted}
              onClick={(event) => {
                // 카드 전체가 Preview 열기 버튼이므로 문의 클릭이 그쪽으로 새지 않게 막는다.
                event.stopPropagation();
                if (!isInquirySubmitted && !inquiryUnavailableReason) onInquiry(result.id);
              }}
              type="button"
            >
              {isInquirySubmitted ? (
                <Check aria-hidden="true" />
              ) : inquiryUnavailableReason ? (
                <AlertTriangle aria-hidden="true" />
              ) : (
                <Flag aria-hidden="true" />
              )}
            </button>
            {inquiryUnavailableReason ? (
              <span className={styles.cardInquiryTooltip} id={inquiryReasonId} role="tooltip">
                {inquiryUnavailableReason}
              </span>
            ) : null}
          </span>
        ) : null}
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

        {/* 계약상 네 값뿐이고 `unknown`은 '정보 없음'으로 온다. 「샷 유형」 이름을 함께 두어야
            그 값이 무엇에 대한 정보 없음인지 카드에서 바로 읽힌다(FRD §6.3). */}
        <p className={styles.cardShotType}>
          <span>샷 유형</span>
          <span className={styles.cardShotTypeValue}>{result.shotType}</span>
        </p>

        <div className={styles.matchedKeywords}>
          <span className={styles.keywordList}>
            <span>키워드</span>
            {result.matchedKeywords.map(({ keyword, origin }) => {
              // 색상만으로 구분하지 않는다 (FRD 6.3) — 사용자가 넣지 않은 말에는 라벨을 붙이고,
              // data-origin 은 그 라벨과 같은 사실을 스타일·테스트가 함께 읽도록 둔다.
              const originLabel = getKeywordOriginLabel(origin);
              return (
                <span className={styles.keywordChip} data-origin={origin} key={keyword}>
                  {keyword}
                  {originLabel === null ? null : (
                    <span className={styles.keywordChipOrigin}>({originLabel})</span>
                  )}
                </span>
              );
            })}
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
        title={`${result.title}\n키워드: ${formatKeywordSummary(result.matchedKeywords)}`}
        type="button"
      />
    </article>
  );
}
