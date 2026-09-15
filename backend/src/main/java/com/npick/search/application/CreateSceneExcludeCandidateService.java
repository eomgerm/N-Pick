package com.npick.search.application;

import java.util.Optional;

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
}
