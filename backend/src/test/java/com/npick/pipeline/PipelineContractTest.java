package com.npick.pipeline;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import com.npick.common.error.BusinessException;
import com.npick.pipeline.domain.model.PipelineRun;
import com.npick.pipeline.domain.model.PipelineStages;
import com.npick.pipeline.infrastructure.json.JobJsonAdapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PipelineContractTest {
    @Test
    void pipelineVersionMatchesPublishedPythonVectorAndCanonicalKeyOrder() {
        var json = new JobJsonAdapter();
        var versions = Map.of(
                "scene_detection", "npick.stage.scene_detection/v1:aaaaaaaa", "ocr", "npick.stage.ocr/v1:bbbbbbbb");
        assertThat("npick-pipeline/v1:" + json.hash(versions).substring(0, 12))
                .isEqualTo("npick-pipeline/v1:64960bae4565");
        assertThat(json.hash(Map.of("a", Map.of("가", 1, "b", 2), "b", List.of("한글"))))
                .isEqualTo(json.hash(Map.of("b", List.of("한글"), "a", Map.of("b", 2, "가", 1))));
    }

    @Test
    void stageOrderAndFatalFlagsMatchMergedWorkerDeclaration() throws Exception {
        String python = Files.readString(Path.of("../ai/src/npick_worker/stages.py"));
        var matcher = Pattern.compile(
                        "StageSpec\\(\\s*\\d+,\\s*\"([^\"]+)\",\\s*\"[^\"]+\",\\s*(True|False)", Pattern.DOTALL)
                .matcher(python);
        Map<String, Boolean> stages = new LinkedHashMap<>();
        while (matcher.find()) stages.put(matcher.group(1), "True".equals(matcher.group(2)));
        assertThat(stages.keySet()).containsExactlyElementsOf(PipelineStages.NAMES);
        assertThat(stages.entrySet().stream().filter(Map.Entry::getValue).map(Map.Entry::getKey))
                .containsExactlyInAnyOrderElementsOf(PipelineStages.FATAL);
    }

    @Test
    void stateMachineRejectsOutOfOrderDuplicateStartAndTerminalRestart() {
        PipelineRun run = run("queued");
        var now = Instant.parse("2026-09-09T00:00:00Z");
        assertThatThrownBy(() -> run.claim("ocr", "mock", UUID.randomUUID(), Map.of(), now))
                .isInstanceOf(BusinessException.class);
        run.claim("scene_detection", "mock", UUID.randomUUID(), Map.of(), now);
        assertThatThrownBy(() -> run.claim("scene_detection", "mock", UUID.randomUUID(), Map.of(), now))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> run("succeeded").claim("scene_detection", "mock", UUID.randomUUID(), Map.of(), now))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> run.state("scene_detection").put("status", "succeeded"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    private static PipelineRun run(String status) {
        Map<String, Object> stages = new LinkedHashMap<>();
        PipelineStages.NAMES.forEach(s -> stages.put(s, Map.of("status", "pending", "attempts", 0)));
        return new PipelineRun(new PipelineRun.Snapshot(
                1,
                2,
                1,
                "version",
                status,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                Map.of("schemaVersion", PipelineRun.SCHEMA, "stages", stages)));
    }
}
