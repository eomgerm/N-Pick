package com.npick.search.presentation.response;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
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
 * 요구하므로, 판정을 두 곳에 두면 규칙이 갈라진다. 목록이 결과 전량을 싣고 다니는 이유도 이것이다. {@code explicit_filters}
 * 도 여기서 함께 낸다 — 형태 검증 결과가 판정에 들어가야 하므로 별도 경로로 빼면 다시 갈라진다(MR !126 리뷰 P2).
 *
 * <p>복원은 저장된 JSON 을 <b>그대로 통과</b>시킨다. 필드별로 DTO 를 세워 옮겨 담지 않는다 — 현재 태그·검색으로 재계산하지
 * 않는다는 FRD §7.2 를 구조로 보장한다.
 *
 * <p>다만 <b>통과와 무검증은 다르다.</b> 값을 고치지는 않되, FE 가 카드를 그릴 수 있는 형태인지는 확인한다. object 인지만
 * 보고 통과시키면 빈 블록이 {@code available} 로 나가 FE 가 해석할 수 없는 {@code results} 를 받는다(MR !126 리뷰 P1).
 * 저장 경계(S15P21A501-60)가 내부 구조를 검증하지 않으므로 읽는 쪽이 판정한다.
 *
 * <p>검증하지 <b>않는</b> 것: 닫힌 어휘의 소속. {@code shot_type} 이 4값 밖이어도 그대로 낸다 — §5.1 의 어휘 제약은
 * {@code POST /search} 응답에만 걸리고, 기록을 소급 수정하지 않는다(FRD §7.2). 키의 존재와 FE 가 깨지는 불변식만 본다.
 */
public record SearchSnapshot(
        String snapshotStatus,
        String status,
        JsonNode explicitFilters,
        Integer resultCount,
        JsonNode representativeResult,
        JsonNode payload) {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * §5.1 이 {@code degraded_reasons} 로 허용하는 공개 어휘. 저장 컬럼과 같지 않다.
     *
     * <p>{@code degraded_reasons_json} 에는 규칙 판정이 {@code "skipped_conflict:<rule_id>"} 처럼 규칙 ID 를 붙인
     * 문자열로도 들어간다({@code JdbcSearchExecutionRecordAdapter#degraded}). 그 값을 그대로 내면 닫힌 enum 자리에
     * 어휘 밖 문자열이 나가 FE 파서가 깨진다.
     */
    private static final Set<String> PUBLIC_DEGRADED_REASONS =
            Set.of("resolver_fallback", "dense_unavailable", "snapshot_save_failed");

    /** 계약이 정한 결과 개수 상한. */
    private static final int MAX_RESULTS = 10;

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

    /** {@code display} 의 표시 문자열 중 값이 null 일 수 있는 것. 제목·설명 없는 클립이 실제로 있다. */
    private static final List<String> NULLABLE_DISPLAY_STRINGS =
            List.of("display_name", "scene_description", "scene_type");

    /** {@code display} 의 표시 문자열 중 항상 값이 있어야 하는 것. */
    private static final List<String> REQUIRED_DISPLAY_STRINGS = List.of("shot_type");

    /** {@code match_evidence} 한 항목에서 항상 값이 있어야 하는 문자열. {@code value} 는 따로 본다. */
    private static final List<String> REQUIRED_EVIDENCE_STRINGS =
            List.of("field", "source", "verification_status");

    /** baseline {@code parse_source} 주석이 정한 저장 어휘. */
    private static final Set<String> SCHEMA_PARSE_SOURCES = Set.of("resolver", "resolver_rule", "fallback");

    /** §5.1 이 정한 {@code shot_type} 어휘. */
    private static final Set<String> SHOT_TYPES = Set.of("anchor", "interview", "b_roll", "unknown");

    /** §5.1 이 정한 {@code match_evidence[].field} 어휘. */
    private static final Set<String> EVIDENCE_FIELDS = Set.of("caption", "ocr", "transcript", "tag");

    /** {@code match_evidence[].verification_status} 어휘. 날짜의 {@code unknown} 은 여기 없다. */
    private static final Set<String> EVIDENCE_VERIFICATIONS = Set.of("verified", "unverified");

    /** 명시 필터에서 선택 가능한 날짜 종류. 선택했으면 from·to 가 모두 필수다. */
    private static final List<String> FILTER_DATE_KEYS = List.of("broadcast_date", "filmed_date");

    private static SearchSnapshot unavailable(String status, JsonNode explicitFilters) {
        return new SearchSnapshot("unavailable", status, explicitFilters, null, null, null);
    }

    public static SearchSnapshot from(SearchHistoryRecord record) {
        SearchHistoryItem item = record.item();
        ArrayNode degradedReasons = publicDegradedReasons(item.degradedReasonsJson());
        String status = publicStatus(degradedReasons);
        // 필터 자체를 읽을 수 없으면 계약이 explicit_filters=null 과 unavailable 을 함께 요구한다.
        JsonNode explicitFilters = validExplicitFilters(item.explicitFiltersJson());
        if (explicitFilters == null) {
            return unavailable(status, null);
        }
        JsonNode filtered = parseOrNull(item.filteredJson());
        String queryResolutionStatus = queryResolutionStatus(degradedReasons, item.parseSource());
        // jsonb 컬럼은 SQL NULL 뿐 아니라 JSON 리터럴 null 도 담을 수 있다 — CAST 하면 4글자 문자열 "null" 이
        // 되어 빈 값 검사를 통과한다. 둘 다 「결과가 확정되지 않았다」는 같은 뜻이므로 object 인지로 판정한다.
        if (filtered == null || !filtered.isObject() || queryResolutionStatus == null) {
            return unavailable(status, explicitFilters);
        }
        if (record.results().size() > MAX_RESULTS) {
            return unavailable(status, explicitFilters);
        }
        ArrayNode results = MAPPER.createArrayNode();
        for (SearchHistoryResultRow row : record.results()) {
            ObjectNode result = toResult(row);
            if (result == null) {
                return unavailable(status, explicitFilters);
            }
            results.add(result);
        }
        if (!ranksAreConsecutive(record.results())) {
            return unavailable(status, explicitFilters);
        }
        return new SearchSnapshot(
                "available",
                status,
                explicitFilters,
                results.size(),
                representativeOf(results),
                payload(item, status, degradedReasons, queryResolutionStatus, filtered, results));
    }

    /**
     * 실행 당시 명시 필터. 읽을 수 없거나 형태가 깨졌으면 null 이며 호출자가 unavailable 로 만든다.
     *
     * <p>{@code null} 을 빈 object 나 현재 검색 화면의 필터로 보충하지 않는다. 필터 미선택({@code {}})과 「필터를 확인할
     * 수 없다」는 다른 사실이다. 선택한 날짜 종류는 {@code from}·{@code to} 가 모두 있고 실제 달력 날짜이며
     * {@code from <= to} 여야 한다 — 한쪽 경계만 있는 과거 미지원 형식을 정상 필터로 내보내지 않는다.
     */
    private static JsonNode validExplicitFilters(String json) {
        JsonNode filters = parseOrNull(json);
        if (filters == null || !filters.isObject()) {
            return null;
        }
        for (String dateKey : FILTER_DATE_KEYS) {
            JsonNode range = filters.get(dateKey);
            if (range == null) {
                continue;
            }
            if (!range.isObject() || !isValidDateRange(range)) {
                return null;
            }
        }
        return filters;
    }

    private static boolean isValidDateRange(JsonNode range) {
        LocalDate from = parseDate(range.path("from").asString(null));
        LocalDate to = parseDate(range.path("to").asString(null));
        return from != null && to != null && !from.isAfter(to);
    }

    private static LocalDate parseDate(String value) {
        if (value == null) {
            return null;
        }
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /**
     * 저장된 기능 저하 사유 중 <b>공개 어휘에 속하는 것만</b> 추린다.
     *
     * <p>저장 컬럼에는 승인된 해석 규칙이 충돌·비호환·실패로 건너뛰어진 사실도
     * {@code "skipped_conflict:<rule_id>"} 형태로 들어간다. 그것은 검수 감사용 기록이고 §5.1 의 {@code degraded_reasons}
     * 는 세 값으로 닫힌 enum 이다. 걸러내지 않으면 FE 파서가 깨진다. 건너뜀 사실 자체는
     * {@code has_applied_review_rule} 과 {@code applied_rules_json} 에 남아 감사 경로
     * ({@code GET /search/executions/{id}}, S15P21A501-60)로 볼 수 있다.
     */
    private static ArrayNode publicDegradedReasons(String degradedReasonsJson) {
        ArrayNode publicReasons = MAPPER.createArrayNode();
        for (JsonNode reason : arrayOrEmpty(degradedReasonsJson)) {
            String value = reason.asString(null);
            if (value != null && PUBLIC_DEGRADED_REASONS.contains(value)) {
                publicReasons.add(value);
            }
        }
        return publicReasons;
    }

    /**
     * 응답이 낼 실행 상태. {@code search_execution.status} 를 그대로 쓰지 않는다.
     *
     * <p>규칙 건너뜀만으로 기록이 {@code degraded} 로 닫힌 실행을 {@code POST /search} 는 {@code succeeded} 로 낸다
     * (공개 사유가 없으므로). 저장값을 그대로 내면 <b>같은 검색이 화면마다 다른 상태로 보인다</b> — 검색 직후엔
     * {@code succeeded}, 기록으로 다시 보면 {@code degraded}. §6.7 이 {@code search_snapshot} 을 「§5 성공 data 와 같은
     * object」로 규정하므로 공개 사유에서 파생해 두 화면을 일치시킨다(S15P21A501-59 와 합의).
     *
     * <p>{@code failed} 는 조회 대상이 아니므로 여기 오지 않는다(범위 조건이 succeeded/degraded 만 읽는다).
     */
    private static String publicStatus(ArrayNode publicDegradedReasons) {
        return publicDegradedReasons.isEmpty() ? "succeeded" : "degraded";
    }

    /**
     * 해석이 완료됐는지 대체 검색으로 떨어졌는지. <b>공개 사유에서 파생한다.</b>
     *
     * <p>§5.1 이 「{@code query_resolution_status=fallback} 여부는 {@code resolver_fallback} 포함 여부와 일치한다」를
     * 요구한다. 두 값을 각자 다른 컬럼에서 뽑으면 그 불변식이 깨진다 — {@code parse_source='fallback'} 인데
     * {@code degraded_reasons} 가 비어 있는 응답이 나갔다(MR !126 리뷰 4차). 한 출처에서 파생하면 구조로 보장된다.
     *
     * <p>{@code parse_source} 는 <b>검증</b>에만 남긴다. NULL 이나 스키마 밖 값이면 「어떻게 해석했는지 기록이 없다」는
     * 뜻이라 {@code resolved} 로 접지 않고 null 을 돌려 unavailable 로 만든다. 저장값이 파생값과 어긋나도(예:
     * {@code fallback} 인데 {@code resolver_fallback} 이 없음) 기록이 깨진 것이므로 unavailable 이다 — 어느 한쪽을
     * 골라 내면 남은 한쪽이 말하는 사실을 지우게 된다.
     */
    private static String queryResolutionStatus(ArrayNode publicDegradedReasons, String parseSource) {
        // Set.of(...) 는 contains(null) 에 NPE 를 던진다. null 은 「기록 없음」이라 여기서 먼저 걸러낸다.
        if (parseSource == null || !SCHEMA_PARSE_SOURCES.contains(parseSource)) {
            return null;
        }
        boolean fellBack = contains(publicDegradedReasons, "resolver_fallback");
        if ("fallback".equals(parseSource) != fellBack) {
            return null;
        }
        return fellBack ? "fallback" : "resolved";
    }

    private static boolean contains(ArrayNode values, String value) {
        for (JsonNode candidate : values) {
            if (value.equals(candidate.asString(null))) {
                return true;
            }
        }
        return false;
    }

    /**
     * 저장된 rank 가 1 부터 연속인가. DB 제약은 양수와 실행 내 중복 없음만 보장한다.
     *
     * <p>rank 2 부터 저장된 기록을 available 로 내면 {@code result_count > 0} 인데 {@code representative_result} 가
     * null 인 응답이 나간다. 계약이 그 조합을 0건에만 쓰므로 FE 가 대표 결과의 존재를 건수로 판단할 수 없게 된다.
     */
    private static boolean ranksAreConsecutive(List<SearchHistoryResultRow> rows) {
        Set<Integer> ranks = new LinkedHashSet<>();
        rows.forEach(row -> ranks.add(row.rank()));
        if (ranks.size() != rows.size()) {
            return false;
        }
        for (int rank = 1; rank <= rows.size(); rank++) {
            if (!ranks.contains(rank)) {
                return false;
            }
        }
        return true;
    }

    /** 결과 한 행 = {@code display}·{@code match} 병합 + 컬럼 4개. 복원할 수 없는 형태면 null 이다. */
    private static ObjectNode toResult(SearchHistoryResultRow row) {
        if (row.explainJson() == null || row.explainJson().isBlank()) {
            return null;
        }
        JsonNode explain = parseOrNull(row.explainJson());
        if (explain == null || !explain.isObject()) {
            return null;
        }
        JsonNode display = explain.get("display");
        JsonNode match = explain.get("match");
        if (!(display instanceof ObjectNode displayObject) || !(match instanceof ObjectNode matchObject)) {
            return null;
        }
        if (!isRenderableDisplay(displayObject) || !isRenderableMatch(matchObject)) {
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

    /**
     * 카드를 그릴 수 있는 표시 블록인가. 키의 존재만으로는 부족하고 <b>타입</b>까지 본다.
     *
     * <p>키만 확인하면 {@code shot_type: {}} 이나 배열인 날짜 값이 available 로 나가 FE 에 그대로 전달된다(MR !126
     * 리뷰 2차). 값을 고치지는 않되, nullable 필드는 {@code null|string}, 나머지 표시 문자열은 string, 구간은 정수로
     * 확인한다.
     *
     * <p>{@code display_name}·{@code scene_description}·{@code scene_type} 의 값은 null 을 허용한다 — 제목 없는 클립이
     * 실제로 있고, 서버가 대체 문자열로 메우지 않는다. 날짜 블록은 {@code value}({@code null|string})와
     * {@code verification_status}(string)가 모두 있어야 한다. {@code value} 가 null 이면 「모른다」이고, 키 자체가
     * 없거나 타입이 어긋나면 「기록이 깨졌다」다.
     *
     * <p>어휘의 소속은 여전히 보지 않는다. {@code shot_type} 이 문자열이면 4값 밖이어도 통과시킨다 — §5.1 의 어휘
     * 제약은 {@code POST /search} 응답에만 걸리고 과거 결과를 소급 수정하지 않는다(FRD §7.2).
     */
    private static boolean isRenderableDisplay(ObjectNode display) {
        if (!NULLABLE_DISPLAY_STRINGS.stream().allMatch(key -> isNullableText(display.get(key)))) {
            return false;
        }
        if (!REQUIRED_DISPLAY_STRINGS.stream().allMatch(key -> isText(display.get(key)))) {
            return false;
        }
        if (!SHOT_TYPES.contains(display.get("shot_type").asString(null))) {
            return false;
        }
        JsonNode start = display.get("start_time_ms");
        JsonNode end = display.get("end_time_ms");
        if (start == null || end == null || !start.isIntegralNumber() || !end.isIntegralNumber()) {
            return false;
        }
        if (start.asLong() < 0 || start.asLong() >= end.asLong()) {
            return false;
        }
        return FILTER_DATE_KEYS.stream().allMatch(key -> hasDateShape(display.get(key)));
    }

    /**
     * 날짜 블록의 값 불변식. §5.1 — {@code value} 가 null 이면 {@code verification_status} 는 {@code unknown},
     * 실제 날짜가 있으면 {@code verified}·{@code unverified} 다. 「모른다」와 「확인했다」를 섞으면 사용자가 없던
     * 확인을 근거로 판단한다.
     */
    private static boolean hasDateShape(JsonNode date) {
        if (date == null || !date.isObject() || !isNullableText(date.get("value"))) {
            return false;
        }
        String status = date.path("verification_status").asString(null);
        String value = date.path("value").asString(null);
        if (value == null) {
            return "unknown".equals(status);
        }
        return parseDate(value) != null && EVIDENCE_VERIFICATIONS.contains(status);
    }

    /**
     * 근거 블록이 온전한가. {@code match_evidence} 는 1개 이상이고 각 항목의 네 키가 타입까지 맞아야 한다.
     *
     * <p>{@code value} 는 null 을 허용한다 — 설명·대사·화면 글자·태그가 모두 없고 의미 검색 유사도만으로 올라온 장면이
     * 있고, 그때 사람이 읽을 근거가 실제로 존재하지 않는다(S15P21A501-59 와 합의, §5.1). {@code field}·{@code source}
     * ·{@code verification_status} 는 항상 문자열이다. {@code matched_keywords} 는 문자열 배열이며 비어 있어도 된다.
     */
    private static boolean isRenderableMatch(ObjectNode match) {
        if (!(match.get("matched_keywords") instanceof ArrayNode keywords)) {
            return false;
        }
        for (JsonNode keyword : keywords) {
            if (!isText(keyword)) {
                return false;
            }
        }
        if (!(match.get("match_evidence") instanceof ArrayNode evidence) || evidence.isEmpty()) {
            return false;
        }
        for (JsonNode item : evidence) {
            if (!item.isObject() || !isNullableText(item.get("value"))) {
                return false;
            }
            if (!REQUIRED_EVIDENCE_STRINGS.stream().allMatch(key -> isText(item.get(key)))) {
                return false;
            }
            if (!EVIDENCE_FIELDS.contains(item.path("field").asString(null))
                    || !EVIDENCE_VERIFICATIONS.contains(item.path("verification_status").asString(null))) {
                return false;
            }
        }
        return true;
    }

    /**
     * 키가 있고 값이 <b>비어 있지 않은</b> 문자열인가.
     *
     * <p>키 부재·타입 불일치·빈 문자열을 같게 다룬다 — 셋 다 「기록이 깨졌다」다. 계약이 표시 문자열을
     * 「null 또는 비어 있지 않은 string」으로 정했으므로 빈 문자열은 유효한 과거 값이 아니다.
     */
    private static boolean isText(JsonNode node) {
        return node != null && node.isString() && !node.asString("").isBlank();
    }

    /** 키가 있고 값이 비어 있지 않은 문자열이거나 명시적 null 인가. 키 자체의 부재는 허용하지 않는다. */
    private static boolean isNullableText(JsonNode node) {
        return node != null && (node.isNull() || isText(node));
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
            SearchHistoryItem item,
            String status,
            ArrayNode degradedReasons,
            String queryResolutionStatus,
            JsonNode filtered,
            ArrayNode results) {
        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("search_execution_id", String.valueOf(item.searchExecutionId()));
        payload.put("status", status);
        payload.set("degraded_reasons", degradedReasons);
        payload.put("query_resolution_status", queryResolutionStatus);
        payload.put("has_applied_review_rule", hasAppliedRule(item.appliedRulesJson()));
        payload.set("guard_summary", guardSummary(filtered, item.appliedExcludesJson()));
        payload.set("shortage_reasons", arrayOf(filtered.get("shortage_reasons")));
        payload.set("results", results);
        return payload;
    }

    /**
     * 적용된 해석 규칙이 하나라도 있는가. 저장이 {@code parse_source='resolver_rule'} 을 같은 파생식(적용 기록에
     * {@code applied} 존재)으로 만들므로 둘은 어긋날 수 없다. 계약 문구가 「저장된 적용 기록의 boolean」이라 기록을 읽는다.
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
