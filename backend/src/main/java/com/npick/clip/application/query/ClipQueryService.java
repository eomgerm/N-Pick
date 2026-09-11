package com.npick.clip.application.query;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.npick.clip.application.error.ClipQueryErrorCode;
import com.npick.clip.application.query.detail.GetClipUseCase;
import com.npick.clip.application.query.list.GetClipsResult;
import com.npick.clip.application.query.list.GetClipsUseCase;
import com.npick.common.error.BusinessException;

@Service
public class ClipQueryService implements GetClipsUseCase, GetClipUseCase {
    private final ClipQueryPort clips;
    private final com.npick.pipeline.application.query.GetProcessingDetailsUseCase processing;

    public ClipQueryService(
            ClipQueryPort clips, com.npick.pipeline.application.query.GetProcessingDetailsUseCase processing) {
        this.clips = clips;
        this.processing = processing;
    }

    // Count and page must observe the same PostgreSQL snapshot during registration/deletion.
    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public GetClipsResult getClips(int page, int size) {
        if (page < 0 || size < 1 || size > 100) throw new BusinessException(ClipQueryErrorCode.INVALID_PAGE);
        long offset = (long) page * size;
        if (offset > Integer.MAX_VALUE) throw new BusinessException(ClipQueryErrorCode.INVALID_PAGE);
        long total = clips.countVisible();
        return new GetClipsResult(
                offset >= total ? java.util.List.of() : clips.findPage((int) offset, size), page, size, total);
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ClipQueryResult getClip(long clipId) {
        var clip =
                clips.findVisible(clipId).orElseThrow(() -> new BusinessException(ClipQueryErrorCode.CLIP_NOT_FOUND));
        var details = clip.latestRun() == null
                ? null
                : processing.getProcessingDetails(clipId, clip.latestRun().pipelineRunId());
        return new ClipQueryResult(
                clip.clipId(),
                clip.title(),
                clip.sourceType(),
                clip.activePipelineRunId(),
                clip.createdAt(),
                clip.updatedAt(),
                clip.defaultTranscriptSource(),
                clip.hasSubtitle(),
                clip.hasScript(),
                clip.latestRun(),
                details);
    }
}
