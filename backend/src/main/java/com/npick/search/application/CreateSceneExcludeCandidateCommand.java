package com.npick.search.application;

/**
 * 장면 제외 후보 생성 요청.
 *
 * @param feedbackId 대상 신고
 * @param reviewerId 요청 검수자
 * @param reviewerRole 요청자가 검수자 역할인가. 편집기자면 거부
 * @param requestKey 생성 멱등 키
 * @param targetSceneId 제외할 장면. 신고가 참조한 장면과 같아야 한다
 */
public record CreateSceneExcludeCandidateCommand(
        long feedbackId, long reviewerId, boolean reviewerRole, String requestKey, long targetSceneId) {}
