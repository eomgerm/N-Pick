package com.npick.tag.application;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.npick.common.error.BusinessException;
import com.npick.tag.application.error.TagCorrectionCandidateErrorCode;
import com.npick.tag.application.port.TagContext;
import com.npick.tag.application.port.TagContextPort;
import com.npick.tag.domain.model.ReviewerTagJudgment;
import com.npick.tag.domain.repository.TagCorrectionCandidateRepository;

/**
 * 검수자가 만든 태그 교정 후보를 저장한다 (S15P21A501-160, F-10).
 *
 * <p>전제(검수 중·태그 교정 판정·담당 검수자)를 확인하고, 변경안 목록을 한 트랜잭션으로 저장한다. 교체가 반려+추가 두 작업으로 와도 원자적으로 처리된다. 범위는 신고의 장면·클립으로만 한정되므로 임의의
 * 대상을 지정할 수 없다. 저장되는 판단은 모두 {@code confirmed=false} 이며 확정(-84) 전까지 검색·해석에 반영되지 않는다.
 */
@Service
public class CreateTagCorrectionCandidateService {

    private final TagContextPort tagContextPort;
    private final TagCorrectionCandidateRepository candidateRepository;

    public CreateTagCorrectionCandidateService(
            TagContextPort tagContextPort, TagCorrectionCandidateRepository candidateRepository) {
        this.tagContextPort = tagContextPort;
        this.candidateRepository = candidateRepository;
    }

    @Transactional
    public List<Long> create(CreateTagCorrectionCandidateCommand command) {
        if (!command.reviewerRole()) {
            throw new BusinessException(TagCorrectionCandidateErrorCode.EDITOR_FORBIDDEN);
        }
        TagContext context = tagContextPort
                .find(command.feedbackId())
                .orElseThrow(() -> new BusinessException(TagCorrectionCandidateErrorCode.FEEDBACK_NOT_FOUND));
        if (!"REVIEWING".equals(context.status())) {
            throw new BusinessException(TagCorrectionCandidateErrorCode.NOT_REVIEWING);
        }
        if (!"tag_correction".equals(context.resolution())) {
            throw new BusinessException(TagCorrectionCandidateErrorCode.NOT_TAG_CORRECTION);
        }
        if (context.reviewedById() == null || context.reviewedById() != command.reviewerId()) {
            throw new BusinessException(TagCorrectionCandidateErrorCode.NOT_REVIEWER);
        }
        if (command.operations().isEmpty()) {
            throw new BusinessException(TagCorrectionCandidateErrorCode.EMPTY_OPERATIONS);
        }

        List<Long> evidenceIds = new ArrayList<>();
        for (TagOperation operation : command.operations()) {
            Long sceneId = operation.scope() == TagScope.SCENE ? context.sceneId() : null;
            evidenceIds.add(candidateRepository.addJudgment(new ReviewerTagJudgment(
                    command.feedbackId(),
                    context.clipId(),
                    sceneId,
                    operation.tagType(),
                    operation.matchValue(),
                    operation.displayName(),
                    operation.action().verificationStatus())));
        }
        return evidenceIds;
    }
}
