package com.npick.feedback.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.npick.feedback.domain.model.FeedbackResolution;

import static org.assertj.core.api.Assertions.assertThat;

class FeedbackResolutionTest {

    @Test
    @DisplayName("no_action·deferred만 종료성이며 사유가 필수다")
    void terminalAndNoteRequired() {
        assertThat(FeedbackResolution.NO_ACTION.isTerminal()).isTrue();
        assertThat(FeedbackResolution.DEFERRED.isTerminal()).isTrue();
        assertThat(FeedbackResolution.NO_ACTION.isNoteRequired()).isTrue();
        assertThat(FeedbackResolution.DEFERRED.isNoteRequired()).isTrue();
    }

    @Test
    @DisplayName("교정 3종은 reviewing 유지(비종료)이며 사유 선택이다")
    void correctionsStayReviewing() {
        for (FeedbackResolution r : new FeedbackResolution[] {
            FeedbackResolution.TAG_CORRECTION, FeedbackResolution.PATCH_PARSE, FeedbackResolution.EXCLUDE_SCENE
        }) {
            assertThat(r.isTerminal()).isFalse();
            assertThat(r.isNoteRequired()).isFalse();
        }
    }

    @Test
    @DisplayName("parse는 소문자 DB값으로 매핑하고 모르는 값은 null")
    void parseMapsKnownValues() {
        assertThat(FeedbackResolution.parse("patch_parse")).isEqualTo(FeedbackResolution.PATCH_PARSE);
        assertThat(FeedbackResolution.parse(" TAG_CORRECTION ")).isEqualTo(FeedbackResolution.TAG_CORRECTION);
        assertThat(FeedbackResolution.parse("closed")).isNull();
        assertThat(FeedbackResolution.parse("")).isNull();
        assertThat(FeedbackResolution.parse(null)).isNull();
    }

    @Test
    @DisplayName("기존 교정타입은 correction으로 매핑된다")
    void legacyCorrectionValuesMapToCorrection() {
        assertThat(FeedbackResolution.fromValue("tag_correction")).isEqualTo(FeedbackResolution.CORRECTION);
        assertThat(FeedbackResolution.fromValue("patch_parse")).isEqualTo(FeedbackResolution.CORRECTION);
        assertThat(FeedbackResolution.fromValue("exclude_scene")).isEqualTo(FeedbackResolution.CORRECTION);
        assertThat(FeedbackResolution.fromValue("correction")).isEqualTo(FeedbackResolution.CORRECTION);
        assertThat(FeedbackResolution.fromValue("no_action")).isEqualTo(FeedbackResolution.NO_ACTION);
    }
}
