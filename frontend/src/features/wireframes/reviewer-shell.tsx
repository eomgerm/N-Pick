'use client';

import {
  ArrowLeft,
  CheckCircle2,
  ChevronRight,
  CircleAlert,
  Clock3,
  History,
  Layers3,
  RefreshCw,
  Search,
  ShieldCheck,
  Sparkles,
  TriangleAlert,
  UserCheck,
} from 'lucide-react';
import Link from 'next/link';
import { usePathname, useRouter, useSearchParams } from 'next/navigation';
import {
  type FormEvent,
  type ReactNode,
  useEffect,
  useMemo,
  useRef,
  useState,
  useTransition,
} from 'react';

import type { WireframeTheme } from '@/features/wireframes/wireframe-themes';
import styles from './reviewer.module.css';
import { inquiries } from '@/features/wireframes/reviewer-inquiries';
import { ReviewerBoard } from '@/features/wireframes/reviewer-board';
import { getReviewUrl } from '@/features/wireframes/reviewer-board-state';
import boardStyles from '@/features/wireframes/reviewer-board.module.css';
import {
  VideoRegistration,
  type RegisteredVideo,
  type VideoRegistrationDraft,
} from '@/features/wireframes/video-registration';
import { ReviewerProgress } from '@/features/wireframes/reviewer-progress';
import type { ProgressTab, ProgressVideo } from '@/features/wireframes/reviewer-progress-state';
import { ResolutionEditor, ResolutionSummary } from '@/features/wireframes/reviewer-resolution';
import {
  completedDemoClip,
  getRegisteredClip,
  type PipelineStage,
  type ProcessingClip,
} from '@/features/wireframes/registration-processing';
import { formatFileSize } from '@/features/wireframes/registration-files';
import registrationStyles from '@/features/wireframes/shinhan-registration.module.css';
import { ReviewerScenePreview } from '@/features/wireframes/reviewer-scene-preview';
import { RegisteredVideoPreview } from '@/features/wireframes/registered-video-preview';
import {
  hasValidResolutionDates,
  normalizeResolution,
} from '@/features/wireframes/reviewer-resolution-state';

interface ReviewerShellProps {
  theme: WireframeTheme;
}

type WorkspaceTab = 'processing' | 'inquiries';
type InquiryStatus = 'pending' | 'reviewing' | 'resolved' | 'dismissed' | 'deferred';
type ReviewAction = 'resolution_patch' | 'exclude_scene' | 'no_action' | 'deferred_p1';
type DeferredField = '' | 'entity' | 'ocr' | 'caption' | 'date' | 'other';
type ReviewStep = 'inspect' | 'edit' | 'verify';

interface InquiryDraft {
  action: ReviewAction;
  reason: string;
  resolutionPatch: string;
  excludeTargetId: string;
  deferredField: DeferredField;
  deferredTarget: string;
  deferredDescription: string;
}

interface OverrideCandidate {
  action: 'resolution_patch' | 'exclude_scene';
  id: string;
  payload: string;
  version: number;
}

interface ReplayResult {
  executionId: string;
  overrideId: string;
  overrideVersion: number;
}

interface TerminalOutcome {
  action: ReviewAction;
  appliedPayload?: string;
  closedAt: string;
  deferredSummary?: string;
  executionId?: string;
  overrideId?: string;
  overrideVersion?: number;
  reason: string;
  targetSummary?: string;
  status: Extract<InquiryStatus, 'resolved' | 'dismissed' | 'deferred'>;
}

interface InquiryWork {
  baselineResults: string[] | null;
  step: ReviewStep;
  candidate: OverrideCandidate | null;
  candidateHistory: OverrideCandidate[];
  draft: InquiryDraft;
  outcome: TerminalOutcome | null;
  replay: ReplayResult | null;
  replayAttempt: number;
  status: InquiryStatus;
}

interface ValidationErrors {
  description?: string;
  field?: string;
  patch?: string;
  reason?: string;
  target?: string;
}

const processingClips: ProcessingClip[] = [
  {
    id: 'clip-0192',
    title: '설 연휴 첫날 서울역 귀성 인파',
    fileName: 'KBC_20260214_뉴스9_교통.mp4',
    servingStatus: 'ready',
    servingSummary: '이전에 등록된 영상은 계속 검색할 수 있어요.',
    latestRun: 'running',
    latestRunLabel: '영상 속 글자를 읽고 있어요',
    activeVersion: 'pipeline-v3.7',
    indexVersion: 'idx-2026.09.04-03',
    missingChannels: [],
    retryable: false,
    stages: [
      { label: '장면 나누기', status: 'succeeded', summary: '24개 장면' },
      { label: '대표 화면 고르기', status: 'succeeded', summary: '대표 화면 58개를 골랐어요' },
      {
        label: '영상 속 글자 읽기',
        status: 'running',
        summary: '대표 화면 58개 중 38개를 읽었어요',
      },
      { label: '검색에 반영하기', status: 'pending', summary: '앞 단계가 끝나면 시작해요' },
    ],
  },
  {
    id: 'clip-0188',
    title: '경부고속도로 양방향 정체',
    fileName: 'KBC_20250930_추석교통.mp4',
    servingStatus: 'ready',
    servingSummary: '이전에 등록된 영상은 계속 검색할 수 있어요.',
    latestRun: 'failed',
    latestRunLabel: '검색에 반영하지 못했어요',
    activeVersion: 'pipeline-v2.9',
    indexVersion: 'idx-2026.08.31-12',
    missingChannels: ['신규 generation (기존 v2 제공)'],
    retryable: true,
    requestId: 'req_9f27b1',
    stages: [
      { label: '장면 나누기', status: 'succeeded', summary: '31개 장면' },
      {
        label: '영상 정보 정리하기',
        status: 'succeeded',
        summary: '영상 정보를 정리했어요. 확인이 필요한 항목이 1개 있어요',
      },
      { label: '검색 준비하기', status: 'succeeded', summary: '31개 장면의 검색 준비를 마쳤어요' },
      {
        label: '검색에 반영하기',
        status: 'failed',
        summary: '준비된 검색 자료를 확인하는 중 문제가 생겼어요',
      },
    ],
  },
  {
    id: 'clip-0179',
    title: '한국도로공사 교통상황실',
    fileName: 'KBC_20251002_도로공사.mp4',
    servingStatus: 'failed',
    servingSummary: '검색 제공 불가',
    latestRun: 'failed',
    latestRunLabel: '영상을 장면별로 나누지 못했어요',
    activeVersion: '없음',
    indexVersion: '없음',
    missingChannels: ['전체 검색 채널'],
    retryable: true,
    requestId: 'req_8b04dc',
    stages: [
      {
        label: '영상 파일 확인하기',
        status: 'succeeded',
        summary: '2분 4초 영상 파일을 확인했어요',
      },
      { label: '장면 나누기', status: 'failed', summary: '영상 파일을 읽다가 중단됐어요' },
      { label: '대표 화면 고르기', status: 'pending', summary: '앞 단계가 끝나면 시작해요' },
      { label: '검색에 반영하기', status: 'pending', summary: '앞 단계가 끝나면 시작해요' },
    ],
  },
];

const actionLabels: Record<ReviewAction, string> = {
  resolution_patch: '검색어의 의미 바로잡기',
  exclude_scene: '이 검색에서 장면 제외',
  no_action: '수정 없이 마무리',
  deferred_p1: '담당팀 확인 요청',
};

const actionDescriptions: Record<ReviewAction, string> = {
  resolution_patch: '검색어를 잘못 이해했을 때',
  exclude_scene: '검색과 관계없는 장면이 나올 때',
  no_action: '결과에 문제가 없거나 확인하기 어려울 때',
  deferred_p1: '영상에 기록된 정보 자체가 잘못됐을 때',
};

const deferredFieldLabels: Record<Exclude<DeferredField, ''>, string> = {
  entity: '인물·기관 정보',
  ocr: '영상 속 글자',
  caption: '장면 설명',
  date: '날짜 정보',
  other: '기타',
};

const statusLabels: Record<InquiryStatus, string> = {
  pending: '접수',
  reviewing: '검수 중',
  resolved: '수정 완료',
  dismissed: '수정 없이 완료',
  deferred: '담당팀 확인 필요',
};

const stageLabels: Record<PipelineStage['status'] | 'queued', string> = {
  queued: '대기',
  succeeded: '완료',
  running: '진행 중',
  failed: '실패',
  pending: '대기',
};

function getStatusIcon(
  status: InquiryStatus | PipelineStage['status'] | 'ready' | 'queued',
): ReactNode {
  if (status === 'resolved' || status === 'succeeded' || status === 'ready') {
    return <CheckCircle2 aria-hidden="true" />;
  }
  if (status === 'failed') {
    return <TriangleAlert aria-hidden="true" />;
  }
  if (status === 'reviewing' || status === 'running') {
    return <RefreshCw aria-hidden="true" />;
  }
  if (status === 'pending' || status === 'queued') {
    return <Clock3 aria-hidden="true" />;
  }
  if (status === 'deferred') {
    return <Layers3 aria-hidden="true" />;
  }
  return <CircleAlert aria-hidden="true" />;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function isValidResolutionSnapshot(value: string): boolean {
  try {
    const parsed: unknown = JSON.parse(value);
    if (!isRecord(parsed)) return false;

    return (
      parsed.schema_version === 'resolution-v1' &&
      parsed.intent === 'scene_search' &&
      Array.isArray(parsed.date_windows) &&
      hasValidResolutionDates(value) &&
      Array.isArray(parsed.incident_names) &&
      Array.isArray(parsed.entities) &&
      Array.isArray(parsed.locations) &&
      Array.isArray(parsed.expanded_terms) &&
      typeof parsed.confidence === 'number' &&
      parsed.confidence >= 0 &&
      parsed.confidence <= 1
    );
  } catch {
    return false;
  }
}

function createInitialWork(): Record<string, InquiryWork> {
  return Object.fromEntries(
    inquiries.map((inquiry) => [
      inquiry.id,
      {
        baselineResults: null,
        step: 'inspect',
        candidate: null,
        candidateHistory: [],
        draft: {
          action: 'exclude_scene',
          reason: '',
          resolutionPatch: inquiry.initialResolution,
          excludeTargetId: inquiry.sceneId,
          deferredField: '',
          deferredTarget: '',
          deferredDescription: '',
        },
        outcome:
          inquiry.initialStatus === 'dismissed'
            ? {
                action: 'no_action',
                reason: '같은 검색 조건에서 확인했으며 별도 조치가 필요하지 않아 종료했습니다.',
                closedAt: inquiry.receivedAt,
                status: 'dismissed',
              }
            : null,
        replay: null,
        replayAttempt: 0,
        status: inquiry.initialStatus,
      } satisfies InquiryWork,
    ]),
  );
}

export function ReviewerShell({ theme }: ReviewerShellProps) {
  const router = useRouter();
  const pathname = usePathname();
  const searchParams = useSearchParams();
  const [isNavigating, startNavigation] = useTransition();
  const diagnosisRef = useRef<HTMLElement | null>(null);
  const inspectionRef = useRef<HTMLElement | null>(null);
  const verificationRef = useRef<HTMLElement | null>(null);
  const historyRef = useRef<HTMLElement | null>(null);
  const detailTitleRef = useRef<HTMLHeadingElement | null>(null);
  const previousDetail = useRef<{ id: string; status: InquiryStatus; step: ReviewStep } | null>(
    null,
  );
  const isProcessing = searchParams.get('view') === 'processing';
  const progressTab: ProgressTab =
    searchParams.get('tab') === 'completed'
      ? 'completed'
      : searchParams.get('tab') === 'uploads'
        ? 'uploads'
        : 'inquiries';
  const selectedClipId = searchParams.get('clip');
  const [registeredVideos, setRegisteredVideos] = useState<RegisteredVideo[]>([]);
  const availableClips = [
    ...processingClips,
    completedDemoClip,
    ...registeredVideos.map(getRegisteredClip),
  ];
  const activeTab: WorkspaceTab =
    isProcessing && availableClips.some(({ id }) => id === selectedClipId)
      ? 'processing'
      : 'inquiries';
  const [retryRequestedIds, setRetryRequestedIds] = useState<string[]>([]);
  const isRegistration = searchParams.get('view') === 'upload';
  const selectedInquiryId = searchParams.get('inquiry');
  const [inquiryWork, setInquiryWork] = useState(createInitialWork);
  const [validationErrors, setValidationErrors] = useState<ValidationErrors>({});
  const [liveMessage, setLiveMessage] = useState('문의 내용을 확인할 수 있어요.');

  const selectedClip = availableClips.find(({ id }) => id === selectedClipId) ?? processingClips[0];
  const selectedInquiry = inquiries.find(({ id }) => id === selectedInquiryId) ?? inquiries[0];
  const isBoard = !isProcessing && !inquiries.some(({ id }) => id === selectedInquiryId);
  const isProgress =
    isProcessing &&
    activeTab !== 'processing' &&
    !inquiries.some(({ id }) => id === selectedInquiryId);
  const selectedWork = inquiryWork[selectedInquiry.id];
  const isRetryRequested = retryRequestedIds.includes(selectedClip.id);
  const displayedLatestRun = isRetryRequested ? 'running' : selectedClip.latestRun;
  const displayedLatestRunLabel = isRetryRequested
    ? '다시 시도할 순서를 기다리고 있어요'
    : selectedClip.latestRunLabel;
  const queueCounts = useMemo(
    () => ({
      pending: Object.values(inquiryWork).filter(({ status }) => status === 'pending').length,
      reviewing: Object.values(inquiryWork).filter(({ status }) => status === 'reviewing').length,
      completed: Object.values(inquiryWork).filter(({ status }) =>
        ['resolved', 'dismissed', 'deferred'].includes(status),
      ).length,
    }),
    [inquiryWork],
  );
  const progressVideos: ProgressVideo[] = [
    ...availableClips.map((clip) => ({
      id: clip.id,
      title: clip.title,
      fileName: clip.fileName,
      status: retryRequestedIds.includes(clip.id) ? ('queued' as const) : clip.latestRun,
      stage: retryRequestedIds.includes(clip.id)
        ? '중단된 단계 다시 시도 대기'
        : clip.latestRunLabel,
      completedSteps: clip.stages.filter((stage) => stage.status === 'succeeded').length,
      totalSteps: clip.stages.length,
      description:
        clip.stages.find((stage) => stage.status === 'running' || stage.status === 'failed')
          ?.summary ?? clip.servingSummary,
      canOpen: true,
    })),
  ];

  useEffect(() => {
    if (isRegistration || isProgress || isBoard) {
      previousDetail.current = null;
      return;
    }
    const id = activeTab === 'inquiries' ? selectedInquiry.id : selectedClip.id;
    if (previousDetail.current?.id !== id) {
      detailTitleRef.current?.focus({ preventScroll: true });
      window.scrollTo({ top: 0, behavior: 'instant' });
    } else if (
      activeTab === 'inquiries' &&
      (previousDetail.current.status !== selectedWork.status ||
        previousDetail.current.step !== selectedWork.step)
    ) {
      const target =
        selectedWork.status !== 'reviewing'
          ? historyRef
          : selectedWork.step === 'inspect'
            ? inspectionRef
            : selectedWork.step === 'edit'
              ? diagnosisRef
              : verificationRef;
      target.current?.focus();
    }
    previousDetail.current = { id, status: selectedWork.status, step: selectedWork.step };
  }, [
    activeTab,
    isBoard,
    isProgress,
    isRegistration,
    selectedClip.id,
    selectedInquiry.id,
    selectedWork.status,
    selectedWork.step,
  ]);

  function updateSelectedWork(updater: (current: InquiryWork) => InquiryWork) {
    setInquiryWork((current) => ({
      ...current,
      [selectedInquiry.id]: updater(current[selectedInquiry.id]),
    }));
  }

  function handleLocationChange(updates: Record<string, string | null>) {
    startNavigation(() => {
      router.push(getReviewUrl(pathname, searchParams.toString(), updates), { scroll: false });
    });
  }

  function handleTabChange(tab: WorkspaceTab) {
    handleLocationChange({
      view: tab === 'processing' ? 'processing' : null,
      tab: null,
      clip: null,
      inquiry: null,
    });
    setLiveMessage(tab === 'processing' ? '처리 현황을 열었습니다.' : '문의 목록을 열었습니다.');
  }

  function handleProgressTabChange(tab: ProgressTab) {
    handleLocationChange({
      view: 'processing',
      tab: tab === 'inquiries' ? null : tab,
      inquiry: null,
      clip: null,
    });
  }

  function handleRegistrationOpen() {
    handleLocationChange({ view: 'upload', inquiry: null, clip: null });
  }

  function handleRegister(draft: VideoRegistrationDraft) {
    const record: RegisteredVideo = {
      id: crypto.randomUUID(),
      fileName: draft.video.name,
      fileSize: draft.video.size,
      sourceType: draft.sourceType,
      broadcastDate: draft.broadcastDate,
      attachments: draft.attachments.map((file) => file.name),
    };
    setRegisteredVideos((current) => [record, ...current]);
    handleProgressTabChange('uploads');
    setLiveMessage(
      `${record.fileName} 등록 내용을 데모 대기 목록에 추가했습니다. 실제 파일은 전송되지 않았습니다.`,
    );
  }

  function handleClipSelect(clipId: string) {
    const clip = availableClips.find(({ id }) => id === clipId);
    handleLocationChange({
      view: 'processing',
      tab: progressTab === 'completed' ? 'completed' : 'uploads',
      clip: clipId,
      inquiry: null,
    });
    if (clip) setLiveMessage(`${clip.title} 처리 상세를 열었습니다.`);
  }

  function handleRetry() {
    if (!selectedClip.retryable || retryRequestedIds.includes(selectedClip.id)) return;
    setRetryRequestedIds((current) => [...current, selectedClip.id]);
    setLiveMessage(`${selectedClip.title} 중단된 단계 다시 시도를 요청했습니다.`);
  }

  function handleInquirySelect(inquiryId: string) {
    const inquiry = inquiries.find(({ id }) => id === inquiryId);
    handleLocationChange({
      inquiry: inquiryId,
      view: isProcessing ? 'processing' : null,
      clip: null,
    });
    setValidationErrors({});
    if (inquiry) setLiveMessage(`${inquiry.id} 문의의 저장된 검수 상태를 열었습니다.`);
  }

  function handleClaim() {
    if (selectedWork.status !== 'pending') return;
    updateSelectedWork((current) => ({ ...current, status: 'reviewing' }));
    setLiveMessage(
      `${selectedInquiry.id} 문의를 맡았습니다. 당시 결과를 살펴보고 같은 조건으로 다시 검색해 주세요.`,
    );
  }

  function handleBaselineReplay() {
    if (selectedWork.status !== 'reviewing') return;
    updateSelectedWork((current) => ({
      ...current,
      baselineResults: [...selectedInquiry.beforeTop10],
      candidate: null,
      replay: null,
      step: 'inspect',
    }));
    setLiveMessage(
      '같은 검색어와 필터로 다시 검색한 예시 결과 10개를 표시했어요. 문의 장면이 여전히 나오는지 확인해 주세요.',
    );
  }

  function handleReviewStep(step: ReviewStep) {
    if (
      selectedWork.status !== 'reviewing' ||
      (step !== 'inspect' && !selectedWork.baselineResults)
    )
      return;
    if (step === 'verify' && !selectedWork.candidate) return;
    updateSelectedWork((current) => ({ ...current, step }));
  }

  function changeDraft(patch: Partial<InquiryDraft>, fieldLabel: string) {
    const hadVerification = Boolean(selectedWork.candidate || selectedWork.replay);
    updateSelectedWork((current) => ({
      ...current,
      candidate: null,
      draft: { ...current.draft, ...patch },
      replay: null,
      step: 'edit',
    }));
    setValidationErrors((current) => {
      if ('action' in patch) return {};
      const next = { ...current };
      if ('resolutionPatch' in patch) delete next.patch;
      if ('excludeTargetId' in patch || 'deferredTarget' in patch) delete next.target;
      if ('deferredField' in patch) delete next.field;
      if ('deferredDescription' in patch) delete next.description;
      if ('reason' in patch) delete next.reason;
      return next;
    });
    if (hadVerification) {
      setLiveMessage(`${fieldLabel}이 바뀌었어요. 수정 내용을 저장하고 결과를 다시 확인해 주세요.`);
    }
  }

  function validateDecision() {
    const { draft } = selectedWork;
    const errors: ValidationErrors = {};

    if (!draft.reason.trim()) errors.reason = '처리 이유를 입력해 주세요.';
    if (draft.action === 'resolution_patch' && !isValidResolutionSnapshot(draft.resolutionPatch)) {
      errors.patch =
        '기간의 시작일과 종료일을 모두 입력하고, 종료일이 시작일보다 빠르지 않은지 확인해 주세요.';
    }
    if (draft.action === 'exclude_scene' && draft.excludeTargetId !== selectedInquiry.sceneId) {
      errors.target = '문의가 참조한 장면을 제외 대상으로 선택해 주세요.';
    }
    if (draft.action === 'deferred_p1') {
      if (!draft.deferredField) errors.field = '확인이 필요한 정보 종류를 선택해 주세요.';
      if (!selectedInquiry.contextIds.includes(draft.deferredTarget.trim())) {
        errors.target = '확인이 필요한 대상을 선택해 주세요.';
      }
      if (!draft.deferredDescription.trim()) {
        errors.description = '교정이 필요한 내용을 설명해 주세요.';
      }
    }

    setValidationErrors(errors);
    return errors;
  }

  function handleDecisionSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (
      selectedWork.status !== 'reviewing' ||
      !selectedWork.baselineResults ||
      selectedWork.step !== 'edit'
    )
      return;
    const errors = validateDecision();
    if (Object.keys(errors).length > 0) {
      setLiveMessage('조치를 저장할 수 없습니다. 연결된 입력 오류를 확인해 주세요.');
      return;
    }

    const { action, reason } = selectedWork.draft;
    if (action === 'no_action') {
      updateSelectedWork((current) => ({
        ...current,
        outcome: {
          action,
          closedAt: '2026.09.04 15:08',
          reason,
          status: 'dismissed',
        },
        status: 'dismissed',
      }));
      setLiveMessage(`${selectedInquiry.id} 문의를 수정 없이 완료했습니다.`);
      return;
    }

    if (action === 'deferred_p1') {
      const { deferredDescription, deferredField, deferredTarget } = selectedWork.draft;
      updateSelectedWork((current) => ({
        ...current,
        outcome: {
          action,
          closedAt: '2026.09.04 15:08',
          deferredSummary: `${deferredField ? deferredFieldLabels[deferredField] : ''} · ${deferredTarget === selectedInquiry.sceneId ? selectedInquiry.sceneTitle : selectedInquiry.evidence} · ${deferredDescription}`,
          reason,
          status: 'deferred',
        },
        status: 'deferred',
      }));
      setLiveMessage(
        `${selectedInquiry.id} 문의에 담당팀 확인이 필요한 내용을 기록했습니다. 영상 정보가 수정된 상태는 아닙니다.`,
      );
      return;
    }

    const version = (selectedWork.candidateHistory.at(-1)?.version ?? 1) + 1;
    const candidate: OverrideCandidate = {
      action,
      id: `ovr_${selectedInquiry.id.toLowerCase().replace('-', '_')}_v${version}`,
      payload:
        action === 'resolution_patch'
          ? normalizeResolution(selectedWork.draft.resolutionPatch)
          : JSON.stringify({
              processing_version: selectedInquiry.sceneVersion,
              scene_id: selectedInquiry.sceneId,
            }),
      version,
    };
    updateSelectedWork((current) => ({
      ...current,
      candidate,
      candidateHistory: [...current.candidateHistory, candidate],
      replay: null,
      step: 'verify',
    }));
    setLiveMessage('수정 내용을 저장했어요. 수정 후 결과를 확인한 뒤 완료해 주세요.');
  }

  function handleReplay() {
    if (
      selectedWork.status !== 'reviewing' ||
      !selectedWork.baselineResults ||
      !selectedWork.candidate ||
      selectedWork.step !== 'verify'
    )
      return;
    const isExcludeValid =
      selectedWork.candidate.action !== 'exclude_scene' ||
      !selectedInquiry.afterTop10SceneIds.includes(selectedInquiry.sceneId);
    const isPatchValid =
      selectedWork.candidate.action !== 'resolution_patch' ||
      isValidResolutionSnapshot(selectedWork.candidate.payload);

    if (!isExcludeValid || !isPatchValid) {
      setLiveMessage(
        isExcludeValid
          ? '결과를 확인하지 못했어요. 검색어의 의미를 다시 저장해 주세요.'
          : '제외한 장면이 검색 결과에 남아 있어요. 수정 내용을 다시 확인해 주세요.',
      );
      return;
    }

    const replayAttempt = selectedWork.replayAttempt + 1;
    const executionId = `exec_replay_${selectedInquiry.id
      .toLowerCase()
      .replace('-', '_')}_${String(replayAttempt).padStart(2, '0')}`;
    updateSelectedWork((current) => ({
      ...current,
      replay: {
        executionId,
        overrideId: current.candidate!.id,
        overrideVersion: current.candidate!.version,
      },
      replayAttempt,
    }));
    setLiveMessage('수정 후 예시 결과를 표시했어요. 결과를 비교한 뒤 수정을 완료할 수 있어요.');
  }

  function handleResolve() {
    if (
      selectedWork.status !== 'reviewing' ||
      !selectedWork.baselineResults ||
      selectedWork.step !== 'verify' ||
      !selectedWork.candidate ||
      !selectedWork.replay ||
      selectedWork.candidate.id !== selectedWork.replay.overrideId ||
      selectedWork.candidate.version !== selectedWork.replay.overrideVersion
    )
      return;
    const { action, reason } = selectedWork.draft;
    updateSelectedWork((current) => ({
      ...current,
      outcome: {
        action,
        appliedPayload: action === 'resolution_patch' ? current.candidate!.payload : undefined,
        closedAt: '2026.09.04 15:08',
        executionId: current.replay!.executionId,
        overrideId: current.replay!.overrideId,
        overrideVersion: current.replay!.overrideVersion,
        reason,
        targetSummary:
          action === 'resolution_patch'
            ? '문의 당시와 같은 검색어와 필터에 검색 해석 적용'
            : `${selectedInquiry.sceneTitle} · ${selectedInquiry.timecode} · 문의 당시와 같은 검색에서만 제외`,
        status: 'resolved',
      },
      status: 'resolved',
    }));
    setLiveMessage(`${selectedInquiry.id} 문의의 수정 결과를 확인하고 완료했습니다.`);
  }

  return (
    <div className={styles.shell} data-theme={theme}>
      <header className={styles.appHeader}>
        <Link className={styles.brand} href="/landing" aria-label="N-Pick 홈">
          <span className={styles.brandMark} aria-hidden="true">
            <span />
            <span />
          </span>
          <span>N-Pick</span>
        </Link>
        <nav className={styles.primaryNav} aria-label="역할별 화면">
          <Link href={`/search/${theme}`}>장면 검색</Link>
          <Link aria-current="page" className={styles.primaryNavActive} href={`/review/${theme}`}>
            검수자 화면
          </Link>
        </nav>
        <span className={styles.roleBadge}>
          <ShieldCheck aria-hidden="true" /> 검수자
        </span>
      </header>

      <main className={styles.page}>
        {isRegistration ? (
          <VideoRegistration
            isNavigating={isNavigating}
            onCancel={() => handleTabChange('inquiries')}
            onRegister={handleRegister}
          />
        ) : isProgress ? (
          <ReviewerProgress
            showCompleted
            activeTab={progressTab}
            inquiries={inquiries.map((inquiry) => ({
              ...inquiry,
              status: inquiryWork[inquiry.id].status,
              stage: inquiryWork[inquiry.id].replay
                ? '수정 결과 확인 완료 · 최종 처리 대기'
                : inquiryWork[inquiry.id].candidate
                  ? '수정 내용 저장됨 · 결과 확인 대기'
                  : '문의 내용과 검색 결과 검수 중',
            }))}
            isNavigating={isNavigating}
            onBack={() => handleTabChange('inquiries')}
            onInquirySelect={handleInquirySelect}
            onRegistrationOpen={handleRegistrationOpen}
            onTabChange={handleProgressTabChange}
            onVideoSelect={handleClipSelect}
            videos={progressVideos}
          />
        ) : isBoard ? (
          <ReviewerBoard
            isNavigating={isNavigating}
            items={inquiries.map((inquiry) => ({
              ...inquiry,
              status: inquiryWork[inquiry.id].status,
            }))}
            onInquirySelect={handleInquirySelect}
            onLocationChange={handleLocationChange}
            onProcessingOpen={() => handleTabChange('processing')}
            onRegistrationOpen={handleRegistrationOpen}
          />
        ) : (
          <>
            <button
              className={boardStyles.backButton}
              disabled={isNavigating}
              onClick={() =>
                isProcessing ? handleProgressTabChange(progressTab) : handleTabChange('inquiries')
              }
              type="button"
            >
              <ArrowLeft aria-hidden="true" />
              {isProcessing ? '처리 현황으로' : '문의 목록으로'}
            </button>
            <section className={styles.hero}>
              <div>
                <p className={styles.eyebrow}>검수자 화면</p>
                <h1 ref={detailTitleRef} tabIndex={-1} className={styles.detailTitle}>
                  {activeTab === 'processing'
                    ? selectedClip.latestRun === 'succeeded'
                      ? '영상 등록 완료'
                      : '영상 등록 상세'
                    : '문의 처리 상세'}
                </h1>
                <p>
                  {activeTab === 'processing'
                    ? '영상의 현재 단계와 처리 결과를 확인하세요.'
                    : '문의 내용을 확인하고 필요한 조치를 진행하세요.'}
                </p>
              </div>
              <div className={styles.operatorCard}>
                <span className={styles.operatorAvatar} aria-hidden="true">
                  나
                </span>
                <span>
                  <small>현재 검수자</small>
                  <strong>나현우 · 아카이빙팀</strong>
                </span>
              </div>
            </section>

            <p aria-live="polite" className={styles.visuallyHidden} role="status">
              {liveMessage}
            </p>

            {activeTab === 'processing' ? (
              <section
                aria-label="영상 등록 상세"
                className={styles.tabPanel}
                id="processing-panel"
                tabIndex={0}
              >
                <div className={boardStyles.detailLayout}>
                  <article className={styles.detailPanel}>
                    <div className={styles.detailHeading}>
                      <div>
                        <span className={styles.detailId}>등록한 영상</span>
                        <h2>{selectedClip.title}</h2>
                        <p>{selectedClip.fileName}</p>
                      </div>
                      <div className={styles.detailActions}>
                        {selectedClip.latestRun === 'succeeded' &&
                        selectedClip.servingStatus === 'ready' ? (
                          <RegisteredVideoPreview
                            key={selectedClip.id}
                            clip={selectedClip}
                            theme={theme}
                          />
                        ) : selectedClip.servingStatus === 'ready' ? (
                          <Link
                            className={styles.primaryButton}
                            href={`/wireframes/${theme}?q=${encodeURIComponent(selectedClip.title)}`}
                          >
                            <Search aria-hidden="true" />
                            영상 검색하기
                          </Link>
                        ) : (
                          <button className={styles.primaryButton} disabled type="button">
                            <Search aria-hidden="true" />
                            검색 불가
                          </button>
                        )}
                        {selectedClip.retryable ? (
                          <button
                            className={styles.secondaryButton}
                            disabled={
                              !selectedClip.retryable || retryRequestedIds.includes(selectedClip.id)
                            }
                            onClick={handleRetry}
                            type="button"
                          >
                            <RefreshCw aria-hidden="true" />
                            {retryRequestedIds.includes(selectedClip.id)
                              ? '재시도 요청됨'
                              : '중단된 단계 다시 시도'}
                          </button>
                        ) : null}
                      </div>
                    </div>

                    {selectedClip.latestRun === 'succeeded' ? (
                      <section className={registrationStyles.completion} role="status">
                        <CheckCircle2 aria-hidden="true" />
                        <div>
                          <h3>영상 등록이 완료됐어요</h3>
                          <p>
                            {selectedClip.sceneCount}개 장면을 검색에 반영했어요.
                            <br />
                            완료 시각 · {selectedClip.completedAt}
                          </p>
                        </div>
                      </section>
                    ) : null}

                    <div className={styles.stateComparison}>
                      <section>
                        <span className={styles.cardLabel}>현재 검색 제공</span>
                        <strong>
                          {getStatusIcon(selectedClip.servingStatus)}
                          {selectedClip.servingStatus === 'ready'
                            ? '검색할 수 있어요'
                            : '아직 검색할 수 없어요'}
                        </strong>
                        <p>{selectedClip.servingSummary}</p>
                      </section>
                      <section>
                        <span className={styles.cardLabel}>최근 등록 작업</span>
                        <strong>
                          {getStatusIcon(displayedLatestRun)}
                          {stageLabels[displayedLatestRun]}
                        </strong>
                        <p>{displayedLatestRunLabel}</p>
                      </section>
                    </div>

                    {selectedClip.registration ? (
                      <section className={registrationStyles.registrationInfo}>
                        <h3>등록한 파일 정보</h3>
                        <dl>
                          <div>
                            <dt>파일 이름</dt>
                            <dd>{selectedClip.registration.fileName}</dd>
                          </div>
                          <div>
                            <dt>파일 크기</dt>
                            <dd>{formatFileSize(selectedClip.registration.fileSize)}</dd>
                          </div>
                          <div>
                            <dt>영상 형식</dt>
                            <dd>
                              {selectedClip.registration.sourceType === 'broadcast'
                                ? '방영본'
                                : '원본'}
                            </dd>
                          </div>
                          <div>
                            <dt>방영일</dt>
                            <dd>{selectedClip.registration.broadcastDate || '미입력'}</dd>
                          </div>
                          <div>
                            <dt>첨부 파일</dt>
                            <dd>{selectedClip.registration.attachments.join(', ') || '없음'}</dd>
                          </div>
                        </dl>
                      </section>
                    ) : null}
                    {selectedClip.latestRun === 'succeeded' &&
                    selectedClip.missingChannels.length > 0 ? (
                      <p className={registrationStyles.notice}>
                        포함되지 않은 정보 · {selectedClip.missingChannels.join(', ')}
                        <br />
                        다른 장면 정보로 검색할 수 있어요. 필요한 내용은 원본 영상에서 확인해
                        주세요.
                      </p>
                    ) : null}

                    {displayedLatestRun === 'failed' ? (
                      <div className={styles.errorNotice} role="alert">
                        <TriangleAlert aria-hidden="true" />
                        <div>
                          <strong>{selectedClip.latestRunLabel}</strong>
                          <p>
                            {selectedClip.servingStatus === 'ready'
                              ? '이전에 등록된 영상은 계속 검색할 수 있어요. 중단된 단계부터 다시 시도해 주세요.'
                              : '원본 영상이 정상적으로 재생되는지 확인한 뒤 다시 시도해 주세요.'}
                          </p>
                        </div>
                      </div>
                    ) : null}

                    <section className={styles.timelineSection}>
                      <SectionHeading
                        eyebrow="등록 진행 상황"
                        title="단계별 처리"
                        meta={displayedLatestRunLabel}
                      />
                      <ol className={styles.stageTimeline}>
                        {isRetryRequested ? (
                          <li className={styles.running}>
                            <span className={styles.stageMarker}>
                              {getStatusIcon('running')}
                              <b>1</b>
                            </span>
                            <span>
                              <strong>중단된 단계 다시 시도</strong>
                              <small>요청을 접수했어요. 순서가 되면 다시 시작해요.</small>
                            </span>
                            <em>진행 중</em>
                          </li>
                        ) : (
                          selectedClip.stages.map((stage, index) => (
                            <li className={styles[stage.status]} key={stage.label}>
                              <span className={styles.stageMarker}>
                                {getStatusIcon(stage.status)}
                                <b>{index + 1}</b>
                              </span>
                              <span>
                                <strong>{stage.label}</strong>
                                <small>{stage.summary}</small>
                              </span>
                              <em>{stageLabels[stage.status]}</em>
                            </li>
                          ))
                        )}
                      </ol>
                    </section>
                    <details className={registrationStyles.processingInfo}>
                      <summary>처리 정보 확인</summary>
                      <dl>
                        <div>
                          <dt>현재 제공하는 처리 버전</dt>
                          <dd>{selectedClip.activeVersion}</dd>
                        </div>
                        <div>
                          <dt>검색 반영 버전</dt>
                          <dd>{selectedClip.indexVersion}</dd>
                        </div>
                        {selectedClip.requestId ? (
                          <div>
                            <dt>오류 문의 번호</dt>
                            <dd>{selectedClip.requestId}</dd>
                          </div>
                        ) : null}
                      </dl>
                    </details>
                    <p className={registrationStyles.demo}>
                      {selectedClip.registration
                        ? '이 화면에서 등록한 데모 영상입니다. 실제 파일 전송과 처리는 연결 전이며, 새로고침하면 등록 내용이 초기화돼요.'
                        : '화면 확인을 위한 예시 처리 내역입니다.'}
                    </p>
                  </article>
                </div>
              </section>
            ) : (
              <section
                aria-label="문의 처리 상세"
                className={styles.tabPanel}
                id="inquiries-panel"
                tabIndex={0}
              >
                <div className={styles.queueSummary}>
                  <span>
                    <Clock3 aria-hidden="true" />
                    <strong>{queueCounts.pending}</strong> 접수 대기
                  </span>
                  <span>
                    <RefreshCw aria-hidden="true" />
                    <strong>{queueCounts.reviewing}</strong> 검수 중
                  </span>
                  <span>
                    <CheckCircle2 aria-hidden="true" />
                    <strong>{queueCounts.completed}</strong> 완료된 문의
                  </span>
                  <p>문의 내용을 확인하고 처리 방법을 선택해 주세요.</p>
                </div>
                <div className={boardStyles.detailLayout}>
                  <article className={styles.detailPanel}>
                    <div className={styles.detailHeading}>
                      <div>
                        <span className={styles.detailId}>
                          문의 {selectedInquiry.id} · {selectedInquiry.requester}님
                        </span>
                        <h2>{selectedInquiry.query}</h2>
                        <p>
                          {selectedInquiry.receivedAt} 접수 · 검색 결과 #{selectedInquiry.rank}
                        </p>
                      </div>
                      <span className={styles.largeStatus} data-status={selectedWork.status}>
                        {getStatusIcon(selectedWork.status)}
                        {statusLabels[selectedWork.status]}
                      </span>
                    </div>

                    <div className={styles.contextGrid}>
                      <ReviewerScenePreview
                        inquiry={selectedInquiry}
                        theme={theme}
                        key={selectedInquiry.id}
                      />
                      <section className={styles.commentCard}>
                        <span className={styles.cardLabel}>문의자의 설명</span>
                        <p>“{selectedInquiry.comment}”</p>
                      </section>
                    </div>

                    <section className={styles.inquiryEvidence}>
                      <div>
                        <span className={styles.cardLabel}>문의자가 선택한 검색 조건</span>
                        <dl>
                          {selectedInquiry.explicitFilters.map((filter) => (
                            <div key={filter.label}>
                              <dt>{filter.label}</dt>
                              <dd>{filter.value}</dd>
                            </div>
                          ))}
                        </dl>
                      </div>
                      <div>
                        <span className={styles.cardLabel}>영상에서 확인한 내용</span>
                        <strong>{selectedInquiry.evidence}</strong>
                        <p>{selectedInquiry.guard}</p>
                      </div>
                    </section>
                    <ResolutionSummary value={selectedInquiry.initialResolution} />

                    {selectedWork.status !== 'pending' ? (
                      <ol className={styles.reviewSteps} aria-label="검수 진행 단계">
                        {(['inspect', 'edit', 'verify', 'complete'] as const).map((step, index) => (
                          <li
                            key={step}
                            aria-current={
                              (selectedWork.outcome ? 'complete' : selectedWork.step) === step
                                ? 'step'
                                : undefined
                            }
                          >
                            <b>{index + 1}</b>
                            <span>
                              {['검색 결과 확인', '수정안 작성', '수정 후 확인', '완료'][index]}
                            </span>
                          </li>
                        ))}
                      </ol>
                    ) : null}

                    {selectedWork.status === 'pending' ? (
                      <section className={styles.claimPanel}>
                        <UserCheck aria-hidden="true" />
                        <div>
                          <strong>아직 담당자가 없습니다.</strong>
                          <p>검수를 시작하면 이 문의를 맡아 처리할 수 있어요.</p>
                        </div>
                        <button
                          className={styles.primaryButton}
                          onClick={handleClaim}
                          type="button"
                        >
                          검수 시작
                        </button>
                      </section>
                    ) : null}

                    {selectedWork.status === 'reviewing' ? (
                      <>
                        {selectedWork.step === 'inspect' ? (
                          <section
                            className={styles.inspectionPanel}
                            ref={inspectionRef}
                            tabIndex={-1}
                            aria-label="검수 전 검색 결과 확인"
                          >
                            <div className={styles.replayHeading}>
                              <div>
                                <p>1. 검색 결과 확인</p>
                                <h3>같은 조건에서도 문제가 나타나나요?</h3>
                              </div>
                              <button
                                className={styles.primaryButton}
                                onClick={handleBaselineReplay}
                                type="button"
                              >
                                <RefreshCw aria-hidden="true" />
                                같은 조건으로 다시 검색
                              </button>
                            </div>
                            <dl className={styles.replayQuery}>
                              <div>
                                <dt>검색어</dt>
                                <dd>{selectedInquiry.query}</dd>
                              </div>
                              <div>
                                <dt>검색 조건</dt>
                                <dd>
                                  {selectedInquiry.explicitFilters
                                    .map(({ label, value }) => `${label}: ${value}`)
                                    .join(' · ')}
                                </dd>
                              </div>
                            </dl>
                            <p className={styles.demoNotice}>
                              화면 시연용 예시 데이터입니다. 실제 검색은 연결 전이며, 아래 재검색
                              결과는 문의 당시와 동일한 목록을 보여줘요.
                            </p>
                            <Top10Comparison
                              before={selectedInquiry.beforeTop10}
                              after={selectedWork.baselineResults}
                              excludedTitle={null}
                              inquiryTitle={selectedInquiry.sceneTitle}
                              variant="inspection"
                            />
                            <div className={styles.replayFooter}>
                              <span aria-live="polite">
                                {selectedWork.baselineResults ? (
                                  <>
                                    <CheckCircle2 aria-hidden="true" />
                                    예시 결과에서 문의 장면이 계속 보여요. 처리 방법을 선택해
                                    주세요.
                                  </>
                                ) : (
                                  <>
                                    <Clock3 aria-hidden="true" />
                                    같은 조건으로 다시 검색한 뒤 결과를 비교해 주세요.
                                  </>
                                )}
                              </span>
                              <button
                                className={styles.primaryButton}
                                disabled={!selectedWork.baselineResults}
                                onClick={() => handleReviewStep('edit')}
                                type="button"
                              >
                                결과 확인하고 수정안 작성 <ChevronRight aria-hidden="true" />
                              </button>
                            </div>
                          </section>
                        ) : null}
                        {selectedWork.step === 'edit' ? (
                          <section
                            className={styles.actionSection}
                            ref={diagnosisRef}
                            tabIndex={-1}
                          >
                            <SectionHeading
                              eyebrow="2. 수정안 작성"
                              title="어떻게 처리할까요?"
                              meta="담당 · 나현우"
                            />
                            <button
                              className={styles.backToResults}
                              onClick={() => handleReviewStep('inspect')}
                              type="button"
                            >
                              <ArrowLeft aria-hidden="true" />
                              검색 결과 다시 확인
                            </button>
                            <fieldset className={styles.actionPicker}>
                              <legend>문의 조치 선택</legend>
                              {(Object.keys(actionLabels) as ReviewAction[]).map((action) => (
                                <label key={action}>
                                  <input
                                    checked={selectedWork.draft.action === action}
                                    name={`action-${selectedInquiry.id}`}
                                    onChange={() => changeDraft({ action }, '조치')}
                                    type="radio"
                                  />
                                  <span>
                                    <strong>{actionLabels[action]}</strong>
                                    <small>{actionDescriptions[action]}</small>
                                  </span>
                                </label>
                              ))}
                            </fieldset>
                            <form className={styles.actionForm} onSubmit={handleDecisionSubmit}>
                              {selectedWork.draft.action === 'resolution_patch' ? (
                                <ResolutionEditor
                                  value={selectedWork.draft.resolutionPatch}
                                  error={validationErrors.patch}
                                  lockedDateFields={selectedInquiry.explicitFilters.flatMap(
                                    (filter) =>
                                      filter.value !== '미지정' && filter.value !== '전체 기간'
                                        ? filter.label === '방송일'
                                          ? ['broadcast_date']
                                          : filter.label === '촬영일'
                                            ? ['filming_date']
                                            : []
                                        : [],
                                  )}
                                  onChange={(resolutionPatch) =>
                                    changeDraft({ resolutionPatch }, '검색어의 의미')
                                  }
                                />
                              ) : null}
                              {selectedWork.draft.action === 'exclude_scene' ? (
                                <fieldset className={styles.targetPicker}>
                                  <legend>제외 대상</legend>
                                  <label>
                                    <input
                                      aria-describedby={
                                        validationErrors.target ? 'exclude-target-error' : undefined
                                      }
                                      aria-invalid={Boolean(validationErrors.target)}
                                      checked={
                                        selectedWork.draft.excludeTargetId ===
                                        selectedInquiry.sceneId
                                      }
                                      onChange={(event) =>
                                        changeDraft(
                                          {
                                            excludeTargetId: event.target.checked
                                              ? selectedInquiry.sceneId
                                              : '',
                                          },
                                          '제외 대상',
                                        )
                                      }
                                      type="checkbox"
                                    />
                                    <span>
                                      <strong>{selectedInquiry.sceneTitle}</strong>
                                      <small>영상 구간 {selectedInquiry.timecode}</small>
                                    </span>
                                  </label>
                                  {validationErrors.target ? (
                                    <FieldError id="exclude-target-error">
                                      {validationErrors.target}
                                    </FieldError>
                                  ) : (
                                    <small>
                                      문의 당시와 같은 검색어·필터에서만 이 장면을 제외해요. 영상은
                                      삭제하지 않아요.
                                    </small>
                                  )}
                                </fieldset>
                              ) : null}
                              {selectedWork.draft.action === 'deferred_p1' ? (
                                <>
                                  <div className={styles.formColumns}>
                                    <label>
                                      <span>확인이 필요한 정보</span>
                                      <select
                                        aria-describedby={
                                          validationErrors.field ? 'field-error' : undefined
                                        }
                                        aria-invalid={Boolean(validationErrors.field)}
                                        onChange={(event) =>
                                          changeDraft(
                                            { deferredField: event.target.value as DeferredField },
                                            '확인할 정보',
                                          )
                                        }
                                        value={selectedWork.draft.deferredField}
                                      >
                                        <option value="">선택</option>
                                        <option value="entity">인물·기관 정보</option>
                                        <option value="ocr">영상 속 글자</option>
                                        <option value="caption">장면 설명</option>
                                        <option value="date">날짜 정보</option>
                                        <option value="other">기타</option>
                                      </select>
                                      {validationErrors.field ? (
                                        <FieldError id="field-error">
                                          {validationErrors.field}
                                        </FieldError>
                                      ) : null}
                                    </label>
                                    <label>
                                      <span>확인 대상</span>
                                      <select
                                        aria-describedby={
                                          validationErrors.target ? 'target-error' : undefined
                                        }
                                        aria-invalid={Boolean(validationErrors.target)}
                                        onChange={(event) =>
                                          changeDraft(
                                            { deferredTarget: event.target.value },
                                            '확인 대상',
                                          )
                                        }
                                        value={selectedWork.draft.deferredTarget}
                                      >
                                        <option value="">대상을 선택해 주세요</option>
                                        {selectedInquiry.contextIds.map((id) => (
                                          <option key={id} value={id}>
                                            {id === selectedInquiry.sceneId
                                              ? `문의 장면 · ${selectedInquiry.sceneTitle}`
                                              : selectedInquiry.evidence}
                                          </option>
                                        ))}
                                      </select>
                                      {validationErrors.target ? (
                                        <FieldError id="target-error">
                                          {validationErrors.target}
                                        </FieldError>
                                      ) : null}
                                    </label>
                                  </div>
                                  <label>
                                    <span>어떤 부분을 확인해야 하나요?</span>
                                    <textarea
                                      aria-describedby={
                                        validationErrors.description
                                          ? 'description-error'
                                          : undefined
                                      }
                                      aria-invalid={Boolean(validationErrors.description)}
                                      onChange={(event) =>
                                        changeDraft(
                                          { deferredDescription: event.target.value },
                                          '확인 요청 내용',
                                        )
                                      }
                                      rows={3}
                                      value={selectedWork.draft.deferredDescription}
                                    />
                                    {validationErrors.description ? (
                                      <FieldError id="description-error">
                                        {validationErrors.description}
                                      </FieldError>
                                    ) : (
                                      <small>
                                        담당팀이 확인할 내용을 기록해요. 아직 영상 정보가 수정된
                                        것은 아니에요.
                                      </small>
                                    )}
                                  </label>
                                </>
                              ) : null}
                              <label>
                                <span>
                                  {selectedWork.draft.action === 'no_action'
                                    ? '수정하지 않는 이유'
                                    : selectedWork.draft.action === 'deferred_p1'
                                      ? '확인이 필요한 이유'
                                      : '처리 이유'}
                                </span>
                                <textarea
                                  aria-describedby={
                                    validationErrors.reason ? 'reason-error' : undefined
                                  }
                                  aria-invalid={Boolean(validationErrors.reason)}
                                  onChange={(event) =>
                                    changeDraft({ reason: event.target.value }, '처리 이유')
                                  }
                                  placeholder="예: 고속도로를 찾는 검색에 역 내부 인터뷰가 나와서 제외합니다."
                                  rows={3}
                                  value={selectedWork.draft.reason}
                                />
                                {validationErrors.reason ? (
                                  <FieldError id="reason-error">
                                    {validationErrors.reason}
                                  </FieldError>
                                ) : null}
                              </label>
                              <button
                                className={
                                  selectedWork.draft.action === 'no_action' ||
                                  selectedWork.draft.action === 'deferred_p1'
                                    ? styles.primaryButton
                                    : styles.secondaryButton
                                }
                                type="submit"
                              >
                                {selectedWork.draft.action === 'no_action' ? (
                                  '수정 없이 완료하기'
                                ) : selectedWork.draft.action === 'deferred_p1' ? (
                                  '확인 요청 기록하기'
                                ) : (
                                  <>
                                    <Sparkles aria-hidden="true" />
                                    수정 내용 저장
                                  </>
                                )}
                              </button>
                            </form>
                          </section>
                        ) : null}

                        {selectedWork.step === 'verify' &&
                        (selectedWork.draft.action === 'resolution_patch' ||
                          selectedWork.draft.action === 'exclude_scene') &&
                        selectedWork.candidate ? (
                          <section
                            className={styles.replayPanel}
                            aria-label="수정 후 검색 결과 확인"
                            ref={verificationRef}
                            tabIndex={-1}
                          >
                            <div className={styles.replayHeading}>
                              <div>
                                <p>3. 수정 후 확인</p>
                                <h3>
                                  {selectedWork.replay
                                    ? '수정 후 결과를 확인해 주세요'
                                    : '수정 내용을 저장했어요'}
                                </h3>
                              </div>
                              <button
                                className={styles.secondaryButton}
                                onClick={handleReplay}
                                type="button"
                              >
                                <RefreshCw aria-hidden="true" />
                                {selectedWork.replay
                                  ? '수정 후 결과 다시 확인'
                                  : '수정 후 결과 확인'}
                              </button>
                            </div>
                            <div className={styles.previewDescription}>
                              <strong>{actionLabels[selectedWork.candidate.action]}</strong>
                              <p>문의 당시와 같은 검색어와 필터로 결과를 비교해요.</p>
                              <small>
                                화면 시연용 예시 결과입니다. 입력 내용에 따른 실제 검색은 연결
                                전이에요.
                              </small>
                            </div>
                            {selectedWork.candidate.action === 'resolution_patch' ? (
                              <ResolutionSummary
                                value={selectedWork.candidate.payload}
                                title="저장한 검색 해석"
                              />
                            ) : null}
                            <Top10Comparison
                              after={selectedWork.replay ? selectedInquiry.afterTop10 : null}
                              before={selectedWork.baselineResults ?? selectedInquiry.beforeTop10}
                              excludedTitle={
                                selectedWork.candidate.action === 'exclude_scene'
                                  ? selectedInquiry.sceneTitle
                                  : null
                              }
                            />
                            <div className={styles.replayFooter}>
                              <button
                                className={styles.secondaryButton}
                                onClick={() => handleReviewStep('edit')}
                                type="button"
                              >
                                <ArrowLeft aria-hidden="true" />
                                수정안 다시 작성
                              </button>
                              <span>
                                {selectedWork.replay ? (
                                  <>
                                    <CheckCircle2 aria-hidden="true" />
                                    수정 후 결과 확인 완료
                                  </>
                                ) : (
                                  <>
                                    <Clock3 aria-hidden="true" />
                                    수정 후 결과를 확인한 뒤 완료해 주세요.
                                  </>
                                )}
                              </span>
                              <button
                                className={styles.primaryButton}
                                disabled={!selectedWork.replay}
                                onClick={handleResolve}
                                type="button"
                              >
                                수정 완료하기
                              </button>
                            </div>
                          </section>
                        ) : null}
                      </>
                    ) : null}

                    {selectedWork.outcome ? (
                      <section className={styles.historyPanel} ref={historyRef} tabIndex={-1}>
                        <History aria-hidden="true" />
                        <div>
                          <span className={styles.cardLabel}>처리 내역</span>
                          <strong>
                            {getStatusIcon(selectedWork.outcome.status)}접수 → 검수 중 →{' '}
                            {statusLabels[selectedWork.outcome.status]}
                          </strong>
                          <p>조치 · {actionLabels[selectedWork.outcome.action]}</p>
                          <p>사유 · {selectedWork.outcome.reason}</p>
                          {selectedWork.outcome.targetSummary ? (
                            <p>적용 범위 · {selectedWork.outcome.targetSummary}</p>
                          ) : null}
                          {selectedWork.outcome.deferredSummary ? (
                            <p>확인 요청 · {selectedWork.outcome.deferredSummary}</p>
                          ) : null}
                          {selectedWork.outcome.status === 'deferred' ? (
                            <p>
                              담당팀이 확인할 내용을 기록했어요. 영상 정보는 아직 수정되지 않았어요.
                            </p>
                          ) : null}
                          {selectedWork.outcome.appliedPayload ? (
                            <ResolutionSummary
                              value={selectedWork.outcome.appliedPayload}
                              title="적용한 검색 해석"
                            />
                          ) : null}
                          <dl>
                            <div>
                              <dt>담당자</dt>
                              <dd>나현우</dd>
                            </div>
                            <div>
                              <dt>완료 시각</dt>
                              <dd>{selectedWork.outcome.closedAt}</dd>
                            </div>
                            <div>
                              <dt>결과 확인</dt>
                              <dd>
                                {selectedWork.outcome.executionId
                                  ? '수정 전후 결과 확인 완료'
                                  : '해당 없음'}
                              </dd>
                            </div>
                          </dl>
                          {selectedWork.outcome.status === 'resolved' ? (
                            <Top10Comparison
                              after={selectedInquiry.afterTop10}
                              before={selectedInquiry.beforeTop10}
                              excludedTitle={
                                selectedWork.outcome.action === 'exclude_scene'
                                  ? selectedInquiry.sceneTitle
                                  : null
                              }
                            />
                          ) : null}
                        </div>
                      </section>
                    ) : null}
                  </article>
                </div>
              </section>
            )}
          </>
        )}
      </main>
    </div>
  );
}

function SectionHeading({
  eyebrow,
  meta,
  title,
}: {
  eyebrow: string;
  meta: string;
  title: string;
}) {
  return (
    <div className={styles.sectionHeading}>
      <div>
        <p>{eyebrow}</p>
        <h3>{title}</h3>
      </div>
      <span>{meta}</span>
    </div>
  );
}

function FieldError({ children, id }: { children: ReactNode; id: string }) {
  return (
    <small className={styles.fieldError} id={id}>
      <CircleAlert aria-hidden="true" />
      {children}
    </small>
  );
}

function Top10Comparison({
  after,
  before,
  excludedTitle,
  inquiryTitle,
  variant = 'correction',
}: {
  after: string[] | null;
  before: string[];
  excludedTitle: string | null;
  inquiryTitle?: string;
  variant?: 'inspection' | 'correction';
}) {
  const beforeLabel = variant === 'inspection' ? '문의 당시 검색 결과' : '수정 전 검색 결과';
  const afterLabel =
    variant === 'inspection' ? '같은 조건으로 다시 검색한 결과' : '수정 후 검색 결과';
  return (
    <div className={styles.resultComparison}>
      <div>
        <span>
          {beforeLabel} · {before.length}개
        </span>
        <ol aria-label={beforeLabel}>
          {before.map((result, index) => (
            <li
              className={
                result === excludedTitle
                  ? styles.excludedCandidate
                  : result === inquiryTitle
                    ? styles.inquiryResult
                    : undefined
              }
              key={`${result}-${index}`}
            >
              <b>{index + 1}</b>
              {result}
              {result === excludedTitle ? <em>제외 대상</em> : null}
              {result === inquiryTitle ? <em>문의 장면</em> : null}
            </li>
          ))}
        </ol>
      </div>
      <ChevronRight aria-hidden="true" />
      <div className={after && variant === 'correction' ? styles.verifiedResults : undefined}>
        <span>
          {afterLabel}
          {after ? ` · ${after.length}개` : ''}
        </span>
        {after ? (
          <ol aria-label={afterLabel}>
            {after.map((result, index) => (
              <li
                key={`${result}-${index}`}
                className={result === inquiryTitle ? styles.inquiryResult : undefined}
              >
                <b>{index + 1}</b>
                {result}
                {result === inquiryTitle ? <em>문의 장면</em> : null}
              </li>
            ))}
          </ol>
        ) : (
          <p>
            {variant === 'inspection'
              ? '위의 ‘같은 조건으로 다시 검색’을 누르면 수정 전 결과를 확인할 수 있어요.'
              : '‘수정 후 결과 확인’을 누르면 여기에서 비교할 수 있어요.'}
          </p>
        )}
      </div>
    </div>
  );
}
