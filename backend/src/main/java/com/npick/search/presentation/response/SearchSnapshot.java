package com.npick.search.presentation.response;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import com.npick.search.application.query.SearchHistoryItem;
import com.npick.search.application.query.SearchHistoryRecord;
import com.npick.search.application.query.SearchHistoryResultRow;

/**
 * 당시 검색 결과 스냅샷의 복원 가능 판정과 조립 (S15P21A501-198).
 *
 * <p><b>목록과 상세가 이 클래스만 쓴다.</b> 계약이 「변하지 않은 기록의 목록/상세가 available 판정을 다르게 하지 않는다」를
 * 요구하므로, 판정을 두 곳에 두면 규칙이 갈라진다. 목록이 결과 전량을 싣고 다니는 이유도 이것이다.
 *
 * <p>복원은 저장된 JSON 을 <b>그대로 통과</b>시킨다. 필드별로 DTO 를 세워 옮겨 담지 않는다 — 현재 태그·검색으로 재계산하지
 * 않는다는 FRD §7.2 를 구조로 보장하고, {@code display} 블록의 키 구성이 바뀌어도(S15P21A501-60 미결) 이 파일만 따라간다.
 *
 * <p>판정 규칙: {@code filtered_json} 이 있어야 결과가 확정된 실행이고, 모든 결과 행이 {@code display}·{@code match} 를
 * 가져야 카드를 그릴 수 있다. 둘 중 하나라도 어긋나면 unavailable 이며 {@code result_count}·{@code representative_result}
 * ·{@code search_snapshot} 을 전부 null 로 낸다. 결과 0건과 저장 불완전을 가르는 유일한 근거가 {@code filtered_json} 이다.
 */
public record SearchSnapshot(
        String snapshotStatus, Integer resultCount, JsonNode representativeResult, JsonNode payload) {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final SearchSnapshot UNAVAILABLE = new SearchSnapshot("unavailable", null, null, null);

    /** 대표 결과가 목록에 싣는 필드. 상세의 results 한 항목에서 이 키만 뽑는다. */
    private static final List<String> REPRESENTATIVE_KEYS = List.of(
            "search_result_id",
            "scene_id",
            "clip_id",
            "display_name",
            "scene_description",
            "start_time_ms",
            "end_time_ms",
            "rank");

    /**
     * 실행 당시 명시 필터. {@code explicit_filters_json} 은 NOT NULL 이라 항상 읽히므로 스냅샷이 unavailable 이어도
     * 실제 object 를 유지한다 — 계약의 「결과 snapshot 만 손상됐고 필터를 읽을 수 있으면 실제 object 를 유지한다」.
     */
    public static JsonNode explicitFilters(String json) {
        return json == null || json.isBlank() ? MAPPER.createObjectNode() : readTree(json);
    }

    public static SearchSnapshot from(SearchHistoryRecord record) {
        SearchHistoryItem item = record.item();
        JsonNode filtered = parseOrNull(item.filteredJson());
        String queryResolutionStatus = queryResolutionStatus(item.parseSource());
        // jsonb 컬럼은 SQL NULL 뿐 아니라 JSON 리터럴 null 도 담을 수 있다 — CAST 하면 4글자 문자열 "null" 이
        // 되어 빈 값 검사를 통과한다. 둘 다 「결과가 확정되지 않았다」는 같은 뜻이므로 object 인지로 판정한다.
        if (filtered == null || !filtered.isObject() || queryResolutionStatus == null) {
            return UNAVAILABLE;
        }
        ArrayNode results = MAPPER.createArrayNode();
        for (SearchHistoryResultRow row : record.results()) {
            ObjectNode result = toResult(row);
            if (result == null) {
                return UNAVAILABLE;
            }
            results.add(result);
        }
        return new SearchSnapshot(
                "available",
                results.size(),
                representativeOf(results),
                payload(item, queryResolutionStatus, filtered, results));
    }

    /**
     * 해석이 완료됐는지 대체 검색으로 떨어졌는지. 스키마가 정한 세 값만 인정한다(baseline {@code parse_source} 주석).
     *
     * <p>NULL 이나 미등록 값을 {@code resolved} 로 접지 않고 null 을 돌려 unavailable 로 만든다. 「어떻게 해석했는지
     * 기록이 없다」를 「정상 해석됐다」로 바꾸면 없던 사실을 만들어 내는 것이다(FRD §7.2). {@code display_name} 을
     * 대체 문자열로 메우지 않는 것과 같은 이유다.
     */
    private static String queryResolutionStatus(String parseSource) {
        if (parseSource == null) {
            return null;
        }
        return switch (parseSource) {
            case "resolver", "resolver_rule" -> "resolved";
            case "fallback" -> "fallback";
            default -> null;
        };
    }

    /** 결과 한 행 = 컬럼 4개 + {@code display}·{@code match} 병합. 둘 중 하나라도 없으면 복원 불가라 null 이다. */
    private static ObjectNode toResult(SearchHistoryResultRow row) {
        if (row.explainJson() == null || row.explainJson().isBlank()) {
            return null;
        }
        JsonNode explain = readTree(row.explainJson());
        JsonNode display = explain.get("display");
        JsonNode match = explain.get("match");
        if (!(display instanceof ObjectNode displayObject) || !(match instanceof ObjectNode matchObject)) {
            return null;
        }
        ObjectNode result = MAPPER.createObjectNode();
        result.setAll(displayObject);
        result.setAll(matchObject);
        // 컬럼을 블록 뒤에 넣어 컬럼이 이기게 한다. setAll 은 merge 가 아니라 replace 이므로 순서를 뒤집으면
        // 저장 블록이 ID·순위를 덮어써 문자열 ID 규칙이 깨지거나 대표 결과가 사라진다.
        result.put("search_result_id", String.valueOf(row.searchResultId()));
        result.put("scene_id", String.valueOf(row.sceneId()));
        result.put("clip_id", String.valueOf(row.clipId()));
        result.put("rank", row.rank());
        return result;
    }

    /** 대표 결과는 저장된 rank=1 행이다. 결과가 없으면 null 이며 2위를 대신 올리지 않는다. */
    private static JsonNode representativeOf(ArrayNode results) {
        for (JsonNode result : results) {
            if (result.get("rank").asInt() == 1) {
                ObjectNode representative = MAPPER.createObjectNode();
                REPRESENTATIVE_KEYS.forEach(key -> representative.set(key, result.get(key)));
                return representative;
            }
        }
        return null;
    }

    private static ObjectNode payload(
            SearchHistoryItem item, String queryResolutionStatus, JsonNode filtered, ArrayNode results) {
        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("search_execution_id", String.valueOf(item.searchExecutionId()));
        payload.put("status", item.status());
        payload.set("degraded_reasons", arrayOrEmpty(item.degradedReasonsJson()));
        payload.put("query_resolution_status", queryResolutionStatus);
        payload.put("has_applied_review_rule", hasAppliedRule(item.appliedRulesJson()));
        payload.set("guard_summary", guardSummary(filtered, item.appliedExcludesJson()));
        payload.set("shortage_reasons", arrayOf(filtered.get("shortage_reasons")));
        payload.set("results", results);
        return payload;
    }

    /**
     * 적용된 해석 규칙이 하나라도 있는가. {@code parse_source='resolver_rule'} 과 항상 같은 값이지만 계약 문구가 「저장된
     * 적용 기록의 boolean」이므로 기록 쪽을 읽는다. 둘이 어긋나면 저장(S15P21A501-59) 버그다.
     */
    private static boolean hasAppliedRule(String appliedRulesJson) {
        for (JsonNode rule : arrayOrEmpty(appliedRulesJson)) {
            if ("applied".equals(rule.path("status").asString(null))) {
                return true;
            }
        }
        return false;
    }

    /**
     * 제외 건수와 사유. 출처가 <b>두 컬럼</b>이다. 살아남은 장면만 남는 {@code search_result} 로는 알 수 없다.
     *
     * <ul>
     *   <li>{@code filtered_json.guard.verdicts} — false-hit guard 판정. 통과한 장면의 판정도 함께 들어오므로
     *       {@code exclusion_reason} 이 있는 것만 센다. {@code GuardExclusionReason} 은
     *       {@code explicit_date_conflict}·{@code approved_incident_conflict} 둘뿐이다.
     *   <li>{@code applied_excludes_json} — 승인된 장면 제외(S15P21A501-58). 계약이 허용하는 세 번째 사유
     *       {@code approved_scene_exclusion} 은 guard 가 내는 값이 아니라 여기서만 온다. 이 컬럼을 빼면 그 사유가
     *       영원히 나오지 않고 건수가 {@code POST /search} 응답보다 작아진다.
     * </ul>
     *
     * <p>같은 장면이 양쪽에 걸리면 한 번만 센다 — 제외된 <b>결과 수</b>이므로 장면 기준으로 센다.
     */
    private static ObjectNode guardSummary(JsonNode filtered, String appliedExcludesJson) {
        Set<String> reasons = new LinkedHashSet<>();
        Set<Long> excludedScenes = new LinkedHashSet<>();
        for (JsonNode verdict : arrayOf(filtered.path("guard").get("verdicts"))) {
            String reason = verdict.path("exclusion_reason").asString(null);
            if (reason != null) {
                excludedScenes.add(verdict.path("scene_id").asLong());
                reasons.add(reason);
            }
        }
        ArrayNode appliedExcludes = arrayOrEmpty(appliedExcludesJson);
        if (!appliedExcludes.isEmpty()) {
            reasons.add("approved_scene_exclusion");
            appliedExcludes.forEach(exclude -> excludedScenes.add(exclude.path("scene_id").asLong()));
        }
        ObjectNode summary = MAPPER.createObjectNode();
        summary.put("excluded_result_count", excludedScenes.size());
        ArrayNode reasonArray = summary.putArray("reasons");
        reasons.forEach(reasonArray::add);
        return summary;
    }

    private static ArrayNode arrayOrEmpty(String json) {
        JsonNode node = parseOrNull(json);
        return node == null ? MAPPER.createArrayNode() : arrayOf(node);
    }

    private static JsonNode parseOrNull(String json) {
        return json == null || json.isBlank() ? null : readTree(json);
    }

    private static ArrayNode arrayOf(JsonNode node) {
        return node instanceof ArrayNode array ? array : MAPPER.createArrayNode();
    }

    private static JsonNode readTree(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (JacksonException e) {
            throw new IllegalStateException("검색 기록 JSON 파싱 실패", e);
        }
    }
}
