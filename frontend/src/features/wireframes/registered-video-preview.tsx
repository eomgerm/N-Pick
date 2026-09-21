'use client';

import { Play } from 'lucide-react';
import { useState } from 'react';

import { formatTimestamp } from '@/features/wireframes/demo-scenes';
import type { ProcessingClip } from '@/features/wireframes/registration-processing';
import { formatSceneDuration } from '@/features/wireframes/scene-preview-media';
import { ScenePreviewDialog } from '@/features/wireframes/scene-dialogs';
import type { WireframeTheme } from '@/features/wireframes/wireframe-themes';
import styles from '@/features/wireframes/reviewer.module.css';
import sceneStyles from '@/features/wireframes/wireframe.module.css';

interface RegisteredVideoPreviewProps {
  clip: ProcessingClip;
  theme: WireframeTheme;
}

export function RegisteredVideoPreview({ clip, theme }: RegisteredVideoPreviewProps) {
  const [isOpen, setIsOpen] = useState(false);
  const scenes = [...(clip.scenes ?? [])]
    .sort((left, right) => left.start - right.start)
    .map((scene) => ({
      id: scene.id,
      title: scene.title,
      clip: clip.fileName,
      sceneStart: scene.start,
      sceneEnd: scene.end,
      duration: formatSceneDuration(scene.end - scene.start),
      totalSeconds: clip.totalSeconds,
      totalDuration: clip.totalSeconds ? formatTimestamp(clip.totalSeconds) : undefined,
      broadcastDate: clip.broadcastDate,
      shotType: scene.shotType,
      evidenceType: '화면 설명',
      evidence: scene.description,
      source: '장면 설명 예시 · 미검증',
      imageClass: sceneStyles.imageOne,
      imageLabel: `${scene.title} · 예시 이미지`,
    }));

  return (
    <>
      <button
        aria-haspopup="dialog"
        className={styles.primaryButton}
        disabled={scenes.length === 0}
        onClick={() => setIsOpen(true)}
        title={scenes.length === 0 ? '아직 확인할 구간 정보가 없어요' : undefined}
        type="button"
      >
        <Play aria-hidden="true" />
        영상 확인하기
      </button>
      {isOpen && scenes[0] ? (
        <ScenePreviewDialog
          contextLabel={`${clip.title} · 전체 구간 확인`}
          notice={`구간과 이미지는 시연용 예시이며 실제 영상 재생은 연결 전이에요.${
            clip.missingChannels.length > 0
              ? ` 포함되지 않은 정보 · ${clip.missingChannels.join(', ')}`
              : ''
          }`}
          onClose={() => setIsOpen(false)}
          result={scenes[0]}
          scenes={scenes}
          theme={theme}
        />
      ) : null}
    </>
  );
}
