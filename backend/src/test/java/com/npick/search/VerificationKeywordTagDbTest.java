package com.npick.search;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.npick.search.application.port.QueryNormalization;
import com.npick.search.application.port.QueryResolutionResult;
import com.npick.search.application.query.search.SceneDiff;
import com.npick.search.application.query.search.VerificationResult;
import com.npick.search.application.query.search.VerifyCorrectionCandidatesUseCase;
import com.npick.search.domain.model.QueryResolution;
import com.npick.support.TestGraph;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 대기 중인 keyword 태그 교정 후보가 검증 재검색(F-12 A/B)에 반영되는가 (S15P21A501-321).
 *
 * <p>설계 §4 가 색인 방식(B안) 대신 검색 시 태그 매칭(A안)을 고른 이유가 이것이다 — 후보를 트랜잭션 안에서 임시 적용하면 BM25 색인은 바뀌지 않지만 태그 조회는 바뀐다.
 */
class VerificationKeywordTagDbTest extends AbstractVerificationSearchDbTest {

    private static final long MEMBER_ID = 8321001L,
            CLIP_ID = 8321010L,
            RUN_ID = 8321020L,
            SCENE_ID = 8321030L,
            KEYWORD_SCENE_ID = 8321031L,
            EXEC_ID = 8321040L,
            RESULT_ID = 8321050L,
            FEEDBACK_ID = 8321060L,
            EVIDENCE_ID = 8321070L;

    @Autowired
    VerifyCorrectionCandidatesUseCase useCase;

    /** 기반 스텁은 품사 없는 토큰을 주는데, 키워드 조건은 명사류 품사 토큰에서만 만든다. 실제 워커 형식(`형태/품사` 소문자)으로 준다. */
    @Override
    protected QueryResolutionResult resolvedResult(String rawQuery) {
        QueryNormalization normalization = new QueryNormalization(rawQuery, List.of("원본질의/nng"), "normalizer/v1");
        QueryResolution resolution = new QueryResolution(
                "query-resolver/v2",
                QueryResolution.Intent.SCENE_SEARCH,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                0.9);
        return new QueryResolutionResult(
                normalization, resolution, List.of(), null, "query-resolver/v2", "prompt/v1", "model/v1", null);
    }

    @Test
    @DisplayName("대기 중인 키워드 태그 후보는 검증 재검색에서만 장면을 진입시키고 롤백된다")
    void pendingKeywordCandidateEntersOnlyInVerification() {
        TestGraph.insertSearchableReportedScene(
                jdbc, MEMBER_ID, CLIP_ID, RUN_ID, SCENE_ID, EXEC_ID, RESULT_ID, FEEDBACK_ID);
        insertTextlessScene(KEYWORD_SCENE_ID);
        TestGraph.insertReviewerKeywordTagCandidate(jdbc, KEYWORD_SCENE_ID, CLIP_ID, FEEDBACK_ID, EVIDENCE_ID, "원본질의");
        TestGraph.claimFeedback(jdbc, FEEDBACK_ID, MEMBER_ID);

        VerificationResult result = useCase.verify(FEEDBACK_ID, MEMBER_ID);

        // 대조군(후보 미적용)에는 없고 실험군(후보 flip)에는 있다 — 키워드 태그 하나로만 들어온 장면이다.
        assertThat(result.entered()).extracting(SceneDiff.Entered::sceneId).contains(KEYWORD_SCENE_ID);
        SceneDiff.Entered entered = result.entered().stream()
                .filter(scene -> scene.sceneId() == KEYWORD_SCENE_ID)
                .findFirst()
                .orElseThrow();
        @SuppressWarnings("unchecked")
        var match = (Map<String, Object>) entered.reason().get("match");
        @SuppressWarnings("unchecked")
        var evidence = (List<Map<String, Object>>) match.get("match_evidence");
        assertThat(evidence).anySatisfy(item -> {
            assertThat(item.get("field")).isEqualTo("tag");
            assertThat(item.get("value")).isEqualTo("원본질의");
        });
        // 검증은 롤백으로 끝난다 — 후보는 여전히 대기다.
        assertThat(jdbc.queryForObject(
                        "SELECT confirmed FROM npick.tag_evidence WHERE evidence_id = ?", Boolean.class, EVIDENCE_ID))
                .isFalse();
    }

    /** 같은 클립·활성 처리 아래 설명·대사·화면 글자가 전혀 없는 장면. 키워드 태그 말고는 들어올 길이 없다. */
    private void insertTextlessScene(long sceneId) {
        OffsetDateTime now = OffsetDateTime.now();
        jdbc.update(
                "INSERT INTO npick.scene(scene_id, clip_id, pipeline_run_id, start_time_ms, end_time_ms, "
                        + "shot_type, created_at, updated_at) VALUES (?, ?, ?, 2000, 3000, 'b_roll', ?, ?) "
                        + "ON CONFLICT DO NOTHING",
                sceneId,
                CLIP_ID,
                RUN_ID,
                now,
                now);
    }
}
