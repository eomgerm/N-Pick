package com.npick.clip.infrastructure.worker;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.npick.clip.application.command.prepare.PrepareStoredTranscriptInputUseCase;
import com.npick.clip.application.command.prepare.PrepareTranscriptInputUseCase;
import com.npick.pipeline.application.port.StageTranscriptInputPort;

/**
 * {@link StageTranscriptInputPort} 구현 (S15P21A501-201).
 *
 * <p>준비 결과를 계약 §4.5 의 모양으로 여기서 직렬화한다. 준비한 쪽이 직렬화까지 마치면 {@code pipeline} 이 {@code Prepared}·
 * {@code TranscriptInput}·{@code ArtifactRef} 를 하나도 알 필요가 없어 모듈 고리가 닫히지 않는다.
 */
@Component
public class WorkerTranscriptInputAdapter implements StageTranscriptInputPort {
    private final PrepareStoredTranscriptInputUseCase transcripts;

    public WorkerTranscriptInputAdapter(PrepareStoredTranscriptInputUseCase transcripts) {
        this.transcripts = transcripts;
    }

    @Override
    public Prepared prepare(long clipId, long runId, String outputKeyPrefix) {
        return new SerializedPrepared(transcripts.prepare(clipId, runId, outputKeyPrefix));
    }

    /** 보존·정리 판단은 그대로 clip 의 준비물이 한다. 이 래퍼는 모양만 바꾼다. */
    private static final class SerializedPrepared implements Prepared {
        private final PrepareTranscriptInputUseCase.Prepared source;

        private SerializedPrepared(PrepareTranscriptInputUseCase.Prepared source) {
            this.source = source;
        }

        @Override
        public Map<String, Object> transcript() {
            return WorkerTranscriptInputAdapter.transcript(source.transcript());
        }

        @Override
        public List<Map<String, Object>> artifacts() {
            return source.artifacts().stream()
                    .map(WorkerTranscriptInputAdapter::artifact)
                    .toList();
        }

        @Override
        public void retain() {
            source.retain();
        }

        @Override
        public void close() {
            source.close();
        }
    }

    private static Map<String, Object> transcript(PrepareTranscriptInputUseCase.TranscriptInput input) {
        var embedded = input.embeddedInspection();
        Map<String, Object> inspection = new LinkedHashMap<>();
        inspection.put("status", embedded.status().name());
        inspection.put("selectedStreamIndex", embedded.selectedStreamIndex());
        inspection.put(
                "attempts",
                embedded.attempts().stream()
                        .map(a -> Map.<String, Object>of("streamIndex", a.streamIndex(), "reasonCode", a.reasonCode()))
                        .toList());
        inspection.put("broadcastCcInspected", embedded.broadcastCcInspected());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("segmentsArtifact", artifact(input.segmentsArtifact()));
        result.put("embeddedInspection", inspection);
        return result;
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
