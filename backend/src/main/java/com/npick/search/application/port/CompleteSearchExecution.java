package com.npick.search.application.port;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.npick.search.application.query.fusion.SearchConfigSnapshot;
import com.npick.search.application.resolution.SearchDegradedReason;
import com.npick.search.domain.model.ParseRuleOutcome;
import com.npick.search.domain.model.QueryResolution;

/** 검색 계산이 끝난 뒤 실행과 결과 목록을 한 트랜잭션으로 완결하는 스냅샷. */
public record CompleteSearchExecution(
        long searchExecutionId,
        ExecutionStatus status,
        List<SearchDegradedReason> degradedReasons,
        String errorCode,
        QueryResolution finalResolution,
        List<ParseRuleOutcome> appliedRules,
        CandidateRecord candidates,
        FilterRecord filtered,
        List<AppliedSceneExclusion> appliedExcludes,
        List<RankedScene> rankedScenes,
        SearchConfigSnapshot config,
        int executionMs,
        Map<String, Object> verificationContext) {

    public CompleteSearchExecution {
        if (searchExecutionId <= 0 || status == null || config == null || executionMs < 0) {
            throw new IllegalArgumentException("검색 완료 스냅샷의 필수 값이 올바르지 않다");
        }
        degradedReasons = degradedReasons == null ? List.of() : List.copyOf(degradedReasons);
        appliedRules = appliedRules == null ? List.of() : List.copyOf(appliedRules);
        appliedExcludes = appliedExcludes == null ? List.of() : List.copyOf(appliedExcludes);
        rankedScenes = rankedScenes == null ? List.of() : List.copyOf(rankedScenes);
        verificationContext = immutableNullable(verificationContext);
        boolean ruleDegraded =
                appliedRules.stream().anyMatch(rule -> rule.status().degraded());
        if (status == ExecutionStatus.SUCCEEDED && (!degradedReasons.isEmpty() || ruleDegraded)) {
            throw new IllegalArgumentException("succeeded 실행에는 기능 저하 사유가 있을 수 없다");
        }
        if (status == ExecutionStatus.DEGRADED && degradedReasons.isEmpty() && !ruleDegraded) {
            throw new IllegalArgumentException("degraded 실행에는 기능 저하 사유가 필요하다");
        }
        if (status == ExecutionStatus.FAILED && (errorCode == null || errorCode.isBlank())) {
            throw new IllegalArgumentException("failed 실행에는 errorCode가 필요하다");
        }
        if (status != ExecutionStatus.FAILED && (candidates == null || filtered == null)) {
            throw new IllegalArgumentException("결과가 나온 실행에는 후보와 필터 기록이 필요하다");
        }
        if (filtered != null && filtered.returnedCount() != rankedScenes.size()) {
            throw new IllegalArgumentException("실제 반환 수와 저장할 결과 수가 일치해야 한다");
        }
        if (status == ExecutionStatus.FAILED && !rankedScenes.isEmpty()) {
            throw new IllegalArgumentException("failed 실행에는 검색 결과를 저장할 수 없다");
        }
    }

    public enum ExecutionStatus {
        SUCCEEDED,
        DEGRADED,
        FAILED;

        public String databaseValue() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    public record CandidateRecord(
            List<Map<String, Object>> lexical, Map<String, Object> dense, Map<String, Object> structured) {
        public CandidateRecord {
            lexical = lexical == null
                    ? List.of()
                    : lexical.stream()
                            .map(value -> java.util.Collections.unmodifiableMap(new LinkedHashMap<>(value)))
                            .toList();
            dense = immutableNullable(dense);
            structured = structured == null ? Map.of() : immutableNullable(structured);
        }

        public Map<String, Object> payload() {
            var value = new LinkedHashMap<String, Object>();
            value.put("lexical", lexical);
            value.put("dense", dense);
            value.put("structured", structured);
            return java.util.Collections.unmodifiableMap(value);
        }
    }

    public record FilterRecord(int returnedCount, List<String> shortageReasons, GuardRecord guard) {
        public FilterRecord {
            if (returnedCount < 0) throw new IllegalArgumentException("returnedCount는 음수일 수 없다");
            shortageReasons = shortageReasons == null ? List.of() : List.copyOf(shortageReasons);
            if (guard == null) throw new IllegalArgumentException("guard 기록은 필수다");
        }

        public Map<String, Object> payload() {
            return Map.of(
                    "returned_count", returnedCount,
                    "shortage_reasons", shortageReasons,
                    "guard", guard.payload());
        }
    }

    public record GuardRecord(boolean incidentGuardActive, List<GuardVerdict> verdicts) {
        public GuardRecord {
            verdicts = verdicts == null ? List.of() : List.copyOf(verdicts);
        }

        private Map<String, Object> payload() {
            return Map.of(
                    "incident_guard_active",
                    incidentGuardActive,
                    "verdicts",
                    verdicts.stream().map(GuardVerdict::payload).toList());
        }
    }

    public record GuardVerdict(long sceneId, String exclusionReason, List<String> fields) {
        public GuardVerdict {
            if (sceneId <= 0) throw new IllegalArgumentException("guard 장면 ID는 양수여야 한다");
            fields = fields == null ? List.of() : List.copyOf(fields);
        }

        private Map<String, Object> payload() {
            var value = new LinkedHashMap<String, Object>();
            value.put("scene_id", Long.toString(sceneId));
            value.put("exclusion_reason", exclusionReason);
            value.put("fields", fields);
            return java.util.Collections.unmodifiableMap(value);
        }
    }

    public record AppliedSceneExclusion(long sceneId, List<Long> ruleIds) {
        public AppliedSceneExclusion {
            ruleIds = ruleIds == null ? List.of() : List.copyOf(ruleIds);
        }
    }

    public record RankedScene(long sceneId, int rank, Map<String, Object> explain) {
        public RankedScene {
            if (sceneId <= 0 || rank <= 0 || explain == null) {
                throw new IllegalArgumentException("검색 결과 장면, 순위, 설명은 필수다");
            }
            explain = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(explain));
        }
    }

    private static Map<String, Object> immutableNullable(Map<String, Object> value) {
        return value == null ? null : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(value));
    }
}
