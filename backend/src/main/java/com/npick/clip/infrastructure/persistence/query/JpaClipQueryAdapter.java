package com.npick.clip.infrastructure.persistence.query;

import java.util.List;
import java.util.Optional;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;

import org.springframework.stereotype.Repository;

import com.npick.clip.application.query.ClipQueryPort;
import com.npick.clip.application.query.ClipQueryResult;

@Repository
public class JpaClipQueryAdapter implements ClipQueryPort {
    private static final String PROJECTION = """
            select new com.npick.clip.infrastructure.persistence.query.ClipQueryRow(
                c.clipId, c.title, c.sourceType, c.activePipelineRunId, c.createdAt, c.updatedAt,
                c.transcriptSource, (c.transcriptFileKey is not null), (c.scriptText is not null), c.registeredById,
                r.pipelineRunId, r.processingNo, r.status, r.errorCode, r.createdAt, r.startedAt, r.finishedAt)
            """;
    private static final String FROM = """
            from ClipJpaEntity c left join PipelineRunJpaEntity r
              on r.clipId = c.clipId and not exists (
                select newer.pipelineRunId from PipelineRunJpaEntity newer
                where newer.clipId = c.clipId and
                  (newer.createdAt > r.createdAt or
                    (newer.createdAt = r.createdAt and newer.pipelineRunId > r.pipelineRunId)))
            where c.deletedAt is null
            """;
    private static final String OWNED = " and c.registeredById = :registeredById";
    private final EntityManager entityManager;

    public JpaClipQueryAdapter(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    public List<ClipQueryResult> findPage(int offset, int size, List<String> statuses, Long registeredById) {
        // One projection query includes the latest run; no per-clip entity lookup.
        var query = entityManager.createQuery(
                PROJECTION + from(registeredById)
                        + (statuses.isEmpty() ? "" : " and coalesce(r.status, 'no_run') in :statuses")
                        + " order by c.createdAt desc, c.clipId desc",
                ClipQueryRow.class);
        if (!statuses.isEmpty()) query.setParameter("statuses", statuses);
        return owner(query, registeredById).setFirstResult(offset).setMaxResults(size).getResultList().stream()
                .map(ClipQueryRow::toResult)
                .toList();
    }

    @Override
    public java.util.Map<String, Long> countByLatestRunStatus(Long registeredById) {
        var counts = new java.util.HashMap<String, Long>();
        for (String status : List.of("queued", "running", "failed", "succeeded", "no_run")) counts.put(status, 0L);
        var query = entityManager.createQuery(
                "select coalesce(r.status, 'no_run'), count(c) " + from(registeredById)
                        + " group by coalesce(r.status, 'no_run')",
                Object[].class);
        owner(query, registeredById).getResultList().forEach(row -> counts.put((String) row[0], (Long) row[1]));
        return java.util.Map.copyOf(counts);
    }

    @Override
    public Optional<ClipQueryResult> findVisible(long clipId) {
        return entityManager
                .createQuery(PROJECTION + FROM + " and c.clipId = :id", ClipQueryRow.class)
                .setParameter("id", clipId)
                .getResultList()
                .stream()
                .findFirst()
                .map(ClipQueryRow::toResult);
    }

    private static String from(Long registeredById) {
        return registeredById == null ? FROM : FROM + OWNED;
    }

    private static <T> TypedQuery<T> owner(TypedQuery<T> query, Long registeredById) {
        return registeredById == null ? query : query.setParameter("registeredById", registeredById);
    }
}
