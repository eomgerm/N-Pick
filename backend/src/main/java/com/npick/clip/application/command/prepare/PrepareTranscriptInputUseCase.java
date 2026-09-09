package com.npick.clip.application.command.prepare;

import java.math.BigDecimal;
import java.util.List;

/** 등록 후 #36/#70이 호출할 입력 준비 경계. 선택·ASR·장면 저장은 수행하지 않는다. */
public interface PrepareTranscriptInputUseCase {
    Prepared prepare(Command command);

    /** DB에서 조회한 키와 현재 배정의 outputKeyPrefix만 전달한다. HTTP 사용자 입력으로 노출하지 않는다. */
    record Command(
            String videoStorageKey, String transcriptFileKey, BigDecimal videoDuration, String outputKeyPrefix) {}

    record Segment(String segmentId, long s, long e, String t, String sourceDetail) {}

    record Segments(String schemaVersion, List<Segment> segments) {
        public Segments {
            segments = List.copyOf(segments);
        }
    }

    record ArtifactRef(String kind, String storageKey, long byteSize, String contentHash) {}

    enum EmbeddedStatus {
        EXTRACTED,
        NO_TRACK,
        UNSUPPORTED,
        NO_VALID_SEGMENTS,
        EXTRACTION_FAILED
    }

    record TrackAttempt(int streamIndex, String reasonCode) {}

    record EmbeddedInspection(
            EmbeddedStatus status,
            Integer selectedStreamIndex,
            List<TrackAttempt> attempts,
            boolean broadcastCcInspected) {
        public EmbeddedInspection {
            attempts = List.copyOf(attempts);
        }
    }
    /** inputs.upstream.transcript에 직렬화한다. 전체 자막은 인라인하지 않는다. */
    record TranscriptInput(ArtifactRef segmentsArtifact, EmbeddedInspection embeddedInspection) {}

    interface Prepared extends AutoCloseable {
        TranscriptInput transcript();

        List<ArtifactRef> artifacts();
        /** 배정 기록 성공/결과 불명확 시 보존. I/O 없는 멱등 전환. */
        void retain();

        void close();
    }
}
