'use client';

import { Play } from 'lucide-react';
import { useState } from 'react';

import type { Inquiry } from '@/features/wireframes/reviewer-inquiries';
import { ScenePreviewDialog } from '@/features/wireframes/scene-dialogs';
import type { WireframeTheme } from '@/features/wireframes/wireframe-themes';
import styles from '@/features/wireframes/reviewer.module.css';

interface ReviewerScenePreviewProps {
  inquiry: Inquiry;
  theme: WireframeTheme;
}

export function ReviewerScenePreview({ inquiry, theme }: ReviewerScenePreviewProps) {
  const [isOpen, setIsOpen] = useState(false);
  const [sceneStart, sceneEnd] = inquiry.timecode.split('–').map((time) => {
    const [minutes, seconds] = time.split(':').map(Number);
    return minutes * 60 + seconds;
  });
  const imageClass = styles[`scene${inquiry.thumbnail}`];

  return (
    <>
      <button
        className={styles.sceneContext}
        type="button"
        aria-haspopup="dialog"
        aria-label={`선택된 장면 재생: ${inquiry.sceneTitle}, ${inquiry.timecode}`}
        onClick={() => setIsOpen(true)}
      >
        <span className={`${styles.scenePreview} ${imageClass}`}>
          <Play aria-hidden="true" fill="currentColor" />
          <span>{inquiry.timecode}</span>
        </span>
        <span>
          <span className={styles.cardLabel}>선택된 장면 · 눌러서 재생</span>
          <strong>{inquiry.sceneTitle}</strong>
          <span className={styles.sceneMeta}>
            검색 결과 {inquiry.rank}번째 · {inquiry.timecode}
          </span>
        </span>
      </button>
      {isOpen ? (
        <ScenePreviewDialog
          theme={theme}
          autoPlay
          contextLabel={`문의 ${inquiry.id} · 선택된 장면`}
          notice="영상 미리보기 데모입니다. 실제 영상 파일은 아직 연결되지 않았어요."
          result={{
            id: inquiry.sceneId,
            title: inquiry.sceneTitle,
            sceneStart,
            sceneEnd,
            duration: `${sceneEnd - sceneStart}초`,
            evidenceType: '영상에서 확인한 내용',
            evidence: inquiry.evidence,
            source: inquiry.guard,
            imageClass,
            imageLabel: `${inquiry.sceneTitle} 미리보기`,
          }}
          onClose={() => setIsOpen(false)}
        />
      ) : null}
    </>
  );
}
