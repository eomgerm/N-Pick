package com.npick.search.presentation.response;

import java.time.Instant;
import java.util.List;

import tools.jackson.databind.JsonNode;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.npick.search.application.query.SearchHistoryItem;
import com.npick.search.application.query.SearchHistoryRecord;
import com.npick.search.application.query.SearchHistoryResultRow;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 스냅샷 복원 가능 판정과 조립 (S15P21A501-198).
 *
 * <p>목록·상세가 이 클래스 하나만 쓰므로 여기 규칙이 곧 두 응답의 판정이다 — 계약의 「변하지 않은 기록의 목록/상세가
 * available 판정을 다르게 하지 않는다」를 구조로 보장한다.
 */
class SearchSnapshotTest {

    private static final String DISPLAY =
            """
            "display": {
              "display_name": "예시 뉴스 · 서울역",
              "scene_description": "대합실 인파",
              "start_time_ms": 42000,
              "end_time_ms": 49000,
              "shot_type": "b_roll",
              "scene_type": "역사 인파",
              "broadcast_date": {"value": "2026-09-14", "verification_status": "verified"},
              "filmed_date": {"value": null, "verification_status": "unknown"}
            }""";

    private static final String MATCH =
            """
            "match": {
              "matched_keywords": ["서울역"],
              "match_evidence": [
                {"field": "ocr", "value": "서울역", "source": "keyframe_ocr",
                 "verification_status": "verified"}
              ]
            }""";

    private static final String FILTERED_OK =
            """
            {"returned_count": 1, "shortage_reasons": ["candidate_pool_exhausted"],
             "guard": {"incident_guard_active": false, "verdicts": []}}""";

    @Test
    @DisplayName("filtered_json 이 없으면 결과 확정 전이므로 unavailable 이고 세 필드가 null 이다")
    void unavailableWhenFilteredJsonMissing() {
        SearchSnapshot snapshot = SearchSnapshot.from(record(null, resultRow(1, explain(DISPLAY, MATCH))));

        assertThat(snapshot.snapshotStatus()).isEqualTo("unavailable");
        assertThat(snapshot.resultCount()).isNull();
        assertThat(snapshot.representativeResult()).isNull();
        assertThat(snapshot.payload()).isNull();
    }

    @Test
    @DisplayName("filtered_json 이 JSON 리터럴 null 이면 unavailable 이다 — SQL NULL 과 같은 뜻이다")
    void unavailableWhenFilteredJsonIsJsonNull() {
        // CAST('null'::jsonb AS text) 는 4글자 문자열 "null" 이라 null·blank 검사를 통과한다.
        SearchSnapshot snapshot = SearchSnapshot.from(record("null", resultRow(1, explain(DISPLAY, MATCH))));

        assertThat(snapshot.snapshotStatus()).isEqualTo("unavailable");
        assertThat(snapshot.payload()).isNull();
    }

    @Test
    @DisplayName("filtered_json 이 object 가 아니면 unavailable 이다")
    void unavailableWhenFilteredJsonIsNotObject() {
        SearchSnapshot snapshot = SearchSnapshot.from(record("[]", resultRow(1, explain(DISPLAY, MATCH))));

        assertThat(snapshot.snapshotStatus()).isEqualTo("unavailable");
        assertThat(snapshot.payload()).isNull();
    }

    @Test
    @DisplayName("display 가 컬럼과 같은 key 를 담고 있어도 컬럼 값이 이긴다")
    void columnsWinOverStoredBlocks() {
        // 저장 블록이 컬럼을 덮으면 문자열 ID 규칙이 깨지고 rank 가 비정수면 대표 결과가 사라진다.
        String colliding = DISPLAY.replace(
                "\"display\": {", "\"display\": {\"scene_id\": 1, \"rank\": \"two\", \"clip_id\": 2,");

        SearchSnapshot snapshot = SearchSnapshot.from(record(FILTERED_OK, resultRow(1, explain(colliding, MATCH))));

        var result = snapshot.payload().get("results").get(0);
        assertThat(result.get("scene_id").asString()).isEqualTo("8301");
        assertThat(result.get("clip_id").asString()).isEqualTo("8101");
        assertThat(result.get("rank").asInt()).isEqualTo(1);
        assertThat(snapshot.representativeResult()).isNotNull();
        assertThat(snapshot.representativeResult().get("scene_id").asString()).isEqualTo("8301");
    }

    @Test
    @DisplayName("parse_source 가 없으면 해석 기록이 없는 것이므로 resolved 라고 단정하지 않는다")
    void unavailableWhenParseSourceMissing() {
        SearchHistoryItem item = item(FILTERED_OK, null, "[]");

        SearchSnapshot snapshot = SearchSnapshot.from(new SearchHistoryRecord(item, List.of()));

        assertThat(snapshot.snapshotStatus()).isEqualTo("unavailable");
        assertThat(snapshot.payload()).isNull();
    }

    @Test
    @DisplayName("parse_source 가 스키마에 없는 값이면 unavailable 이다 — 임의로 resolved 로 접지 않는다")
    void unavailableWhenParseSourceUnknown() {
        SearchHistoryItem item = item(FILTERED_OK, "something_new", "[]");

        SearchSnapshot snapshot = SearchSnapshot.from(new SearchHistoryRecord(item, List.of()));

        assertThat(snapshot.snapshotStatus()).isEqualTo("unavailable");
    }

    @Test
    @DisplayName("parse_source 가 fallback 이면 query_resolution_status 도 fallback 이다")
    void fallbackParseSourceMapsToFallback() {
        SearchHistoryItem item = item(FILTERED_OK, "fallback", "[]");

        SearchSnapshot snapshot = SearchSnapshot.from(new SearchHistoryRecord(item, List.of()));

        assertThat(snapshot.snapshotStatus()).isEqualTo("available");
        assertThat(snapshot.payload().get("query_resolution_status").asString()).isEqualTo("fallback");
    }

    @Test
    @DisplayName("결과가 0건이어도 filtered_json 이 있으면 정상 완료이므로 available 이다")
    void availableWithZeroResults() {
        SearchSnapshot snapshot = SearchSnapshot.from(record(FILTERED_OK));

        assertThat(snapshot.snapshotStatus()).isEqualTo("available");
        assertThat(snapshot.resultCount()).isZero();
        assertThat(snapshot.representativeResult()).isNull();
        assertThat(snapshot.payload().get("results")).isEmpty();
    }

    @Test
    @DisplayName("한 행이라도 display 가 없으면 복원 불가이므로 unavailable 이다")
    void unavailableWhenAnyRowLacksDisplay() {
        SearchSnapshot snapshot = SearchSnapshot.from(record(
                FILTERED_OK,
                resultRow(1, explain(DISPLAY, MATCH)),
                resultRow(2, "{\"score\": {}}")));

        assertThat(snapshot.snapshotStatus()).isEqualTo("unavailable");
        assertThat(snapshot.payload()).isNull();
    }

    @Test
    @DisplayName("results 한 항목은 display·match 병합에 컬럼 4개를 얹은 것이다")
    void resultMergesDisplayAndMatchWithColumns() {
        SearchSnapshot snapshot = SearchSnapshot.from(record(FILTERED_OK, resultRow(1, explain(DISPLAY, MATCH))));

        var result = snapshot.payload().get("results").get(0);
        assertThat(result.get("search_result_id").asString()).isEqualTo("8801");
        assertThat(result.get("scene_id").asString()).isEqualTo("8301");
        assertThat(result.get("clip_id").asString()).isEqualTo("8101");
        assertThat(result.get("rank").asInt()).isEqualTo(1);
        assertThat(result.get("display_name").asString()).isEqualTo("예시 뉴스 · 서울역");
        assertThat(result.get("scene_type").asString()).isEqualTo("역사 인파");
        assertThat(result.get("filmed_date").get("verification_status").asString()).isEqualTo("unknown");
        assertThat(result.get("matched_keywords").get(0).asString()).isEqualTo("서울역");
        assertThat(result.get("match_evidence").get(0).get("field").asString()).isEqualTo("ocr");
        assertThat(snapshot.representativeResult().get("display_name").asString())
                .isEqualTo("예시 뉴스 · 서울역");
    }

    @Test
    @DisplayName("display_name 이 null 이면 그대로 null 로 통과시키고 available 을 유지한다")
    void keepsNullDisplayName() {
        String noTitle = DISPLAY.replace("\"예시 뉴스 · 서울역\"", "null");

        SearchSnapshot snapshot = SearchSnapshot.from(record(FILTERED_OK, resultRow(1, explain(noTitle, MATCH))));

        assertThat(snapshot.snapshotStatus()).isEqualTo("available");
        assertThat(snapshot.payload().get("results").get(0).get("display_name").isNull()).isTrue();
        assertThat(snapshot.representativeResult().get("display_name").isNull()).isTrue();
    }

    @Test
    @DisplayName("표시 키가 아예 없어도 대표 결과는 key 를 생략하지 않고 null 로 낸다")
    void representativeKeepsMissingKeysAsNull() {
        String minimal = "\"display\": {\"display_name\": \"제목\"}";

        SearchSnapshot snapshot = SearchSnapshot.from(record(FILTERED_OK, resultRow(1, explain(minimal, MATCH))));

        assertThat(snapshot.representativeResult().has("scene_description")).isTrue();
        assertThat(snapshot.representativeResult().get("scene_description").isNull()).isTrue();
        assertThat(snapshot.representativeResult().get("start_time_ms").isNull()).isTrue();
    }

    @Test
    @DisplayName("resolver_rule 은 규칙이 적용된 정상 해석이므로 resolved 다")
    void resolverRuleCountsAsResolved() {
        SearchHistoryItem item = item(FILTERED_OK, "resolver_rule", "[{\"rule_id\":1,\"status\":\"applied\"}]");

        SearchSnapshot snapshot = SearchSnapshot.from(new SearchHistoryRecord(item, List.of()));

        assertThat(snapshot.payload().get("query_resolution_status").asString()).isEqualTo("resolved");
        assertThat(snapshot.payload().get("has_applied_review_rule").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("guard_summary 는 승인된 장면 제외도 센다 — guard 판정만 보면 approved_scene_exclusion 이 영원히 안 나온다")
    void guardSummaryIncludesApprovedSceneExclusions() {
        // GuardExclusionReason 에는 explicit_date_conflict·approved_incident_conflict 둘뿐이다.
        // 승인된 장면 제외(-58)는 filtered_json 이 아니라 applied_excludes_json 에 있다.
        String filtered =
                """
                {"returned_count": 1, "shortage_reasons": ["guard_excluded"],
                 "guard": {"incident_guard_active": false, "verdicts": [
                   {"scene_id": 5, "exclusion_reason": "explicit_date_conflict"}]}}""";
        String appliedExcludes = "[{\"scene_id\": 12, \"rule_ids\": [301, 305]}, {\"scene_id\": 13,"
                + " \"rule_ids\": [302]}]";

        SearchSnapshot snapshot = SearchSnapshot.from(
                new SearchHistoryRecord(item(filtered, "resolver", "[]", appliedExcludes), List.of()));

        var guard = snapshot.payload().get("guard_summary");
        assertThat(guard.get("excluded_result_count").asInt()).isEqualTo(3);
        assertThat(guard.get("reasons"))
                .extracting(JsonNode::asString)
                .containsExactly("explicit_date_conflict", "approved_scene_exclusion");
    }

    @Test
    @DisplayName("같은 장면이 guard 와 승인 제외에 모두 걸려도 제외 결과는 한 건이다")
    void guardSummaryCountsSceneOnce() {
        String filtered =
                """
                {"returned_count": 0, "shortage_reasons": ["guard_excluded"],
                 "guard": {"incident_guard_active": false, "verdicts": [
                   {"scene_id": 12, "exclusion_reason": "explicit_date_conflict"}]}}""";
        String appliedExcludes = "[{\"scene_id\": 12, \"rule_ids\": [301]}]";

        SearchSnapshot snapshot = SearchSnapshot.from(
                new SearchHistoryRecord(item(filtered, "resolver", "[]", appliedExcludes), List.of()));

        var guard = snapshot.payload().get("guard_summary");
        assertThat(guard.get("excluded_result_count").asInt()).isEqualTo(1);
        assertThat(guard.get("reasons"))
                .extracting(JsonNode::asString)
                .containsExactly("explicit_date_conflict", "approved_scene_exclusion");
    }

    @Test
    @DisplayName("승인 제외가 없으면 approved_scene_exclusion 을 내지 않는다")
    void guardSummaryOmitsApprovedReasonWhenNoExclusions() {
        SearchSnapshot snapshot = SearchSnapshot.from(record(FILTERED_OK));

        var guard = snapshot.payload().get("guard_summary");
        assertThat(guard.get("excluded_result_count").asInt()).isZero();
        assertThat(guard.get("reasons")).isEmpty();
    }

    @Test
    @DisplayName("guard_summary 는 제외된 판정만 센다 — 통과 판정은 제외 건수에 넣지 않는다")
    void guardSummaryCountsOnlyExcludedVerdicts() {
        String filtered =
                """
                {"returned_count": 1, "shortage_reasons": ["guard_excluded"],
                 "guard": {"incident_guard_active": false, "verdicts": [
                   {"scene_id": 1, "exclusion_reason": null},
                   {"scene_id": 2, "exclusion_reason": "explicit_date_conflict"},
                   {"scene_id": 3, "exclusion_reason": "explicit_date_conflict"}]}}""";

        SearchSnapshot snapshot = SearchSnapshot.from(record(filtered));

        var guard = snapshot.payload().get("guard_summary");
        assertThat(guard.get("excluded_result_count").asInt()).isEqualTo(2);
        assertThat(guard.get("reasons")).singleElement().satisfies(reason ->
                assertThat(reason.asString()).isEqualTo("explicit_date_conflict"));
        assertThat(snapshot.payload().get("shortage_reasons").get(0).asString()).isEqualTo("guard_excluded");
    }

    private static String explain(String... blocks) {
        return "{" + String.join(",", blocks) + "}";
    }

    private static SearchHistoryResultRow resultRow(int rank, String explainJson) {
        return new SearchHistoryResultRow(8800L + rank, 8300L + rank, 8101L, rank, explainJson);
    }

    private static SearchHistoryRecord record(String filteredJson, SearchHistoryResultRow... rows) {
        return new SearchHistoryRecord(item(filteredJson, "resolver", "[]"), List.of(rows));
    }

    private static SearchHistoryItem item(String filteredJson, String parseSource, String appliedRulesJson) {
        return item(filteredJson, parseSource, appliedRulesJson, "[]");
    }

    private static SearchHistoryItem item(
            String filteredJson, String parseSource, String appliedRulesJson, String appliedExcludesJson) {
        return new SearchHistoryItem(
                9701L,
                "서울역 귀성 인파",
                "{}",
                Instant.parse("2026-09-15T03:00:00Z"),
                "succeeded",
                "[]",
                parseSource,
                appliedRulesJson,
                appliedExcludesJson,
                filteredJson);
    }
}
