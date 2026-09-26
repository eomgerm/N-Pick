'use client';
import {
  Check,
  ChevronLeft,
  ChevronRight,
  CircleSlash,
  Download,
  LoaderCircle,
  Play,
  TriangleAlert,
  X,
} from 'lucide-react';
import { useEffect, useId, useRef, useState } from 'react';

import { getVerificationStatusLabel, type SearchResult } from '@/features/wireframes/demo-scenes';
import {
  formatMediaTime,
  getClipDownloadUrl,
  getSceneDownloadUrl,
} from '@/features/wireframes/scene-preview-media';
import {
  checkClipDownload,
  ClipDownloadError,
  fetchSceneDownload,
  saveSceneDownload,
  SceneDownloadError,
  startClipDownload,
} from '@/features/wireframes/scene-download';
import {
  canCreateInquiry,
  type SearchExecutionPresentation,
  successfulSearchExecution,
} from '@/features/wireframes/search-execution-status';
import { SearchResultNotices } from '@/features/wireframes/search-result-notices';
import type { WireframeTheme } from '@/features/wireframes/wireframe-themes';
import styles from '@/features/wireframes/wireframe.module.css';
import shinhanStyles from '@/features/wireframes/shinhan-search.module.css';
import { ScenePreviewPlayer } from '@/features/wireframes/scene-preview-player';
import { SceneDialog } from '@/features/wireframes/scene-dialog';
import previewStyles from '@/features/wireframes/scene-preview-dialog.module.css';

type ScenePreviewResult = Pick<
  SearchResult,
  | 'title'
  | 'duration'
  | 'sceneStart'
  | 'sceneEnd'
  | 'evidence'
  | 'source'
  | 'imageClass'
  | 'imageLabel'
> &
  Partial<
    Pick<
      SearchResult,
      | 'additionalEvidence'
      | 'clipId'
      | 'clip'
      | 'sceneId'
      | 'searchResultId'
      | 'totalSeconds'
      | 'totalDuration'
      | 'broadcastDate'
      | 'filmedDate'
      | 'filmingState'
      | 'matchEvidence'
      | 'shotType'
    >
  > & {
    id: string | number;
    evidenceType: string;
  };

interface ScenePreviewDialogProps {
  result: ScenePreviewResult;
  scenes?: ScenePreviewResult[];
  theme: WireframeTheme;
  isSubmitted?: boolean;
  isSubmitting?: boolean;
  onInquiry?: () => void;
  contextLabel?: string;
  notice?: string;
  autoPlay?: boolean;
  searchExecution?: SearchExecutionPresentation;
  onClose: () => void;
}

export function ScenePreviewDialog({
  result: initialResult,
  scenes,
  theme,
  isSubmitted,
  isSubmitting = false,
  onInquiry,
  onClose,
  contextLabel,
  notice,
  autoPlay = true,
  searchExecution = successfulSearchExecution,
}: ScenePreviewDialogProps) {
  const [selectedSceneId, setSelectedSceneId] = useState(initialResult.id);
  const [downloadingSceneId, setDownloadingSceneId] = useState<string | null>(null);
  const [sceneDownloadFailure, setSceneDownloadFailure] = useState<{
    sceneId: string;
    message: string;
  } | null>(null);
  const sceneDownloadControllerRef = useRef<AbortController | null>(null);
  const [checkingClipId, setCheckingClipId] = useState<string | null>(null);
  const [clipDownloadFailure, setClipDownloadFailure] = useState<{
    clipId: string;
    message: string;
  } | null>(null);
  const clipDownloadControllerRef = useRef<AbortController | null>(null);
  const result = scenes?.find((scene) => scene.id === selectedSceneId) ?? initialResult;
  const filmingStatus = result.filmedDate ? (result.filmingState ?? 'unknown') : 'unknown';
  const evidenceField = result.matchEvidence?.field ?? result.evidenceType;
  const evidenceValue = result.matchEvidence?.value ?? result.evidence;
  const evidenceSource = result.matchEvidence?.source ?? result.source;
  const selectedSceneIndex = scenes?.findIndex((scene) => scene.id === result.id) ?? -1;
  const sceneListRef = useRef<HTMLOListElement>(null);
  const closeButtonRef = useRef<HTMLButtonElement>(null);
  const inquiryUnavailableReasonId = useId();
  const hasSavedResult =
    typeof result.searchResultId === 'string' && /^[1-9]\d*$/.test(result.searchResultId);
  // 문의 가능 여부는 선택한 결과 자신의 저장 상태로 판단한다. 더보기로 이어 붙인 실행 상태가
  // 다른 페이지의 snapshot 실패로 degraded 여도, 이 결과에 search_result_id 가 있으면 문의할 수
  // 있다. snapshot 실패한 페이지의 결과는 id 자체가 없어 hasSavedResult 로 이미 걸러진다
  // (web-api §5.1, S15P21A501-251 P1). 전역 canCreateInquiry 게이트는 문구 안내에만 쓴다.
  const isInquiryUnavailable = !isSubmitted && !hasSavedResult;
  const clipDownloadUrl = getClipDownloadUrl(result.clipId);
  const sceneDownloadUrl = getSceneDownloadUrl(result.sceneId);
  const isSceneDownloading = downloadingSceneId === result.sceneId;
  const sceneDownloadError =
    sceneDownloadFailure?.sceneId === result.sceneId ? sceneDownloadFailure?.message : null;
  const isClipDownloadChecking = checkingClipId === result.clipId;
  const clipDownloadError =
    clipDownloadFailure?.clipId === result.clipId ? clipDownloadFailure?.message : null;
  useEffect(() => {
    closeButtonRef.current?.focus({ preventScroll: true });
  }, []);
  useEffect(() => {
    const list = sceneListRef.current;
    const selected = list?.querySelector<HTMLElement>('[aria-current="true"]');
    if (!list || !selected) return;
    const listBounds = list.getBoundingClientRect();
    const selectedBounds = selected.getBoundingClientRect();
    if (selectedBounds.top < listBounds.top) {
      list.scrollTop += selectedBounds.top - listBounds.top;
    } else if (selectedBounds.bottom > listBounds.bottom) {
      list.scrollTop += selectedBounds.bottom - listBounds.bottom;
    }
  }, [result.id]);
  useEffect(() => {
    return () => {
      const controller = sceneDownloadControllerRef.current;
      controller?.abort();
      if (sceneDownloadControllerRef.current === controller) {
        sceneDownloadControllerRef.current = null;
        setDownloadingSceneId(null);
      }
    };
  }, [result.sceneId]);
  useEffect(() => {
    return () => {
      const controller = clipDownloadControllerRef.current;
      controller?.abort();
      if (clipDownloadControllerRef.current === controller) {
        clipDownloadControllerRef.current = null;
        setCheckingClipId(null);
      }
    };
  }, [result.clipId]);

  async function handleSceneDownload() {
    if (!sceneDownloadUrl || !result.sceneId || sceneDownloadControllerRef.current) return;
    const sceneId = result.sceneId;
    const controller = new AbortController();
    sceneDownloadControllerRef.current = controller;
    setDownloadingSceneId(sceneId);
    setSceneDownloadFailure(null);
    setClipDownloadFailure(null);
    try {
      const download = await fetchSceneDownload(sceneDownloadUrl, controller.signal);
      if (!controller.signal.aborted) {
        saveSceneDownload(download.blob, download.fileName ?? `scene-${sceneId}.mp4`);
      }
    } catch (error) {
      if (!controller.signal.aborted) {
        setSceneDownloadFailure({
          sceneId,
          message:
            error instanceof SceneDownloadError
              ? error.message
              : '장면 영상을 다운로드하지 못했습니다. 잠시 후 다시 시도해 주세요.',
        });
      }
    } finally {
      if (sceneDownloadControllerRef.current === controller) {
        sceneDownloadControllerRef.current = null;
        setDownloadingSceneId(null);
      }
    }
  }
  async function handleClipDownload() {
    if (!clipDownloadUrl || !result.clipId || clipDownloadControllerRef.current) return;
    const clipId = result.clipId;
    const controller = new AbortController();
    clipDownloadControllerRef.current = controller;
    setCheckingClipId(clipId);
    setClipDownloadFailure(null);
    setSceneDownloadFailure(null);
    try {
      await checkClipDownload(clipDownloadUrl, controller.signal);
      if (!controller.signal.aborted) startClipDownload(clipDownloadUrl);
    } catch (error) {
      if (!controller.signal.aborted) {
        setClipDownloadFailure({
          clipId,
          message:
            error instanceof ClipDownloadError
              ? error.message
              : '원본 클립을 다운로드하지 못했습니다. 잠시 후 다시 시도해 주세요.',
        });
      }
    } finally {
      if (clipDownloadControllerRef.current === controller) {
        clipDownloadControllerRef.current = null;
        setCheckingClipId(null);
      }
    }
  }
  function handleSceneSelect(scene: ScenePreviewResult) {
    if (scene.id === result.id) return;
    setSelectedSceneId(scene.id);
  }

  return (
    <SceneDialog
      theme={theme}
      className={styles.previewModal}
      labelledBy="preview-title"
      onClose={onClose}
    >
      <div className={styles.previewModalHeader}>
        <div>
          <span>{contextLabel ?? `구간 영상 정보 · 장면 #${result.id}`}</span>
          <h2 id="preview-title">{result.title}</h2>
          {result.clip ? <p className={previewStyles.previewClipName}>{result.clip}</p> : null}
          {notice ? <p className={styles.previewNotice}>{notice}</p> : null}
        </div>
        <div className={styles.previewHeaderActions}>
          <button
            aria-label="Preview 닫기"
            className={styles.iconButton}
            onClick={onClose}
            ref={closeButtonRef}
            title="Preview 닫기"
            type="button"
          >
            <X aria-hidden="true" />
          </button>
        </div>
        <div className={previewStyles.previewHeaderMetaRow}>
          <p className={previewStyles.previewSceneMeta}>
            <span>
              {formatMediaTime(result.sceneStart)} – {formatMediaTime(result.sceneEnd)}
            </span>
            <span>{result.duration}</span>
          </p>
          {onInquiry ? (
            <div className={styles.previewInquiryAction}>
              <button
                aria-busy={isSubmitting}
                aria-describedby={isInquiryUnavailable ? inquiryUnavailableReasonId : undefined}
                aria-disabled={isInquiryUnavailable || undefined}
                className={styles.previewReportButton}
                data-state={
                  isSubmitted
                    ? 'submitted'
                    : isSubmitting
                      ? 'submitting'
                      : isInquiryUnavailable
                        ? 'unavailable'
                        : 'ready'
                }
                disabled={isSubmitted || isSubmitting}
                onClick={() => {
                  if (!isInquiryUnavailable) onInquiry();
                }}
                type="button"
              >
                {isSubmitted ? (
                  <Check aria-hidden="true" />
                ) : isSubmitting ? (
                  <LoaderCircle aria-hidden="true" className={shinhanStyles.spinner} />
                ) : isInquiryUnavailable ? (
                  <CircleSlash aria-hidden="true" />
                ) : (
                  <TriangleAlert aria-hidden="true" />
                )}
                {isSubmitted
                  ? '접수됨'
                  : isSubmitting
                    ? '접수 중'
                    : isInquiryUnavailable
                      ? '문의 불가'
                      : '이상해요'}
              </button>
              {isInquiryUnavailable ? (
                <span
                  className={styles.previewInquiryTooltip}
                  id={inquiryUnavailableReasonId}
                  role="tooltip"
                >
                  {!canCreateInquiry(searchExecution)
                    ? '검색 기록을 저장하지 못해 이 결과에서는 문의할 수 없습니다.'
                    : '저장된 검색 결과가 아니므로 문의할 수 없습니다.'}
                </span>
              ) : null}
            </div>
          ) : null}
        </div>
      </div>

      <div className={styles.previewModalBody}>
        <div className={styles.previewPlayer}>
          <ScenePreviewPlayer
            key={`${result.id}:${result.clipId ?? ''}`}
            clipId={result.clipId}
            sceneStart={result.sceneStart}
            sceneEnd={result.sceneEnd}
            title={result.title}
            autoPlay={autoPlay}
          />
          {clipDownloadUrl || sceneDownloadUrl ? (
            <div className={styles.previewPlayerActions}>
              <div
                className={styles.previewDownloadActions}
                aria-label="영상 다운로드"
                role="group"
              >
                {sceneDownloadUrl ? (
                  <button
                    aria-busy={isSceneDownloading || undefined}
                    className={styles.previewDownloadButton}
                    disabled={isSceneDownloading}
                    onClick={() => void handleSceneDownload()}
                    type="button"
                  >
                    {isSceneDownloading ? (
                      <LoaderCircle aria-hidden="true" className={shinhanStyles.spinner} />
                    ) : (
                      <Download aria-hidden="true" />
                    )}
                    {isSceneDownloading ? '장면 준비 중…' : '장면 다운로드'}
                  </button>
                ) : null}
                {clipDownloadUrl ? (
                  <button
                    aria-busy={isClipDownloadChecking || undefined}
                    className={styles.previewDownloadButton}
                    disabled={isClipDownloadChecking}
                    onClick={() => void handleClipDownload()}
                    type="button"
                  >
                    {isClipDownloadChecking ? (
                      <LoaderCircle aria-hidden="true" className={shinhanStyles.spinner} />
                    ) : (
                      <Download aria-hidden="true" />
                    )}
                    {isClipDownloadChecking ? '원본 확인 중…' : '원본 클립 다운로드'}
                  </button>
                ) : null}
              </div>
            </div>
          ) : null}
          {sceneDownloadError || clipDownloadError ? (
            <p className={styles.previewDownloadError} role="alert">
              {sceneDownloadError ?? clipDownloadError}
            </p>
          ) : null}
          <SearchResultNotices execution={searchExecution} variant="preview" />
        </div>

        <div className={styles.previewSidebar}>
          {scenes && scenes.length > 0 ? (
            <section className={styles.sceneBrowser} aria-labelledby="all-scenes-title">
              <div className={styles.sceneBrowserHeading}>
                <h3 id="all-scenes-title">
                  전체 구간 <span>{scenes.length}</span>
                </h3>
                <span>시간순</span>
              </div>
              <div className={styles.sceneNavigation}>
                <button
                  aria-label="이전 구간"
                  disabled={selectedSceneIndex <= 0}
                  onClick={() => handleSceneSelect(scenes[selectedSceneIndex - 1])}
                  type="button"
                >
                  <ChevronLeft aria-hidden="true" /> 이전
                </button>
                <span aria-live="polite" aria-atomic="true">
                  구간 {selectedSceneIndex + 1} / {scenes.length}
                </span>
                <button
                  aria-label="다음 구간"
                  disabled={selectedSceneIndex >= scenes.length - 1}
                  onClick={() => handleSceneSelect(scenes[selectedSceneIndex + 1])}
                  type="button"
                >
                  다음 <ChevronRight aria-hidden="true" />
                </button>
              </div>
              <ol className={styles.sceneList} ref={sceneListRef}>
                {scenes.map((scene, index) => (
                  <li key={scene.id}>
                    <button
                      aria-current={scene.id === result.id ? 'true' : undefined}
                      aria-label={`구간 ${index + 1}: ${scene.title}, ${formatMediaTime(scene.sceneStart)}부터 ${formatMediaTime(scene.sceneEnd)}까지`}
                      onClick={() => handleSceneSelect(scene)}
                      type="button"
                    >
                      <span className={styles.sceneNumber}>
                        {String(index + 1).padStart(2, '0')}
                      </span>
                      <span className={styles.sceneListInfo}>
                        <strong>{scene.title}</strong>
                        <span>
                          {formatMediaTime(scene.sceneStart)} – {formatMediaTime(scene.sceneEnd)} ·{' '}
                          {scene.duration}
                        </span>
                      </span>
                      {scene.id === result.id ? <Play aria-hidden="true" /> : null}
                    </button>
                  </li>
                ))}
              </ol>
            </section>
          ) : null}
          <section
            className={styles.previewEvidenceSection}
            aria-labelledby="preview-evidence-title"
          >
            <h3 id="preview-evidence-title">{onInquiry ? '검색 근거' : '확인 근거'}</h3>
            <div className={styles.previewEvidence}>
              <div className={styles.previewEvidenceHeading}>
                <span>
                  {evidenceField}
                  {onInquiry ? ' 일치' : ''}
                </span>
                {result.matchEvidence ? (
                  <span className={styles.statusBadge} data-status={result.matchEvidence.status}>
                    {getVerificationStatusLabel(result.matchEvidence.status)}
                  </span>
                ) : null}
              </div>
              <strong>{evidenceValue}</strong>
              <p>{result.matchEvidence ? `출처 · ${evidenceSource}` : evidenceSource}</p>
            </div>
            {result.additionalEvidence?.map((evidence, index) => (
              <div className={styles.previewEvidence} key={index}>
                <div className={styles.previewEvidenceHeading}>
                  <span>{evidence.field}</span>
                  <span className={styles.statusBadge} data-status={evidence.status}>
                    {getVerificationStatusLabel(evidence.status)}
                  </span>
                </div>
                <strong>{evidence.value}</strong>
                <p>출처 · {evidence.source}</p>
              </div>
            ))}
          </section>
          <section className={styles.previewDetails} aria-labelledby="preview-details-title">
            <h3 id="preview-details-title">장면 정보</h3>
            <dl>
              <div>
                <dt>방송일</dt>
                <dd>{result.broadcastDate ?? '미상'}</dd>
              </div>
              <div>
                <dt>촬영일</dt>
                <dd>
                  {result.filmedDate
                    ? `${result.filmedDate} · ${getVerificationStatusLabel(filmingStatus)}`
                    : getVerificationStatusLabel(filmingStatus)}
                </dd>
              </div>
              <div>
                <dt>샷 유형</dt>
                <dd>{result.shotType ?? '정보 없음'}</dd>
              </div>
            </dl>
          </section>
        </div>
      </div>
    </SceneDialog>
  );
}
