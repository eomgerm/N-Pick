package com.npick.clip.domain.model;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

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
    void preservesIndependentDatesAsVerifiedClipLevelInputEvidence() {
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
            // 사용자 입력은 관측 근거다. 미검증으로 두면 방송일·촬영일 필터가 아무것도 걸러내지 못한다 (F-04, S15P21A501-231).
            assertThat(evidence.verificationStatus()).isEqualTo("verified");
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
    void rejectsFilmedDateAfterRegistrationDay() {
        assertThatThrownBy(() ->
                        registration(SourceType.BROADCAST, null, LocalDate.of(2026, 9, 8), null, null, definition()))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        error -> assertThat(error.errorCode()).isEqualTo(ClipRegistrationErrorCode.FUTURE_FILMED_DATE));
    }

    @Test
    void rejectsBroadcastDateAfterRegistrationDay() {
        assertThatThrownBy(() ->
                        registration(SourceType.BROADCAST, LocalDate.of(2026, 9, 8), null, null, null, definition()))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        error -> assertThat(error.errorCode())
                                .isEqualTo(ClipRegistrationErrorCode.FUTURE_BROADCAST_DATE));
    }

    /** 코드마다 가리키는 입력이 하나여야 화면이 방송일 문구를 촬영일 밑에 붙이지 않는다. */
    @Test
    void separatesErrorCodePerDateField() {
        assertThat(ClipRegistrationErrorCode.FUTURE_BROADCAST_DATE.code()).isEqualTo("CLIP_400_013");
        assertThat(ClipRegistrationErrorCode.BROADCAST_DATE_BEFORE_FILMED_DATE.code())
                .isEqualTo("CLIP_400_013");
        assertThat(ClipRegistrationErrorCode.FUTURE_FILMED_DATE.code()).isEqualTo("CLIP_400_014");
    }

    @Test
    void rejectsBroadcastDateEarlierThanFilmedDate() {
        assertThatThrownBy(() -> registration(
                        SourceType.BROADCAST,
                        LocalDate.of(2026, 9, 5),
                        LocalDate.of(2026, 9, 6),
                        null,
                        null,
                        definition()))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        error -> assertThat(error.errorCode())
                                .isEqualTo(ClipRegistrationErrorCode.BROADCAST_DATE_BEFORE_FILMED_DATE));
    }

    @Test
    void acceptsVideoFilmedAndBroadcastOnRegistrationDay() {
        LocalDate today = LocalDate.of(2026, 9, 7);
        var registration = registration(SourceType.BROADCAST, today, today, null, null, definition());
        assertThat(registration.broadcastDate()).isEqualTo(today);
        assertThat(registration.filmedDate()).isEqualTo(today);
    }

    /** 미래 날짜 검사를 앞에 끼워 넣어도 자료 영상의 방송일 거부는 그대로 CLIP_400_003 이어야 한다. */
    @Test
    void keepsArchiveBroadcastDateRejectionForFutureBroadcastDate() {
        assertThatThrownBy(() ->
                        registration(SourceType.ARCHIVE, LocalDate.of(2026, 9, 8), null, null, null, definition()))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        error -> assertThat(error.errorCode())
                                .isEqualTo(ClipRegistrationErrorCode.ARCHIVE_BROADCAST_DATE));
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

    @ParameterizedTest
    @CsvSource({"가, 50", "😀, 25"})
    void enforcesTitleLimitInUtf16UnitsEvenOutsideHttpValidation(String character, int count) {
        String title = character.repeat(count);
        assertThat(titled(title).title()).isEqualTo(title);
        assertThatThrownBy(() -> titled(title + character)).isInstanceOfSatisfying(BusinessException.class, error -> {
            assertThat(error.errorCode()).isEqualTo(ClipRegistrationErrorCode.TITLE_TOO_LONG);
            assertThat(error.errorCode().message()).isEqualTo("제목은 50자 이내로 입력해 주세요.");
        });
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void treatsBlankOptionalTitleAsAbsent(String title) {
        assertThat(titled(title).title()).isNull();
    }

    /**
     * S15P21A501-226 재현. dev 의 clip 64행 중 53행이 이 상태로 저장돼 있었다.
     *
     * <p>「인수위」를 CP949 로 적은 바이트다. UTF-8 로 읽으면 {@code CE BC} 만 우연히 유효한 두 바이트 문자(μ)로 살아남고 나머지는 한 바이트씩 U+FFFD 가 된다. 사라진 바이트
     * 값은 어디에도 남지 않아 되돌릴 수 없으므로, 저장 전에 거절하는 것 말고는 손쓸 방법이 없다.
     */
    @Test
    void rejectsTitleThatLostBytesToAFailedDecode() {
        byte[] cp949 = {(byte) 0xC0, (byte) 0xCE, (byte) 0xBC, (byte) 0xF6, (byte) 0xC0, (byte) 0xA7};
        String mojibake = new String(cp949, StandardCharsets.UTF_8);
        assertThat(mojibake).contains(String.valueOf((char) 0xFFFD));

        assertThatThrownBy(() -> titled(mojibake))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        error -> assertThat(error.errorCode()).isEqualTo(ClipRegistrationErrorCode.TITLE_NOT_UTF8));
        assertThat(titled("설 연휴 교통 정보").title()).isEqualTo("설 연휴 교통 정보");
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

    private InitialClipRegistration titled(String title) {
        return new InitialClipRegistration(
                123,
                456,
                SourceType.BROADCAST,
                "clips/123/original",
                "a".repeat(64),
                title,
                null,
                null,
                789,
                null,
                null,
                definition(),
                registeredAt);
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
