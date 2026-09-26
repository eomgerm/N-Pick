'use client';

import { useQuery } from '@tanstack/react-query';
import { ImageOff, Play, RefreshCw } from 'lucide-react';
import { useState } from 'react';

import { ApiErrorNotice } from '@/components/api-error-notice';
import {
  getClipAnalysisScenes,
  type ClipAnalysisScene,
} from '@/features/wireframes/clip-analysis-api';
import {
  formatSceneInterval,
  tagScopeLabel,
  tagTypeLabel,
  tagVerificationLabel,
  transcriptSourceLabel,
} from '@/features/wireframes/clip-analysis-view';
import { PageNumbers } from '@/features/wireframes/list-pagination-controls';
import { SceneThumbnail } from '@/features/wireframes/scene-thumbnail';
import { createApiUrl } from '@/lib/api/client';

import styles from './processing-analysis-results.module.css';

const PAGE_SIZE = 20;

interface ProcessingAnalysisResultsProps {
  className?: string;
  clipId: string;
  pipelineRunId: string | null;
  isProcessing: boolean;
  onSeek: (startTimeMs: number) => void;
}

function SceneResult({ scene, onSeek }: { scene: ClipAnalysisScene; onSeek: () => void }) {
  return (
    <li className={styles.scene}>
      <div className={styles.frame}>
        {scene.representative_frame_timestamp_ms === null ? (
          <span className={styles.frameEmpty}>
            <ImageOff aria-hidden="true" />
            대표 프레임이 없습니다.
          </span>
        ) : (
          <SceneThumbnail
            alt={`장면 ${scene.scene_index} 대표 프레임`}
            src={createApiUrl(`/scenes/${scene.scene_id}/thumbnail`).toString()}
          />
        )}
      </div>
      <div className={styles.sceneBody}>
        <div className={styles.sceneHeading}>
          <div>
            <h3>장면 {String(scene.scene_index).padStart(2, '0')}</h3>
            <p>{formatSceneInterval(scene.start_time_ms, scene.end_time_ms)}</p>
          </div>
          <button
            aria-label={`장면 ${scene.scene_index} 영상에서 보기`}
            className={styles.seekButton}
            onClick={onSeek}
            type="button"
          >
            <Play aria-hidden="true" />
            영상에서 보기
          </button>
        </div>

        <div className={styles.resultBlock}>
          <h4>장면 설명</h4>
          <p>{scene.caption ?? '생성된 캡션이 없습니다.'}</p>
        </div>

        <div className={styles.resultBlock}>
          <div className={styles.blockHeading}>
            <h4>채택된 대사</h4>
            {scene.transcript ? (
              <span>{transcriptSourceLabel(scene.transcript.source)}</span>
            ) : null}
          </div>
          <p>{scene.transcript?.text ?? '연결된 자막이나 음성 인식 결과가 없습니다.'}</p>
        </div>

        <div className={styles.resultBlock}>
          <h4>태그</h4>
          {scene.tags.length ? (
            <ul className={styles.tags} aria-label={`장면 ${scene.scene_index} 태그`}>
              {scene.tags.map((tag) => (
                <li key={tag.tag_id}>
                  <span>{tagTypeLabel(tag.type)}</span>
                  <strong>{tag.name}</strong>
                  <small>
                    {tagScopeLabel(tag.scope)} · {tagVerificationLabel(tag.verification)}
                  </small>
                </li>
              ))}
            </ul>
          ) : (
            <p>생성된 태그가 없습니다.</p>
          )}
        </div>

        <div className={styles.resultBlock}>
          <h4>화면 속 글자</h4>
          {scene.ocr_texts.length ? (
            <p className={styles.ocrText}>{scene.ocr_texts.join(' · ')}</p>
          ) : (
            <p>추출된 화면 글자가 없습니다.</p>
          )}
        </div>
      </div>
    </li>
  );
}

export function ProcessingAnalysisResults({
  className,
  clipId,
  pipelineRunId,
  isProcessing,
  onSeek,
}: ProcessingAnalysisResultsProps) {
  const [page, setPage] = useState(0);
  const analysis = useQuery({
    queryKey: ['clip-analysis-scenes', clipId, pipelineRunId, page],
    queryFn: ({ signal }) => getClipAnalysisScenes(clipId, pipelineRunId!, page, PAGE_SIZE, signal),
    enabled: pipelineRunId !== null,
    placeholderData: (previous) => previous,
    refetchInterval: isProcessing ? 5_000 : false,
  });
  const data = analysis.data;

  return (
    <section className={`${className ?? ''} ${styles.root}`} aria-label="분석 결과">
      <div className={styles.heading}>
        <div>
          <h2>분석 결과</h2>
          <p>장면마다 실제 생성된 대표 프레임과 검색 정보를 확인합니다.</p>
        </div>
        {data && !data.search_applied ? (
          <span className={styles.unpublished}>검색 미반영 결과</span>
        ) : null}
      </div>

      {pipelineRunId === null ? (
        <p className={styles.empty}>처리가 시작되면 장면별 분석 결과가 여기에 표시됩니다.</p>
      ) : analysis.isPending ? (
        <p className={styles.empty} role="status">
          분석 결과를 불러오는 중…
        </p>
      ) : analysis.isError ? (
        <div className={styles.error}>
          <ApiErrorNotice error={analysis.error} />
          <button disabled={analysis.isFetching} onClick={() => analysis.refetch()} type="button">
            <RefreshCw aria-hidden="true" />
            분석 결과 다시 시도
          </button>
        </div>
      ) : data ? (
        <>
          {!data.search_applied ? (
            <p className={styles.notice}>
              최신 처리에서 생성된 결과입니다. 현재 검색에는 이전 처리 결과가 사용되고 있습니다.
            </p>
          ) : null}
          <dl className={styles.summary} aria-label="분석 결과 요약">
            <div>
              <dt>장면</dt>
              <dd>총 {data.summary.total_scenes}개 장면</dd>
            </div>
            <div>
              <dt>캡션</dt>
              <dd>
                캡션 {data.summary.captioned_scenes}/{data.summary.total_scenes}
              </dd>
            </div>
            <div>
              <dt>대사</dt>
              <dd>
                대사 {data.summary.transcript_scenes}/{data.summary.total_scenes}
              </dd>
            </div>
            <div>
              <dt>태그</dt>
              <dd>
                태그 {data.summary.tagged_scenes}/{data.summary.total_scenes}
              </dd>
            </div>
          </dl>

          {data.items.length ? (
            <ul className={styles.scenes} aria-busy={analysis.isFetching} aria-label="분석된 장면">
              {data.items.map((scene) => (
                <SceneResult
                  key={scene.scene_id}
                  scene={scene}
                  onSeek={() => onSeek(scene.start_time_ms)}
                />
              ))}
            </ul>
          ) : (
            <p className={styles.empty}>
              {isProcessing
                ? '장면 분석을 진행하고 있습니다. 생성된 결과가 아직 없습니다.'
                : '이 처리에서 생성된 장면이 없습니다.'}
            </p>
          )}

          {data.total_pages > 1 ? (
            <nav className={styles.pagination} aria-label="분석 장면 페이지">
              <PageNumbers
                isCompact
                isDisabled={analysis.isFetching}
                onPageChange={(nextPage) => setPage(nextPage - 1)}
                page={page + 1}
                totalPages={data.total_pages}
              />
            </nav>
          ) : null}
        </>
      ) : null}
    </section>
  );
}
