package com.npick.clip.infrastructure.media;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@EnabledIfEnvironmentVariable(named = "NPICK_MEDIA_TESTS", matches = "true")
class FfprobeVideoReaderIntegrationTest {
    @TempDir
    Path directory;

    private final FfprobeVideoReader reader =
            new FfprobeVideoReader("ffprobe", Duration.ofSeconds(10), new ObjectMapper());

    @Test
    void readsActualVideoWithoutModifyingOriginal() throws Exception {
        Path video = directory.resolve("sample with spaces.mp4");
        generate("-f", "lavfi", "-i", "color=c=black:s=320x240:r=25", "-t", "1", "-c:v", "mpeg4", video.toString());
        byte[] original = Files.readAllBytes(video);

        var metadata = reader.readVideo(video);

        assertThat(metadata.container()).contains("mp4");
        assertThat(metadata.durationSeconds()).isEqualByComparingTo("1");
        assertThat(metadata.videoStreams()).containsExactly(new FfprobeVideoReader.VideoStream("mpeg4", 320, 240));
        assertThat(metadata.audioCodecs()).isEmpty();
        assertThat(Files.readAllBytes(video)).isEqualTo(original);
    }

    @Test
    void rejectsAudioOnlyFileAfterReadingItsInformation() throws Exception {
        Path audio = directory.resolve("audio.wav");
        generate("-f", "lavfi", "-i", "anullsrc=r=16000:cl=mono", "-t", "1", audio.toString());
        var metadata = reader.read(audio);
        assertThat(metadata.videoStreams()).isEmpty();
        assertThat(metadata.audioCodecs()).containsExactly("pcm_s16le");
        assertThatThrownBy(() -> reader.readVideo(audio))
                .isInstanceOf(IOException.class)
                .hasMessage("영상 스트림이 없는 파일은 등록할 수 없습니다.");
    }

    @Test
    void terminatesProcessOnTimeout() throws Exception {
        Path pidFile = directory.resolve("process.pid");
        Path sleeper = Files.writeString(
                directory.resolve("slow-probe"), "#!/bin/sh\necho $$ > '" + pidFile + "'\nexec sleep 30\n");
        assertThat(sleeper.toFile().setExecutable(true)).isTrue();
        Path file = Files.writeString(directory.resolve("input.mp4"), "input");
        var slowReader = new FfprobeVideoReader(sleeper.toString(), Duration.ofMillis(500), new ObjectMapper());
        assertThatThrownBy(() -> slowReader.readVideo(file))
                .isInstanceOf(TimeoutException.class)
                .hasMessage("영상 정보 조회 시간이 초과되었습니다.");
        long pid = Long.parseLong(Files.readString(pidFile).trim());
        assertThat(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false))
                .isFalse();
    }

    private void generate(String... arguments) throws Exception {
        List<String> command = new ArrayList<>(List.of("ffmpeg", "-v", "error", "-nostdin"));
        command.addAll(List.of(arguments));
        Process process = new ProcessBuilder(command)
                .redirectError(ProcessBuilder.Redirect.INHERIT)
                .start();
        try {
            assertThat(process.waitFor(10, TimeUnit.SECONDS)).isTrue();
            assertThat(process.exitValue()).isZero();
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly().waitFor();
            }
        }
    }
}
