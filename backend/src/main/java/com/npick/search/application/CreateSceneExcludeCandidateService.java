package com.npick.search.application;

import java.util.Optional;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import com.npick.common.error.BusinessException;
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
 * <p><b>트랜잭션 경계는 저장소가 갖는다.</b> 이 서비스는 트랜잭션을 열지 않는다. 유니크 위반이 저장소 트랜잭션 안에서만 롤백되어야 복구 조회(멱등)가 오염 없이 성립하기 때문이다.
 */
@Service
public class CreateSceneExcludeCandidateService {

    private final ExcludeContextPort excludeContextPort;
    private final SceneExcludeCandidateRepository candidateRepository;

    public CreateSceneExcludeCandidateService(
            ExcludeContextPort excludeContextPort, SceneExcludeCandidateRepository candidateRepository) {
        this.excludeContextPort = excludeContextPort;
        this.candidateRepository = candidateRepository;
    }

    public ParseCandidateOutcome create(CreateSceneExcludeCandidateCommand command) {
        if (!command.reviewerRole()) {
            throw new BusinessException(SceneExcludeCandidateErrorCode.EDITOR_FORBIDDEN);
        }
        ExcludeContext context = excludeContextPort
                .find(command.feedbackId())
                .orElseThrow(() -> new BusinessException(SceneExcludeCandidateErrorCode.FEEDBACK_NOT_FOUND));
        if (!"REVIEWING".equals(context.status())) {
            throw new BusinessException(SceneExcludeCandidateErrorCode.NOT_REVIEWING);
        }
        if (!"exclude_scene".equals(context.resolution())) {
            throw new BusinessException(SceneExcludeCandidateErrorCode.NOT_EXCLUDE_SCENE);
        }
        if (context.reviewedById() == null || context.reviewedById() != command.reviewerId()) {
            throw new BusinessException(SceneExcludeCandidateErrorCode.NOT_REVIEWER);
        }
        if (command.targetSceneId() != context.sceneId()) {
            throw new BusinessException(SceneExcludeCandidateErrorCode.WRONG_TARGET_SCENE);
        }

        Optional<Long> existing = candidateRepository.findId(command.feedbackId(), command.requestKey());
        if (existing.isPresent()) {
            return ParseCandidateOutcome.existing(existing.get());
        }

        try {
            long id = candidateRepository.save(new SceneExcludeCandidate(
                    command.feedbackId(),
                    command.requestKey(),
                    command.targetSceneId(),
                    context.queryFingerprint(),
                    context.normalizedQuery(),
                    context.normalizedFiltersJson(),
                    context.normalizationVersion()));
            return ParseCandidateOutcome.created(id);
        } catch (DuplicateKeyException race) {
            // 같은 요청키의 동시 저장. 저장소 트랜잭션만 롤백됐으므로 여기서 다시 조회하면 이미 만들어진 후보가 보인다.
            return candidateRepository
                    .findId(command.feedbackId(), command.requestKey())
                    .map(ParseCandidateOutcome::existing)
                    .orElseThrow(() -> race);
        }
    }
}
