package com.npick.feedback.presentation.response;

import java.time.Instant;
import java.util.List;

import com.npick.feedback.application.query.ExecutionSnapshot;
import com.npick.feedback.application.query.InquiryDetail;
import com.npick.feedback.application.query.ReviewHistory;
import com.npick.feedback.application.query.SceneEvidence;

public record InquiryDetailResponse(
        long feedbackId,
        String status,
        String resolution,
        String resolutionNote,
        Instant createdAt,
        String comment,
        String resultExplainJson,
        ExecutionSnapshotResponse execution,
        List<SceneEvidenceResponse> evidence,
        ReviewHistoryResponse history) {

    public static InquiryDetailResponse from(InquiryDetail detail) {
        return new InquiryDetailResponse(
                detail.feedbackId(),
                detail.status(),
                detail.resolution(),
                detail.resolutionNote(),
                detail.createdAt(),
                detail.comment(),
                detail.resultExplainJson(),
                ExecutionSnapshotResponse.from(detail.execution()),
                detail.evidence().stream().map(SceneEvidenceResponse::from).toList(),
                ReviewHistoryResponse.from(detail.history()));
    }

    public record ExecutionSnapshotResponse(
            String queryText,
            String parsedQueryJson,
            String resolverOutputJson,
            String appliedRulesJson,
            String appliedExcludesJson) {
        public static ExecutionSnapshotResponse from(ExecutionSnapshot snapshot) {
            return new ExecutionSnapshotResponse(
                    snapshot.queryText(),
                    snapshot.parsedQueryJson(),
                    snapshot.resolverOutputJson(),
                    snapshot.appliedRulesJson(),
                    snapshot.appliedExcludesJson());
        }
    }

    public record SceneEvidenceResponse(
            long taggingId, String tagName, String source, String verifiedState, String scope) {
        public static SceneEvidenceResponse from(SceneEvidence evidence) {
            return new SceneEvidenceResponse(
                    evidence.taggingId(),
                    evidence.tagName(),
                    evidence.source(),
                    evidence.verifiedState(),
                    evidence.scope());
        }
    }

    public record ReviewHistoryResponse(Long reviewedById, Instant reviewStartedAt, Long verifiedByExecutionId) {
        public static ReviewHistoryResponse from(ReviewHistory history) {
            return new ReviewHistoryResponse(
                    history.reviewedById(), history.reviewStartedAt(), history.verifiedByExecutionId());
        }
    }
}
