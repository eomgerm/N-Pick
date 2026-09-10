package com.npick.pipeline.domain.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.npick.pipeline.domain.model.PipelineRun;

public interface PipelineRunRepository {
    /** 후보 행은 FOR UPDATE SKIP LOCKED로 잠그고 호출 트랜잭션 종료까지 유지한다. */
    List<PipelineRun> lockCandidates();

    Optional<PipelineRun> lock(long runId);

    List<PipelineRun> lockExpired(Instant before);

    void save(PipelineRun run, Instant now);
}
