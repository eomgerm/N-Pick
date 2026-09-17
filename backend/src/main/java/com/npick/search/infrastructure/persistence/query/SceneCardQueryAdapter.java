package com.npick.search.infrastructure.persistence.query;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import com.npick.search.application.query.card.FindSceneCardsQueryPort;
import com.npick.search.application.query.card.SceneCard;

@Repository
class SceneCardQueryAdapter implements FindSceneCardsQueryPort {

    /**
     * 클립 조인에 활성 처리·삭제 조건을 걸지 않는다. 적격 판정은 후보 단계({@code EligibleScenesQueryAdapter})가 이미 했고, 여기 오는 것은 순위·제외를 모두 통과한
     * 장면뿐이다. 여기서 한 번 더 거르면 같은 판정을 두 곳에서 하게 되고, 검색 도중 재처리가 끝나 활성 처리가 바뀌면 순위에는 있는데 카드가 없는 장면이 생긴다.
     */
    private static final String FIND_CARDS_SQL = """
            SELECT s.scene_id, s.clip_id, c.title, s.caption, s.caption_tokens,
                   s.start_time_ms, s.end_time_ms, s.shot_type,
                   s.transcript_text, s.transcript_tokens
            FROM npick.scene s
            JOIN npick.clip c ON c.clip_id = s.clip_id
            WHERE s.scene_id = ANY(:sceneIds)
            """;

    /** 관측 순서를 keyframe 시각으로 고정한다. 정렬이 없으면 같은 검색을 다시 해도 근거 나열 순서가 달라져 기록 비교가 흔들린다. */
    private static final String FIND_OCR_SQL = """
            SELECT k.scene_id, o.raw_text, o.tokens
            FROM npick.ocr_observation o
            JOIN npick.keyframe k ON k.keyframe_id = o.keyframe_id
            WHERE k.scene_id = ANY(:sceneIds)
            ORDER BY k.scene_id, k.timestamp_ms, o.ocr_observation_id
            """;

    private final NamedParameterJdbcTemplate jdbc;

    SceneCardQueryAdapter(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Map<Long, SceneCard> find(Collection<Long> sceneIds) {
        Objects.requireNonNull(sceneIds, "sceneIds");
        if (sceneIds.isEmpty()) return Map.of();

        var parameters = new MapSqlParameterSource("sceneIds", sceneIds.toArray(Long[]::new));
        Map<Long, List<SceneCard.OcrText>> ocrByScene = new LinkedHashMap<>();
        jdbc.query(FIND_OCR_SQL, parameters, row -> {
            ocrByScene
                    .computeIfAbsent(row.getLong("scene_id"), id -> new ArrayList<>())
                    .add(new SceneCard.OcrText(row.getString("raw_text"), tokens(row.getString("tokens"))));
        });

        Map<Long, SceneCard> cards = new LinkedHashMap<>();
        jdbc.query(FIND_CARDS_SQL, parameters, row -> {
            long sceneId = row.getLong("scene_id");
            cards.put(
                    sceneId,
                    new SceneCard(
                            sceneId,
                            row.getLong("clip_id"),
                            row.getString("title"),
                            row.getString("caption"),
                            row.getLong("start_time_ms"),
                            row.getLong("end_time_ms"),
                            row.getString("shot_type"),
                            tokens(row.getString("caption_tokens")),
                            row.getString("transcript_text"),
                            tokens(row.getString("transcript_tokens")),
                            ocrByScene.getOrDefault(sceneId, List.of())));
        });
        return cards;
    }

    /**
     * 색인 토큰은 공백으로 이어 붙인 한 덩어리다 ({@code WordSceneCandidateAdapter} 가 {@code string_to_array(:tokens, ' ')} 로 같은 규약을 쓴다).
     */
    private List<String> tokens(String joined) {
        if (joined == null || joined.isBlank()) return List.of();
        return List.of(joined.trim().split("\\s+"));
    }
}
