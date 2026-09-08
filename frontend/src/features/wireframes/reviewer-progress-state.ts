import type { ReviewBoardItem } from '@/features/wireframes/reviewer-board-state';

export type ProgressTab = 'inquiries' | 'uploads' | 'completed';

export interface ProgressInquiry extends ReviewBoardItem {
  stage: string;
}

export interface ProgressVideo {
  id: string;
  title: string;
  fileName: string;
  status: 'queued' | 'running' | 'failed' | 'succeeded';
  stage: string;
  completedSteps: number;
  totalSteps: number;
  description: string;
  canOpen: boolean;
}

export function getProgressOverview(inquiries: ProgressInquiry[], videos: ProgressVideo[]) {
  return {
    inquiries: {
      total: inquiries.length,
      closed: inquiries.filter((item) => item.status === 'closed').length,
      open: inquiries.filter((item) => item.status === 'open').length,
      active: inquiries.filter((item) => item.status === 'reviewing'),
    },
    videos: {
      total: videos.length,
      completed: videos.filter((item) => item.status === 'succeeded').length,
      completedItems: videos.filter((item) => item.status === 'succeeded'),
      queued: videos.filter((item) => item.status === 'queued').length,
      running: videos.filter((item) => item.status === 'running').length,
      failed: videos.filter((item) => item.status === 'failed').length,
      active: videos.filter((item) => item.status !== 'succeeded'),
    },
  };
}
