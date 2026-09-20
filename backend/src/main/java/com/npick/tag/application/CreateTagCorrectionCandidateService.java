package com.npick.tag.application;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.npick.common.error.BusinessException;
import com.npick.common.persistence.CorrectionStateLock;
import com.npick.tag.application.error.TagCorrectionCandidateErrorCode;
import com.npick.tag.application.port.TagContext;
import com.npick.tag.application.port.TagContextPort;
import com.npick.tag.domain.model.ReviewerTagJudgment;
import com.npick.tag.domain.model.TagMatchValue;
import com.npick.tag.domain.model.TagType;
import com.npick.tag.domain.repository.TagCorrectionCandidateRepository;

/**
 * 검수자가 만든 태그 교정 후보를 저장한다 (S15P21A501-160, F-10).
 *
 * <p>전제(검수 중·태그 교정 판정·담당 검수자)를 확인하고, 변경안 목록을 한 트랜잭션으로 저장한다. 교체가 반려+추가 두 작업으로 와도 원자적으로 처리된다. 범위는 신고의 장면·클립으로만 한정되므로 임의의
 * 대상을 지정할 수 없다. 저장되는 판단은 모두 {@code confirmed=false} 이며 확정(-84) 전까지 검색·해석에 반영되지 않는다.
 */
@Service
public class CreateTagCorrectionCandidateService implements CreateTagCorrectionCandidateUseCase {

    private final TagContextPort tagContextPort;
    private final TagCorrectionCandidateRepository candidateRepository;
    private final CorrectionStateLock correctionStateLock;

    public CreateTagCorrectionCandidateService(
            TagContextPort tagContextPort,
            TagCorrectionCandidateRepository candidateRepository,
            CorrectionStateLock correctionStateLock) {
        this.tagContextPort = tagContextPort;
        this.candidateRepository = candidateRepository;
        this.correctionStateLock = correctionStateLock;
    }

    @Override
    @Transactional
    public List<Long> create(CreateTagCorrectionCandidateCommand command) {
        if (!command.reviewerRole()) {
            throw new BusinessException(TagCorrectionCandidateErrorCode.EDITOR_FORBIDDEN);
        }
        correctionStateLock.acquire();
        TagContext context = tagContextPort
                .find(command.feedbackId())
                .orElseThrow(() -> new BusinessException(TagCorrectionCandidateErrorCode.FEEDBACK_NOT_FOUND));
        if (!"REVIEWING".equals(context.status())) {
            throw new BusinessException(TagCorrectionCandidateErrorCode.NOT_REVIEWING);
        }
        // F-09 진단표: tag_correction 과 patch_parse(태그·해석 모두 잘못) 두 경로에서 태그 변경안을 만든다. patch_parse 는 해석 규칙 후보와
        // 함께 태그 교정 기록도 연결한다.
        if (!"tag_correction".equals(context.resolution()) && !"patch_parse".equals(context.resolution())) {
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
            String tagType = validTagType(operation.tagType());
            String matchValue = normalizedMatchValue(operation.matchValue());
            Long sceneId = operation.scope() == TagScope.SCENE ? context.sceneId() : null;
            evidenceIds.add(candidateRepository.addJudgment(new ReviewerTagJudgment(
                    command.feedbackId(),
                    context.clipId(),
                    sceneId,
                    tagType,
                    matchValue,
                    operation.displayName(),
                    operation.action().verificationStatus())));
        }
        return evidenceIds;
    }

    // 11종 어휘 검증을 경계에서 한다. TagType.from 은 미확인 값에 500 성 예외를 던지므로, 외부 입력에는 400 으로 바꿔 돌려준다
    // (읽기측 ROW_MAPPER 가 미확인 tag_type 에 500 으로 죽는 위험을 이 엔드포인트가 도달 가능하게 만들지 않도록).
    private String validTagType(String raw) {
        try {
            return TagType.from(raw).storedValue();
        } catch (BusinessException e) {
            throw new BusinessException(TagCorrectionCandidateErrorCode.INVALID_TAG_TYPE);
        }
    }

    // 백엔드 단일 정규화 지점(F-04). 검수자 입력을 검색어와 같은 표기로 맞춰 저장한다 — 안 걸면 쓰기/읽기 표기가 갈려 확정 후에도 조용히 0건이 된다.
    private String normalizedMatchValue(String raw) {
        String normalized = TagMatchValue.normalize(raw);
        if (normalized.isBlank()) {
            throw new BusinessException(TagCorrectionCandidateErrorCode.INVALID_MATCH_VALUE);
        }
        return normalized;
    }
}
