package com.npick.clip.infrastructure.media;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@EnabledIfEnvironmentVariable(named = "NPICK_MEDIA_TESTS", matches = "true")
class FfmpegVideoValidatorIntegrationTest {
    @TempDir
    Path directory;

    private final FfmpegVideoValidator validator = new FfmpegVideoValidator("ffmpeg", Duration.ofSeconds(10));
    private final ObjectMapper objectMapper = new ObjectMapper();

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void acceptsWholeVideoWithOrWithoutAudioWithoutChangingOriginal(boolean includeAudio) throws Exception {
        Path video = generateVideo(includeAudio, false);
        byte[] original = Files.readAllBytes(video);

        validator.validate(video);

        assertThat(Files.readAllBytes(video)).isEqualTo(original);
    }

    @ParameterizedTest
    @ValueSource(ints = {50, 99})
    void rejectsCorruptionInMiddleOrFinalFrameEvenWhenMetadataIsReadable(int packetIndex) throws Exception {
        Path video = generateVideo(false, false);
        corruptVideoPacket(video, "v:0", packetIndex);
        var reader = new FfprobeVideoReader("ffprobe", Duration.ofSeconds(10), objectMapper);
        assertThat(reader.readVideo(video).videoStreams()).hasSize(1);

        // 앞 1초가 정상이어도 중간(2초) 또는 마지막(3.96초) 손상은 전체 검사에서 거부해야 한다.
        run("ffmpeg", "-v", "error", "-xerror", "-i", video.toString(), "-t", "1", "-f", "null", "-");
        assertThatThrownBy(() -> validator.validate(video))
                .isInstanceOf(IOException.class)
                .hasMessage("영상 전체를 디코딩할 수 없습니다.");
    }

    @Test
    void checksSecondVideoStreamAsWell() throws Exception {
        Path video = generateVideo(false, true);
        corruptVideoPacket(video, "v:1", 99);
        assertThatThrownBy(() -> validator.validate(video))
                .isInstanceOf(IOException.class)
                .hasMessage("영상 전체를 디코딩할 수 없습니다.");
    }

    @Test
    void distinguishesTimeoutAndStopsUnfinishedProcess() throws Exception {
        Path pidFile = directory.resolve("process.pid");
        Path sleeper = Files.writeString(
                directory.resolve("slow-ffmpeg"), "#!/bin/sh\necho $$ > '" + pidFile + "'\nexec sleep 30\n");
        assertThat(sleeper.toFile().setExecutable(true)).isTrue();
        Path input = Files.writeString(directory.resolve("input.mp4"), "input");
        var slowValidator = new FfmpegVideoValidator(sleeper.toString(), Duration.ofMillis(500));

        assertThatThrownBy(() -> slowValidator.validate(input))
                .isInstanceOf(TimeoutException.class)
                .hasMessage("전체 영상 검증 시간이 초과되어 검증을 완료하지 못했습니다.");

        long pid = Long.parseLong(Files.readString(pidFile).trim());
        assertThat(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false))
                .isFalse();
    }

    @Test
    void reportsMissingExecutableAsExecutionFailure() throws Exception {
        Path video = Files.writeString(directory.resolve("input.mp4"), "input");
        var unavailable =
                new FfmpegVideoValidator(directory.resolve("missing-ffmpeg").toString(), Duration.ofSeconds(10));
        assertThatThrownBy(() -> unavailable.validate(video))
                .isInstanceOf(IOException.class)
                .hasMessageNotContaining("영상 전체를 디코딩할 수 없습니다.");
    }

    private Path generateVideo(boolean includeAudio, boolean secondVideo) throws Exception {
        Path video = directory.resolve("sample video.mp4");
        List<String> command =
                new ArrayList<>(List.of("ffmpeg", "-v", "error", "-f", "lavfi", "-i", "testsrc2=s=160x120:r=25"));
        if (includeAudio) {
            command.addAll(List.of("-f", "lavfi", "-i", "anullsrc=r=16000:cl=mono"));
        }
        command.addAll(List.of("-map", "0:v"));
        if (secondVideo) {
            command.addAll(List.of("-map", "0:v"));
        }
        if (includeAudio) {
            command.addAll(List.of("-map", "1:a", "-c:a", "aac"));
        }
        command.addAll(List.of("-t", "4", "-c:v", "mpeg4", "-g", "1", video.toString()));
        run(command.toArray(String[]::new));
        return video;
    }

    private void corruptVideoPacket(Path video, String stream, int packetIndex) throws Exception {
        String json = run(
                "ffprobe",
                "-v",
                "error",
                "-select_streams",
                stream,
                "-show_packets",
                "-show_entries",
                "packet=pos,size,pts_time",
                "-of",
                "json",
                video.toString());
        var packets = objectMapper.readTree(json).path("packets");
        assertThat(packets.size()).isEqualTo(100);
        var packet = packets.get(packetIndex);
        assertThat(packet.path("pts_time").asDouble()).isGreaterThanOrEqualTo(2);
        int offset = packet.path("pos").asInt();
        int size = packet.path("size").asInt();
        byte[] bytes = Files.readAllBytes(video);
        Arrays.fill(bytes, offset, offset + size, (byte) 0);
        Files.write(video, bytes);
    }

    private String run(String... command) throws Exception {
        Path output = Files.createTempFile(directory, "command-", ".out");
        Process process = new ProcessBuilder(command)
                .redirectOutput(output.toFile())
                .redirectError(ProcessBuilder.Redirect.INHERIT)
                .start();
        try {
            assertThat(process.waitFor(10, TimeUnit.SECONDS)).isTrue();
            assertThat(process.exitValue()).isZero();
            return Files.readString(output);
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly().waitFor();
            }
            Files.deleteIfExists(output);
        }
    }
}
