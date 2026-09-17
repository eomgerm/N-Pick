package com.npick.search.infrastructure.persistence.query;

import java.util.List;
import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.npick.search.application.query.card.FindSceneCardsQueryPort;
import com.npick.search.application.query.card.SceneCard;
import com.npick.support.NpickPostgres;

import static org.assertj.core.api.Assertions.assertThat;

// 실 PostgreSQL 대상. 결과 카드의 당시 표시값 조회(-59, F-07·web-api §5) 통합 테스트.
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(SceneCardQueryAdapter.class)
class SceneCardQueryAdapterDbTest {

    @Autowired
    private FindSceneCardsQueryPort port;

    @Autowired
    private EntityManager em;

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry properties) {
        NpickPostgres.datasource(properties);
    }

    @Test
    @DisplayName("카드 한 장에 필요한 장면·클립 값을 한 번에 읽는다")
    void readsSceneAndClipValuesForOneCard() {
        seedClip(9101, "'KBC 뉴스9'");
        seedScene(9301, 9101, 42000, 49000, "b_roll", "'서울역 귀성 인파'", "'서울역 귀성 인파'", "'귀성객이 몰렸습니다'", "'귀성객 몰리다'");

        SceneCard card = port.find(List.of(9301L)).get(9301L);

        assertThat(card.sceneId()).isEqualTo(9301L);
        assertThat(card.clipId()).isEqualTo(9101L);
        assertThat(card.clipTitle()).isEqualTo("KBC 뉴스9");
        assertThat(card.caption()).isEqualTo("서울역 귀성 인파");
        assertThat(card.startTimeMs()).isEqualTo(42000L);
        assertThat(card.endTimeMs()).isEqualTo(49000L);
        assertThat(card.shotType()).isEqualTo("b_roll");
        assertThat(card.captionTokens()).containsExactly("서울역", "귀성", "인파");
        assertThat(card.transcriptText()).isEqualTo("귀성객이 몰렸습니다");
        assertThat(card.transcriptTokens()).containsExactly("귀성객", "몰리다");
    }

    @Test
    @DisplayName("OCR 근거를 keyframe 을 거쳐 장면에 붙인다")
    void attachesOcrEvidenceThroughKeyframes() {
        // match_evidence 의 field="ocr" 항목이 여기서 나온다. 한 장면에 keyframe 이 여럿이고
        // keyframe 마다 관측이 여럿일 수 있어 목록으로 받는다.
        seedClip(9101, "'KBC 뉴스9'");
        seedScene(9301, 9101, 0, 1000, "b_roll", "'설 연휴'", "'설 연휴'", null, null);
        seedKeyframe(9401, 9301, 0);
        seedKeyframe(9402, 9301, 500);
        seedOcr(9501, 9401, "'서울역 · 설 연휴 귀성객'", "'서울역 설 연휴 귀성객'");
        seedOcr(9502, 9402, "'귀성길 정체'", "'귀성길 정체'");

        SceneCard card = port.find(List.of(9301L)).get(9301L);

        assertThat(card.ocrTexts()).hasSize(2);
        assertThat(card.ocrTexts()).extracting(SceneCard.OcrText::rawText)
                .containsExactlyInAnyOrder("서울역 · 설 연휴 귀성객", "귀성길 정체");
        assertThat(card.ocrTexts()).flatExtracting(SceneCard.OcrText::tokens).contains("서울역", "정체");
    }

    @Test
    @DisplayName("제목 없는 클립은 null 로 온다 — 대체 표기는 표현 계층이 정한다")
    void nullClipTitleIsCarriedThrough() {
        // clip.title 은 nullable 이다. 여기서 임의 문자열로 메우면 기록에도 그 값이 남아
        // 「제목이 없었다」와 「제목이 이랬다」를 나중에 구분할 수 없다 (§7.2).
        seedClip(9102, "NULL");
        seedScene(9302, 9102, 0, 1000, "anchor", "'앵커 멘트'", "'앵커 멘트'", null, null);

        assertThat(port.find(List.of(9302L)).get(9302L).clipTitle()).isNull();
    }

    @Test
    @DisplayName("없는 장면은 키가 없다")
    void missingScenesAreAbsentFromTheResult() {
        seedClip(9101, "'KBC 뉴스9'");
        seedScene(9301, 9101, 0, 1000, "b_roll", "'설 연휴'", "'설 연휴'", null, null);

        assertThat(port.find(List.of(9301L, 9999L))).containsOnlyKeys(9301L);
    }

    @Test
    @DisplayName("빈 입력은 조회하지 않는다")
    void emptyInputIsNotQueried() {
        assertThat(port.find(List.of())).isEmpty();
    }

    private void seedClip(long clipId, String title) {
        exec("INSERT INTO npick.member (member_id, login_id, password_hash, name, role, created_at, updated_at)"
                + " VALUES (9001, 'editor-9001', 'hash', '편집기자', 'editor', now(), now())"
                + " ON CONFLICT DO NOTHING");
        exec("INSERT INTO npick.clip (clip_id, source_type, storage_key, content_hash, title, transcript_source,"
                + " registered_by_id, created_at, updated_at) VALUES (" + clipId + ", 'broadcast', 'clips/" + clipId
                + "/o', repeat('a', 64), " + title + ", 'none', 9001, now(), now())");
        exec("INSERT INTO npick.pipeline_run (pipeline_run_id, clip_id, processing_no, pipeline_version, status,"
                + " stage_states_json, created_at, updated_at) VALUES (" + (clipId + 100) + ", " + clipId
                + ", 1, 'v1', 'succeeded', '{}'::jsonb, now(), now())");
        exec("UPDATE npick.clip SET active_pipeline_run_id = " + (clipId + 100) + " WHERE clip_id = " + clipId);
    }

    private void seedScene(
            long sceneId,
            long clipId,
            long startMs,
            long endMs,
            String shotType,
            String caption,
            String captionTokens,
            String transcriptText,
            String transcriptTokens) {
        exec("INSERT INTO npick.scene (scene_id, clip_id, pipeline_run_id, start_time_ms, end_time_ms, caption,"
                + " caption_tokens, shot_type, transcript_text, transcript_tokens, created_at, updated_at) VALUES ("
                + sceneId + ", " + clipId + ", " + (clipId + 100) + ", " + startMs + ", " + endMs + ", " + caption
                + ", " + captionTokens + ", '" + shotType + "', " + nullable(transcriptText) + ", "
                + nullable(transcriptTokens) + ", now(), now())");
    }

    private void seedKeyframe(long keyframeId, long sceneId, long timestampMs) {
        exec("INSERT INTO npick.keyframe (keyframe_id, scene_id, timestamp_ms, storage_key) VALUES (" + keyframeId
                + ", " + sceneId + ", " + timestampMs + ", 'frames/" + keyframeId + ".jpg')");
    }

    private void seedOcr(long observationId, long keyframeId, String rawText, String tokens) {
        exec("INSERT INTO npick.ocr_observation (ocr_observation_id, keyframe_id, raw_text, tokens, confidence,"
                + " bounding_box_json) VALUES (" + observationId + ", " + keyframeId + ", " + rawText + ", " + tokens
                + ", 0.9, '{}'::jsonb)");
    }

    private String nullable(String value) {
        return value == null ? "NULL" : value;
    }

    private void exec(String sql) {
        em.createNativeQuery(sql).executeUpdate();
    }
}
