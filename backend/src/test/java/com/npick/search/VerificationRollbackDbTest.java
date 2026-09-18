package com.npick.search;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.npick.search.application.query.search.VerifyCorrectionCandidatesUseCase;
import com.npick.support.TestGraph;

import static org.assertj.core.api.Assertions.assertThat;

class VerificationRollbackDbTest extends AbstractVerificationSearchDbTest {

    private static final long MEMBER_ID = 8303001L, CLIP_ID = 8303010L, RUN_ID = 8303020L,
            SCENE_ID = 8303030L, EXEC_ID = 8303040L, RESULT_ID = 8303050L, FEEDBACK_ID = 8303060L,
            EVIDENCE_ID = 8303070L;

    @Autowired private VerifyCorrectionCandidatesUseCase useCase;

    @BeforeEach
    void seed() {
        TestGraph.insertSearchableReportedScene(jdbc, MEMBER_ID, CLIP_ID, RUN_ID, SCENE_ID, EXEC_ID, RESULT_ID, FEEDBACK_ID);
        TestGraph.insertReviewerTagCandidate(jdbc, SCENE_ID, CLIP_ID, FEEDBACK_ID, EVIDENCE_ID); // confirmed=false
    }

    @Test
    @DisplayName("검증이 끝나면 후보 flip 이 롤백돼 공유 데이터가 그대로다")
    void rollsBackCandidateFlip() {
        useCase.verify(FEEDBACK_ID, MEMBER_ID);
        Boolean confirmed = jdbc.queryForObject(
                "SELECT confirmed FROM npick.tag_evidence WHERE evidence_id = ?", Boolean.class, EVIDENCE_ID);
        assertThat(confirmed).isFalse(); // flip(true) 이 롤백됐다
    }
}
