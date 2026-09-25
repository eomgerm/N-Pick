package com.npick.feedback.application;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.npick.feedback.application.query.CorrectionCandidates;
import com.npick.feedback.domain.error.FeedbackErrorCode;
import com.npick.feedback.domain.error.FeedbackException;
import com.npick.feedback.domain.model.Feedback;
import com.npick.feedback.domain.model.FeedbackStatus;
import com.npick.feedback.domain.repository.FeedbackRepository;
import com.npick.search.application.query.pending.ListPendingSearchRuleCandidatesUseCase;
import com.npick.search.application.query.pending.PendingSearchRuleCandidates;
import com.npick.tag.application.query.ListPendingTagCorrectionCandidatesUseCase;

/**
 * 이 신고의 대기 중인 교정 후보를 모아 돌려준다 (S15P21A501-317). 태그 근거는 tag, 규칙 후보는 search 가 공개한 조회 UseCase 로 읽는다(설계 정본 §14).
 *
 * <p>가드는 신고 존재와 담당 검수자뿐이다. 검수자 role 은 보안 계층({@code /api/v1/review/**})이 막는다. 검수 중(REVIEWING)이 아니면 오류 대신 빈 목록을 준다 — 확정 뒤
 * 사용 중단·교체된 규칙도 {@code active=false} 라 후보와 구분되지 않기 때문이다. 교정 상태 잠금은 잡지 않는다.
 *
 * <p>세 조회를 한 읽기 트랜잭션으로 묶어 같은 커넥션에서 읽는다.
 */
@Service
public class CorrectionCandidateQueryService implements GetCorrectionCandidatesUseCase {

    private final FeedbackRepository feedbackRepository;
    private final ListPendingTagCorrectionCandidatesUseCase pendingTags;
    private final ListPendingSearchRuleCandidatesUseCase pendingRules;

    public CorrectionCandidateQueryService(
            FeedbackRepository feedbackRepository,
            ListPendingTagCorrectionCandidatesUseCase pendingTags,
            ListPendingSearchRuleCandidatesUseCase pendingRules) {
        this.feedbackRepository = feedbackRepository;
        this.pendingTags = pendingTags;
        this.pendingRules = pendingRules;
    }

    @Override
    @Transactional(readOnly = true)
    public CorrectionCandidates get(long feedbackId, long reviewerId) {
        Feedback feedback = feedbackRepository
                .findById(feedbackId)
                .orElseThrow(() -> new FeedbackException(FeedbackErrorCode.FEEDBACK_NOT_FOUND));
        // 담당자는 CLOSED 후에도 reviewed_by_id 로 남는다. 아직 아무도 잡지 않은 OPEN 신고는 담당자가 없어 403 이다.
        if (feedback.reviewedById() == null || feedback.reviewedById() != reviewerId) {
            throw new FeedbackException(FeedbackErrorCode.NOT_REVIEWER);
        }
        // 대기 후보는 검수 중에만 있다 — 종료(no_action·deferred)는 지우고 확정은 켠다. 검수 중이 아니면 active=false 규칙은 후보가 아니라
        // 확정 뒤 사용 중단·교체된 이력이므로 복원 대상으로 내보내지 않는다.
        if (feedback.status() != FeedbackStatus.REVIEWING) {
            return new CorrectionCandidates(List.of(), List.of(), List.of());
        }
        PendingSearchRuleCandidates rules = pendingRules.listPending(feedbackId);
        return new CorrectionCandidates(
                pendingTags.listPending(feedbackId).stream()
                        .map(tag -> new CorrectionCandidates.TagCandidate(
                                tag.evidenceId(),
                                tag.taggingId(),
                                tag.action().name(),
                                tag.scope().name(),
                                tag.tagType(),
                                tag.matchValue(),
                                tag.displayName()))
                        .toList(),
                rules.parsePatches().stream()
                        .map(patch -> new CorrectionCandidates.ParsePatchCandidate(
                                patch.searchRuleId(), patch.conditionJson(), patch.patchJson(), patch.replacesRuleId()))
                        .toList(),
                rules.sceneExcludes().stream()
                        .map(exclude -> new CorrectionCandidates.SceneExcludeCandidate(
                                exclude.searchRuleId(), exclude.targetSceneId()))
                        .toList());
    }
}
