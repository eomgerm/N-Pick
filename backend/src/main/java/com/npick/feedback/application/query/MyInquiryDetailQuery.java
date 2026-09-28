package com.npick.feedback.application.query;

import java.util.Optional;

/**
 * 「내 문의 기록」 상세 조회 (S15P21A501-185). 본인(created_by_id) 소유 문의만 단건 조회한다 — 타인 소유·미존재를 구분하지 않고 동일하게 빈 값을 돌려줘 존재 여부를 노출하지
 * 않는다.
 */
public interface MyInquiryDetailQuery {

    Optional<MyInquiryDetail> findByOwner(long feedbackId, long ownerId);
}
