package com.npick.clip.infrastructure.transcript;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import com.npick.clip.application.command.prepare.PrepareTranscriptInputUseCase.EmbeddedStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MovTextExtractorTest {
    @Test
    void reportsUnsupportedCodecSeparatelyFromMissingTrackAndExtractionFailure() throws Exception {
        var process = mock(SubtitleProcess.class);
        var extractor =
                new MovTextExtractor(process, new SubtitleParser(), new ObjectMapper(), Duration.ofSeconds(2), 1024);
        for (var pair : List.of(new Object[] {"[]", EmbeddedStatus.NO_TRACK}, new Object[] {
            "[{\"index\":1,\"codec_type\":\"subtitle\",\"codec_name\":\"hdmv_pgs_subtitle\"}]",
            EmbeddedStatus.UNSUPPORTED
        })) {
            when(process.run(any(), any(), anyInt())).thenReturn(probe((String) pair[0]));
            var result = extractor.extract(Path.of("server.mp4"), BigDecimal.TEN);
            assertThat(result.inspection().status()).isEqualTo(pair[1]);
            assertThat(result.inspection().broadcastCcInspected()).isFalse();
        }
        when(process.run(any(), any(), anyInt()))
                .thenReturn(probe("[{\"index\":1,\"codec_type\":\"subtitle\",\"codec_name\":\"mov_text\"}]"))
                .thenThrow(new IOException("private path"));
        var result = extractor.extract(Path.of("server.mp4"), BigDecimal.TEN);
        assertThat(result.inspection().status()).isEqualTo(EmbeddedStatus.EXTRACTION_FAILED);
        assertThat(result.inspection().attempts())
                .extracting(a -> a.reasonCode())
                .containsExactly("EXTRACTION_FAILED");
    }

    private byte[] probe(String streams) {
        return ("{\"format\":{\"format_name\":\"mov,mp4\"},\"streams\":" + streams + "}")
                .getBytes(StandardCharsets.UTF_8);
    }
}
