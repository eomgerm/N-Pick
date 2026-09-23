package com.npick.search.application;

import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.npick.common.error.BusinessException;
import com.npick.common.persistence.CorrectionStateLock;
import com.npick.search.application.error.SceneExcludeCandidateErrorCode;
import com.npick.search.application.port.ExcludeContext;
import com.npick.search.application.port.ExcludeContextPort;
import com.npick.search.domain.model.SceneExcludeCandidate;
import com.npick.search.domain.repository.SceneExcludeCandidateRepository;

/**
 * 검수자가 만든 비활성 장면 제외 후보를 저장한다 (S15P21A501-82, F-11).
 *
 * <p>전제(검수 중·장면 제외 판정·담당 검수자)와 대상 검증(제외 장면 == 신고 장면)만 한다. 본문 검증이 없는 것이 patch_parse 후보와의 차이다 — 제외는 조건/연산이 아니라 원 검색 지문으로
 * 매칭하기 때문이다. 검색 시 적용(-58)·검증(-83)·확정(-85)은 이 서비스의 일이 아니다.
 *
 * <p>확정과 같은 교정 상태 잠금을 잡은 짧은 트랜잭션 안에서 전제를 다시 읽고 후보를 저장한다. 삽입은 {@code ON CONFLICT DO NOTHING} 이라 충돌이 트랜잭션을 오염시키지 않으며, 충돌
 * 뒤 기존 후보 조회도 같은 트랜잭션에서 안전하게 이어진다.
 */
@Service
public class CreateSceneExcludeCandidateService implements CreateSceneExcludeCandidateUseCase {

    private final ExcludeContextPort excludeContextPort;
    private final SceneExcludeCandidateRepository candidateRepository;
    private final CorrectionStateLock correctionStateLock;

    public CreateSceneExcludeCandidateService(
            ExcludeContextPort excludeContextPort,
            SceneExcludeCandidateRepository candidateRepository,
            CorrectionStateLock correctionStateLock) {
        this.excludeContextPort = excludeContextPort;
        this.candidateRepository = candidateRepository;
        this.correctionStateLock = correctionStateLock;
    }

    @Override
    @Transactional
    public ParseCandidateOutcome create(CreateSceneExcludeCandidateCommand command) {
        if (!command.reviewerRole()) {
            throw new BusinessException(SceneExcludeCandidateErrorCode.EDITOR_FORBIDDEN);
        }
        correctionStateLock.acquire();
        ExcludeContext context = excludeContextPort
                .find(command.feedbackId())
                .orElseThrow(() -> new BusinessException(SceneExcludeCandidateErrorCode.FEEDBACK_NOT_FOUND));
        if (!"REVIEWING".equals(context.status())) {
            throw new BusinessException(SceneExcludeCandidateErrorCode.NOT_REVIEWING);
        }
        // 재설계 후 검수자는 통합 판정 "correction" 하나만 저장한다(S15P21A501-281) — 어떤 후보를 만들지는 저장된 resolution 이
        // 아니라 호출한 생성 API 로 정해진다. 레거시 3값도 하위 호환으로 통과시켜야 하므로 "교정류인가"만 본다.
        // feedback.domain.model.FeedbackResolution 을 그대로 쓰면 search↔feedback 모듈 순환 의존이 생겨(ModuleBoundaryArchitectureTest)
        // 같은 어휘를 여기서 다시 나열한다.
        if (!isCorrectionResolution(context.resolution())) {
            throw new BusinessException(SceneExcludeCandidateErrorCode.NOT_EXCLUDE_SCENE);
        }
        if (context.reviewedById() == null || context.reviewedById() != command.reviewerId()) {
            throw new BusinessException(SceneExcludeCandidateErrorCode.NOT_REVIEWER);
        }
        if (command.targetSceneId() != context.sceneId()) {
            throw new BusinessException(SceneExcludeCandidateErrorCode.WRONG_TARGET_SCENE);
        }

        // 멱등은 (신고, 대상 장면) 단위다 — exclude 후보는 내용이 전부 신고 컨텍스트에서 파생돼 장면당 하나뿐이다.
        // request_key 가 달라진 재시도도 같은 장면이면 기존 후보를 돌려준다(부분 유니크 인덱스가 DB 에서도 보장).
        Optional<Long> existing = candidateRepository.findByTargetScene(command.feedbackId(), command.targetSceneId());
        if (existing.isPresent()) {
            return ParseCandidateOutcome.existing(existing.get());
        }

        Optional<Long> inserted = candidateRepository.insertIfAbsent(new SceneExcludeCandidate(
                command.feedbackId(),
                command.requestKey(),
                command.targetSceneId(),
                context.queryFingerprint(),
                context.normalizedQuery(),
                context.normalizedFiltersJson(),
                context.normalizationVersion()));
        if (inserted.isPresent()) {
            return ParseCandidateOutcome.created(inserted.get());
        }
        // 동시 저장으로 방금 충돌했다. ON CONFLICT 가 예외 없이 흡수했으므로 다시 조회하면 먼저 만들어진 후보가 보인다.
        return candidateRepository
                .findByTargetScene(command.feedbackId(), command.targetSceneId())
                .map(ParseCandidateOutcome::existing)
                .orElseThrow(() -> new IllegalStateException("insert conflict but no existing candidate found"));
    }

    // FeedbackResolution.fromValue 와 같은 어휘(레거시 3종 + 통합 "correction"). 임포트 대신 나열하는 이유는 위 가드 주석 참고.
    private boolean isCorrectionResolution(String resolution) {
        return "correction".equals(resolution)
                || "tag_correction".equals(resolution)
                || "patch_parse".equals(resolution)
                || "exclude_scene".equals(resolution);
    }
}
