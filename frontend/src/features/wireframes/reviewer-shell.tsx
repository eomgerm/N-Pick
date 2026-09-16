'use client';

import { useQueryClient } from '@tanstack/react-query';
import { ArrowLeft } from 'lucide-react';
import { usePathname, useRouter, useSearchParams } from 'next/navigation';
import { useRef, useState, useTransition } from 'react';

import { ProcessingClipDetail } from '@/features/wireframes/processing-clip-detail';
import { InquiryDetail } from '@/features/wireframes/review-inquiry-detail';
import { ReviewInquiryWorkspace } from '@/features/wireframes/review-inquiry-workspace';
import { getReviewTabUrl, getReviewUrl } from '@/features/wireframes/reviewer-board-state';
import { ReviewerLayout } from '@/features/wireframes/reviewer-layout';
import { ReviewerProgress, ReviewerProgressHeading } from '@/features/wireframes/reviewer-progress';
import type { ProgressTab } from '@/features/wireframes/reviewer-progress-state';
import progressStyles from '@/features/wireframes/reviewer-progress.module.css';
import {
  VideoRegistration,
  VideoRegistrationHeading,
  type RegisteredVideo,
} from '@/features/wireframes/video-registration';
import type { WireframeTheme } from '@/features/wireframes/wireframe-themes';
import styles from '@/features/wireframes/reviewer.module.css';

interface ReviewerShellProps {
  theme: WireframeTheme;
}

export function ReviewerShell({ theme }: ReviewerShellProps) {
  const router = useRouter();
  const pathname = usePathname();
  const searchParams = useSearchParams();
  const queryClient = useQueryClient();
  const [isNavigating, startNavigation] = useTransition();
  const [isRegistrationBusy, setIsRegistrationBusy] = useState(false);
  const registrationBusyRef = useRef(false);
  const isProcessing = searchParams.get('view') === 'processing';
  const isRegistration = searchParams.get('view') === 'upload';
  const clipId = searchParams.get('clip');
  const feedbackId = searchParams.get('inquiry');
  const rawTab = searchParams.get('tab');
  const progressTab: ProgressTab =
    rawTab === 'completed' || rawTab === 'uploads' ? rawTab : 'inquiries';
  const isInteractionLocked = isNavigating || isRegistrationBusy;

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
    handleLocationChange({
      view: 'upload',
      tab: null,
      clip: null,
      inquiry: null,
      progressPage: null,
    });
  }
  function handleRegister(record: RegisteredVideo) {
    void queryClient.invalidateQueries({ queryKey: ['processing-clips'] });
    void queryClient.invalidateQueries({ queryKey: ['processing-clip', record.id] });
    // Registration returns a real ID; the detail query owns all subsequent processing state.
    startNavigation(() => {
      router.push(
        getReviewUrl(pathname, searchParams.toString(), {
          view: 'processing',
          tab: 'uploads',
          clip: record.id,
          inquiry: null,
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
          <button
            type="button"
            className={progressStyles.backButton}
            disabled={isInteractionLocked}
            onClick={() => handleLocationChange({ clip: null })}
          >
            <ArrowLeft aria-hidden="true" /> 처리 현황으로
          </button>
        ) : isProcessing && !feedbackId ? (
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
          <ProcessingClipDetail key={clipId} clipId={clipId} />
        ) : feedbackId ? (
          <InquiryDetail key={feedbackId} feedbackId={feedbackId} theme={theme} />
        ) : (
          <ReviewerProgress
            activeTab={progressTab}
            isNavigating={isInteractionLocked}
            onTabChange={(tab) =>
              handleLocationChange({ tab: tab === 'inquiries' ? null : tab, progressPage: null })
            }
            onInquirySelect={(id) => handleLocationChange({ inquiry: id, clip: null })}
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
