package com.npick.tag.domain.model;

/**
 * 검수자가 만든 태그 판단 하나(S15P21A501-160, F-10). {@code tag_evidence} 의
 * {@code source='reviewer_feedback'}·{@code confirmed=false} 행으로 저장된다.
 *
 * <p>네 가지 작업(추가·반려·교체·복원)은 이 판단의 {@code verificationStatus} 로 표현된다 — 추가/복원승인=verified, 반려=rejected, 개입 해제=withdrawn.
 * 교체는 반려 판단과 추가 판단 두 개로 이뤄진다. 대상 태그·태깅이 없으면 만들고, 있으면 재사용한다(공용 태그 사전의 의미는 바꾸지 않는다).
 *
 * @param sourceFeedbackId 원인 신고
 * @param clipId 대상 클립
 * @param sceneId 장면 범위면 장면 id, 클립 범위면 {@code null}
 * @param tagType 태그 유형 (location, event 등)
 * @param matchValue 검색용 정규화 값
 * @param displayName 표시 이름
 * @param verificationStatus verified | rejected | withdrawn
 */
public record ReviewerTagJudgment(
        long sourceFeedbackId,
        long clipId,
        Long sceneId,
        String tagType,
        String matchValue,
        String displayName,
        String verificationStatus) {}
