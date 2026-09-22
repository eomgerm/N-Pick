package com.npick.feedback.application;

import java.time.Instant;
import java.util.List;
import java.util.Locale;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.npick.common.persistence.CorrectionStateLock;
import com.npick.feedback.application.query.InquiryDetail;
import com.npick.feedback.application.query.InquiryDetailQuery;
import com.npick.feedback.application.query.InquiryListItem;
import com.npick.feedback.application.query.InquiryListPage;
import com.npick.feedback.application.query.InquiryListQuery;
import com.npick.feedback.domain.error.FeedbackErrorCode;
import com.npick.feedback.domain.error.FeedbackException;
import com.npick.feedback.domain.model.Feedback;
import com.npick.feedback.domain.model.FeedbackResolution;
import com.npick.feedback.domain.model.FeedbackStatus;
import com.npick.feedback.domain.repository.FeedbackRepository;
import com.npick.search.application.DiscardSearchRuleCandidatesUseCase;
import com.npick.tag.application.DiscardTagCorrectionUseCase;

@Service
public class InquiryReviewService
        implements ListInquiriesUseCase, GetInquiryDetailUseCase, ClaimInquiryUseCase, ResolveInquiryUseCase {

    private final InquiryListQuery listQuery;
    private final InquiryDetailQuery detailQuery;
    private final FeedbackRepository repository;
    private final CorrectionStateLock correctionStateLock;
    private final DiscardTagCorrectionUseCase discardTagCorrection;
    private final DiscardSearchRuleCandidatesUseCase discardSearchRuleCandidates;

    public InquiryReviewService(
            InquiryListQuery listQuery,
            InquiryDetailQuery detailQuery,
            FeedbackRepository repository,
            CorrectionStateLock correctionStateLock,
            DiscardTagCorrectionUseCase discardTagCorrection,
            DiscardSearchRuleCandidatesUseCase discardSearchRuleCandidates) {
        this.listQuery = listQuery;
        this.detailQuery = detailQuery;
        this.repository = repository;
        this.correctionStateLock = correctionStateLock;
        this.discardTagCorrection = discardTagCorrection;
        this.discardSearchRuleCandidates = discardSearchRuleCandidates;
    }

    @Override
    @Transactional(readOnly = true)
    public InquiryListPage list(String statusFilter, int page, int size) {
        String status = (statusFilter == null || statusFilter.isBlank()) ? null : statusFilter.toUpperCase(Locale.ROOT);
        List<InquiryListItem> items = listQuery.findByStatus(status, page, size);
        long totalElements = listQuery.countByStatus(status);
        return new InquiryListPage(items, totalElements, listQuery.countGroupedByStatus());
    }

    @Override
    public InquiryDetail detail(long feedbackId) {
        return detailQuery
                .findById(feedbackId)
                .orElseThrow(() -> new FeedbackException(FeedbackErrorCode.FEEDBACK_NOT_FOUND));
    }

    @Override
    @Transactional
    public void claim(long feedbackId, long reviewerId) {
        if (repository.claim(feedbackId, reviewerId, Instant.now()) == 0) {
            // CAS 0행. 최신 상태를 CAS 이후에 다시 읽어 판정한다(경합·재시도·종료 반영).
            // 존재하지 않으면 404, 지금도 이 검수자가 잡고 있는 reviewing이면 재시도로 보고 성공(소유자 멱등), 그 외엔 충돌.
            Feedback current = repository
                    .findById(feedbackId)
                    .orElseThrow(() -> new FeedbackException(FeedbackErrorCode.FEEDBACK_NOT_FOUND));
            boolean ownedByCaller = current.status() == FeedbackStatus.REVIEWING
                    && current.reviewedById() != null
                    && current.reviewedById() == reviewerId;
            if (!ownedByCaller) {
                throw new FeedbackException(FeedbackErrorCode.ALREADY_CLAIMED);
            }
        }
    }

    /**
     * 검수 처리 결과를 기록한다(F-09). 교정 3종은 reviewing 유지, no_action·deferred 는 사유를 필수로 받아 이 자리에서 closed 로 종료한다. reviewing 동안은
     * 판정을 몇 번이든 덮어쓸 수 있고, closed 후엔 CAS 가드로 잠긴다. 판정 변경은 후보 생성·확정과 같은 교정 상태 잠금에 참여해 전제 조회와 쓰기가 서로 끼어들지 않게 한다.
     */
    @Override
    @Transactional
    public void resolve(long feedbackId, long reviewerId, String rawResolution, String note) {
        FeedbackResolution resolution = FeedbackResolution.parse(rawResolution);
        if (resolution == null) {
            throw new FeedbackException(FeedbackErrorCode.INVALID_RESOLUTION);
        }
        // 공백만 있는 사유는 "미제공"으로 정규화한다. 그래야 COALESCE 가 기존 사유를 지우지 않고 유지한다.
        String normalizedNote = (note == null || note.isBlank()) ? null : note;
        if (resolution.isNoteRequired() && normalizedNote == null) {
            throw new FeedbackException(FeedbackErrorCode.NOTE_REQUIRED);
        }
        correctionStateLock.acquire();
        Feedback feedback = repository
                .findById(feedbackId)
                .orElseThrow(() -> new FeedbackException(FeedbackErrorCode.FEEDBACK_NOT_FOUND));
        if (feedback.status() != FeedbackStatus.REVIEWING) {
            throw new FeedbackException(FeedbackErrorCode.NOT_RESOLVABLE);
        }
        if (feedback.reviewedById() == null || feedback.reviewedById() != reviewerId) {
            throw new FeedbackException(FeedbackErrorCode.NOT_REVIEWER);
        }
        if (repository.resolve(feedbackId, reviewerId, resolution, normalizedNote, Instant.now()) == 0) {
            throw new FeedbackException(FeedbackErrorCode.NOT_RESOLVABLE); // 조회~갱신 사이 경합
        }
        // no_action 은 "오류 없음"으로 되돌린 종료다 — 검수 중 만들어 뒀던 대기 후보(교정 필요로 갔다가 되돌린 경우)가 이 신고 아래
        // confirmed=false·active=false 로 남아 있으면 나중 편집에 고아로 섞여 든다. 같은 트랜잭션(위 CAS 와 같은 correctionStateLock
        // 범위) 안에서 지워 부분 상태가 생기지 않게 한다. deferred(폐기 예정 값)·교정 3종은 건드리지 않는다.
        if (resolution == FeedbackResolution.NO_ACTION) {
            discardTagCorrection.discardPending(feedbackId);
            discardSearchRuleCandidates.discardPending(feedbackId);
        }
    }
}
