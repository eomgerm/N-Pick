package com.npick.clip.infrastructure.transcript;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;

import com.npick.clip.application.command.prepare.PrepareTranscriptInputUseCase.Command;
import com.npick.clip.application.command.prepare.PrepareTranscriptInputUseCase.EmbeddedStatus;

import static org.assertj.core.api.Assertions.assertThat;

/** FFmpeg로 실제 mov_text를 mux하고 실제 ffprobe/ffmpeg와 입력 준비 어댑터를 함께 실행한다. */
@EnabledIfEnvironmentVariable(named = "NPICK_MEDIA_TESTS", matches = "true")
class MovTextExtractorIntegrationTest {
    @TempDir
    Path root;

    private final SubtitleProcess process = new SubtitleProcess();
    private final ObjectMapper mapper = new ObjectMapper();
    private final SubtitleParser parser = new SubtitleParser();
    private final MovTextExtractor extractor =
            new MovTextExtractor(process, parser, mapper, Duration.ofSeconds(20), 1024 * 1024);

    @ParameterizedTest
    @ValueSource(strings = {"mp4", "mov"})
    void preservesKoreanExactTimesAndPrefersKoreanDefaultOverOtherDefaults(String extension) throws Exception {
        Path video = mux(extension, List.of("eng", "und", "kor", "kor"), List.of(true, true, false, true), false);
        byte[] original = Files.readAllBytes(video);
        var extracted = extractor.extract(video, new BigDecimal("3"));
        assertThat(extracted.inspection().status()).isEqualTo(EmbeddedStatus.EXTRACTED);
        assertThat(extracted.inspection().selectedStreamIndex()).isEqualTo(4);
        assertThat(extracted.inspection().broadcastCcInspected()).isFalse();
        assertThat(extracted.cues())
                .containsExactly(
                        new SubtitleParser.Cue(0, 123, 1234, "트랙 3 한글 첫째"),
                        new SubtitleParser.Cue(1, 1244, 2345, "트랙 3 둘째 줄"));
        assertThat(Files.readAllBytes(video)).isEqualTo(original);

        var intake = new LocalTranscriptIntakeAdapter(root, 1024 * 1024, parser)
                .receive(TranscriptIntakeTest.subtitle(), new BigDecimal("3"), 25);
        intake.retain();
        intake.close();
        var preparation = new LocalTranscriptInputPreparation(root, 1024 * 1024, parser, extractor, mapper);
        var command = new Command(
                video.getFileName().toString(),
                intake.storageKey(),
                new BigDecimal("3"),
                "runs/88/transcript_selection/a1/");
        try (var prepared = preparation.prepare(command)) {
            var ref = prepared.transcript().segmentsArtifact();
            byte[] bytes = Files.readAllBytes(root.resolve(ref.storageKey()));
            assertThat(ref.storageKey()).startsWith(command.outputKeyPrefix());
            assertThat(ref.byteSize()).isEqualTo(bytes.length);
            assertThat(ref.contentHash()).isEqualTo(TranscriptFiles.hash(bytes)).isNotEqualTo(intake.contentHash());
            assertThat(prepared.artifacts()).containsExactly(ref);
            var snapshot = mapper.readTree(bytes);
            assertThat(snapshot.path("schemaVersion").asText()).isEqualTo("npick.transcript.segments/v1");
            assertThat(snapshot.path("segments").size()).isEqualTo(3);
            assertThat(snapshot.path("segments").get(0).path("segmentId").asText())
                    .isEqualTo("uploaded-0");
            assertThat(snapshot.path("segments").get(1).path("segmentId").asText())
                    .isEqualTo("embedded-0");
            // 겹치는 원문을 선택·절단하지 않은 입력 스냅샷이다.
            assertThat(snapshot.path("segments").get(1).path("s").asLong()).isEqualTo(123);
            assertThat(snapshot.path("segments").get(1).has("selected")).isFalse();
            assertThat(mapper.writeValueAsBytes(prepared.transcript()).length).isLessThan(1024 * 1024);
            try (var retry = preparation.prepare(command)) {
                assertThat(retry.transcript().segmentsArtifact().storageKey()).isNotEqualTo(ref.storageKey());
                assertThat(Files.readAllBytes(root.resolve(ref.storageKey()))).isEqualTo(bytes);
            }
        }
        assertThat(Files.readAllBytes(root.resolve(intake.storageKey()))).isEqualTo(TranscriptIntakeTest.BYTES);
    }

    @ParameterizedTest
    @ValueSource(strings = {"mp4", "mov"})
    void prefersUnspecifiedLanguageThenLowerStreamIndexAndFallsBackFromEmptyTrack(String extension) throws Exception {
        var unspecified = extractor.extract(
                mux(extension, List.of("eng", "und", "und"), List.of(true, false, false), false), BigDecimal.TEN);
        assertThat(unspecified.inspection().selectedStreamIndex()).isEqualTo(2);
        var tie =
                extractor.extract(mux(extension, List.of("kor", "kor"), List.of(false, false), false), BigDecimal.TEN);
        assertThat(tie.inspection().selectedStreamIndex()).isEqualTo(1);
        var fallback =
                extractor.extract(mux(extension, List.of("kor", "kor"), List.of(true, false), true), BigDecimal.TEN);
        assertThat(fallback.inspection().selectedStreamIndex()).isEqualTo(2);
        assertThat(fallback.inspection().attempts())
                .extracting(a -> a.reasonCode())
                .containsExactly("NO_VALID_SEGMENTS", "EXTRACTED");
    }

    @Test
    void distinguishesNoTrackUnsupportedContainerAndCorruptMedia() throws Exception {
        Path noTrack = mux("mp4", List.of(), List.of(), false);
        assertThat(extractor.extract(noTrack, BigDecimal.TEN).inspection().status())
                .isEqualTo(EmbeddedStatus.NO_TRACK);
        Path mkv = root.resolve("unsupported.mkv");
        process.run(
                List.of("ffmpeg", "-v", "error", "-i", noTrack.toString(), "-c", "copy", mkv.toString()),
                Duration.ofSeconds(20),
                1024);
        assertThat(extractor.extract(mkv, BigDecimal.TEN).inspection().status()).isEqualTo(EmbeddedStatus.UNSUPPORTED);
        Path corrupt = Files.writeString(root.resolve("corrupt.mp4"), "invalid media");
        assertThat(extractor.extract(corrupt, BigDecimal.TEN).inspection().status())
                .isEqualTo(EmbeddedStatus.EXTRACTION_FAILED);
    }

    @ParameterizedTest
    @CsvSource({"mp4,3600", "mov,3600", "mp4,3600.5", "mov,3600.5"})
    void preservesHourBoundaryThroughActualMovTextExtraction(String extension, BigDecimal duration) throws Exception {
        Path subtitle = root.resolve("hour.srt");
        String text =
                "1\n00:00:00,000 --> 00:00:00,100\n시작 기준\n\n2\n00:30:00,000 --> 00:30:00,100\n중간 기준\n\n3\n00:59:59,500 --> 01:00:00,000\n한 시간 경계\n";
        if (duration.compareTo(new BigDecimal("3600")) > 0) {
            text += "\n4\n01:00:00,100 --> 01:00:00,500\n한 시간 이후\n";
        }
        Files.writeString(subtitle, text);
        Path video = root.resolve("hour." + extension);
        // 30분 중간 cue는 일부 muxer에서 단일 긴 무자막 구간이 축소되는 현상을 피한다.
        // 저프레임률 합성 영상: 실제 타임라인을 유지하며 파일 크기와 생성 비용을 제한한다.
        process.run(
                List.of(
                        "ffmpeg",
                        "-v",
                        "error",
                        "-nostdin",
                        "-copyts",
                        "-f",
                        "lavfi",
                        "-i",
                        "color=c=black:s=64x64:r=2:d=" + duration.toPlainString(),
                        "-i",
                        subtitle.toString(),
                        "-map",
                        "0:v",
                        "-map",
                        "1:s",
                        "-c:v",
                        "mpeg4",
                        "-c:s",
                        "mov_text",
                        "-metadata:s:s:0",
                        "language=kor",
                        video.toString()),
                Duration.ofSeconds(20),
                1024 * 1024);
        var probe = mapper.readTree(process.run(
                List.of(
                        "ffprobe",
                        "-v",
                        "error",
                        "-show_entries",
                        "format=duration:stream=codec_name",
                        "-of",
                        "json",
                        video.toString()),
                Duration.ofSeconds(20),
                1024 * 1024));
        assertThat(new BigDecimal(probe.path("format").path("duration").asText()))
                .isEqualByComparingTo(duration);
        assertThat(probe.path("streams").get(1).path("codec_name").asText()).isEqualTo("mov_text");
        byte[] raw = process.run(
                List.of(
                        "ffmpeg",
                        "-v",
                        "error",
                        "-copyts",
                        "-i",
                        video.toString(),
                        "-map",
                        "0:1",
                        "-c:s",
                        "webvtt",
                        "-f",
                        "webvtt",
                        "pipe:1"),
                Duration.ofSeconds(20),
                1024 * 1024);
        assertThat(new String(raw, java.nio.charset.StandardCharsets.UTF_8)).containsPattern("0?1:00:00\\.000");
        var result = extractor.extract(video, duration);
        assertThat(result.inspection().status()).isEqualTo(EmbeddedStatus.EXTRACTED);
        assertThat(result.cues().get(2)).isEqualTo(new SubtitleParser.Cue(2, 3599500, 3600000, "한 시간 경계"));
        if (duration.compareTo(new BigDecimal("3600")) > 0) {
            assertThat(result.cues()).hasSize(4);
            assertThat(result.cues().get(3)).isEqualTo(new SubtitleParser.Cue(3, 3600100, 3600500, "한 시간 이후"));
        } else {
            assertThat(result.cues()).hasSize(3);
        }
    }

    private Path mux(String extension, List<String> languages, List<Boolean> defaults, boolean firstEmpty)
            throws Exception {
        List<String> command = new ArrayList<>(
                List.of("ffmpeg", "-v", "error", "-nostdin", "-f", "lavfi", "-i", "color=c=black:s=64x64:r=25:d=3"));
        for (int i = 0; i < languages.size(); i++) {
            Path subtitle = root.resolve("track-" + i + ".srt");
            Files.writeString(
                    subtitle,
                    firstEmpty && i == 0
                            ? "1\n00:00:00,123 --> 00:00:01,234\n\u00a0\n"
                            : "1\n00:00:00,123 --> 00:00:01,234\n트랙 " + i
                                    + " 한글 첫째\n\n2\n00:00:01,244 --> 00:00:02,345\n트랙 " + i + " 둘째 줄\n");
            command.addAll(List.of("-i", subtitle.toString()));
        }
        command.addAll(List.of("-map", "0:v", "-c:v", "mpeg4"));
        for (int i = 0; i < languages.size(); i++) {
            command.addAll(List.of(
                    "-map",
                    (i + 1) + ":s",
                    "-metadata:s:s:" + i,
                    "language=" + languages.get(i),
                    "-disposition:s:" + i,
                    defaults.get(i) ? "default" : "0"));
        }
        Path output = root.resolve("video-" + java.util.UUID.randomUUID() + "." + extension);
        command.addAll(List.of("-c:s", "mov_text", output.toString()));
        process.run(command, Duration.ofSeconds(20), 1024 * 1024);
        return output;
    }
}
