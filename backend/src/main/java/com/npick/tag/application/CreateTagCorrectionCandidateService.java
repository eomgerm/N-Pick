package com.npick.tag.application;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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
 *
 * <p>이 엔드포인트에는 멱등 키가 없는 대신 자연 키로 중복을 막는다 (S15P21A501-317). 같은 신고·같은 태깅(유형·값·장면/클립 범위)·같은 판단으로 대기 중인 근거가 이미 있으면 새로 넣지 않고
 * 그 근거 id 를 돌려준다 — 새로고침 뒤 복원한 편집을 다시 보내거나 응답을 잃고 재시도해도 근거가 쌓이지 않는다. 확정된 근거는 재사용하지 않는다.
 *
 * <p>한 신고·한 태깅에 대기 판단은 <b>하나만</b> 남긴다. 판단이 바뀌면(승인→반려 등) 반대 판단의 대기 근거를 지우고 새 판단을 재사용하거나 만든다. 확정(-84)은 대기 근거를 모두 올리고 해석기는
 * 최신 판단을 쓰므로, 옛 판단을 재사용해 두 판단이 함께 남으면 최종 의도와 반대 판단이 이길 수 있다. 한 요청 안에서 같은 태깅에 판단이 여럿 오면 마지막 변경안이 이긴다.
 */
@Service
public class CreateTagCorrectionCandidateService implements CreateTagCorrectionCandidateUseCase {

    // 한 장면·클립 교정은 보통 1~5개, 교체가 2개씩이다. 20 이면 교체 10건을 한 번에 보내는 셈이라 정상 작업은 막지 않는다.
    private static final int MAX_OPERATIONS_PER_REQUEST = 20;

    // 이 엔드포인트에는 멱등 키가 없다. 같은 판단은 자연 키로 재사용하지만(S15P21A501-317) 서로 다른 판단을 계속 보내면 쌓이므로
    // 신고 단위로도 센다 (S15P21A501-255). 재사용분은 새 근거가 아니라 세지 않는다.
    private static final int MAX_JUDGMENTS_PER_FEEDBACK = 50;

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
    public CreateTagCorrectionCandidateResult create(CreateTagCorrectionCandidateCommand command) {
        if (!command.reviewerRole()) {
            throw new BusinessException(TagCorrectionCandidateErrorCode.EDITOR_FORBIDDEN);
        }
        // 본문 형태만 보는 두 검사는 전역 교정 상태 잠금보다 앞에 둔다 — 거대한 요청이 400 을 받기까지
        // 확정·판정 변경·규칙 중단을 함께 막지 않도록, 가장 싼 관문을 가장 먼저 지나게 한다.
        if (command.operations().isEmpty()) {
            throw new BusinessException(TagCorrectionCandidateErrorCode.EMPTY_OPERATIONS);
        }
        if (command.operations().size() > MAX_OPERATIONS_PER_REQUEST) {
            throw new BusinessException(TagCorrectionCandidateErrorCode.TOO_MANY_OPERATIONS);
        }
        correctionStateLock.acquire();
        TagContext context = tagContextPort
                .find(command.feedbackId())
                .orElseThrow(() -> new BusinessException(TagCorrectionCandidateErrorCode.FEEDBACK_NOT_FOUND));
        if (!"REVIEWING".equals(context.status())) {
            throw new BusinessException(TagCorrectionCandidateErrorCode.NOT_REVIEWING);
        }
        // 재설계 후 검수자는 통합 판정 "correction" 하나만 저장한다(S15P21A501-281) — 태그/해석/장면제외 중 무엇을 만들지는
        // 이제 저장된 resolution 문자열이 아니라 어느 생성 API 를 호출했는지로 정해진다. 레거시 3값(tag_correction·patch_parse·exclude_scene)
        // 행도 그대로 통과시켜야 하므로(하위 호환) "교정류인가"만 본다. feedback.domain.model.FeedbackResolution 을 그대로 쓰면
        // tag↔feedback 모듈 순환 의존이 생겨(ModuleBoundaryArchitectureTest) 같은 어휘를 여기서 다시 나열한다.
        if (!isCorrectionResolution(context.resolution())) {
            throw new BusinessException(TagCorrectionCandidateErrorCode.NOT_TAG_CORRECTION);
        }
        if (context.reviewedById() == null || context.reviewedById() != command.reviewerId()) {
            throw new BusinessException(TagCorrectionCandidateErrorCode.NOT_REVIEWER);
        }
        List<ReviewerTagJudgment> judgments = command.operations().stream()
                .map(operation -> toJudgment(command.feedbackId(), context, operation))
                .toList();

        // 태깅마다 최종 판단 하나. 한 요청 안에서 같은 태깅에 판단이 여럿 오면 마지막 변경안이 이긴다(LinkedHashMap 은 첫 등장 순서를 지킨다).
        Map<TaggingKey, ReviewerTagJudgment> finalJudgments = new LinkedHashMap<>();
        for (ReviewerTagJudgment judgment : judgments) {
            finalJudgments.put(TaggingKey.of(judgment), judgment);
        }

        // 반대 판단의 대기 근거를 먼저 지우고, 같은 판단이 남아 있으면 재사용한다. 아래 상한 초과로 거부되면 트랜잭션이 이 삭제도 되돌린다.
        Map<TaggingKey, Long> ids = new HashMap<>();
        Map<TaggingKey, ReviewerTagJudgment> toCreate = new LinkedHashMap<>();
        finalJudgments.forEach((key, judgment) -> {
            candidateRepository.discardConflictingPending(judgment);
            Optional<Long> pending = candidateRepository.findPendingJudgment(judgment);
            if (pending.isPresent()) {
                ids.put(key, pending.get());
            } else {
                toCreate.put(key, judgment);
            }
        });

        // 부분 저장을 남기지 않으려고 저장 루프 전에 이번 요청으로 새로 만들 것까지 더해 판정한다. 재사용분은 새 근거가 아니라 세지 않고,
        // 방금 지운 반대 판단은 이미 빠진 수로 센다.
        if (candidateRepository.countByFeedback(command.feedbackId()) + toCreate.size() > MAX_JUDGMENTS_PER_FEEDBACK) {
            throw new BusinessException(TagCorrectionCandidateErrorCode.JUDGMENT_LIMIT_EXCEEDED);
        }

        toCreate.forEach((key, judgment) -> ids.put(key, candidateRepository.addJudgment(judgment)));

        // 변경안마다 그 태깅에 최종으로 남은 근거 id 를 준다. 뒤 변경안에 진 앞 변경안도 살아남은 근거 id 를 받는다.
        List<Long> evidenceIds = new ArrayList<>();
        for (ReviewerTagJudgment judgment : judgments) {
            evidenceIds.add(ids.get(TaggingKey.of(judgment)));
        }
        return new CreateTagCorrectionCandidateResult(evidenceIds, toCreate.size());
    }

    private ReviewerTagJudgment toJudgment(long feedbackId, TagContext context, TagOperation operation) {
        String tagType = validTagType(operation.tagType());
        String matchValue = normalizedMatchValue(operation.matchValue());
        Long sceneId = operation.scope() == TagScope.SCENE ? context.sceneId() : null;
        return new ReviewerTagJudgment(
                feedbackId,
                context.clipId(),
                sceneId,
                tagType,
                matchValue,
                operation.displayName(),
                operation.action().verificationStatus());
    }

    // 대기 판단을 하나로 모으는 태깅 키. 신고·클립은 한 요청 안에서 같으므로 범위·태그만 담는다. 판단은 키가 아니다 — 태깅당 판단 하나만 남긴다.
    // 표시 이름은 태그 사전 값이라 키가 아니다.
    private record TaggingKey(Long sceneId, String tagType, String matchValue) {

        static TaggingKey of(ReviewerTagJudgment judgment) {
            return new TaggingKey(judgment.sceneId(), judgment.tagType(), judgment.matchValue());
        }
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

    // FeedbackResolution.fromValue 와 같은 어휘(레거시 3종 + 통합 "correction"). 임포트 대신 나열하는 이유는 클래스 상단 주석 참고.
    private boolean isCorrectionResolution(String resolution) {
        return "correction".equals(resolution)
                || "tag_correction".equals(resolution)
                || "patch_parse".equals(resolution)
                || "exclude_scene".equals(resolution);
    }
}
