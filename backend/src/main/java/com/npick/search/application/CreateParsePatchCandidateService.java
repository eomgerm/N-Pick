package com.npick.search.application;

import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import com.npick.common.error.BusinessException;
import com.npick.search.application.error.ParseRuleCandidateErrorCode;
import com.npick.search.application.port.ParseContext;
import com.npick.search.application.port.ParseContextPort;
import com.npick.search.domain.model.ParseRule;
import com.npick.search.domain.model.ParseRuleCandidate;
import com.npick.search.domain.model.QueryResolution;
import com.npick.search.domain.repository.ParseRuleCandidateRepository;
import com.npick.search.infrastructure.persistence.mapper.ParseRuleJsonMapper;

/**
 * 검수자가 만든 비활성 patch_parse 규칙 후보를 저장한다 (S15P21A501-81, F-11).
 *
 * <p>검증은 두 가지다 — 전제(검수 중·해석 교정·담당 검수자)와 본문(parse-rule/v1 문법·출력 계약 호환). 조건 매칭과 explicit_filter 보호는 <b>발화 시점(-49)</b>
 * 소관이다: resolver_output 에는 explicit_filter 값이 없고(validator 가 강등, F-05), patch_parse 는 이번 신고가 아니라 조건이 맞는 미래 검색에
 * 적용되므로(F-11) 대상이 지금 해석에 실재할 것을 생성 시점에 요구하지 않는다.
 *
 * <p><b>트랜잭션 경계는 저장소가 갖는다.</b> 이 서비스는 트랜잭션을 열지 않는다. 유니크 위반이 저장소 트랜잭션 안에서만 롤백되어야 복구 조회(멱등)가 오염 없이 성립하기 때문이다.
 */
@Service
public class CreateParsePatchCandidateService implements CreateParsePatchCandidateUseCase {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final ParseContextPort parseContextPort;
    private final ParseRuleCandidateRepository candidateRepository;
    private final ParseRuleJsonMapper jsonMapper;

    public CreateParsePatchCandidateService(
            ParseContextPort parseContextPort,
            ParseRuleCandidateRepository candidateRepository,
            ParseRuleJsonMapper jsonMapper) {
        this.parseContextPort = parseContextPort;
        this.candidateRepository = candidateRepository;
        this.jsonMapper = jsonMapper;
    }

    @Override
    public ParseCandidateOutcome create(CreateParsePatchCandidateCommand command) {
        if (!command.reviewerRole()) {
            throw new BusinessException(ParseRuleCandidateErrorCode.EDITOR_FORBIDDEN);
        }
        ParseContext context = parseContextPort
                .find(command.feedbackId())
                .orElseThrow(() -> new BusinessException(ParseRuleCandidateErrorCode.FEEDBACK_NOT_FOUND));
        if (!"REVIEWING".equals(context.status())) {
            throw new BusinessException(ParseRuleCandidateErrorCode.NOT_REVIEWING);
        }
        if (!"patch_parse".equals(context.resolution())) {
            throw new BusinessException(ParseRuleCandidateErrorCode.NOT_PATCH_PARSE);
        }
        if (context.reviewedById() == null || context.reviewedById() != command.reviewerId()) {
            throw new BusinessException(ParseRuleCandidateErrorCode.NOT_REVIEWER);
        }

        Optional<Long> existing = candidateRepository.findId(command.feedbackId(), command.requestKey());
        if (existing.isPresent()) {
            return ParseCandidateOutcome.existing(existing.get());
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
}
