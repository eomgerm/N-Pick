package com.npick.search.domain.model;

/**
 * 규칙 하나가 그 실행에서 어떻게 됐는지의 기록. {@code search_execution.applied_rules_json} 한 항목에 대응한다 (§7.2).
 *
 * <p>상태가 다섯인 것이 이 타입의 존재 이유다. F-11 완료 기준이 「충돌·실패·비호환과 <b>단순 조건 불일치</b>를 구분한다」를 요구한다. 넷을 하나의 「미적용」으로 뭉개면 규칙이 왜 안 걸렸는지 알
 * 방법이 없어지고, FRD §11 이 경계한 「오류 없이 조용히 안 걸린다」가 그대로 재현된다.
 *
 * @param appliedOrder 적용 순서. 1 부터. 적용되지 않았으면 {@code null}
 * @param conflictGroup 충돌 그룹 번호. {@link Status#SKIPPED_CONFLICT} 일 때만. 같은 번호끼리 부딪힌 것이다
 */
public record ParseRuleOutcome(
        long ruleId, String bodySnapshot, Status status, String reason, Integer appliedOrder, Integer conflictGroup) {

    public enum Status {
        /** 조건이 맞아 적용됐다. */
        APPLIED("applied"),
        /**
         * 조건이 맞지 않았다. <b>오류가 아니다.</b>
         *
         * <p>규칙이 있고 검색이 그 패턴이 아니면 이게 정상 결과다. baseline 의 {@code degraded_reasons_json} 주석도 「단순 복수 규칙 일치나 정상 조건 불일치는 오류가
         * 아니다」로 같은 구분을 적어 뒀다.
         */
        SKIPPED_CONDITION_UNMET("skipped_condition_unmet"),
        /** 다른 규칙과 같은 자리를 다르게 바꾸려 해서 그룹 전체를 건너뛰었다 (F-11). */
        SKIPPED_CONFLICT("skipped_conflict"),
        /** 규칙 문법이나 해석 출력 계약이 맞지 않아 이 실행에서 건너뛰었다 (F-14). */
        SKIPPED_INCOMPATIBLE("skipped_incompatible"),
        /** 적용할 수 없거나 적용 결과가 유효하지 않아 되돌렸다. 부분 적용 상태는 남지 않는다 (F-11). */
        FAILED("failed");

        private final String jsonName;

        Status(String jsonName) {
            this.jsonName = jsonName;
        }

        public String jsonName() {
            return jsonName;
        }

        /** {@code degraded_reasons_json} 에 남길 사유인가. 조건 불일치와 정상 적용은 기능 저하가 아니다. */
        public boolean degraded() {
            return this == SKIPPED_CONFLICT || this == SKIPPED_INCOMPATIBLE || this == FAILED;
        }
    }

    public static ParseRuleOutcome applied(ParseRule rule, int order) {
        return new ParseRuleOutcome(rule.ruleId(), rule.bodySnapshot(), Status.APPLIED, null, order, null);
    }

    public static ParseRuleOutcome conditionUnmet(ParseRule rule, String reason) {
        return new ParseRuleOutcome(
                rule.ruleId(), rule.bodySnapshot(), Status.SKIPPED_CONDITION_UNMET, reason, null, null);
    }

    public static ParseRuleOutcome conflict(ParseRule rule, int group, String reason) {
        return new ParseRuleOutcome(rule.ruleId(), rule.bodySnapshot(), Status.SKIPPED_CONFLICT, reason, null, group);
    }

    public static ParseRuleOutcome incompatible(ParseRule rule, String reason) {
        return new ParseRuleOutcome(
                rule.ruleId(), rule.bodySnapshot(), Status.SKIPPED_INCOMPATIBLE, reason, null, null);
    }

    public static ParseRuleOutcome failed(ParseRule rule, String reason) {
        return new ParseRuleOutcome(rule.ruleId(), rule.bodySnapshot(), Status.FAILED, reason, null, null);
    }
}
