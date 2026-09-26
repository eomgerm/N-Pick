package com.npick.clip.application.query.analysis;

import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.npick.clip.application.error.ClipQueryErrorCode;
import com.npick.common.error.BusinessException;
import com.npick.tag.application.query.ResolveSceneTagsUseCase;
import com.npick.tag.domain.model.EffectiveTag;

@Service
public class ClipAnalysisScenesQueryService implements GetClipAnalysisScenesUseCase {
    private final ClipAnalysisScenesQueryPort scenes;
    private final ResolveSceneTagsUseCase tags;

    public ClipAnalysisScenesQueryService(ClipAnalysisScenesQueryPort scenes, ResolveSceneTagsUseCase tags) {
        this.scenes = scenes;
        this.tags = tags;
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ClipAnalysisScenesResult get(long clipId, long pipelineRunId, int page, int size) {
        if (page < 0 || size < 1 || size > 100 || (long) page * size > Integer.MAX_VALUE) {
            throw new BusinessException(ClipQueryErrorCode.INVALID_PAGE);
        }
        var scope = scenes.findRunScope(clipId, pipelineRunId)
                .orElseThrow(() -> new BusinessException(ClipQueryErrorCode.CLIP_NOT_FOUND));
        var coverage = scenes.findCoverage(clipId, pipelineRunId);
        var sceneIds = coverage.stream()
                .map(ClipAnalysisScenesQueryPort.SceneCoverage::sceneId)
                .toList();
        Map<Long, List<EffectiveTag>> resolvedTags = tags.resolveForRun(clipId, pipelineRunId, sceneIds);
        var items = scenes.findPage(clipId, pipelineRunId, page * size, size).stream()
                .map(row -> new ClipAnalysisScenesResult.Scene(
                        row.sceneId(),
                        row.sceneIndex(),
                        row.startTimeMs(),
                        row.endTimeMs(),
                        row.representativeFrameTimestampMs(),
                        row.caption(),
                        row.shotType(),
                        row.transcriptText() == null
                                ? null
                                : new ClipAnalysisScenesResult.Transcript(row.transcriptText(), row.transcriptSource()),
                        resolvedTags.getOrDefault(row.sceneId(), List.of()).stream()
                                .map(ClipAnalysisScenesQueryService::tag)
                                .toList(),
                        row.ocrTexts()))
                .toList();
        return new ClipAnalysisScenesResult(
                clipId,
                pipelineRunId,
                scope.searchApplied(),
                new ClipAnalysisScenesResult.Summary(
                        coverage.size(),
                        coverage.stream()
                                .filter(ClipAnalysisScenesQueryPort.SceneCoverage::captioned)
                                .count(),
                        coverage.stream()
                                .filter(ClipAnalysisScenesQueryPort.SceneCoverage::transcripted)
                                .count(),
                        sceneIds.stream()
                                .filter(sceneId -> !resolvedTags
                                        .getOrDefault(sceneId, List.of())
                                        .isEmpty())
                                .count()),
                items,
                page,
                size,
                coverage.size());
    }

    private static ClipAnalysisScenesResult.Tag tag(EffectiveTag tag) {
        return new ClipAnalysisScenesResult.Tag(
                tag.tagId(),
                tag.tagType().storedValue(),
                tag.name(),
                tag.matchValue(),
                tag.scope().name().toLowerCase(java.util.Locale.ROOT),
                tag.source(),
                tag.verification().name().toLowerCase(java.util.Locale.ROOT));
    }
}
