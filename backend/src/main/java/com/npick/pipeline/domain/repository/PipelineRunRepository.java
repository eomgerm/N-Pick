package com.npick.pipeline.domain.repository;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.npick.pipeline.domain.model.PipelineRun;

public interface PipelineRunRepository {
    /** 잠금 없이 제한된 후보 배치를 조회한다. 잘못된 상태도 커서를 전진시킬 수 있다. */
    List<Candidate> candidates(Cursor after, Map<String, String> capabilities);

    record Cursor(Instant createdAt, long id) {}

    record Candidate(Cursor cursor, PipelineRun run) {}

    /** 배정 직전 해당 행만 잠그고 최신 상태를 반환한다. 다른 배정자가 잠근 행은 건너뛴다. */
    Optional<PipelineRun> tryLockCandidate(long runId);

    Optional<PipelineRun> lock(long runId);

    List<PipelineRun> lockExpired(Instant before);

    void save(PipelineRun run, Instant now);
}
