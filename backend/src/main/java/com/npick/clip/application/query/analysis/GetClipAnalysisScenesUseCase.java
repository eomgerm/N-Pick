package com.npick.clip.application.query.analysis;

public interface GetClipAnalysisScenesUseCase {
    ClipAnalysisScenesResult get(long clipId, long pipelineRunId, int page, int size);
}
