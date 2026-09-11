package com.npick.pipeline;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.npick.clip.application.query.media.GetWorkerMediaInputUseCase;
import com.npick.pipeline.application.command.WorkerInputAssembler;

import static org.assertj.core.api.Assertions.assertThat;

class WorkerInputAssemblerTest {
    @Test
    void attachesStoredMediaAndRequiresBothSharedVolumeDeclarations() {
        var transcript = Map.of("segmentsArtifact", Map.of("storageKey", "runs/1/transcript_selection/a1/input.json"));
        for (boolean server : List.of(false, true)) {
            for (boolean worker : List.of(false, true)) {
                var assembler = new WorkerInputAssembler(
                        id -> {
                            assertThat(id).isEqualTo(2);
                            return new GetWorkerMediaInputUseCase.MediaInput("clips/2/source.mp4", 123);
                        },
                        server);
                var result = assembler.attach(
                        Map.of(
                                "assigned",
                                true,
                                "job",
                                Map.of("clipId", "2", "inputs", Map.of("upstream", Map.of("transcript", transcript)))),
                        worker);
                var job = (Map<?, ?>) result.get("job");
                var inputs = (Map<?, ?>) job.get("inputs");
                var media = (Map<?, ?>) inputs.get("media");
                assertThat(media.get("transport")).isEqualTo(server && worker ? "shared-volume" : "http");
                assertThat(media.get("sizeBytes")).isEqualTo(123L);
                assertThat(((Map<?, ?>) inputs.get("upstream")).get("transcript"))
                        .isEqualTo(transcript);
            }
        }
    }
}
