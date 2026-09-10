package com.npick.search.domain.model;

import java.time.LocalDate;
import java.util.List;

import com.npick.common.error.BusinessException;
import com.npick.search.domain.error.SearchErrorCode;

/**
 * 검수자가 승인한 해석 교정 규칙 하나. {@code search_rule} 의 {@code action = 'patch_parse'} 행에 대응한다.
 *
 * <p><b>이 타입이 {@code condition_json}·{@code patch_json} 의 형식 정본이다</b> (S15P21A501-49). FRD §11 이 「규칙 자체의 형식과 허용 연산은
 * {@code query_resolver/schema.py} 에 없다」고 구분해 남긴 그 자리다. 프로세스 경계를 넘지 않으므로 {@code docs/contracts/} 가 아니라 여기에 둔다.
 *
 * <h2>어휘를 닫는 것이 이 설계의 전부다</h2>
 *
 * 조건·연산이 {@link Condition.Op}·{@link Patch.Op} enum 과 {@link ResolutionAxis} 로만 표현된다. 정규식·스크립트·JSON 경로·외부 호출은 <b>적을 자리가
 * 없다</b> — 런타임 검사로 막는 것이 아니라 문법에 없다 (F-11 「임의 JSON 편집·스크립트·정규식·외부 호출은 지원하지 않는다」).
 *
 * <h2>JSON 표기</h2>
 *
 * <pre>{@code
 * condition_json
 * {
 *   "syntax_version": "parse-rule/v1",
 *   "resolution_schema_version": "query-resolver/v2",
 *   "all": [
 *     { "axis": "locations",      "op": "has_value", "value": "○○공장" },
 *     { "axis": "incident_names", "op": "is_empty" },
 *     { "axis": "intent",         "op": "equals",    "value": "scene_search" }
 *   ]
 * }
 *
 * patch_json
 * {
 *   "syntax_version": "parse-rule/v1",
 *   "operations": [
 *     { "op": "remove_item", "axis": "locations", "value": "○○공장" },
 *     { "op": "add_item",    "axis": "incident_names",
 *       "value_from": { "axis": "locations", "value": "○○공장" } }
 *   ]
 * }
 * }</pre>
 *
 * <p>{@code all} 만 있고 {@code any}·{@code not} 은 없다. F-11 이 요구하는 것은 AND 뿐이다. 이름을 {@code all} 로 둔 것은 나중에 {@code any} 가
 * 필요해질 때 형식을 바꾸지 않고 키를 더할 수 있게 한 것뿐이다.
 *
 * @param ruleId {@code search_rule_id}
 * @param syntaxVersion 규칙 문법 버전. {@link #SYNTAX_VERSION} 과 다르면 그 실행에서 건너뛴다 (F-14)
 * @param resolutionSchemaVersion 이 규칙이 겨냥한 리졸버 출력 계약. 해석의 {@code schemaVersion} 과 다르면 건너뛴다 (F-14)
 * @param bodySnapshot {@code applied_rules_json} 에 남길 본문. 규칙 원본 JSON 을 그대로 들고 다닌다 — 기록을 축약하지 않기 위해서다 (§7.2)
 * @param parseError JSON 을 이 타입으로 읽지 못한 사유. 정상 규칙은 {@code null} 이다. <b>읽지 못한 규칙을 목록에서 빼지 않고 사유와 함께 들고 오는 이유</b>는 빼면 그
 *     규칙이 오류 없이 조용히 안 걸리기 때문이다 (§11). 이 값이 있으면 {@code skipped_incompatible} 로 기록된다 (F-14)
 */
public record ParseRule(
        long ruleId,
        String syntaxVersion,
        String resolutionSchemaVersion,
        Condition condition,
        Patch patch,
        String bodySnapshot,
        String parseError) {

    /** 이 코드가 해석할 수 있는 규칙 문법 버전. 어휘가 늘거나 의미가 바뀔 때만 올린다. */
    public static final String SYNTAX_VERSION = "parse-rule/v1";

    public ParseRule {
        boolean bodyPresent = condition != null && patch != null;
        // 본문과 파싱 실패 사유 중 정확히 하나. 둘 다 없으면 무엇을 적용할지 알 수 없고, 둘 다 있으면
        // 실패한 규칙을 적용하려 들 수 있다. 어느 쪽이든 조용히 잘못된 검색으로 이어진다.
        if (bodyPresent == (parseError != null)) {
            throw new BusinessException(SearchErrorCode.RULE_BODY_INCOMPLETE);
        }
    }

    /** 읽지 못한 규칙. 목록에서 빼지 않고 이 모양으로 들고 와 비호환으로 기록한다. */
    public static ParseRule unparsed(long ruleId, String bodySnapshot, String reason) {
        return new ParseRule(ruleId, null, null, null, null, bodySnapshot, reason);
    }

    /**
     * 이 규칙을 그 해석에 적용할 수 없는 사유. 적용할 수 있으면 {@code null} 이다.
     *
     * <p>세 갈래를 한 자리에서 본다 — 읽지 못한 규칙, 버전이 어긋난 규칙, 어휘는 맞지만 조합이 성립하지 않는 규칙. 셋 다 F-14 의 「규칙 문법·출력 형식 비호환 → 그 실행에서 건너뛰고 이유
     * 기록」에 해당한다.
     *
     * <p>버전 대조가 두 번인 이유는 어긋날 수 있는 것이 둘이기 때문이다 — <b>규칙을 읽는 문법</b>과 <b>규칙이 키를 거는 해석 계약</b>. baseline 주석의 「조건 문법·출력 계약
     * 호환성은 condition_json 의 버전으로 검사한다」가 이 둘이다.
     */
    public String incompatibleReason(QueryResolution resolution) {
        if (parseError != null) {
            return parseError;
        }
        if (!SYNTAX_VERSION.equals(syntaxVersion)) {
            return "규칙 문법 %s 를 읽을 수 없다 (이 코드는 %s)".formatted(syntaxVersion, SYNTAX_VERSION);
        }
        if (resolutionSchemaVersion == null || !resolutionSchemaVersion.equals(resolution.schemaVersion())) {
            return "해석 계약 %s 를 겨냥한 규칙이다 (이 해석은 %s)".formatted(resolutionSchemaVersion, resolution.schemaVersion());
        }
        if (condition.all().isEmpty()) {
            // 조건 없는 규칙은 모든 검색에 걸린다. 검수자가 승인한 것은 특정 패턴의 교정이다 (F-11).
            return "조건이 비어 있다";
        }
        if (patch.operations().isEmpty()) {
            return "변경 연산이 비어 있다";
        }
        for (Condition.Predicate predicate : condition.all()) {
            String problem = predicate.problem();
            if (problem != null) {
                return problem;
            }
        }
        for (Patch.Operation operation : patch.operations()) {
            String problem = operation.problem();
            if (problem != null) {
                return problem;
            }
        }
        return null;
    }

    /** 조건 묶음. 전부 참일 때만 적용한다. */
    public record Condition(List<Predicate> all) {

        public Condition {
            all = all == null ? List.of() : List.copyOf(all);
        }

        /**
         * 조건 하나.
         *
         * @param type {@code has_type} 의 대상 유형. 다른 연산에서는 {@code null}
         * @param value {@code has_value}·{@code equals} 의 대상 값. 다른 연산에서는 {@code null}
         */
        public record Predicate(ResolutionAxis axis, Op op, String type, String value) {

            /** 축과 연산의 조합이 성립하지 않는 사유. 성립하면 {@code null}. */
            String problem() {
                if (axis.list() == (op == Op.EQUALS)) {
                    return "%s 축에 %s 는 쓸 수 없다".formatted(axis.jsonName(), op.jsonName());
                }
                return switch (op) {
                    case EQUALS, HAS_VALUE ->
                        value == null || value.isBlank() ? "%s 에 값이 없다".formatted(op.jsonName()) : null;
                    case HAS_TYPE -> type == null || type.isBlank() ? "has_type 에 유형이 없다" : null;
                    case IS_EMPTY, IS_NOT_EMPTY -> null;
                };
            }
        }

        /** 허용 조건 어휘. 닫혀 있다. */
        public enum Op {
            /** 목록 축에 이 값을 가진 항목이 있다. */
            HAS_VALUE("has_value"),
            /** 목록 축에 이 유형인 항목이 있다. */
            HAS_TYPE("has_type"),
            /** 목록 축이 비어 있다. */
            IS_EMPTY("is_empty"),
            /** 목록 축이 비어 있지 않다. */
            IS_NOT_EMPTY("is_not_empty"),
            /** 스칼라 축이 이 값이다. */
            EQUALS("equals");

            private final String jsonName;

            Op(String jsonName) {
                this.jsonName = jsonName;
            }

            public String jsonName() {
                return jsonName;
            }
        }
    }

    /** 변경 연산 묶음. 한 규칙의 연산은 전부 적용되거나 전부 버려진다 (F-11 「일부만 성공한 상태를 남기지 않는다」). */
    public record Patch(List<Operation> operations) {

        public Patch {
            operations = operations == null ? List.of() : List.copyOf(operations);
        }

        /**
         * 변경 연산 하나.
         *
         * @param target 대상 항목의 모양. {@code add_item} 에서는 넣을 항목, {@code remove_item} 에서는 지울 항목, {@code set} 에서는 설정할 값
         * @param valueFrom 원본 해석의 다른 항목에서 값을 가져올 때의 참조. F-11 「필요한 원본 항목의 값을 재사용할 수 있다」. {@code null} 이면
         *     {@link Target#value()} 를 리터럴로 쓴다
         */
        public record Operation(Op op, ResolutionAxis axis, Target target, ValueRef valueFrom) {

            /** 축과 연산의 조합이 성립하지 않는 사유. 성립하면 {@code null}. */
            String problem() {
                if (target == null) {
                    return "%s 에 대상이 없다".formatted(op.jsonName());
                }
                if (axis.list() != op.forList()) {
                    return "%s 축에 %s 는 쓸 수 없다".formatted(axis.jsonName(), op.jsonName());
                }
                if (valueFrom != null) {
                    if (op != Op.ADD_ITEM) {
                        return "value_from 은 add_item 에만 쓴다";
                    }
                    if (axis == ResolutionAxis.DATE_WINDOWS) {
                        // 날짜 구간은 재사용할 값 문자열이 없다. 옮길 대상이 아니라 새로 적을 대상이다.
                        return "date_windows 에는 value_from 을 쓸 수 없다";
                    }
                    if (valueFrom.axis() == ResolutionAxis.DATE_WINDOWS
                            || !valueFrom.axis().list()) {
                        return "value_from 이 %s 를 가리킨다"
                                .formatted(valueFrom.axis().jsonName());
                    }
                    if (valueFrom.value() == null || valueFrom.value().isBlank()) {
                        return "value_from 에 값이 없다";
                    }
                }
                return switch (op) {
                    case SET -> target.value() == null || target.value().isBlank() ? "set 에 값이 없다" : null;
                    case UNSET -> null;
                    case ADD_ITEM, REMOVE_ITEM -> targetProblem();
                };
            }

            private String targetProblem() {
                boolean typeRequired = axis == ResolutionAxis.ENTITIES
                        || axis == ResolutionAxis.LOCATIONS
                        || axis == ResolutionAxis.DATE_WINDOWS;
                if (typeRequired == (target.type() == null || target.type().isBlank())) {
                    return typeRequired
                            ? "%s 대상에 유형이 없다".formatted(axis.jsonName())
                            : "%s 대상에는 유형을 쓰지 않는다".formatted(axis.jsonName());
                }
                if (axis == ResolutionAxis.DATE_WINDOWS) {
                    return target.start() == null || target.endExclusive() == null ? "date_windows 대상에 구간이 없다" : null;
                }
                boolean hasValue = target.value() != null && !target.value().isBlank();
                return hasValue || valueFrom != null ? null : "%s 대상에 값이 없다".formatted(axis.jsonName());
            }
        }

        /**
         * 연산이 가리키는 항목.
         *
         * @param type {@code entities}·{@code locations} 의 유형, {@code date_windows} 의 날짜 필드. 없는 축은 {@code null}
         * @param value 값. {@code date_windows} 에서는 {@code null} 이고 {@code start}·{@code endExclusive} 를 쓴다
         */
        public record Target(String type, String value, LocalDate start, LocalDate endExclusive) {

            /** 이 대상을 항목으로 본 것. {@code origin} 은 호출부가 정한다 — 그 결정이 F-05 보호의 핵심이다. */
            public ResolutionAxis.Item asItem(QueryResolution.Origin origin, QueryResolution.QuerySpan querySpan) {
                return new ResolutionAxis.Item(type, value, start, endExclusive, origin, querySpan, 0.0);
            }
        }

        /**
         * 원본 해석의 항목 하나를 가리키는 참조.
         *
         * <p>여기서 가져온 값은 <b>출처와 원문 구간을 함께 승계한다</b>. 같은 원문 구간을 가리키므로 {@code query[span] == value} 가 그대로 성립한다 (F-05). 반대로
         * 리터럴로 새로 적은 값은 승계할 근거가 없어 {@code inferred} 로 강제된다.
         */
        public record ValueRef(ResolutionAxis axis, String type, String value) {}

        /** 허용 연산 어휘. 닫혀 있다. F-11 의 「값 설정·해제, 목록 항목 추가·제거」와 1:1 이다. */
        public enum Op {
            /** 스칼라 축에 값을 설정한다. */
            SET("set"),
            /** 스칼라 축의 값을 해제한다. */
            UNSET("unset"),
            /** 목록 축에 항목을 추가한다. */
            ADD_ITEM("add_item"),
            /** 목록 축에서 항목을 제거한다. */
            REMOVE_ITEM("remove_item");

            private final String jsonName;

            Op(String jsonName) {
                this.jsonName = jsonName;
            }

            public String jsonName() {
                return jsonName;
            }

            /** 목록 축에 쓰는 연산인가. 스칼라 축과 목록 축이 서로의 연산을 받지 않는다. */
            public boolean forList() {
                return this == ADD_ITEM || this == REMOVE_ITEM;
            }
        }
    }
}
