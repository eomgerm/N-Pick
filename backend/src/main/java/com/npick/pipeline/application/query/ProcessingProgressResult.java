package com.npick.pipeline.application.query;

public record ProcessingProgressResult(
        String recordStatus,
        String currentStage,
        Integer totalSteps,
        Integer succeededSteps,
        Integer skippedSteps,
        Integer failedSteps) {
    public static ProcessingProgressResult from(ProcessingDetailsResult record) {
        if (record == null) return null;
        boolean complete = java.util.Set.of("available", "legacy").contains(record.recordStatus());
        var running = record.stages().stream()
                .filter(stage -> "running".equals(stage.status()))
                .toList();
        return new ProcessingProgressResult(
                record.recordStatus(),
                running.size() == 1 ? running.getFirst().name() : null,
                complete ? record.stages().size() : null,
                complete
                        ? (int) record.stages().stream()
                                .filter(stage -> "succeeded".equals(stage.status()))
                                .count()
                        : null,
                complete
                        ? (int) record.stages().stream()
                                .filter(stage -> "skipped".equals(stage.status()))
                                .count()
                        : null,
                complete
                        ? (int) record.stages().stream()
                                .filter(stage -> "failed".equals(stage.status()))
                                .count()
                        : null);
    }
}
