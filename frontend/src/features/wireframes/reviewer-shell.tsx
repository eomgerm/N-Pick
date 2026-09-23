'use client';

import { useQueryClient } from '@tanstack/react-query';
import { ArrowLeft, Info } from 'lucide-react';
import { usePathname, useRouter, useSearchParams } from 'next/navigation';
import { useEffect, useRef, useState, useTransition } from 'react';

import { ProcessingClipDetail } from '@/features/wireframes/processing-clip-detail';
import { ReviewInquiryWorkspace } from '@/features/wireframes/review-inquiry-workspace';
import { getReviewTabUrl, getReviewUrl } from '@/features/wireframes/reviewer-board-state';
import { ReviewerLayout } from '@/features/wireframes/reviewer-layout';
import { ReviewerProgress, ReviewerProgressHeading } from '@/features/wireframes/reviewer-progress';
import progressStyles from '@/features/wireframes/reviewer-progress.module.css';
import {
  VideoRegistration,
  VideoRegistrationHeading,
  type RegisteredVideo,
} from '@/features/wireframes/video-registration';
import type { ClipRegistrationOutcome } from '@/features/wireframes/video-registration-api';
import type { WireframeTheme } from '@/features/wireframes/wireframe-themes';
import { SuccessToast } from '@/features/wireframes/success-toast';
import styles from '@/features/wireframes/reviewer.module.css';

interface ReviewerShellProps {
  theme: WireframeTheme;
}

const registrationNotices: Record<ClipRegistrationOutcome, { label: string; heading: string }> = {
  created: { label: '등록 완료', heading: '영상 등록 완료' },
  duplicate_own: { label: '이미 등록된 영상', heading: '이미 등록한 영상입니다.' },
  duplicate_other: {
    label: '이미 등록된 영상',
    heading: '다른 사용자가 이미 등록한 영상입니다.',
  },
};

// 중복이면 입력한 제목·파일명이 아니라 실제로 열리는 clip 을 설명한다. 둘을 섞으면 남의 영상을 내 것으로 읽는다.
const duplicateNoticeDetail =
  '같은 영상 파일이 이미 등록되어 있어 아래에 기존 등록 정보를 표시합니다. 이번에 입력한 제목과 날짜는 저장되지 않았습니다.';

export function ReviewerShell({ theme }: ReviewerShellProps) {
  const router = useRouter();
  const pathname = usePathname();
  const searchParams = useSearchParams();
  const queryClient = useQueryClient();
  const [isNavigating, startNavigation] = useTransition();
  const [isRegistrationBusy, setIsRegistrationBusy] = useState(false);
  const [registeredVideo, setRegisteredVideo] = useState<RegisteredVideo | null>(null);
  const registrationDetailShownRef = useRef<string | null>(null);
  const registrationBusyRef = useRef(false);
  const isProcessing = searchParams.get('view') === 'processing';
  const isRegistration = searchParams.get('view') === 'upload';
  const clipId = searchParams.get('clip');
  const isInteractionLocked = isNavigating || isRegistrationBusy;

  useEffect(() => {
    const registeredId = registeredVideo?.id;
    if (!registeredId) return;

    const isRegisteredDetail = isProcessing && clipId === registeredId;
    const isPendingRegistrationNavigation =
      isRegistration && registrationDetailShownRef.current !== registeredId;

    if (isRegisteredDetail) {
      registrationDetailShownRef.current = registeredId;
      return;
    }
    if (isPendingRegistrationNavigation) return;

    registrationDetailShownRef.current = null;
    setRegisteredVideo(null);
  }, [clipId, isProcessing, isRegistration, registeredVideo]);

  function handleLocationChange(updates: Record<string, string | null>) {
    if (registrationBusyRef.current) return;
    startNavigation(() => {
      router.push(getReviewUrl(pathname, searchParams.toString(), updates), { scroll: false });
    });
  }
  function handleTabChange(tab: 'inquiries' | 'processing') {
    if (registrationBusyRef.current) return;
    startNavigation(() => {
      router.push(getReviewTabUrl(pathname, searchParams.toString(), tab), { scroll: false });
    });
  }
  function handleRegistrationOpen() {
    registrationDetailShownRef.current = null;
    setRegisteredVideo(null);
    handleLocationChange({
      view: 'upload',
      tab: null,
      clip: null,
      inquiry: null,
      progressPage: null,
    });
  }
  function handleRegister(record: RegisteredVideo) {
    registrationDetailShownRef.current = null;
    setRegisteredVideo(record);
    void queryClient.invalidateQueries({ queryKey: ['processing-clips'] });
    void queryClient.invalidateQueries({ queryKey: ['processing-clip', record.id] });
    // Registration returns a real ID; the detail query owns all subsequent processing state.
    startNavigation(() => {
      router.push(
        getReviewUrl(pathname, searchParams.toString(), {
          view: 'processing',
          // 칩이 걸려 있으면 방금 올린 영상(no_run)이 목록에서 빠진다.
          clipStatus: null,
          clip: record.id,
          progressPage: null,
        }),
        { scroll: false },
      );
    });
  }

  if (!isProcessing && !isRegistration) return <ReviewInquiryWorkspace theme={theme} />;

  return (
    <ReviewerLayout
      theme={theme}
      headerContent={
        isRegistration ? (
          <VideoRegistrationHeading
            isDisabled={isInteractionLocked}
            onBack={() => handleTabChange('inquiries')}
          />
        ) : isProcessing && clipId ? (
          <div className={progressStyles.detailHeader}>
            <p>영상 등록 처리 상세</p>
            <button
              type="button"
              className={progressStyles.backButton}
              disabled={isInteractionLocked}
              onClick={() => handleLocationChange({ clip: null })}
            >
              <ArrowLeft aria-hidden="true" /> 처리 현황으로
            </button>
          </div>
        ) : isProcessing ? (
          <ReviewerProgressHeading
            isNavigating={isInteractionLocked}
            onRegistrationOpen={handleRegistrationOpen}
          />
        ) : undefined
      }
      isInteractionLocked={isInteractionLocked}
      onTabChange={handleTabChange}
      onRegistrationOpen={handleRegistrationOpen}
    >
      <main className={styles.page}>
        {isRegistration ? (
          <VideoRegistration
            isNavigating={isNavigating}
            onBusyChange={(isBusy) => {
              registrationBusyRef.current = isBusy;
              setIsRegistrationBusy(isBusy);
            }}
            onCancel={() => handleTabChange('inquiries')}
            onRegister={handleRegister}
          />
        ) : clipId ? (
          <>
            {/* 등록 성공(created)은 완료 사실만 알리는 일회성 피드백이라 자동 소멸 토스트로
                띄운다. 중복 등록(duplicate_*)은 아래에 열리는 기존 등록 정보를 설명하는 안내라
                계속 남겨 둔다 (S15P21A501-303). */}
            {registeredVideo?.id === clipId && registeredVideo.outcome !== 'created' ? (
              <section
                aria-label="영상 등록 결과"
                aria-live="polite"
                className={`${styles.registrationNotice} ${styles.duplicateNotice}`}
                role="status"
              >
                <Info aria-hidden="true" />
                <div>
                  <p>{registrationNotices[registeredVideo.outcome].label}</p>
                  <h2>{registrationNotices[registeredVideo.outcome].heading}</h2>
                  <span>{duplicateNoticeDetail}</span>
                </div>
              </section>
            ) : null}
            <SuccessToast
              message={
                registeredVideo?.id === clipId && registeredVideo.outcome === 'created'
                  ? `${registrationNotices.created.heading} · ${registeredVideo.fileName} · 처리 대기 상태로 상세 화면에서 진행 상황을 확인할 수 있습니다.`
                  : ''
              }
            />
            <ProcessingClipDetail key={clipId} clipId={clipId} />
          </>
        ) : (
          <ReviewerProgress
            isNavigating={isInteractionLocked}
            onFilterChange={handleLocationChange}
            onVideoSelect={(id) => handleLocationChange({ clip: id, inquiry: null })}
            onPageChange={(page) =>
              handleLocationChange({ progressPage: page === 1 ? null : String(page) })
            }
          />
        )}
      </main>
    </ReviewerLayout>
  );
}
