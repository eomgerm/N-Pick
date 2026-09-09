package com.npick.clip.domain.model;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import com.npick.clip.domain.error.ClipRegistrationErrorCode;
import com.npick.clip.domain.model.InitialClipRegistration.PipelineDefinition;
import com.npick.clip.domain.model.InitialClipRegistration.SourceType;
import com.npick.common.error.BusinessException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InitialClipRegistrationTest {
    private final Instant registeredAt = Instant.parse("2026-09-07T00:00:00Z");

    @Test
    void initializesQueuedRunForAllTenStagesFromWorkerDefinition() throws Exception {
        String stagesSource = Files.readString(Path.of("../ai/src/npick_worker/stages.py"));
        List<String> stages = Pattern.compile("StageSpec\\(\\s*\\d+,\\s*\"([^\"]+)\"")
                .matcher(stagesSource)
                .results()
                .map(match -> match.group(1))
                .toList();
        assertThat(stages).hasSize(10);
        var definition = new PipelineDefinition("test-pipeline-v1", stages);
        var registration = registration(SourceType.BROADCAST, null, null, null, null, definition);

        assertThat(registration.processingNo()).isEqualTo(1);
        assertThat(registration.status()).isEqualTo("queued");
        assertThat(registration.activePipelineRunId()).isNull();
        assertThat(registration.startedAt()).isNull();
        assertThat(registration.finishedAt()).isNull();
        assertThat(registration.errorCode()).isNull();
        assertThat(registration.stageStates().keySet()).containsExactlyElementsOf(stages);
        assertThat(registration.stageStates().values()).allSatisfy(stage -> {
            assertThat(stage.status()).isEqualTo("pending");
            assertThat(stage.attempts()).isZero();
        });
        assertThatThrownBy(() -> registration.stageStates().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThat(registration.dateEvidence()).isEmpty();
    }

    @Test
    void doesNotMarkPlannedAsrOrPlainScriptAsActualTranscriptSource() {
        var plainScript = registration(SourceType.BROADCAST, null, null, null, "참고용 일반 대본", definition());
        assertThat(plainScript.transcriptSource()).isEqualTo("none");
        assertThat(plainScript.scriptText()).isEqualTo("참고용 일반 대본");
        var withSubtitle =
                registration(SourceType.BROADCAST, null, null, "subtitles/123/provided.srt", null, definition());
        assertThat(withSubtitle.transcriptSource()).isEqualTo("provided");
    }

    @Test
    void preservesIndependentDatesAsUnverifiedClipLevelInputEvidence() {
        LocalDate broadcast = LocalDate.of(2026, 9, 7);
        LocalDate filmed = LocalDate.of(2024, 2, 29);
        var registration = registration(SourceType.BROADCAST, broadcast, filmed, null, null, definition());
        assertThat(registration.dateEvidence())
                .extracting(InitialClipRegistration.DateEvidence::date)
                .containsExactly(broadcast, filmed);
        assertThat(registration.dateEvidence())
                .extracting(InitialClipRegistration.DateEvidence::tagType)
                .containsExactly("broadcast_date", "filmed_date");
        assertThat(registration.dateEvidence()).allSatisfy(evidence -> {
            assertThat(evidence.source()).isEqualTo("user_input");
            assertThat(evidence.verificationStatus()).isEqualTo("unverified");
            assertThat(evidence.confidence()).isNull();
            assertThat(evidence.sceneId()).isNull();
            assertThat(evidence.sourceRefType()).isNull();
            assertThat(evidence.sourceRefId()).isNull();
            assertThat(evidence.sourceFeedbackId()).isNull();
        });
        var oneDate = registration(SourceType.ARCHIVE, null, filmed, null, null, definition());
        assertThat(oneDate.broadcastDate()).isNull();
        assertThat(oneDate.dateEvidence()).hasSize(1);
    }

    @Test
    void rejectsBroadcastDateForArchiveEvenOutsideHttpValidation() {
        assertThatThrownBy(() ->
                        registration(SourceType.ARCHIVE, LocalDate.of(2026, 9, 7), null, null, null, definition()))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        error -> assertThat(error.errorCode())
                                .isEqualTo(ClipRegistrationErrorCode.ARCHIVE_BROADCAST_DATE));
    }

    @Test
    void rejectsMissingVersionAndInvalidStageDefinition() {
        assertThatThrownBy(() -> new PipelineDefinition(" ", List.of("scene_detection")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PipelineDefinition("test-v1", List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PipelineDefinition("test-v1", List.of("asr", "asr")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsUnsupportedSourceType() {
        assertThatThrownBy(() -> SourceType.fromValue("mp4"))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        error ->
                                assertThat(error.errorCode()).isEqualTo(ClipRegistrationErrorCode.INVALID_SOURCE_TYPE));
    }

    private PipelineDefinition definition() {
        // 모델 단위 테스트의 최소 정의. 실제 단계 정본은 위 계약 테스트에서 별도로 검증한다.
        return new PipelineDefinition("test-pipeline-v1", List.of("scene_detection"));
    }

    private InitialClipRegistration registration(
            SourceType type,
            LocalDate broadcast,
            LocalDate filmed,
            String subtitle,
            String script,
            PipelineDefinition definition) {
        return new InitialClipRegistration(
                123,
                456,
                type,
                "clips/123/original",
                "a".repeat(64),
                "제목",
                subtitle,
                script,
                789,
                broadcast,
                filmed,
                definition,
                registeredAt);
    }
}
