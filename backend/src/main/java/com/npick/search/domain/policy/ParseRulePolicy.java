package com.npick.search.domain.policy;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import com.npick.search.domain.model.ParseRule;
import com.npick.search.domain.model.ParseRuleOutcome;
import com.npick.search.domain.model.QueryResolution;
import com.npick.search.domain.model.ResolutionAxis;

/**
 * 승인된 해석 교정 규칙을 AI 원본 해석에 적용한다 (FRD v3.2 F-05 4항, F-11).
 *
 * <p>질의 지문이 같은지로 판정하지 않는다. <b>AI 원본 해석의 조건이 맞는지</b>로 판정하므로 표현이 달라도 같은 패턴이면 교정되고, 문장이 비슷해도 조건이 다르면 적용되지 않는다. 유사도 매칭은 만들지
 * 않는다 (§1.2).
 *
 * <h2>한 번만 판정한다</h2>
 *
 * 조건 판정과 연산 대상 해석이 <b>전부 원본 해석 하나</b>를 기준으로 끝난다. 앞 규칙의 결과로 다시 조건을 찾거나 LLM 을 재호출하지 않는다 (F-11). 부수 효과로 충돌 판정이 싸진다 — 각 연산이
 * 무엇을 건드릴지 적용 전에 이미 확정되기 때문이다.
 *
 * <h2>충돌은 쓰기 키로 본다</h2>
 *
 * 규칙마다 「무엇을 어떻게 바꾸는가」를 (쓰기 키, 효과) 쌍으로 뽑고, <b>같은 키를 서로 다른 효과로</b> 건드리는 규칙들을 한 충돌 그룹으로 묶어 그룹 전체를 건너뛴다. 패치를 순서 바꿔 돌려보는
 * 방식(비가환성 실측)은 정확하지만 규칙 수의 제곱만큼 적용을 반복해야 해서 쓰지 않는다. 키가 다르면 독립이므로 함께 적용한다 — F-11 「독립 규칙은 함께 적용한다」이고, 임의의 마지막 규칙이 이기는 방식은
 * 쓰지 않는다.
 *
 * <h2>롤백은 공짜다</h2>
 *
 * {@link QueryResolution} 이 불변이라 규칙별로 복사본에 적용한 뒤 유효할 때만 채택한다. 실패하면 복사본을 버리는 것으로 끝나므로 <b>한 규칙의 일부만 적용된 상태가 물리적으로 생기지
 * 않는다</b> (F-11).
 *
 * <p>설계 정본 §7 의 Policy 다 — 상태를 갖지 않고 Spring 을 모른다. 호출부(검색 오케스트레이션, {@code S15P21A501-59})가 필드로 직접 생성해 보유한다.
 */
public final class ParseRulePolicy {

    /**
     * 적용 결과.
     *
     * @param resolution 검색이 실제로 쓸 최종 해석. {@code search_execution.parsed_query_json} 에 들어간다. 적용된 규칙이 없으면 원본과 같다
     * @param outcomes 규칙별 결과. 판정 순서({@code search_rule_id} 오름차순)대로다. {@code applied_rules_json} 에 들어간다
     */
    public record Result(QueryResolution resolution, List<ParseRuleOutcome> outcomes) {

        public Result {
            outcomes = outcomes == null ? List.of() : List.copyOf(outcomes);
        }

        /** 기능 저하로 기록할 사유. 조건 불일치는 정상이라 여기 들어가지 않는다 (§6.2, baseline {@code degraded_reasons_json} 주석). */
        public List<String> degradedReasons() {
            return outcomes.stream()
                    .filter(o -> o.status().degraded())
                    .map(o -> o.status().jsonName() + ":" + o.ruleId())
                    .toList();
        }
    }

    /**
     * 규칙을 판정해 적용한다.
     *
     * @param original 교정 전 AI 원본 해석. {@code search_execution.resolver_output_json} 에 그대로 보존되는 값이고 <b>이 메서드는 이것을 바꾸지
     *     않는다</b> (§7.2)
     * @param activeRules 활성 {@code patch_parse} 규칙. 조회 자체가 실패했다면 이 메서드를 부르지 말고 검색을 실패시킨다 (§6.2) — 빈 목록으로 바꿔 넘기면 사람의 결정을
     *     조용히 건너뛰는 것이 된다
     */
    public Result apply(QueryResolution original, List<ParseRule> activeRules) {
        if (activeRules == null || activeRules.isEmpty()) {
            return new Result(original, List.of());
        }

        List<ParseRule> ordered = activeRules.stream()
                // 결정적 순서. 충돌을 걷어낸 뒤엔 순서가 결과를 바꾸지 않지만 적용 순서를 기록해야 하므로 재현 가능해야 한다 (§7.2).
                .sorted(Comparator.comparingLong(ParseRule::ruleId))
                .toList();

        Map<Long, ParseRuleOutcome> settled = new LinkedHashMap<>();
        List<Candidate> candidates = new ArrayList<>();

        for (ParseRule rule : ordered) {
            String incompatible = rule.incompatibleReason(original);
            if (incompatible != null) {
                settled.put(rule.ruleId(), ParseRuleOutcome.incompatible(rule, incompatible));
                continue;
            }
            String unmet = unmetReason(rule.condition(), original);
            if (unmet != null) {
                settled.put(rule.ruleId(), ParseRuleOutcome.conditionUnmet(rule, unmet));
                continue;
            }
            try {
                candidates.add(new Candidate(rule, resolveOperations(rule, original)));
            } catch (UnapplicableRule ex) {
                settled.put(rule.ruleId(), ParseRuleOutcome.failed(rule, ex.getMessage()));
            } catch (RuntimeException ex) {
                // 규칙 하나 때문에 검색 전체가 실패하면 안 된다. F-14 는 그 실행에서 건너뛰고 이유를 남기라고 한다.
                settled.put(rule.ruleId(), ParseRuleOutcome.failed(rule, unexpected(ex)));
            }
        }

        markConflicts(candidates, settled);

        QueryResolution working = original;
        int order = 0;
        for (Candidate candidate : candidates) {
            if (settled.containsKey(candidate.rule().ruleId())) {
                continue; // 충돌 그룹에서 걸러졌다
            }
            QueryResolution attempt;
            String invalid;
            try {
                attempt = applyAll(working, candidate.operations());
                invalid = validate(attempt, working, candidate.touchedAxes());
            } catch (RuntimeException ex) {
                settled.put(candidate.rule().ruleId(), ParseRuleOutcome.failed(candidate.rule(), unexpected(ex)));
                continue;
            }
            if (invalid != null) {
                // 복사본을 버린다. working 은 마지막 유효 상태 그대로다 (F-11).
                settled.put(candidate.rule().ruleId(), ParseRuleOutcome.failed(candidate.rule(), invalid));
                continue;
            }
            working = attempt;
            settled.put(candidate.rule().ruleId(), ParseRuleOutcome.applied(candidate.rule(), ++order));
        }

        List<ParseRuleOutcome> outcomes =
                ordered.stream().map(r -> settled.get(r.ruleId())).toList();
        return new Result(working, outcomes);
    }

    // ── 조건 판정 ────────────────────────────────────────────────────────

    /** 만족하지 않은 첫 조건의 사유. 전부 만족하면 {@code null}. */
    private String unmetReason(ParseRule.Condition condition, QueryResolution resolution) {
        for (ParseRule.Condition.Predicate predicate : condition.all()) {
            if (!satisfied(predicate, resolution)) {
                return "%s %s 불일치"
                        .formatted(predicate.axis().jsonName(), predicate.op().jsonName());
            }
        }
        return null;
    }

    private boolean satisfied(ParseRule.Condition.Predicate predicate, QueryResolution resolution) {
        if (predicate.axis() == ResolutionAxis.INTENT) {
            return ResolutionAxis.intentJson(resolution.intent()).equals(predicate.value());
        }
        List<ResolutionAxis.Item> items = predicate.axis().read(resolution);
        return switch (predicate.op()) {
            case HAS_VALUE -> items.stream().anyMatch(i -> predicate.value().equals(i.value()));
            case HAS_TYPE -> items.stream().anyMatch(i -> predicate.type().equals(i.type()));
            case IS_EMPTY -> items.isEmpty();
            case IS_NOT_EMPTY -> !items.isEmpty();
            case EQUALS -> false; // 목록 축에는 쓸 수 없다. incompatibleReason 이 먼저 걸러낸다
        };
    }

    // ── 연산 해석: 무엇을 어떻게 바꿀지 원본 기준으로 확정한다 ──────────────

    private record Candidate(ParseRule rule, List<ResolvedOperation> operations) {

        /** 이 규칙이 실제로 쓰는 축. 유효성 검사를 여기로 좁힌다. */
        Set<ResolutionAxis> touchedAxes() {
            return operations.stream().map(ResolvedOperation::axis).collect(Collectors.toUnmodifiableSet());
        }
    }

    /**
     * 적용 준비가 끝난 연산 하나.
     *
     * @param writeKey 이 연산이 건드리는 자리. 충돌 판정의 키다
     * @param effect 그 자리를 어떻게 바꾸는가. 같은 키에 효과가 다르면 충돌이고, 같으면 멱등이라 충돌이 아니다
     */
    private record ResolvedOperation(
            ParseRule.Patch.Op op,
            ResolutionAxis axis,
            ResolutionAxis.Item item,
            QueryResolution.Intent intent,
            String writeKey,
            String effect) {}

    /** 규칙을 적용할 수 없다. 조건 불일치가 아니라 규칙 쪽 문제이므로 {@code failed} 로 기록한다. */
    private static final class UnapplicableRule extends RuntimeException {
        UnapplicableRule(String message) {
            super(message);
        }
    }

    private List<ResolvedOperation> resolveOperations(ParseRule rule, QueryResolution original) {
        List<ResolvedOperation> resolved = new ArrayList<>();
        for (ParseRule.Patch.Operation operation : rule.patch().operations()) {
            resolved.add(resolve(operation, original));
        }
        // 한 규칙이 스스로 같은 자리를 다르게 바꾸면 그것은 충돌이 아니라 잘못 쓴 규칙이다.
        for (int i = 0; i < resolved.size(); i++) {
            for (int j = i + 1; j < resolved.size(); j++) {
                if (resolved.get(i).writeKey().equals(resolved.get(j).writeKey())
                        && !resolved.get(i).effect().equals(resolved.get(j).effect())) {
                    throw new UnapplicableRule(
                            "한 규칙 안에서 %s 를 다르게 바꾼다".formatted(resolved.get(i).writeKey()));
                }
            }
        }
        return List.copyOf(resolved);
    }

    private ResolvedOperation resolve(ParseRule.Patch.Operation operation, QueryResolution original) {
        ResolutionAxis axis = operation.axis();
        return switch (operation.op()) {
            case SET -> {
                // 어휘 대조는 ParseRule.incompatibleReason 이 이미 했다. 여기 도달하면 값은 유효하다.
                QueryResolution.Intent intent = ResolutionAxis.intentFrom(
                                operation.target().value())
                        .orElseThrow(() -> new UnapplicableRule(
                                "모르는 intent 값 %s".formatted(operation.target().value())));
                yield new ResolvedOperation(
                        ParseRule.Patch.Op.SET,
                        axis,
                        null,
                        intent,
                        axis.jsonName(),
                        "set:" + ResolutionAxis.intentJson(intent));
            }
            case UNSET ->
                new ResolvedOperation(
                        ParseRule.Patch.Op.UNSET,
                        axis,
                        null,
                        QueryResolution.Intent.UNKNOWN,
                        axis.jsonName(),
                        "set:" + ResolutionAxis.intentJson(QueryResolution.Intent.UNKNOWN));
            case ADD_ITEM -> {
                ResolutionAxis.Item item = added(operation, original);
                yield new ResolvedOperation(
                        ParseRule.Patch.Op.ADD_ITEM, axis, item, null, writeKey(axis, item), addEffect(axis, item));
            }
            case REMOVE_ITEM -> {
                ResolutionAxis.Item target = operation.target().asItem(QueryResolution.Origin.INFERRED, null);
                ResolutionAxis.Item existing = find(axis.read(original), target.identity())
                        // 조건이 존재를 확인했어야 하는데 안 했다는 뜻이다. 조용히 넘기면 규칙의 오작성이 드러나지 않는다 (§11).
                        .orElseThrow(
                                () -> new UnapplicableRule("remove_item 대상 없음: %s".formatted(writeKey(axis, target))));
                if (existing.origin() == QueryResolution.Origin.EXPLICIT_FILTER) {
                    throw new UnapplicableRule("명시적 사용자 필터는 규칙이 제거할 수 없다");
                }
                yield new ResolvedOperation(
                        ParseRule.Patch.Op.REMOVE_ITEM, axis, existing, null, writeKey(axis, existing), "remove");
            }
        };
    }

    /**
     * 추가 연산의 효과.
     *
     * <p><b>축이 저장하지 않는 것은 효과에 넣지 않는다.</b> {@code expanded_terms} 는 문자열만 저장하므로 출처가 달라도 결과가 같다. 출처를 효과에 넣으면 같은 단어를 리터럴로
     * 넣는 규칙과 원본 값으로 넣는 규칙이 충돌로 묶여 <b>둘 다 건너뛰어진다</b> — 결과가 같은데도. F-11 「독립 규칙은 함께 적용한다」 위반이다.
     */
    private static String addEffect(ResolutionAxis axis, ResolutionAxis.Item item) {
        return axis.carriesOrigin() ? "add:%s:%s".formatted(item.origin(), item.querySpan()) : "add";
    }

    /**
     * 추가할 항목. <b>F-05 보호를 코드로 지키는 지점이다.</b>
     *
     * <ul>
     *   <li>{@code value_from} 으로 원본 항목의 값을 옮기면 출처와 원문 구간을 <b>승계</b>한다. 같은 원문 구간을 가리키므로 {@code query[span] == value} 가
     *       그대로 성립한다
     *   <li>리터럴로 새로 적은 값은 {@code inferred} · 구간 없음으로 <b>강제</b>한다. 규칙이 값을 사용자 명시로 위장할 수 없다 — F-05 「원문에서 확인되지 않는
     *       인물·사건·날짜를 사용자의 명시 조건으로 표시하지 않는다」
     *   <li>{@code explicit_filter} 항목은 값 재사용 대상에서도 뺀다. 다른 축으로 옮기는 것도 사용자 필터를 규칙이 만든 조건으로 바꾸는 것이다
     * </ul>
     */
    private ResolutionAxis.Item added(ParseRule.Patch.Operation operation, QueryResolution original) {
        ParseRule.Patch.ValueRef ref = operation.valueFrom();
        if (ref == null) {
            return operation.target().asItem(QueryResolution.Origin.INFERRED, null);
        }
        ResolutionAxis.Item source = find(
                        ref.axis().read(original),
                        new ResolutionAxis.Item(
                                        ref.type(), ref.value(), null, null, QueryResolution.Origin.INFERRED, null, 0.0)
                                .identity())
                .orElseThrow(() -> new UnapplicableRule(
                        "value_from 참조 대상 없음: %s|%s".formatted(ref.axis().jsonName(), ref.value())));
        if (source.origin() == QueryResolution.Origin.EXPLICIT_FILTER) {
            throw new UnapplicableRule("명시적 사용자 필터의 값은 재사용할 수 없다");
        }
        return new ResolutionAxis.Item(
                operation.target().type(),
                source.value(),
                source.start(),
                source.endExclusive(),
                source.origin(),
                source.querySpan(),
                source.confidence());
    }

    private static String writeKey(ResolutionAxis axis, ResolutionAxis.Item item) {
        return axis.jsonName() + "|" + item.identity();
    }

    private static Optional<ResolutionAxis.Item> find(List<ResolutionAxis.Item> items, String identity) {
        return items.stream().filter(i -> i.identity().equals(identity)).findFirst();
    }

    // ── 충돌 그룹 ───────────────────────────────────────────────────────

    /** 같은 쓰기 키를 다른 효과로 건드리는 규칙들을 한 그룹으로 묶어 전부 건너뛴다. 나머지는 그대로 적용된다 (F-11). */
    private void markConflicts(List<Candidate> candidates, Map<Long, ParseRuleOutcome> settled) {
        int n = candidates.size();
        int[] parent = new int[n];
        for (int i = 0; i < n; i++) {
            parent[i] = i;
        }

        Map<String, List<Integer>> byKey = new LinkedHashMap<>();
        Map<String, List<String>> effectsByKey = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) {
            for (ResolvedOperation op : candidates.get(i).operations()) {
                byKey.computeIfAbsent(op.writeKey(), k -> new ArrayList<>()).add(i);
                effectsByKey
                        .computeIfAbsent(op.writeKey(), k -> new ArrayList<>())
                        .add(op.effect());
            }
        }

        List<String> conflictedKeys = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : effectsByKey.entrySet()) {
            List<String> effects = entry.getValue();
            boolean uniform = effects.stream().distinct().count() <= 1;
            if (uniform) {
                continue; // 같은 효과면 멱등이다. 여러 규칙이 같은 결과를 원하는 것은 충돌이 아니다
            }
            conflictedKeys.add(entry.getKey());
            List<Integer> touching = byKey.get(entry.getKey());
            for (int i = 1; i < touching.size(); i++) {
                union(parent, touching.get(0), touching.get(i));
            }
        }
        if (conflictedKeys.isEmpty()) {
            return;
        }

        Map<Integer, Integer> groupNumbers = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) {
            int root = root(parent, i);
            boolean grouped = false;
            for (int j = 0; j < n; j++) {
                if (j != i && root(parent, j) == root) {
                    grouped = true;
                    break;
                }
            }
            if (!grouped) {
                continue;
            }
            int group = groupNumbers.computeIfAbsent(root, k -> groupNumbers.size() + 1);
            Candidate candidate = candidates.get(i);
            String keys = candidate.operations().stream()
                    .map(ResolvedOperation::writeKey)
                    .filter(conflictedKeys::contains)
                    .distinct()
                    .reduce((a, b) -> a + ", " + b)
                    .orElse("");
            settled.put(
                    candidate.rule().ruleId(),
                    ParseRuleOutcome.conflict(candidate.rule(), group, "같은 자리를 다르게 바꾼다: " + keys));
        }
    }

    private static int root(int[] parent, int i) {
        while (parent[i] != i) {
            parent[i] = parent[parent[i]];
            i = parent[i];
        }
        return i;
    }

    private static void union(int[] parent, int a, int b) {
        int ra = root(parent, a);
        int rb = root(parent, b);
        if (ra != rb) {
            parent[rb] = ra;
        }
    }

    // ── 적용과 유효성 ────────────────────────────────────────────────────

    private QueryResolution applyAll(QueryResolution base, List<ResolvedOperation> operations) {
        QueryResolution working = base;
        for (ResolvedOperation operation : operations) {
            ResolutionAxis axis = operation.axis();
            working = switch (operation.op()) {
                case SET, UNSET -> ResolutionAxis.writeIntent(working, operation.intent());
                case ADD_ITEM -> axis.write(working, ResolutionAxis.plus(axis.read(working), operation.item()));
                // 대상이 이미 없으면 그대로 둔다. 원본 기준 검증은 resolve 단계에서 끝났고, 적용은 멱등이어야
                // 같은 항목을 지우는 독립 규칙 둘이 서로를 실패시키지 않는다.
                case REMOVE_ITEM ->
                    axis.write(
                            working,
                            axis.read(working).stream()
                                    .filter(i -> !i.identity()
                                            .equals(operation.item().identity()))
                                    .toList());
            };
        }
        return working;
    }

    /**
     * 적용 후 해석이 검색에 쓸 수 있는 상태인가. 사유를 반환하면 그 규칙은 되돌려진다.
     *
     * <p>리졸버 쪽 {@code validator.py} 가 지키는 불변식과 같은 것을 본다. 규칙이 그 불변식을 깨뜨릴 수 있는 경로가 여기라서 같은 검사가 두 번 필요하다.
     *
     * <p><b>규칙이 건드린 축만 본다. 그리고 적용 전에도 있던 위반은 그 규칙 탓으로 돌리지 않는다.</b> 해석 전체를 보면 리졸버가 낸 흠 하나가 무관한 규칙까지 전부 {@code failed} 로
     * 만든다 — 예를 들어 리졸버가 {@code expanded_terms} 에 같은 단어를 두 번 넣으면({@code validator.py} 는 이 축을 중복 제거하지 않는다)
     * {@code locations} 만 고치는 규칙까지 그 사유로 실패한다. 승인된 교정이 전부 사라지고 원인은 규칙으로 기록된다.
     *
     * @param base 이 규칙을 적용하기 직전의 상태. 여기서 이미 성립하던 위반은 무시한다
     */
    private String validate(QueryResolution attempt, QueryResolution base, Set<ResolutionAxis> touched) {
        for (ResolutionAxis axis : touched) {
            if (!axis.list()) {
                continue;
            }
            String problem = axisProblem(attempt, axis);
            if (problem != null && !problem.equals(axisProblem(base, axis))) {
                return problem;
            }
            // 명시적 사용자 필터는 규칙이 바꿀 수 없다 (F-11). resolve 단계에서도 막지만 결과로 한 번 더 확인한다.
            List<String> kept = axis.read(attempt).stream()
                    .filter(i -> i.origin() == QueryResolution.Origin.EXPLICIT_FILTER)
                    .map(ResolutionAxis.Item::identity)
                    .toList();
            for (ResolutionAxis.Item item : axis.read(base)) {
                if (item.origin() == QueryResolution.Origin.EXPLICIT_FILTER && !kept.contains(item.identity())) {
                    return "%s 의 명시적 사용자 필터가 사라졌다".formatted(axis.jsonName());
                }
            }
        }
        return null;
    }

    /** 한 축의 불변식 위반 사유. 없으면 {@code null}. */
    private String axisProblem(QueryResolution resolution, ResolutionAxis axis) {
        List<ResolutionAxis.Item> items = axis.read(resolution);
        List<String> identities =
                items.stream().map(ResolutionAxis.Item::identity).toList();
        if (identities.stream().distinct().count() != identities.size()) {
            return "%s 에 같은 항목이 중복된다".formatted(axis.jsonName());
        }
        for (ResolutionAxis.Item item : items) {
            if (axis == ResolutionAxis.DATE_WINDOWS) {
                if (item.start() == null
                        || item.endExclusive() == null
                        || !item.start().isBefore(item.endExclusive())) {
                    return "date_windows 구간이 유효하지 않다";
                }
            } else if (item.value() == null || item.value().isBlank()) {
                return "%s 에 빈 값이 있다".formatted(axis.jsonName());
            }
            if (item.origin() == QueryResolution.Origin.EXPLICIT_QUERY && item.querySpan() == null) {
                return "%s 의 explicit_query 항목에 원문 구간이 없다".formatted(axis.jsonName());
            }
        }
        return null;
    }

    /** 예상하지 못한 예외의 사유 문구. 규칙 문제와 코드 버그를 기록에서 구분할 수 있게 예외 종류를 남긴다. */
    private static String unexpected(RuntimeException ex) {
        return "적용 중 예외 %s: %s".formatted(ex.getClass().getSimpleName(), ex.getMessage());
    }
}
