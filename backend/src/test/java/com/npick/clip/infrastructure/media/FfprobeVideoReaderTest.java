package com.npick.clip.infrastructure.media;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FfprobeVideoReaderTest {
    private final FfprobeVideoReader reader =
            new FfprobeVideoReader("ffprobe", Duration.ofSeconds(10), new ObjectMapper());

    @Test
    void extractsVideoAndAudioInformation() throws Exception {
        var metadata = reader.parse("""
                {"format":{"format_name":"mov,mp4,m4a,3gp,3g2,mj2","duration":"1.250000"},
                 "streams":[
                   {"codec_type":"video","codec_name":"h264","width":320,"height":240},
                   {"codec_type":"audio","codec_name":"aac"}]}
                """);
        assertThat(metadata.container()).contains("mp4");
        assertThat(metadata.durationSeconds()).isEqualByComparingTo("1.25");
        assertThat(metadata.videoStreams()).containsExactly(new FfprobeVideoReader.VideoStream("h264", 320, 240));
        assertThat(metadata.audioCodecs()).containsExactly("aac");
        reader.requireVideoStream(metadata);
    }

    @Test
    void doesNotTreatCoverArtAsVideoOrInventMissingDuration() throws Exception {
        var metadata = reader.parse("""
                {"format":{"format_name":"mp3"},"streams":[
                  {"codec_type":"audio","codec_name":"mp3"},
                  {"codec_type":"video","codec_name":"mjpeg","disposition":{"attached_pic":1}}]}
                """);
        assertThat(metadata.videoStreams()).isEmpty();
        assertThat(metadata.audioCodecs()).containsExactly("mp3");
        assertThat(metadata.durationSeconds()).isNull();
        assertThatThrownBy(() -> reader.requireVideoStream(metadata))
                .isInstanceOf(IOException.class)
                .hasMessage("영상 스트림이 없는 파일은 등록할 수 없습니다.");
    }

    @Test
    void rejectsMalformedProbeOutput() {
        for (String json : new String[] {"not json", "{}", "null", "{\"format\":{},\"streams\":{}}"}) {
            assertThatThrownBy(() -> reader.parse(json)).isInstanceOf(IOException.class);
        }
    }

    @Test
    void reportsUnavailableExecutable(@TempDir Path directory) throws Exception {
        Path file = Files.write(directory.resolve("video.mp4"), new byte[] {1});
        var unavailable = new FfprobeVideoReader(
                directory.resolve("missing-ffprobe").toString(), Duration.ofSeconds(10), new ObjectMapper());
        assertThatThrownBy(() -> unavailable.read(file)).isInstanceOf(IOException.class);
    }
}
