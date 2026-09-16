package com.npick.feedback.application;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.npick.feedback.application.query.MyInquiryDetail;
import com.npick.feedback.application.query.MyInquiryDetailQuery;
import com.npick.feedback.application.query.MyInquiryListPage;
import com.npick.feedback.application.query.MyInquiryListQuery;
import com.npick.feedback.domain.error.FeedbackErrorCode;
import com.npick.feedback.domain.error.FeedbackException;

/**
 * 검색 화면용 「내 문의 기록」 조회 서비스 (S15P21A501-185). 본인 소유 문의만 읽는 읽기 전용 경로다.
 */
@Service
public class MyInquiryQueryService {

    private final MyInquiryListQuery listQuery;
    private final MyInquiryDetailQuery detailQuery;

    public MyInquiryQueryService(MyInquiryListQuery listQuery, MyInquiryDetailQuery detailQuery) {
        this.listQuery = listQuery;
        this.detailQuery = detailQuery;
    }

    @Transactional(readOnly = true)
    public MyInquiryListPage listMine(long ownerId, int page, int size) {
        return new MyInquiryListPage(
                listQuery.findByOwner(ownerId, page, size), listQuery.countByOwner(ownerId));
    }

    /** 타인 소유·미존재를 구분하지 않고 동일하게 {@link FeedbackErrorCode#FEEDBACK_NOT_FOUND} 로 던진다(존재 여부 노출 금지). */
    @Transactional(readOnly = true)
    public MyInquiryDetail detailMine(long feedbackId, long ownerId) {
        return detailQuery
                .findByOwner(feedbackId, ownerId)
                .orElseThrow(() -> new FeedbackException(FeedbackErrorCode.FEEDBACK_NOT_FOUND));
    }
}
