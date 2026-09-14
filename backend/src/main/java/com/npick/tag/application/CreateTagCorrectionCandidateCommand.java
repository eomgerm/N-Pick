package com.npick.tag.application;

import java.util.List;

/**
 * 태그 교정 후보 생성 요청. 여러 변경안(추가·반려·개입 해제)을 한 번에 담을 수 있고, 교체는 반려+추가 두 작업으로 표현한다. 전체가 한 트랜잭션으로 저장된다.
 *
 * @param feedbackId 대상 신고
 * @param reviewerId 요청 검수자
 * @param reviewerRole 요청자가 검수자 역할인가
 * @param operations 변경안 목록 (하나 이상)
 */
public record CreateTagCorrectionCandidateCommand(
        long feedbackId, long reviewerId, boolean reviewerRole, List<TagOperation> operations) {}
