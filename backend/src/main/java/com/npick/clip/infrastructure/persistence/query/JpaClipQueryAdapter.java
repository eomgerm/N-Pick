package com.npick.clip.infrastructure.persistence.query;

import java.util.List;
import java.util.Optional;
import jakarta.persistence.EntityManager;

import org.springframework.stereotype.Repository;

import com.npick.clip.application.query.ClipQueryPort;
import com.npick.clip.application.query.ClipQueryResult;

@Repository
public class JpaClipQueryAdapter implements ClipQueryPort {
    private static final String PROJECTION = """
            select new com.npick.clip.infrastructure.persistence.query.ClipQueryRow(
                c.clipId, c.title, c.sourceType, c.activePipelineRunId, c.createdAt, c.updatedAt,
                c.transcriptSource, (c.transcriptFileKey is not null), (c.scriptText is not null),
                r.pipelineRunId, r.processingNo, r.status, r.errorCode, r.createdAt, r.startedAt, r.finishedAt)
            from ClipJpaEntity c left join PipelineRunJpaEntity r
              on r.clipId = c.clipId and not exists (
                select newer.pipelineRunId from PipelineRunJpaEntity newer
                where newer.clipId = c.clipId and
                  (newer.createdAt > r.createdAt or
                    (newer.createdAt = r.createdAt and newer.pipelineRunId > r.pipelineRunId)))
            where c.deletedAt is null
            """;
    private final EntityManager entityManager;

    public JpaClipQueryAdapter(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    public List<ClipQueryResult> findPage(int offset, int size) {
        // One projection query includes the latest run; no per-clip entity lookup.
        return entityManager
                .createQuery(PROJECTION + " order by c.createdAt desc, c.clipId desc", ClipQueryRow.class)
                .setFirstResult(offset)
                .setMaxResults(size)
                .getResultList()
                .stream()
                .map(ClipQueryRow::toResult)
                .toList();
    }

    @Override
    public long countVisible() {
        return entityManager
                .createQuery("select count(c) from ClipJpaEntity c where c.deletedAt is null", Long.class)
                .getSingleResult();
    }

    @Override
    public Optional<ClipQueryResult> findVisible(long clipId) {
        return entityManager
                .createQuery(PROJECTION + " and c.clipId = :id", ClipQueryRow.class)
                .setParameter("id", clipId)
                .getResultList()
                .stream()
                .findFirst()
                .map(ClipQueryRow::toResult);
    }
}
