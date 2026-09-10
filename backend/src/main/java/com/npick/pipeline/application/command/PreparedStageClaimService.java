package com.npick.pipeline.application.command;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.npick.clip.application.command.prepare.PrepareStoredTranscriptInputUseCase;
import com.npick.clip.application.command.prepare.PrepareTranscriptInputUseCase;
import com.npick.common.error.BusinessException;
import com.npick.pipeline.application.command.claim.AttachStageInputUseCase;
import com.npick.pipeline.application.command.claim.ClaimStageCommand;
import com.npick.pipeline.application.command.claim.ClaimStageUseCase;
import com.npick.pipeline.application.command.claim.ReserveStageUseCase;
import com.npick.pipeline.application.command.complete.CompleteStageCommand;
import com.npick.pipeline.application.command.complete.CompleteStageUseCase;
import com.npick.pipeline.application.port.PreparationLeasePort;
import com.npick.pipeline.domain.model.JsonValues;
import com.npick.pipeline.domain.model.PipelineStages;

@Service
public class PreparedStageClaimService implements ClaimStageUseCase {
    private final ReserveStageUseCase reservations;
    private final AttachStageInputUseCase inputs;
    private final CompleteStageUseCase completion;
    private final PrepareStoredTranscriptInputUseCase transcripts;
    private final PreparationLeasePort leases;

    public PreparedStageClaimService(
            ReserveStageUseCase reservations,
            AttachStageInputUseCase inputs,
            CompleteStageUseCase completion,
            PrepareStoredTranscriptInputUseCase transcripts,
            PreparationLeasePort leases) {
        this.reservations = reservations;
        this.inputs = inputs;
        this.completion = completion;
        this.transcripts = transcripts;
        this.leases = leases;
    }

    public Optional<Map<String, Object>> claim(ClaimStageCommand command) {
        var assigned = reservations.reserve(command);
        if (assigned.isEmpty()) return assigned;
        var assignment = assigned.orElseThrow();
        var job = JsonValues.object(assignment.get("job"));
        if (!"transcript_selection".equals(job.get("stage"))) return assigned;
        long run = Long.parseLong((String) job.get("pipelineRunId"));
        long clip = Long.parseLong((String) job.get("clipId"));
        UUID lease = UUID.fromString(
                (String) JsonValues.object(assignment.get("lease")).get("leaseId"));
        try (var guard = leases.maintain(run, "transcript_selection", command.workerId(), lease)) {
            String startedAt = Clock.systemUTC().instant().toString();
            long startedNanos = System.nanoTime();
            PrepareTranscriptInputUseCase.Prepared prepared;
            try {
                prepared = transcripts.prepare(clip, run, (String) job.get("outputKeyPrefix"));
            } catch (RuntimeException failure) {
                recordPreparationFailure(
                        run,
                        command.workerId(),
                        lease,
                        job,
                        startedAt,
                        java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos),
                        failure);
                throw failure;
            }
            try (prepared) {
                guard.verify();
                Map<String, Object> transcript = transcript(prepared.transcript());
                var artifacts = prepared.artifacts().stream()
                        .map(PreparedStageClaimService::artifact)
                        .toList();
                try {
                    var ready = inputs.attach(run, command.workerId(), lease, transcript, artifacts);
                    prepared.retain();
                    return Optional.of(ready);
                } catch (BusinessException rejected) {
                    // 도메인 fencing 거절은 쓰기 전 거절이므로 미보존 입력을 정리한다.
                    throw rejected;
                } catch (RuntimeException | Error unknown) {
                    prepared.retain();
                    throw unknown;
                }
            }
        }
    }

    private void recordPreparationFailure(
            long run,
            String worker,
            UUID lease,
            Map<String, Object> job,
            String startedAt,
            long durationMs,
            RuntimeException failure) {
        String now = Clock.systemUTC().instant().toString();
        Map<String, Object> versions = new LinkedHashMap<>();
        versions.put("stageVersion", "unknown");
        versions.put("outputSchemaVersion", PipelineStages.outputSchema("transcript_selection"));
        versions.put("configVersion", null);
        versions.put("modelVersion", null);
        versions.put("promptVersion", null);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("envelopeVersion", "stage-result/v1");
        result.put("stage", "transcript_selection");
        result.put("leaseId", lease.toString());
        result.put("attempt", job.get("attempt"));
        result.put("idempotencyKey", job.get("idempotencyKey"));
        result.put("status", "failed");
        result.put("startedAt", startedAt);
        result.put("finishedAt", now);
        result.put("durationMs", durationMs);
        result.put("versions", versions);
        result.put("output", null);
        result.put(
                "error",
                Map.of(
                        "code",
                        "STAGE_FAILED",
                        "retryable",
                        true,
                        "message",
                        "자막 입력 준비를 완료하지 못했습니다.",
                        "detail",
                        Map.of("phase", "input_preparation")));
        try {
            completion.complete(new CompleteStageCommand(
                    run, "transcript_selection", worker, (String) job.get("idempotencyKey"), result));
        } catch (RuntimeException persistenceFailure) {
            failure.addSuppressed(persistenceFailure);
        }
    }

    private static Map<String, Object> transcript(PrepareTranscriptInputUseCase.TranscriptInput input) {
        Map<String, Object> inspection = new LinkedHashMap<>();
        inspection.put("status", input.embeddedInspection().status().name());
        inspection.put("selectedStreamIndex", input.embeddedInspection().selectedStreamIndex());
        inspection.put(
                "attempts",
                input.embeddedInspection().attempts().stream()
                        .map(a -> Map.of("streamIndex", a.streamIndex(), "reasonCode", a.reasonCode()))
                        .toList());
        inspection.put("broadcastCcInspected", input.embeddedInspection().broadcastCcInspected());
        return JsonValues.copy(
                Map.of("segmentsArtifact", artifact(input.segmentsArtifact()), "embeddedInspection", inspection));
    }

    private static Map<String, Object> artifact(PrepareTranscriptInputUseCase.ArtifactRef ref) {
        return Map.of(
                "kind",
                ref.kind(),
                "storageKey",
                ref.storageKey(),
                "byteSize",
                ref.byteSize(),
                "contentHash",
                ref.contentHash());
    }
}
