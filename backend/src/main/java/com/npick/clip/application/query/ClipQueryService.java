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
    private final com.npick.pipeline.application.query.GetProcessingProgressUseCase progress;
    private final com.npick.member.application.query.GetMemberSummariesUseCase members;

    public ClipQueryService(
            ClipQueryPort clips,
            com.npick.pipeline.application.query.GetProcessingDetailsUseCase processing,
            com.npick.pipeline.application.query.GetProcessingProgressUseCase progress,
            com.npick.member.application.query.GetMemberSummariesUseCase members) {
        this.clips = clips;
        this.processing = processing;
        this.progress = progress;
        this.members = members;
    }

    // Count and page must observe the same PostgreSQL snapshot during registration/deletion.
    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public GetClipsResult getClips(int page, int size, java.util.List<String> statuses, Long registeredById) {
        if (statuses == null
                || !java.util.Set.of("queued", "running", "failed", "succeeded", "no_run")
                        .containsAll(statuses)) throw new BusinessException(ClipQueryErrorCode.INVALID_STATUS);
        if (page < 0 || size < 1 || size > 100) throw new BusinessException(ClipQueryErrorCode.INVALID_PAGE);
        long offset = (long) page * size;
        if (offset > Integer.MAX_VALUE) throw new BusinessException(ClipQueryErrorCode.INVALID_PAGE);
        var counts = clips.countByLatestRunStatus(registeredById);
        long total = counts.entrySet().stream()
                .filter(entry -> statuses.isEmpty() || statuses.contains(entry.getKey()))
                .mapToLong(java.util.Map.Entry::getValue)
                .sum();
        var items = offset >= total
                ? java.util.List.<ClipQueryResult>of()
                : clips.findPage((int) offset, size, statuses, registeredById);
        var runIds = items.stream()
                .filter(item -> item.latestRun() != null)
                .map(item -> item.latestRun().pipelineRunId())
                .toList();
        // 등록자는 페이지 전체를 한 번에 묻는다. member 모듈의 JPA 는 건드리지 않는다 (설계 정본 §14).
        var registrants = members.findByIds(
                items.stream().map(ClipQueryResult::registeredById).distinct().toList());
        var named = items.stream()
                .map(item -> item.withRegisteredBy(registrants.get(item.registeredById())))
                .toList();
        return new GetClipsResult(named, page, size, total, counts, progress.getProgress(runIds));
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
                clip.registeredById(),
                members.findByIds(java.util.List.of(clip.registeredById())).get(clip.registeredById()),
                clip.latestRun(),
                details);
    }
}
