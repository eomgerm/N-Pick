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

    // ── 규칙 하나가 검색 전체를 죽이지 못한다 ──────────────────────────────

    @Test
    @DisplayName("모르는 유형은 검색을 죽이지 않고 비호환으로 기록된다")
    void unknownTypeIsRecordedNotThrown() {
        QueryResolution original = withLocation(FACTORY, QueryResolution.Origin.EXPLICIT_QUERY, span(0, 4));
        // locations 의 유형은 location·facility 뿐이다. factory 가 통과하면 적용 단계의 valueOf 가 던져
        // 규칙 하나 때문에 검색 요청 전체가 실패한다.
        ParseRule badType = rule(
                10L,
                List.of(hasValue(ResolutionAxis.LOCATIONS, FACTORY)),
                List.of(operation(ParseRule.Patch.Op.ADD_ITEM, ResolutionAxis.LOCATIONS, "factory", "△△공장")));

        ParseRulePolicy.Result result = policy.apply(original, List.of(badType));

        assertThat(result.outcomes().getFirst().status()).isEqualTo(ParseRuleOutcome.Status.SKIPPED_INCOMPATIBLE);
        assertThat(result.outcomes().getFirst().reason()).contains("없는 유형 factory");
        assertThat(result.resolution()).isEqualTo(original);
    }

    @Test
    @DisplayName("리졸버 원본에 이미 있던 흠은 무관한 규칙을 실패시키지 않는다")
    void preExistingResolverFlawsDoNotFailUnrelatedRules() {
        // validator.py 는 expanded_terms 를 중복 제거하지 않는다. 해석 전체를 검사하면 이 중복 하나가
        // locations 만 고치는 규칙까지 failed 로 만들어 승인된 교정이 전부 사라진다.
        QueryResolution original = new QueryResolution(
                SCHEMA,
                QueryResolution.Intent.SCENE_SEARCH,
                List.of(),
                List.of(),
                List.of(),
                List.of(new QueryResolution.Location(
                        QueryResolution.LocationType.LOCATION,
                        FACTORY,
                        QueryResolution.Origin.EXPLICIT_QUERY,
                        span(0, 4),
                        0.9)),
                List.of(),
                List.of("화재", "화재"),
                0.8);

        ParseRulePolicy.Result result = policy.apply(original, List.of(removeFactory(10L)));

        assertThat(result.outcomes().getFirst().status()).isEqualTo(ParseRuleOutcome.Status.APPLIED);
        assertThat(result.resolution().locations()).isEmpty();
        assertThat(result.degradedReasons()).isEmpty();
    }

    @Test
    @DisplayName("모르는 intent 값은 조건 불일치가 아니라 비호환이다")
    void unknownIntentLiteralIsIncompatibleNotAMissedCondition() {
        QueryResolution original = withLocation(FACTORY, QueryResolution.Origin.EXPLICIT_QUERY, span(0, 4));
        ParseRule typo = rule(
                10L,
                List.of(new ParseRule.Condition.Predicate(
                        ResolutionAxis.INTENT, ParseRule.Condition.Op.EQUALS, null, "scene")),
                List.of(operation(ParseRule.Patch.Op.REMOVE_ITEM, ResolutionAxis.LOCATIONS, "location", FACTORY)));

        ParseRulePolicy.Result result = policy.apply(original, List.of(typo));

        // 조건 불일치로 기록하면 degraded 에도 안 남아 검수자는 자기 규칙이 절대 안 걸린다는 것을 모른다 (§11).
        assertThat(result.outcomes().getFirst().status()).isEqualTo(ParseRuleOutcome.Status.SKIPPED_INCOMPATIBLE);
        assertThat(result.outcomes().getFirst().reason()).contains("모르는 intent 값 scene");
        assertThat(result.degradedReasons()).hasSize(1);
    }

    @Test
    @DisplayName("출처를 저장하지 않는 축에서는 출처가 달라도 충돌이 아니다")
    void originDoesNotCauseConflictOnAxesThatDiscardIt() {
        QueryResolution original = new QueryResolution(
                SCHEMA,
                QueryResolution.Intent.SCENE_SEARCH,
                List.of(),
                List.of(new QueryResolution.IncidentName("화재", QueryResolution.Origin.EXPLICIT_QUERY, span(0, 2), 0.9)),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                0.8);

        ParseRule.Condition.Predicate incidentPresent = new ParseRule.Condition.Predicate(
                ResolutionAxis.INCIDENT_NAMES, ParseRule.Condition.Op.IS_NOT_EMPTY, null, null);
        // 같은 단어를 리터럴로 넣는 규칙과 원본 값으로 넣는 규칙. expanded_terms 는 출처를 버리므로 결과가 같다.
        ParseRule literal = rule(
                10L,
                List.of(incidentPresent),
                List.of(operation(ParseRule.Patch.Op.ADD_ITEM, ResolutionAxis.EXPANDED_TERMS, null, "화재")));
        ParseRule reused = rule(
                20L,
                List.of(incidentPresent),
                List.of(new ParseRule.Patch.Operation(
                        ParseRule.Patch.Op.ADD_ITEM,
                        ResolutionAxis.EXPANDED_TERMS,
                        new ParseRule.Patch.Target(null, null, null, null),
                        new ParseRule.Patch.ValueRef(ResolutionAxis.INCIDENT_NAMES, null, "화재"))));

        ParseRulePolicy.Result result = policy.apply(original, List.of(literal, reused));

        assertThat(result.outcomes())
                .extracting(ParseRuleOutcome::status)
                .containsExactly(ParseRuleOutcome.Status.APPLIED, ParseRuleOutcome.Status.APPLIED);
        assertThat(result.resolution().expandedTerms()).containsExactly("화재");
    }

    // ── 닫힌 어휘 나머지 ─────────────────────────────────────────────────

    @Test
    @DisplayName("unset 은 검색 의도를 unknown 으로 돌린다")
    void unsetResetsTheIntent() {
        QueryResolution original = withLocation(FACTORY, QueryResolution.Origin.EXPLICIT_QUERY, span(0, 4));
        ParseRule reset = rule(
                10L,
                List.of(hasValue(ResolutionAxis.LOCATIONS, FACTORY)),
                List.of(operation(ParseRule.Patch.Op.UNSET, ResolutionAxis.INTENT, null, null)));

        ParseRulePolicy.Result result = policy.apply(original, List.of(reset));

        assertThat(result.outcomes().getFirst().status()).isEqualTo(ParseRuleOutcome.Status.APPLIED);
        assertThat(result.resolution().intent()).isEqualTo(QueryResolution.Intent.UNKNOWN);
    }

    @Test
    @DisplayName("has_type 은 저장된 유형에만 맞는다")
    void hasTypeMatchesOnlyTheStoredType() {
        QueryResolution original = withLocation(FACTORY, QueryResolution.Origin.EXPLICIT_QUERY, span(0, 4));

        assertThat(statusOf(original, typeRule(10L, "location"))).isEqualTo(ParseRuleOutcome.Status.APPLIED);
        assertThat(statusOf(original, typeRule(20L, "facility")))
                .isEqualTo(ParseRuleOutcome.Status.SKIPPED_CONDITION_UNMET);
    }

    @Test
    @DisplayName("날짜 조건을 추가하고 제거한다")
    void addsAndRemovesADateWindow() {
        QueryResolution original = withLocation(FACTORY, QueryResolution.Origin.EXPLICIT_QUERY, span(0, 4));
        ParseRule.Patch.Target window =
                new ParseRule.Patch.Target("broadcast_date", null, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 2, 1));

        ParseRulePolicy.Result added = policy.apply(
                original,
                List.of(rule(
                        10L,
                        List.of(hasValue(ResolutionAxis.LOCATIONS, FACTORY)),
                        List.of(new ParseRule.Patch.Operation(
                                ParseRule.Patch.Op.ADD_ITEM, ResolutionAxis.DATE_WINDOWS, window, null)))));

        assertThat(added.outcomes().getFirst().status()).isEqualTo(ParseRuleOutcome.Status.APPLIED);
        assertThat(added.resolution().dateWindows()).singleElement().satisfies(w -> {
            assertThat(w.field()).isEqualTo(QueryResolution.DateField.BROADCAST_DATE);
            assertThat(w.origin()).isEqualTo(QueryResolution.Origin.INFERRED);
        });

        ParseRulePolicy.Result removed = policy.apply(
                added.resolution(),
                List.of(rule(
                        10L,
                        List.of(new ParseRule.Condition.Predicate(
                                ResolutionAxis.DATE_WINDOWS, ParseRule.Condition.Op.IS_NOT_EMPTY, null, null)),
                        List.of(new ParseRule.Patch.Operation(
                                ParseRule.Patch.Op.REMOVE_ITEM, ResolutionAxis.DATE_WINDOWS, window, null)))));

        assertThat(removed.outcomes().getFirst().status()).isEqualTo(ParseRuleOutcome.Status.APPLIED);
        assertThat(removed.resolution().dateWindows()).isEmpty();
    }

    @Test
    @DisplayName("인물 항목을 사건명으로 옮긴다")
    void movesAnEntityToTheIncidentName() {
        // F-11 예시의 변형. entities 축은 유형이 필수라 유형 대조까지 함께 지난다.
        QueryResolution original = new QueryResolution(
                SCHEMA,
                QueryResolution.Intent.SCENE_SEARCH,
                List.of(),
                List.of(),
                List.of(new QueryResolution.Entity(
                        QueryResolution.EntityType.ORGANIZATION,
                        "○○청 화재",
                        QueryResolution.Origin.EXPLICIT_QUERY,
                        span(0, 6),
                        0.9)),
                List.of(),
                List.of(),
                List.of(),
                0.8);

        ParseRule move = rule(
                10L,
                List.of(new ParseRule.Condition.Predicate(
                        ResolutionAxis.ENTITIES, ParseRule.Condition.Op.HAS_TYPE, "organization", null)),
                List.of(
                        operation(ParseRule.Patch.Op.REMOVE_ITEM, ResolutionAxis.ENTITIES, "organization", "○○청 화재"),
                        new ParseRule.Patch.Operation(
                                ParseRule.Patch.Op.ADD_ITEM,
                                ResolutionAxis.INCIDENT_NAMES,
                                new ParseRule.Patch.Target(null, null, null, null),
                                new ParseRule.Patch.ValueRef(ResolutionAxis.ENTITIES, "organization", "○○청 화재"))));

        ParseRulePolicy.Result result = policy.apply(original, List.of(move));

        assertThat(result.outcomes().getFirst().status()).isEqualTo(ParseRuleOutcome.Status.APPLIED);
        assertThat(result.resolution().entities()).isEmpty();
        assertThat(result.resolution().incidentNames()).singleElement().satisfies(name -> {
            assertThat(name.value()).isEqualTo("○○청 화재");
            // 원본 항목의 값을 옮긴 것이므로 출처와 원문 구간을 승계한다 (F-05).
            assertThat(name.origin()).isEqualTo(QueryResolution.Origin.EXPLICIT_QUERY);
            assertThat(name.querySpan()).isEqualTo(span(0, 6));
        });
    }

    // ── 성립할 수 없는 규칙은 조건 불일치로 위장되지 않는다 ─────────────────

    @Test
    @DisplayName("영원히 맞을 수 없는 조건·연산 조합은 비호환이다")
    void impossibleShapesAreIncompatible() {
        QueryResolution original = withLocation(FACTORY, QueryResolution.Origin.EXPLICIT_QUERY, span(0, 4));
        ParseRule.Patch.Operation harmless =
                operation(ParseRule.Patch.Op.REMOVE_ITEM, ResolutionAxis.LOCATIONS, "location", FACTORY);

        // 유형이 없는 축에 has_type
        assertThat(reasonOf(
                        original,
                        rule(
                                10L,
                                List.of(new ParseRule.Condition.Predicate(
                                        ResolutionAxis.INCIDENT_NAMES,
                                        ParseRule.Condition.Op.HAS_TYPE,
                                        "person",
                                        null)),
                                List.of(harmless))))
                .contains("유형이 없어");
        // 값이 없는 축에 has_value
        assertThat(reasonOf(
                        original,
                        rule(20L, List.of(hasValue(ResolutionAxis.DATE_WINDOWS, "2026-01-01")), List.of(harmless))))
                .contains("값이 없어");
        // 리터럴과 원본 값 참조를 함께 적으면 리터럴이 조용히 버려진다
        assertThat(reasonOf(
                        original,
                        rule(
                                30L,
                                List.of(hasValue(ResolutionAxis.LOCATIONS, FACTORY)),
                                List.of(new ParseRule.Patch.Operation(
                                        ParseRule.Patch.Op.ADD_ITEM,
                                        ResolutionAxis.INCIDENT_NAMES,
                                        new ParseRule.Patch.Target(null, "직접 적은 값", null, null),
                                        new ParseRule.Patch.ValueRef(ResolutionAxis.LOCATIONS, "location", FACTORY))))))
                .contains("함께 쓸 수 없다");
        // date_windows 대상에 값을 적으면 원본과 절대 매칭되지 않는다
        assertThat(reasonOf(
                        original,
                        rule(
                                40L,
                                List.of(hasValue(ResolutionAxis.LOCATIONS, FACTORY)),
                                List.of(new ParseRule.Patch.Operation(
                                        ParseRule.Patch.Op.REMOVE_ITEM,
                                        ResolutionAxis.DATE_WINDOWS,
                                        new ParseRule.Patch.Target(
                                                "broadcast_date",
                                                "아무값",
                                                LocalDate.of(2026, 1, 1),
                                                LocalDate.of(2026, 2, 1)),
                                        null)))))
                .contains("값을 쓰지 않는다");
    }

    // ── MR 리뷰 반영 (!55) ───────────────────────────────────────────────

    @Test
    @DisplayName("연산이 쓰지 않는 피연산자는 조용히 무시되지 않는다")
    void unusedOperandsAreRejected() {
        QueryResolution original = withLocation(FACTORY, QueryResolution.Origin.EXPLICIT_QUERY, span(0, 4));
        ParseRule.Patch.Operation harmless =
                operation(ParseRule.Patch.Op.SET, ResolutionAxis.INTENT, null, "recent_scene");

        // has_value 는 type 을 보지 않는다. 무시하면 person:X 규칙이 organization:X 에도 걸린다.
        assertThat(reasonOf(
                        original,
                        rule(
                                10L,
                                List.of(new ParseRule.Condition.Predicate(
                                        ResolutionAxis.ENTITIES, ParseRule.Condition.Op.HAS_VALUE, "person", "김철수")),
                                List.of(harmless))))
                .isEqualTo("has_value 는 type 을 쓰지 않는다");
        // has_type 은 value 를 보지 않는다
        assertThat(reasonOf(
                        original,
                        rule(
                                20L,
                                List.of(new ParseRule.Condition.Predicate(
                                        ResolutionAxis.LOCATIONS, ParseRule.Condition.Op.HAS_TYPE, "location", "○○공장")),
                                List.of(harmless))))
                .isEqualTo("has_type 는 value 를 쓰지 않는다");
        // is_empty 는 둘 다 보지 않는다
        assertThat(reasonOf(
                        original,
                        rule(
                                30L,
                                List.of(new ParseRule.Condition.Predicate(
                                        ResolutionAxis.INCIDENT_NAMES, ParseRule.Condition.Op.IS_EMPTY, null, "아무값")),
                                List.of(harmless))))
                .isEqualTo("is_empty 는 value 를 쓰지 않는다");
    }

    @Test
    @DisplayName("같은 값을 다른 confidence 로 넣는 두 규칙은 충돌이다")
    void differentConfidenceOnTheSameValueIsAConflict() {
        // confidence 가 효과에서 빠지면 충돌로 잡히지 않고 먼저 온 규칙이 이긴다 — 규칙 ID 순서가
        // 최종 confidence 를 바꾼다. F-11 은 임의의 규칙이 이기는 방식을 금지한다.
        QueryResolution original = new QueryResolution(
                SCHEMA,
                QueryResolution.Intent.SCENE_SEARCH,
                List.of(),
                List.of(),
                List.of(),
                List.of(new QueryResolution.Location(
                        QueryResolution.LocationType.LOCATION,
                        FACTORY,
                        QueryResolution.Origin.EXPLICIT_QUERY,
                        span(0, 4),
                        0.9)),
                List.of(),
                List.of(),
                0.8);

        // 리터럴 추가는 confidence 0.0, value_from 은 원본의 0.9 를 승계한다.
        ParseRule literal = rule(
                10L,
                List.of(hasValue(ResolutionAxis.LOCATIONS, FACTORY)),
                List.of(operation(ParseRule.Patch.Op.ADD_ITEM, ResolutionAxis.INCIDENT_NAMES, null, FACTORY)));
        ParseRule reused = rule(
                20L,
                List.of(hasValue(ResolutionAxis.LOCATIONS, FACTORY)),
                List.of(new ParseRule.Patch.Operation(
                        ParseRule.Patch.Op.ADD_ITEM,
                        ResolutionAxis.INCIDENT_NAMES,
                        new ParseRule.Patch.Target(null, null, null, null),
                        new ParseRule.Patch.ValueRef(ResolutionAxis.LOCATIONS, "location", FACTORY))));

        ParseRulePolicy.Result result = policy.apply(original, List.of(literal, reused));

        assertThat(result.outcomes())
                .extracting(ParseRuleOutcome::status)
                .containsExactly(ParseRuleOutcome.Status.SKIPPED_CONFLICT, ParseRuleOutcome.Status.SKIPPED_CONFLICT);
        assertThat(result.resolution().incidentNames()).isEmpty();
    }

    @Test
    @DisplayName("규칙 ID 순서를 바꿔도 결과가 같다")
    void theResultDoesNotDependOnRuleIdOrder() {
        QueryResolution original = withLocation(FACTORY, QueryResolution.Origin.EXPLICIT_QUERY, span(0, 4));
        ParseRule move = moveFactoryToIncident(10L);
        ParseRule intent = setIntent(20L, "recent_scene");

        // 목록 순서를 뒤집어도 판정 전에 규칙 ID 로 정렬하므로 같은 해석이 나온다.
        assertThat(policy.apply(original, List.of(move, intent)).resolution())
                .isEqualTo(policy.apply(original, List.of(intent, move)).resolution());
    }

    @Test
    @DisplayName("set 의 intent 값이 틀리면 조건과 무관하게 비호환이다")
    void unknownIntentInSetIsAlwaysIncompatible() {
        QueryResolution original = withLocation(FACTORY, QueryResolution.Origin.EXPLICIT_QUERY, span(0, 4));
        ParseRule.Patch.Operation typo = operation(ParseRule.Patch.Op.SET, ResolutionAxis.INTENT, null, "typo");

        // 조건이 맞든 안 맞든 같은 결과여야 한다. 규칙 본문이 잘못된 것은 그 실행의 사정과 무관하다.
        ParseRule matching = rule(10L, List.of(hasValue(ResolutionAxis.LOCATIONS, FACTORY)), List.of(typo));
        ParseRule notMatching = rule(20L, List.of(hasValue(ResolutionAxis.LOCATIONS, "△△공장")), List.of(typo));

        for (ParseRule candidate : List.of(matching, notMatching)) {
            assertThat(reasonOf(original, candidate)).isEqualTo("모르는 intent 값 typo");
        }
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

    private ParseRuleOutcome.Status statusOf(QueryResolution original, ParseRule rule) {
        return policy.apply(original, List.of(rule)).outcomes().getFirst().status();
    }

    private String reasonOf(QueryResolution original, ParseRule rule) {
        ParseRuleOutcome outcome =
                policy.apply(original, List.of(rule)).outcomes().getFirst();
        assertThat(outcome.status()).isEqualTo(ParseRuleOutcome.Status.SKIPPED_INCOMPATIBLE);
        return outcome.reason();
    }

    /** locations 유형으로 판정하는 규칙. 패치는 검색 의도만 건드려 조건 판정만 드러나게 한다. */
    private static ParseRule typeRule(long ruleId, String type) {
        return rule(
                ruleId,
                List.of(new ParseRule.Condition.Predicate(
                        ResolutionAxis.LOCATIONS, ParseRule.Condition.Op.HAS_TYPE, type, null)),
                List.of(operation(ParseRule.Patch.Op.SET, ResolutionAxis.INTENT, null, "recent_scene")));
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
