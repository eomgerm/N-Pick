package com.npick.search.domain.policy;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.npick.search.domain.model.ParseRule;
import com.npick.search.domain.model.ParseRuleOutcome;
import com.npick.search.domain.model.QueryResolution;
import com.npick.search.domain.model.ResolutionAxis;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F-11 완료 기준을 항목별로 확인한다.
 *
 * <p>시나리오는 FRD F-11 의 예시를 그대로 쓴다 — 공장명이 장소로만 해석되어 사건 검색을 놓친 경우, 장소 항목을 제거하고 원본 항목의 값을 사건명으로 옮긴다.
 */
class ParseRulePolicyTest {

    private static final String SCHEMA = "query-resolver/v2";
    private static final String FACTORY = "○○공장";

    private final ParseRulePolicy policy = new ParseRulePolicy();

    // ── 조건 판정 ────────────────────────────────────────────────────────

    @Test
    @DisplayName("표현이 달라도 해석 조건이 맞으면 교정한다")
    void appliesWhenConditionMatchesRegardlessOfWording() {
        // 같은 규칙, 원문 구간만 다른 두 해석. 질의 지문이 아니라 해석 조건으로 판정하므로 둘 다 걸린다.
        QueryResolution first = withLocation(FACTORY, QueryResolution.Origin.EXPLICIT_QUERY, span(0, 4));
        QueryResolution second = withLocation(FACTORY, QueryResolution.Origin.EXPLICIT_QUERY, span(7, 11));

        for (QueryResolution original : List.of(first, second)) {
            ParseRulePolicy.Result result = policy.apply(original, List.of(moveFactoryToIncident(10L)));

            assertThat(result.outcomes())
                    .singleElement()
                    .extracting(ParseRuleOutcome::status)
                    .isEqualTo(ParseRuleOutcome.Status.APPLIED);
            assertThat(result.resolution().locations()).isEmpty();
            assertThat(result.resolution().incidentNames())
                    .singleElement()
                    .extracting(QueryResolution.IncidentName::value)
                    .isEqualTo(FACTORY);
        }
    }

    @Test
    @DisplayName("문장이 비슷해도 조건이 다르면 적용하지 않고, 그것은 오류가 아니다")
    void skipsWhenConditionDoesNotMatch() {
        QueryResolution original = withLocation("△△공장", QueryResolution.Origin.EXPLICIT_QUERY, span(0, 4));

        ParseRulePolicy.Result result = policy.apply(original, List.of(moveFactoryToIncident(10L)));

        assertThat(result.outcomes())
                .singleElement()
                .extracting(ParseRuleOutcome::status)
                .isEqualTo(ParseRuleOutcome.Status.SKIPPED_CONDITION_UNMET);
        assertThat(result.resolution()).isEqualTo(original);
        // 조건 불일치는 기능 저하가 아니다. degraded 로 새면 정상 검색이 매번 「일부 기능 누락」으로 보인다.
        assertThat(result.degradedReasons()).isEmpty();
    }

    // ── 복수 적용과 충돌 ─────────────────────────────────────────────────

    @Test
    @DisplayName("독립 규칙 둘은 한 실행에서 함께 적용된다")
    void appliesIndependentRulesTogether() {
        QueryResolution original = withLocation(FACTORY, QueryResolution.Origin.EXPLICIT_QUERY, span(0, 4));

        ParseRulePolicy.Result result =
                policy.apply(original, List.of(moveFactoryToIncident(10L), setIntent(20L, "recent_scene")));

        assertThat(result.outcomes())
                .extracting(ParseRuleOutcome::status)
                .containsExactly(ParseRuleOutcome.Status.APPLIED, ParseRuleOutcome.Status.APPLIED);
        assertThat(result.outcomes()).extracting(ParseRuleOutcome::appliedOrder).containsExactly(1, 2);
        assertThat(result.resolution().locations()).isEmpty();
        assertThat(result.resolution().incidentNames()).hasSize(1);
        assertThat(result.resolution().intent()).isEqualTo(QueryResolution.Intent.RECENT_SCENE);
    }

    @Test
    @DisplayName("두 번째 규칙의 조건도 원본 해석으로 판정한다 — 앞 규칙의 결과를 보지 않는다")
    void evaluatesEveryConditionAgainstTheOriginalResolution() {
        QueryResolution original = withLocation(FACTORY, QueryResolution.Origin.EXPLICIT_QUERY, span(0, 4));

        // setIntent 의 조건도 locations 에 공장이 있는지를 본다. moveFactoryToIncident 가 먼저 그것을 지우지만,
        // 판정이 원본 기준이므로 둘 다 걸린다 (F-11 「모든 조건을 같은 AI 원본 해석에서 한 번 판정한다」).
        ParseRulePolicy.Result result =
                policy.apply(original, List.of(moveFactoryToIncident(10L), setIntent(20L, "recent_scene")));

        assertThat(result.resolution().intent()).isEqualTo(QueryResolution.Intent.RECENT_SCENE);
    }

    @Test
    @DisplayName("충돌 그룹만 건너뛰고 나머지는 적용하며 사유를 남긴다")
    void skipsOnlyTheConflictGroup() {
        QueryResolution original = withLocation(FACTORY, QueryResolution.Origin.EXPLICIT_QUERY, span(0, 4));

        ParseRulePolicy.Result result = policy.apply(
                original,
                List.of(
                        moveFactoryToIncident(10L),
                        setIntent(20L, "recent_scene"),
                        setIntent(30L, "unknown"))); // 20 과 같은 자리를 다르게 바꾼다

        assertThat(result.outcomes())
                .extracting(ParseRuleOutcome::ruleId, ParseRuleOutcome::status)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(10L, ParseRuleOutcome.Status.APPLIED),
                        org.assertj.core.groups.Tuple.tuple(20L, ParseRuleOutcome.Status.SKIPPED_CONFLICT),
                        org.assertj.core.groups.Tuple.tuple(30L, ParseRuleOutcome.Status.SKIPPED_CONFLICT));
        // 같은 그룹 번호로 묶여야 「무엇과 부딪혔는지」가 기록에서 복원된다.
        assertThat(result.outcomes().get(1).conflictGroup())
                .isEqualTo(result.outcomes().get(2).conflictGroup());
        assertThat(result.outcomes().get(1).reason()).contains("intent");
        // 임의의 마지막 규칙이 이기지 않는다.
        assertThat(result.resolution().intent()).isEqualTo(QueryResolution.Intent.SCENE_SEARCH);
        // 충돌하지 않은 규칙은 그대로 적용된다.
        assertThat(result.resolution().locations()).isEmpty();
    }

    @Test
    @DisplayName("같은 결과를 원하는 두 규칙은 충돌이 아니다")
    void identicalEffectsAreNotAConflict() {
        QueryResolution original = withLocation(FACTORY, QueryResolution.Origin.EXPLICIT_QUERY, span(0, 4));

        ParseRulePolicy.Result result =
                policy.apply(original, List.of(setIntent(20L, "recent_scene"), setIntent(30L, "recent_scene")));

        assertThat(result.outcomes())
                .extracting(ParseRuleOutcome::status)
                .containsExactly(ParseRuleOutcome.Status.APPLIED, ParseRuleOutcome.Status.APPLIED);
        assertThat(result.resolution().intent()).isEqualTo(QueryResolution.Intent.RECENT_SCENE);
    }

    @Test
    @DisplayName("같은 항목을 지우는 독립 규칙 둘이 서로를 실패시키지 않는다")
    void removingTheSameItemTwiceIsIdempotent() {
        QueryResolution original = withLocation(FACTORY, QueryResolution.Origin.EXPLICIT_QUERY, span(0, 4));

        ParseRulePolicy.Result result = policy.apply(original, List.of(removeFactory(10L), removeFactory(20L)));

        assertThat(result.outcomes())
                .extracting(ParseRuleOutcome::status)
                .containsExactly(ParseRuleOutcome.Status.APPLIED, ParseRuleOutcome.Status.APPLIED);
        assertThat(result.resolution().locations()).isEmpty();
    }

    // ── 원자성 ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("적용 결과가 유효하지 않으면 그 규칙 전체를 되돌린다")
    void rollsBackTheWholeRuleWhenTheResultIsInvalid() {
        QueryResolution original = withLocation(FACTORY, QueryResolution.Origin.EXPLICIT_QUERY, span(0, 4));

        // 연산 둘 중 앞은 성공하고 뒤가 유효하지 않은 날짜 구간을 만든다.
        ParseRule broken = rule(
                10L,
                List.of(hasValue(ResolutionAxis.LOCATIONS, FACTORY)),
                List.of(
                        operation(ParseRule.Patch.Op.REMOVE_ITEM, ResolutionAxis.LOCATIONS, "location", FACTORY),
                        new ParseRule.Patch.Operation(
                                ParseRule.Patch.Op.ADD_ITEM,
                                ResolutionAxis.DATE_WINDOWS,
                                new ParseRule.Patch.Target(
                                        "broadcast_date", null, LocalDate.of(2026, 2, 1), LocalDate.of(2026, 1, 1)),
                                null)));

        ParseRulePolicy.Result result = policy.apply(original, List.of(broken));

        assertThat(result.outcomes())
                .singleElement()
                .extracting(ParseRuleOutcome::status)
                .isEqualTo(ParseRuleOutcome.Status.FAILED);
        // 앞 연산까지 적용된 반쪽 상태가 남지 않는다.
        assertThat(result.resolution()).isEqualTo(original);
        assertThat(result.degradedReasons()).hasSize(1);
    }

    @Test
    @DisplayName("제거 대상이 원본에 없으면 조용히 넘기지 않고 실패로 남긴다")
    void failsWhenRemoveTargetIsMissing() {
        QueryResolution original = withLocation(FACTORY, QueryResolution.Origin.EXPLICIT_QUERY, span(0, 4));

        ParseRule rule = rule(
                10L,
                List.of(hasValue(ResolutionAxis.LOCATIONS, FACTORY)),
                List.of(operation(ParseRule.Patch.Op.REMOVE_ITEM, ResolutionAxis.LOCATIONS, "facility", FACTORY)));

        ParseRulePolicy.Result result = policy.apply(original, List.of(rule));

        assertThat(result.outcomes().getFirst().status()).isEqualTo(ParseRuleOutcome.Status.FAILED);
        assertThat(result.outcomes().getFirst().reason()).contains("remove_item 대상 없음");
    }

    // ── 출처 보호 (F-05) ────────────────────────────────────────────────

    @Test
    @DisplayName("명시적 사용자 필터는 규칙이 제거할 수 없다")
    void neverRemovesAnExplicitUserFilter() {
        QueryResolution original = withLocation(FACTORY, QueryResolution.Origin.EXPLICIT_FILTER, null);

        ParseRulePolicy.Result result = policy.apply(original, List.of(removeFactory(10L)));

        assertThat(result.outcomes().getFirst().status()).isEqualTo(ParseRuleOutcome.Status.FAILED);
        assertThat(result.outcomes().getFirst().reason()).contains("명시적 사용자 필터");
        assertThat(result.resolution().locations()).hasSize(1);
    }

    @Test
    @DisplayName("명시적 사용자 필터의 값은 다른 축으로 재사용할 수 없다")
    void neverReusesAnExplicitUserFilterValue() {
        QueryResolution original = withLocation(FACTORY, QueryResolution.Origin.EXPLICIT_FILTER, null);

        ParseRule rule = rule(
                10L,
                List.of(hasValue(ResolutionAxis.LOCATIONS, FACTORY)),
                List.of(new ParseRule.Patch.Operation(
                        ParseRule.Patch.Op.ADD_ITEM,
                        ResolutionAxis.INCIDENT_NAMES,
                        new ParseRule.Patch.Target(null, null, null, null),
                        new ParseRule.Patch.ValueRef(ResolutionAxis.LOCATIONS, "location", FACTORY))));

        ParseRulePolicy.Result result = policy.apply(original, List.of(rule));

        assertThat(result.outcomes().getFirst().status()).isEqualTo(ParseRuleOutcome.Status.FAILED);
        assertThat(result.resolution().incidentNames()).isEmpty();
    }

    @Test
    @DisplayName("원본 값을 옮기면 출처와 원문 구간을 승계한다")
    void inheritsOriginAndSpanWhenReusingAnOriginalValue() {
        QueryResolution.QuerySpan original_span = span(3, 7);
        QueryResolution original = withLocation(FACTORY, QueryResolution.Origin.EXPLICIT_QUERY, original_span);

        ParseRulePolicy.Result result = policy.apply(original, List.of(moveFactoryToIncident(10L)));

        QueryResolution.IncidentName moved = result.resolution().incidentNames().getFirst();
        assertThat(moved.origin()).isEqualTo(QueryResolution.Origin.EXPLICIT_QUERY);
        assertThat(moved.querySpan()).isEqualTo(original_span);
    }

    @Test
    @DisplayName("리터럴로 새로 적은 값은 inferred 로 강제된다")
    void forcesInferredForLiteralValues() {
        QueryResolution original = withLocation(FACTORY, QueryResolution.Origin.EXPLICIT_QUERY, span(0, 4));

        ParseRule rule = rule(
                10L,
                List.of(hasValue(ResolutionAxis.LOCATIONS, FACTORY)),
                List.of(operation(ParseRule.Patch.Op.ADD_ITEM, ResolutionAxis.INCIDENT_NAMES, null, "공장 화재")));

        ParseRulePolicy.Result result = policy.apply(original, List.of(rule));

        QueryResolution.IncidentName added = result.resolution().incidentNames().getFirst();
        assertThat(added.origin()).isEqualTo(QueryResolution.Origin.INFERRED);
        assertThat(added.querySpan()).isNull();
    }

    // ── 비호환 (F-14) ───────────────────────────────────────────────────

    @Test
    @DisplayName("문법 버전이 다르면 그 실행에서 건너뛴다")
    void skipsRulesWrittenInAnotherSyntaxVersion() {
        QueryResolution original = withLocation(FACTORY, QueryResolution.Origin.EXPLICIT_QUERY, span(0, 4));
        ParseRule old = new ParseRule(
                10L,
                "parse-rule/v0",
                SCHEMA,
                new ParseRule.Condition(List.of(hasValue(ResolutionAxis.LOCATIONS, FACTORY))),
                new ParseRule.Patch(List.of(
                        operation(ParseRule.Patch.Op.REMOVE_ITEM, ResolutionAxis.LOCATIONS, "location", FACTORY))),
                "{}",
                null);

        ParseRulePolicy.Result result = policy.apply(original, List.of(old));

        assertThat(result.outcomes().getFirst().status()).isEqualTo(ParseRuleOutcome.Status.SKIPPED_INCOMPATIBLE);
        assertThat(result.resolution()).isEqualTo(original);
    }

    @Test
    @DisplayName("다른 해석 계약을 겨냥한 규칙은 건너뛴다")
    void skipsRulesTargetingAnotherResolutionContract() {
        QueryResolution original = withLocation(FACTORY, QueryResolution.Origin.EXPLICIT_QUERY, span(0, 4));
        ParseRule old = new ParseRule(
                10L,
                ParseRule.SYNTAX_VERSION,
                "query-resolver/v1",
                new ParseRule.Condition(List.of(hasValue(ResolutionAxis.LOCATIONS, FACTORY))),
                new ParseRule.Patch(List.of(
                        operation(ParseRule.Patch.Op.REMOVE_ITEM, ResolutionAxis.LOCATIONS, "location", FACTORY))),
                "{}",
                null);

        ParseRulePolicy.Result result = policy.apply(original, List.of(old));

        assertThat(result.outcomes().getFirst().status()).isEqualTo(ParseRuleOutcome.Status.SKIPPED_INCOMPATIBLE);
    }

    @Test
    @DisplayName("읽지 못한 규칙은 목록에서 사라지지 않고 비호환으로 남는다")
    void reportsUnparsedRules() {
        QueryResolution original = withLocation(FACTORY, QueryResolution.Origin.EXPLICIT_QUERY, span(0, 4));

        ParseRulePolicy.Result result =
                policy.apply(original, List.of(ParseRule.unparsed(10L, "{\"broken\":true}", "모르는 axis: place")));

        assertThat(result.outcomes()).singleElement().satisfies(outcome -> {
            assertThat(outcome.status()).isEqualTo(ParseRuleOutcome.Status.SKIPPED_INCOMPATIBLE);
            assertThat(outcome.reason()).contains("모르는 axis");
            assertThat(outcome.bodySnapshot()).contains("broken");
        });
    }

    @Test
    @DisplayName("조건 없는 규칙은 모든 검색에 걸리므로 비호환으로 본다")
    void rejectsRulesWithoutAnyCondition() {
        QueryResolution original = withLocation(FACTORY, QueryResolution.Origin.EXPLICIT_QUERY, span(0, 4));
        ParseRule unconditional = rule(
                10L,
                List.of(),
                List.of(operation(ParseRule.Patch.Op.REMOVE_ITEM, ResolutionAxis.LOCATIONS, "location", FACTORY)));

        ParseRulePolicy.Result result = policy.apply(original, List.of(unconditional));

        assertThat(result.outcomes().getFirst().status()).isEqualTo(ParseRuleOutcome.Status.SKIPPED_INCOMPATIBLE);
        assertThat(result.outcomes().getFirst().reason()).isEqualTo("조건이 비어 있다");
    }

    @Test
    @DisplayName("규칙이 없으면 원본 해석을 그대로 쓴다")
    void keepsTheOriginalWhenThereAreNoRules() {
        QueryResolution original = withLocation(FACTORY, QueryResolution.Origin.EXPLICIT_QUERY, span(0, 4));

        assertThat(policy.apply(original, List.of()).resolution()).isEqualTo(original);
        assertThat(policy.apply(original, null).outcomes()).isEmpty();
    }

    // ── 시나리오 조립 도구 ───────────────────────────────────────────────

    private static QueryResolution.QuerySpan span(int start, int end) {
        return new QueryResolution.QuerySpan(start, end);
    }

    private static QueryResolution withLocation(
            String value, QueryResolution.Origin origin, QueryResolution.QuerySpan querySpan) {
        return new QueryResolution(
                SCHEMA,
                QueryResolution.Intent.SCENE_SEARCH,
                List.of(),
                List.of(),
                List.of(),
                List.of(new QueryResolution.Location(
                        QueryResolution.LocationType.LOCATION, value, origin, querySpan, 0.9)),
                List.of(),
                List.of(),
                0.8);
    }

    private static ParseRule.Condition.Predicate hasValue(ResolutionAxis axis, String value) {
        return new ParseRule.Condition.Predicate(axis, ParseRule.Condition.Op.HAS_VALUE, null, value);
    }

    private static ParseRule.Patch.Operation operation(
            ParseRule.Patch.Op op, ResolutionAxis axis, String type, String value) {
        return new ParseRule.Patch.Operation(op, axis, new ParseRule.Patch.Target(type, value, null, null), null);
    }

    private static ParseRule rule(
            long ruleId, List<ParseRule.Condition.Predicate> predicates, List<ParseRule.Patch.Operation> operations) {
        return new ParseRule(
                ruleId,
                ParseRule.SYNTAX_VERSION,
                SCHEMA,
                new ParseRule.Condition(predicates),
                new ParseRule.Patch(operations),
                "{\"rule\":%d}".formatted(ruleId),
                null);
    }

    /** FRD F-11 의 예시 규칙. 장소 항목을 제거하고 <b>원본 항목의 값</b>을 사건명으로 옮긴다. */
    private static ParseRule moveFactoryToIncident(long ruleId) {
        return rule(
                ruleId,
                List.of(
                        hasValue(ResolutionAxis.LOCATIONS, FACTORY),
                        new ParseRule.Condition.Predicate(
                                ResolutionAxis.INCIDENT_NAMES, ParseRule.Condition.Op.IS_EMPTY, null, null)),
                List.of(
                        operation(ParseRule.Patch.Op.REMOVE_ITEM, ResolutionAxis.LOCATIONS, "location", FACTORY),
                        new ParseRule.Patch.Operation(
                                ParseRule.Patch.Op.ADD_ITEM,
                                ResolutionAxis.INCIDENT_NAMES,
                                new ParseRule.Patch.Target(null, null, null, null),
                                new ParseRule.Patch.ValueRef(ResolutionAxis.LOCATIONS, "location", FACTORY))));
    }

    private static ParseRule removeFactory(long ruleId) {
        return rule(
                ruleId,
                List.of(hasValue(ResolutionAxis.LOCATIONS, FACTORY)),
                List.of(operation(ParseRule.Patch.Op.REMOVE_ITEM, ResolutionAxis.LOCATIONS, "location", FACTORY)));
    }

    private static ParseRule setIntent(long ruleId, String intent) {
        return rule(
                ruleId,
                List.of(hasValue(ResolutionAxis.LOCATIONS, FACTORY)),
                List.of(operation(ParseRule.Patch.Op.SET, ResolutionAxis.INTENT, null, intent)));
    }
}
