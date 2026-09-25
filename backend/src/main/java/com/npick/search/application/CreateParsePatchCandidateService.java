package com.npick.search.application;

import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.npick.common.error.BusinessException;
import com.npick.common.persistence.CorrectionStateLock;
import com.npick.search.application.error.ParseRuleCandidateErrorCode;
import com.npick.search.application.port.ParseContext;
import com.npick.search.application.port.ParseContextPort;
import com.npick.search.application.port.ParseRuleJsonPort;
import com.npick.search.domain.model.ParseRule;
import com.npick.search.domain.model.ParseRuleCandidate;
import com.npick.search.domain.model.QueryResolution;
import com.npick.search.domain.repository.ParseRuleCandidateRepository;

/**
 * 검수자가 만든 비활성 patch_parse 규칙 후보를 저장한다 (S15P21A501-81, F-11).
 *
 * <p>검증은 두 가지다 — 전제(검수 중·해석 교정·담당 검수자)와 본문(parse-rule/v1 문법·출력 계약 호환). 조건 매칭과 explicit_filter 보호는 <b>발화 시점(-49)</b>
 * 소관이다: resolver_output 에는 explicit_filter 값이 없고(validator 가 강등, F-05), patch_parse 는 이번 신고가 아니라 조건이 맞는 미래 검색에
 * 적용되므로(F-11) 대상이 지금 해석에 실재할 것을 생성 시점에 요구하지 않는다.
 *
 * <p>확정과 같은 교정 상태 잠금을 잡은 짧은 트랜잭션 안에서 전제를 다시 읽고 후보를 저장한다. 삽입은 {@code ON CONFLICT DO NOTHING} 이라 충돌이 트랜잭션을 오염시키지 않으며, 충돌
 * 뒤 기존 후보 조회도 같은 트랜잭션에서 안전하게 이어진다.
 */
@Service
public class CreateParsePatchCandidateService implements CreateParsePatchCandidateUseCase {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    // 신고 하나 = 해석 오류 하나(F-11)이고 교체도 R1→R2 한 쌍이라 실사용은 1~3개다. 초안을 고쳐 다시 낼 때마다
    // Idempotency-Key 가 바뀌어 행이 늘어나므로(F-12 5) 그 반복분까지 덮는 값으로 둔다 (S15P21A501-255).
    private static final int MAX_CANDIDATES_PER_FEEDBACK = 10;

    private final ParseContextPort parseContextPort;
    private final ParseRuleCandidateRepository candidateRepository;
    private final ParseRuleJsonPort jsonMapper;
    private final CorrectionStateLock correctionStateLock;

    public CreateParsePatchCandidateService(
            ParseContextPort parseContextPort,
            ParseRuleCandidateRepository candidateRepository,
            ParseRuleJsonPort jsonMapper,
            CorrectionStateLock correctionStateLock) {
        this.parseContextPort = parseContextPort;
        this.candidateRepository = candidateRepository;
        this.jsonMapper = jsonMapper;
        this.correctionStateLock = correctionStateLock;
    }

    @Override
    @Transactional
    public ParseCandidateOutcome create(CreateParsePatchCandidateCommand command) {
        if (!command.reviewerRole()) {
            throw new BusinessException(ParseRuleCandidateErrorCode.EDITOR_FORBIDDEN);
        }
        correctionStateLock.acquire();
        ParseContext context = parseContextPort
                .find(command.feedbackId())
                .orElseThrow(() -> new BusinessException(ParseRuleCandidateErrorCode.FEEDBACK_NOT_FOUND));
        if (!"REVIEWING".equals(context.status())) {
            throw new BusinessException(ParseRuleCandidateErrorCode.NOT_REVIEWING);
        }
        // 재설계 후 검수자는 통합 판정 "correction" 하나만 저장한다(S15P21A501-281) — 어떤 후보를 만들지는 저장된 resolution 이
        // 아니라 호출한 생성 API 로 정해진다. 레거시 3값도 하위 호환으로 통과시켜야 하므로 "교정류인가"만 본다.
        // feedback.domain.model.FeedbackResolution 을 그대로 쓰면 search↔feedback 모듈 순환 의존이 생겨(ModuleBoundaryArchitectureTest)
        // 같은 어휘를 여기서 다시 나열한다.
        if (!isCorrectionResolution(context.resolution())) {
            throw new BusinessException(ParseRuleCandidateErrorCode.NOT_PATCH_PARSE);
        }
        if (context.reviewedById() == null || context.reviewedById() != command.reviewerId()) {
            throw new BusinessException(ParseRuleCandidateErrorCode.NOT_REVIEWER);
        }

        Optional<Long> existing = candidateRepository.findId(command.feedbackId(), command.requestKey());
        if (existing.isPresent()) {
            return ParseCandidateOutcome.existing(existing.get());
        }

        // 멱등 재생(위)을 통과시킨 뒤에 센다 — 상한에 닿았다고 같은 키의 재시도가 갑자기 409 가 되면 안 된다.
        // 세는 대상은 대기 중인 후보뿐이며, 세기와 쓰기는 같은 교정 상태 잠금 안에 있다.
        // 대기 후보를 버리는 경로는 없다. 상한에 닿으면 남은 후보로 확정하거나 판정을 다시 내려야 한다.
        if (candidateRepository.countByFeedback(command.feedbackId()) >= MAX_CANDIDATES_PER_FEEDBACK) {
            throw new BusinessException(ParseRuleCandidateErrorCode.CANDIDATE_LIMIT_EXCEEDED);
        }

        if (context.resolverOutputJson() == null || context.resolverOutputJson().isBlank()) {
            // 원본 해석이 없으면 본문을 대조할 대상이 없다. 본문 오류가 아니라 전제 부재이므로 별도 코드로 정직하게 알린다.
            throw new BusinessException(ParseRuleCandidateErrorCode.RESOLVER_OUTPUT_ABSENT);
        }
        ParseRule rule = jsonMapper.toDomain(0L, command.conditionJson(), command.patchJson());
        if (rule.parseError() != null || rule.incompatibleReason(probe(context.resolverOutputJson())) != null) {
            throw new BusinessException(ParseRuleCandidateErrorCode.INVALID_CANDIDATE);
        }
        if (command.replacesRuleId() != null && !candidateRepository.existsActivePatchParse(command.replacesRuleId())) {
            throw new BusinessException(ParseRuleCandidateErrorCode.REPLACES_NOT_FOUND);
        }
        // 같은 R1 을 교체 대상으로 가리키는 대기 후보가 이미 있으면 거부한다 (S15P21A501-309). 함께 확정하면
        // 첫 후보가 R1 을 끈 뒤 둘째의 교체가 0행이 돼 전체 롤백되므로, 검증까지 가기 전에 생성에서 막는다.
        // 멱등 재생은 위에서 이미 걸러졌으므로 여기 오는 건 새 후보뿐이다.
        if (command.replacesRuleId() != null
                && candidateRepository.existsPendingReplacing(command.feedbackId(), command.replacesRuleId())) {
            throw new BusinessException(ParseRuleCandidateErrorCode.REPLACES_CONFLICT);
        }

        Optional<Long> inserted = candidateRepository.insertIfAbsent(new ParseRuleCandidate(
                command.feedbackId(),
                command.requestKey(),
                command.conditionJson(),
                command.patchJson(),
                command.replacesRuleId()));
        if (inserted.isPresent()) {
            return ParseCandidateOutcome.created(inserted.get());
        }
        // 동시 저장으로 방금 충돌했다. ON CONFLICT 가 예외 없이 흡수했으므로 다시 조회하면 먼저 만들어진 후보가 보인다.
        return candidateRepository
                .findId(command.feedbackId(), command.requestKey())
                .map(ParseCandidateOutcome::existing)
                .orElseThrow(() -> new IllegalStateException("insert conflict but no existing candidate found"));
    }

    /**
     * 본문 검증에 필요한 최소 해석. {@code incompatibleReason} 은 축 내용이 아니라 {@code schemaVersion} 만 보므로 나머지 축은 비운다. resolver_output
     * 전체를 {@link QueryResolution} 으로 파싱하는 경로는 아직 없고, 후보 생성에 필요하지도 않다(매칭은 -49 발화 시점).
     */
    private QueryResolution probe(String resolverOutputJson) {
        return new QueryResolution(
                schemaVersionOf(resolverOutputJson),
                QueryResolution.Intent.UNKNOWN,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                0.0);
    }

    private String schemaVersionOf(String resolverOutputJson) {
        try {
            JsonNode node = OBJECT_MAPPER.readTree(resolverOutputJson);
            return node.hasNonNull("schema_version")
                    ? node.get("schema_version").asText()
                    : null;
        } catch (Exception malformed) {
            return null;
        }
    }

    // FeedbackResolution.fromValue 와 같은 어휘(레거시 3종 + 통합 "correction"). 임포트 대신 나열하는 이유는 위 가드 주석 참고.
    private boolean isCorrectionResolution(String resolution) {
        return "correction".equals(resolution)
                || "tag_correction".equals(resolution)
                || "patch_parse".equals(resolution)
                || "exclude_scene".equals(resolution);
    }
}
