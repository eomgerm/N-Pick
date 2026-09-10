package com.npick.search.infrastructure.persistence.mapper;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import com.npick.search.domain.model.ParseRule;
import com.npick.search.domain.model.ResolutionAxis;

/**
 * {@code condition_json}·{@code patch_json} 을 {@link ParseRule} 로 읽는다.
 *
 * <p>도메인이 Jackson 을 모르게 하는 경계다 (설계 정본 §4). 형식 정본은 {@link ParseRule} 이고 이 클래스는 그 형식을 JSON 에서 꺼내는 일만 한다.
 *
 * <h2>모르는 것은 거부한다</h2>
 *
 * 모르는 키·모르는 축 이름·모르는 연산 이름을 만나면 그 규칙을 {@link ParseRule#unparsed} 로 만든다. <b>조용히 무시하지 않는다.</b> 무시하면 규칙이 절반만 적용되거나, 오타 하나로
 * 규칙 전체가 오류 없이 안 걸린다 — FRD §11 이 경계한 그 실패 방식이다.
 *
 * <p>읽지 못한 규칙을 목록에서 빼지도 않는다. 사유를 들고 와 {@code skipped_incompatible} 로 기록되게 한다 (F-14).
 */
@Component
public class ParseRuleJsonMapper {

    private static final Set<String> CONDITION_KEYS = Set.of("syntax_version", "resolution_schema_version", "all");
    private static final Set<String> PATCH_KEYS = Set.of("syntax_version", "operations");
    private static final Set<String> PREDICATE_KEYS = Set.of("axis", "op", "type", "value");
    private static final Set<String> OPERATION_KEYS =
            Set.of("op", "axis", "type", "value", "start", "end_exclusive", "value_from");
    private static final Set<String> VALUE_FROM_KEYS = Set.of("axis", "type", "value");

    /**
     * 전용 {@link ObjectMapper}. 애플리케이션 빈을 주입받지 않는다.
     *
     * <p>규칙 JSON 은 <b>저장 형식</b>이다. API 응답 직렬화 설정을 바꿨다고 이미 저장된 규칙을 읽는 방식이 달라지면 안 된다. 여기서 쓰는 것은 {@code readTree} 와
     * {@code createObjectNode} 뿐이라 설정할 것도 없다.
     */
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 규칙 행 하나를 도메인 규칙으로 읽는다. 읽지 못하면 예외를 던지지 않고 {@link ParseRule#unparsed} 를 낸다.
     *
     * <p>엔티티가 아니라 값을 받는다. 파싱이 이 클래스의 전부이고 JPA 를 끌고 들어올 이유가 없다.
     */
    public ParseRule toDomain(long ruleId, String conditionJson, String patchJson) {
        String snapshot = snapshot(conditionJson, patchJson);
        try {
            return parse(ruleId, conditionJson, patchJson, snapshot);
        } catch (RuntimeException ex) {
            return ParseRule.unparsed(ruleId, snapshot, "규칙 JSON 을 읽을 수 없다: " + ex.getMessage());
        }
    }

    /** {@code applied_rules_json} 에 남길 본문. 두 컬럼을 그대로 담는다 — 축약하지 않는다 (§7.2). */
    private String snapshot(String conditionJson, String patchJson) {
        return objectMapper
                .createObjectNode()
                .put("condition_json", conditionJson)
                .put("patch_json", patchJson)
                .toString();
    }

    private ParseRule parse(long ruleId, String conditionJson, String patchJson, String snapshot) {
        JsonNode condition = read(conditionJson, "condition_json");
        JsonNode patch = read(patchJson, "patch_json");
        allowOnly(condition, CONDITION_KEYS, "condition_json");
        allowOnly(patch, PATCH_KEYS, "patch_json");

        String syntaxVersion = required(condition, "syntax_version");
        // 두 컬럼이 각자 문법 버전을 들고 있다. 어긋난 채로 읽으면 어느 쪽 문법으로 읽었는지 알 수 없다.
        if (!syntaxVersion.equals(required(patch, "syntax_version"))) {
            throw new IllegalArgumentException("syntax_version 이 condition_json 과 patch_json 에서 다르다");
        }

        return new ParseRule(
                ruleId,
                syntaxVersion,
                required(condition, "resolution_schema_version"),
                new ParseRule.Condition(predicates(array(condition, "all"))),
                new ParseRule.Patch(operations(array(patch, "operations"))),
                snapshot,
                null);
    }

    private List<ParseRule.Condition.Predicate> predicates(JsonNode all) {
        List<ParseRule.Condition.Predicate> predicates = new ArrayList<>();
        for (JsonNode node : all) {
            allowOnly(node, PREDICATE_KEYS, "all[]");
            predicates.add(new ParseRule.Condition.Predicate(
                    axis(required(node, "axis")),
                    conditionOp(required(node, "op")),
                    optional(node, "type"),
                    optional(node, "value")));
        }
        return predicates;
    }

    private List<ParseRule.Patch.Operation> operations(JsonNode operations) {
        List<ParseRule.Patch.Operation> parsed = new ArrayList<>();
        for (JsonNode node : operations) {
            allowOnly(node, OPERATION_KEYS, "operations[]");
            parsed.add(new ParseRule.Patch.Operation(
                    patchOp(required(node, "op")),
                    axis(required(node, "axis")),
                    new ParseRule.Patch.Target(
                            optional(node, "type"),
                            optional(node, "value"),
                            date(node, "start"),
                            date(node, "end_exclusive")),
                    valueFrom(node.get("value_from"))));
        }
        return parsed;
    }

    private ParseRule.Patch.ValueRef valueFrom(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        allowOnly(node, VALUE_FROM_KEYS, "value_from");
        return new ParseRule.Patch.ValueRef(
                axis(required(node, "axis")), optional(node, "type"), optional(node, "value"));
    }

    // ── 어휘 해석 ───────────────────────────────────────────────────────

    private static ResolutionAxis axis(String jsonName) {
        return ResolutionAxis.from(jsonName).orElseThrow(() -> new IllegalArgumentException("모르는 axis: " + jsonName));
    }

    private static ParseRule.Condition.Op conditionOp(String jsonName) {
        for (ParseRule.Condition.Op op : ParseRule.Condition.Op.values()) {
            if (op.jsonName().equals(jsonName)) {
                return op;
            }
        }
        throw new IllegalArgumentException("모르는 조건 op: " + jsonName);
    }

    private static ParseRule.Patch.Op patchOp(String jsonName) {
        for (ParseRule.Patch.Op op : ParseRule.Patch.Op.values()) {
            if (op.jsonName().equals(jsonName)) {
                return op;
            }
        }
        throw new IllegalArgumentException("모르는 연산 op: " + jsonName);
    }

    // ── JSON 읽기 ──────────────────────────────────────────────────────

    private JsonNode read(String json, String where) {
        if (json == null || json.isBlank()) {
            throw new IllegalArgumentException(where + " 이 비어 있다");
        }
        try {
            JsonNode node = objectMapper.readTree(json);
            if (!node.isObject()) {
                throw new IllegalArgumentException(where + " 이 객체가 아니다");
            }
            return node;
        } catch (com.fasterxml.jackson.core.JacksonException ex) {
            throw new IllegalArgumentException(where + " 이 올바른 JSON 이 아니다", ex);
        }
    }

    private static void allowOnly(JsonNode node, Set<String> allowed, String where) {
        for (Iterator<String> names = node.fieldNames(); names.hasNext(); ) {
            String name = names.next();
            if (!allowed.contains(name)) {
                throw new IllegalArgumentException("%s 에 모르는 키 %s".formatted(where, name));
            }
        }
    }

    private static String required(JsonNode node, String field) {
        String value = optional(node, field);
        if (value == null) {
            throw new IllegalArgumentException(field + " 이 없다");
        }
        return value;
    }

    private static String optional(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isTextual()) {
            throw new IllegalArgumentException(field + " 이 문자열이 아니다");
        }
        return value.asText();
    }

    private static JsonNode array(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isArray()) {
            throw new IllegalArgumentException(field + " 이 배열이 아니다");
        }
        return value;
    }

    private static LocalDate date(JsonNode node, String field) {
        String value = optional(node, field);
        if (value == null) {
            return null;
        }
        try {
            return LocalDate.parse(value);
        } catch (java.time.format.DateTimeParseException ex) {
            throw new IllegalArgumentException("%s 가 YYYY-MM-DD 가 아니다: %s".formatted(field, value), ex);
        }
    }
}
