package com.npick.clip.infrastructure.persistence.query;

import java.util.List;
import jakarta.persistence.EntityManager;

import org.springframework.stereotype.Component;

import com.npick.clip.application.query.thumbnail.SceneKeyframeQueryPort;
import com.npick.clip.application.query.thumbnail.SceneKeyframeSource;

/**
 * 대표 keyframe 한 칸만 읽는다. Aggregate 를 적재하지 않으므로 Domain Repository 가 아니다(설계 정본 §9).
 *
 * <p>{@code scene}·{@code keyframe} 에는 JPA Entity 가 없다. 이 조회만을 위해 만드는 것은 정본 §17 이 금지한 보일러플레이트이므로 native query 를 쓴다.
 * 스키마를 명시하는 이유는 JDBC 연결의 기본 {@code search_path} 에 {@code npick} 이 없기 때문이다.
 */
@Component
public class SceneKeyframeQueryAdapter implements SceneKeyframeQueryPort {

    /**
     * 장면 행이 하나라도 있으면 결과가 한 줄 나오고, 그 줄의 {@code storage_key} 가 {@code null} 이면 keyframe 이 없다는 뜻이다. 그래서 «장면 없음» 과
     * «keyframe 없음» 이 한 번의 왕복으로 갈린다.
     *
     * <p>{@code deleted_at IS NULL} 만 걸고 {@code active_pipeline_run_id} 는 걸지 않는다. 검색 결과 카드는 활성 처리의 장면만 담지만, 검수 문의 큐는 접수
     * 당시의 장면을 그대로 보여 준다({@code InquiryListQueryAdapter}) — 재처리로 활성 run 이 바뀌었다고 이미 접수된 문의의 카드가 이미지를 잃으면 검수자가 무엇을 판단하는지 알
     * 수 없다. 반면 논리 삭제한 영상의 프레임은 재생과 같은 이유로 더 내보내지 않는다.
     *
     * <p>대표는 최소 {@code keyframe_id} 다 ({@code docs/contracts/job-api.md} §4.3.1). {@code keyframe} 테이블에 대표를 표시할 칸이 없어서
     * AI 가 순서로 알려 준다 — 선명도로 고른 대표를 목록 첫 원소에 싣고, BE 가 그 순서대로 INSERT 하므로 대표가 그 장면의 최소 {@code keyframe_id} 가 된다. 저장 시점에
     * {@code keyframes[0].timestampMs} 를 {@code representativeTimestampMs} 와 대조해 순서가 어긋난 출력은 애초에
     * 거부한다({@code JdbcWorkerStageOutputAdapter}).
     *
     * <p><b>{@code timestamp_ms} 로 정렬하면 안 된다.</b> 대표는 선명도로 뽑히므로 시각이 가장 이르지 않다 — 장면 앞머리에는 디졸브·암전이 오기 쉽고, 그 흐릿한 프레임이 결과
     * 카드의 얼굴이 된다. 같은 절이 이 오류를 이름 붙여 금지하고 있다.
     */
    private static final String SQL = """
            SELECT (SELECT k.storage_key
                    FROM npick.keyframe k
                    WHERE k.scene_id = s.scene_id
                    ORDER BY k.keyframe_id
                    LIMIT 1) AS storage_key
            FROM npick.scene s
            JOIN npick.clip c ON c.clip_id = s.clip_id AND c.deleted_at IS NULL
            WHERE s.scene_id = :sceneId
            """;

    private final EntityManager entityManager;

    public SceneKeyframeQueryAdapter(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    @SuppressWarnings("unchecked")
    public SceneKeyframeSource findRepresentativeKeyframe(long sceneId) {
        List<Object> rows = entityManager
                .createNativeQuery(SQL)
                .setParameter("sceneId", sceneId)
                .setMaxResults(1)
                .getResultList();
        if (rows.isEmpty()) {
            return SceneKeyframeSource.sceneMissing();
        }
        String storageKey = (String) rows.getFirst();
        return storageKey == null ? SceneKeyframeSource.keyframeMissing() : SceneKeyframeSource.of(storageKey);
    }
}
