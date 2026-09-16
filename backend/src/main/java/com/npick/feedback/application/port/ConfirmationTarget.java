package com.npick.feedback.application.port;

/**
 * 확정 전제 판정에 필요한 신고 상태 (S15P21A501-84). Aggregate 전체가 아니라 확정 결정에 쓰는 칸만 읽는 Projection 이다.
 *
 * @param status 신고 상태 (OPEN/REVIEWING/CLOSED)
 * @param resolution 처리 결과 (tag_correction/patch_parse/…). 미처리면 {@code null}
 * @param reviewedById 담당 검수자. 없으면 {@code null}
 * @param verifiedByExecutionId 이미 확정에 연결된 검증 실행. 미확정이면 {@code null}
 */
public record ConfirmationTarget(
        String status, String resolution, Long reviewedById, Long verifiedByExecutionId) {}
