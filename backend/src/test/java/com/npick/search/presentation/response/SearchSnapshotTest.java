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

    // ---- P1: display·match 의 필수 필드 검증 (MR !126 리뷰) ----

    @Test
    @DisplayName("display 에 필수 표시 필드가 빠지면 unavailable 이다 — object 이기만 하면 통과시키지 않는다")
    void unavailableWhenDisplayLacksRequiredFields() {
        String noShotType = DISPLAY.replace("\"shot_type\": \"b_roll\",", "");

        SearchSnapshot snapshot =
                SearchSnapshot.from(record(FILTERED_OK, resultRow(1, explain(noShotType, MATCH))));

        assertThat(snapshot.snapshotStatus()).isEqualTo("unavailable");
        assertThat(snapshot.payload()).isNull();
    }

    @Test
    @DisplayName("display 가 빈 object 면 unavailable 이다")
    void unavailableWhenDisplayIsEmptyObject() {
        SearchSnapshot snapshot =
                SearchSnapshot.from(record(FILTERED_OK, resultRow(1, explain("\"display\": {}", MATCH))));

        assertThat(snapshot.snapshotStatus()).isEqualTo("unavailable");
    }

    @Test
    @DisplayName("날짜 블록에 verification_status 가 없으면 unavailable 이다")
    void unavailableWhenDateLacksVerificationStatus() {
        String brokenDate = DISPLAY.replace(
                "\"filmed_date\": {\"value\": null, \"verification_status\": \"unknown\"}",
                "\"filmed_date\": {\"value\": null}");

        SearchSnapshot snapshot =
                SearchSnapshot.from(record(FILTERED_OK, resultRow(1, explain(brokenDate, MATCH))));

        assertThat(snapshot.snapshotStatus()).isEqualTo("unavailable");
    }

    @Test
    @DisplayName("구간이 뒤집혀 있으면 unavailable 이다 — FE 가 그릴 수 없다")
    void unavailableWhenTimeRangeInverted() {
        String inverted = DISPLAY.replace("\"start_time_ms\": 42000", "\"start_time_ms\": 49000")
                .replace("\"end_time_ms\": 49000", "\"end_time_ms\": 42000");

        SearchSnapshot snapshot =
                SearchSnapshot.from(record(FILTERED_OK, resultRow(1, explain(inverted, MATCH))));

        assertThat(snapshot.snapshotStatus()).isEqualTo("unavailable");
    }

    @Test
    @DisplayName("match_evidence 가 비어 있으면 unavailable 이다 — 계약은 1개 이상을 요구한다")
    void unavailableWhenMatchEvidenceEmpty() {
        String noEvidence = "\"match\": {\"matched_keywords\": [], \"match_evidence\": []}";

        SearchSnapshot snapshot =
                SearchSnapshot.from(record(FILTERED_OK, resultRow(1, explain(DISPLAY, noEvidence))));

        assertThat(snapshot.snapshotStatus()).isEqualTo("unavailable");
    }

    @Test
    @DisplayName("match_evidence 항목에 source 가 없으면 unavailable 이다")
    void unavailableWhenEvidenceLacksSource() {
        String noSource = "\"match\": {\"matched_keywords\": [\"서울역\"], \"match_evidence\":"
                + " [{\"field\": \"ocr\", \"value\": \"서울역\", \"verification_status\": \"verified\"}]}";

        SearchSnapshot snapshot =
                SearchSnapshot.from(record(FILTERED_OK, resultRow(1, explain(DISPLAY, noSource))));

        assertThat(snapshot.snapshotStatus()).isEqualTo("unavailable");
    }

    @Test
    @DisplayName("match_evidence 의 value 는 null 이어도 available 이다 — 근거가 실제로 없을 수 있다")
    void allowsNullEvidenceValue() {
        String nullValue = "\"match\": {\"matched_keywords\": [], \"match_evidence\":"
                + " [{\"field\": \"tag\", \"value\": null, \"source\": \"dense_similarity\","
                + " \"verification_status\": \"unverified\"}]}";

        SearchSnapshot snapshot =
                SearchSnapshot.from(record(FILTERED_OK, resultRow(1, explain(DISPLAY, nullValue))));

        assertThat(snapshot.snapshotStatus()).isEqualTo("available");
        assertThat(snapshot.payload()
                        .get("results")
                        .get(0)
                        .get("match_evidence")
                        .get(0)
                        .get("value")
                        .isNull())
                .isTrue();
    }

    // ---- P1 후속: 스칼라 필드의 타입 검증 (MR !126 리뷰 2차) ----

    @Test
    @DisplayName("shot_type 이 문자열이 아니면 unavailable 이다 — 키만 있으면 통과시키지 않는다")
    void unavailableWhenShotTypeNotString() {
        String broken = DISPLAY.replace("\"shot_type\": \"b_roll\"", "\"shot_type\": {}");

        SearchSnapshot snapshot = SearchSnapshot.from(record(FILTERED_OK, resultRow(1, explain(broken, MATCH))));

        assertThat(snapshot.snapshotStatus()).isEqualTo("unavailable");
    }

    @Test
    @DisplayName("display_name 이 문자열도 null 도 아니면 unavailable 이다")
    void unavailableWhenDisplayNameNotStringOrNull() {
        String broken = DISPLAY.replace("\"예시 뉴스 · 서울역\"", "[\"제목\"]");

        SearchSnapshot snapshot = SearchSnapshot.from(record(FILTERED_OK, resultRow(1, explain(broken, MATCH))));

        assertThat(snapshot.snapshotStatus()).isEqualTo("unavailable");
    }

    @Test
    @DisplayName("날짜 value 가 배열이면 unavailable 이다")
    void unavailableWhenDateValueNotStringOrNull() {
        String broken = DISPLAY.replace(
                "\"broadcast_date\": {\"value\": \"2026-09-14\"", "\"broadcast_date\": {\"value\": []");

        SearchSnapshot snapshot = SearchSnapshot.from(record(FILTERED_OK, resultRow(1, explain(broken, MATCH))));

        assertThat(snapshot.snapshotStatus()).isEqualTo("unavailable");
    }

    @Test
    @DisplayName("날짜 verification_status 가 문자열이 아니면 unavailable 이다")
    void unavailableWhenVerificationStatusNotString() {
        String broken = DISPLAY.replace("\"verification_status\": \"unknown\"", "\"verification_status\": 3");

        SearchSnapshot snapshot = SearchSnapshot.from(record(FILTERED_OK, resultRow(1, explain(broken, MATCH))));

        assertThat(snapshot.snapshotStatus()).isEqualTo("unavailable");
    }

    @Test
    @DisplayName("match_evidence 의 field 가 숫자면 unavailable 이다")
    void unavailableWhenEvidenceFieldNotString() {
        String broken = MATCH.replace("\"field\": \"ocr\"", "\"field\": 7");

        SearchSnapshot snapshot = SearchSnapshot.from(record(FILTERED_OK, resultRow(1, explain(DISPLAY, broken))));

        assertThat(snapshot.snapshotStatus()).isEqualTo("unavailable");
    }

    @Test
    @DisplayName("match_evidence 의 source 가 문자열이 아니면 unavailable 이다")
    void unavailableWhenEvidenceSourceNotString() {
        String broken = MATCH.replace("\"source\": \"keyframe_ocr\"", "\"source\": {}");

        SearchSnapshot snapshot = SearchSnapshot.from(record(FILTERED_OK, resultRow(1, explain(DISPLAY, broken))));

        assertThat(snapshot.snapshotStatus()).isEqualTo("unavailable");
    }

    @Test
    @DisplayName("matched_keywords 원소가 문자열이 아니면 unavailable 이다")
    void unavailableWhenKeywordNotString() {
        String broken = MATCH.replace("[\"서울역\"]", "[\"서울역\", 5]");

        SearchSnapshot snapshot = SearchSnapshot.from(record(FILTERED_OK, resultRow(1, explain(DISPLAY, broken))));

        assertThat(snapshot.snapshotStatus()).isEqualTo("unavailable");
    }

    @Test
    @DisplayName("start_time_ms 가 문자열이면 unavailable 이다")
    void unavailableWhenTimeNotInteger() {
        String broken = DISPLAY.replace("\"start_time_ms\": 42000", "\"start_time_ms\": \"42000\"");

        SearchSnapshot snapshot = SearchSnapshot.from(record(FILTERED_OK, resultRow(1, explain(broken, MATCH))));

        assertThat(snapshot.snapshotStatus()).isEqualTo("unavailable");
    }

    // ---- P2-2: rank 연속성과 개수 상한 ----

    @Test
    @DisplayName("rank 가 1 부터 시작하지 않으면 unavailable 이다")
    void unavailableWhenRankDoesNotStartAtOne() {
        SearchSnapshot snapshot = SearchSnapshot.from(record(
                FILTERED_OK, resultRow(2, explain(DISPLAY, MATCH)), resultRow(3, explain(DISPLAY, MATCH))));

        assertThat(snapshot.snapshotStatus()).isEqualTo("unavailable");
        assertThat(snapshot.resultCount()).isNull();
        assertThat(snapshot.representativeResult()).isNull();
    }

    @Test
    @DisplayName("rank 에 빈칸이 있으면 unavailable 이다")
    void unavailableWhenRankHasGap() {
        SearchSnapshot snapshot = SearchSnapshot.from(record(
                FILTERED_OK, resultRow(1, explain(DISPLAY, MATCH)), resultRow(3, explain(DISPLAY, MATCH))));

        assertThat(snapshot.snapshotStatus()).isEqualTo("unavailable");
    }

    @Test
    @DisplayName("결과가 10개를 넘으면 unavailable 이다 — 계약 상한은 10 이다")
    void unavailableWhenMoreThanTenResults() {
        SearchHistoryResultRow[] rows = new SearchHistoryResultRow[11];
        for (int rank = 1; rank <= 11; rank++) {
            rows[rank - 1] = resultRow(rank, explain(DISPLAY, MATCH));
        }

        SearchSnapshot snapshot = SearchSnapshot.from(record(FILTERED_OK, rows));

        assertThat(snapshot.snapshotStatus()).isEqualTo("unavailable");
    }

    @Test
    @DisplayName("rank 1..N 이 연속이면 available 이고 대표 결과는 1위다")
    void availableWhenRanksAreConsecutive() {
        SearchSnapshot snapshot = SearchSnapshot.from(record(
                FILTERED_OK, resultRow(1, explain(DISPLAY, MATCH)), resultRow(2, explain(DISPLAY, MATCH))));

        assertThat(snapshot.snapshotStatus()).isEqualTo("available");
        assertThat(snapshot.resultCount()).isEqualTo(2);
        assertThat(snapshot.representativeResult().get("rank").asInt()).isEqualTo(1);
    }

    // ---- P2-1: explicit_filters 형태 검증이 판정에 들어간다 ----

    @Test
    @DisplayName("명시 필터가 object 가 아니면 explicit_filters 는 null 이고 unavailable 이다")
    void unavailableWhenExplicitFiltersNotObject() {
        SearchSnapshot snapshot = SearchSnapshot.from(new SearchHistoryRecord(
                item(FILTERED_OK, "resolver", "[]", "[]", "[]"), List.of()));

        assertThat(snapshot.explicitFilters()).isNull();
        assertThat(snapshot.snapshotStatus()).isEqualTo("unavailable");
    }

    @Test
    @DisplayName("명시 필터가 없으면(기록 누락) explicit_filters 는 null 이고 unavailable 이다")
    void unavailableWhenExplicitFiltersMissing() {
        SearchSnapshot snapshot = SearchSnapshot.from(
                new SearchHistoryRecord(item(FILTERED_OK, "resolver", "[]", "[]", null), List.of()));

        assertThat(snapshot.explicitFilters()).isNull();
        assertThat(snapshot.snapshotStatus()).isEqualTo("unavailable");
    }

    @Test
    @DisplayName("날짜 필터에 to 가 빠지면 손상이므로 unavailable 이다")
    void unavailableWhenDateFilterHalfOpen() {
        String halfOpen = "{\"broadcast_date\": {\"from\": \"2026-09-01\"}}";

        SearchSnapshot snapshot = SearchSnapshot.from(
                new SearchHistoryRecord(item(FILTERED_OK, "resolver", "[]", "[]", halfOpen), List.of()));

        assertThat(snapshot.explicitFilters()).isNull();
        assertThat(snapshot.snapshotStatus()).isEqualTo("unavailable");
    }

    @Test
    @DisplayName("날짜 필터의 from 이 to 보다 늦으면 unavailable 이다")
    void unavailableWhenDateFilterInverted() {
        String inverted = "{\"broadcast_date\": {\"from\": \"2026-09-15\", \"to\": \"2026-09-01\"}}";

        SearchSnapshot snapshot = SearchSnapshot.from(
                new SearchHistoryRecord(item(FILTERED_OK, "resolver", "[]", "[]", inverted), List.of()));

        assertThat(snapshot.snapshotStatus()).isEqualTo("unavailable");
    }

    @Test
    @DisplayName("날짜가 달력에 없으면 unavailable 이다")
    void unavailableWhenDateFilterNotACalendarDate() {
        String bogus = "{\"filmed_date\": {\"from\": \"2026-02-30\", \"to\": \"2026-03-01\"}}";

        SearchSnapshot snapshot = SearchSnapshot.from(
                new SearchHistoryRecord(item(FILTERED_OK, "resolver", "[]", "[]", bogus), List.of()));

        assertThat(snapshot.snapshotStatus()).isEqualTo("unavailable");
    }

    @Test
    @DisplayName("필터 미선택은 빈 object 이며 available 을 막지 않는다")
    void emptyFilterObjectIsValid() {
        SearchSnapshot snapshot = SearchSnapshot.from(
                new SearchHistoryRecord(item(FILTERED_OK, "resolver", "[]", "[]", "{}"), List.of()));

        assertThat(snapshot.explicitFilters().isObject()).isTrue();
        assertThat(snapshot.explicitFilters()).isEmpty();
        assertThat(snapshot.snapshotStatus()).isEqualTo("available");
    }

    @Test
    @DisplayName("두 날짜 필터가 모두 온전하면 그대로 통과시킨다")
    void keepsBothDateFilters() {
        String both = "{\"broadcast_date\": {\"from\": \"2026-09-01\", \"to\": \"2026-09-15\"},"
                + " \"filmed_date\": {\"from\": \"2026-08-28\", \"to\": \"2026-08-29\"}}";

        SearchSnapshot snapshot = SearchSnapshot.from(
                new SearchHistoryRecord(item(FILTERED_OK, "resolver", "[]", "[]", both), List.of()));

        assertThat(snapshot.snapshotStatus()).isEqualTo("available");
        assertThat(snapshot.explicitFilters().get("broadcast_date").get("to").asString())
                .isEqualTo("2026-09-15");
        assertThat(snapshot.explicitFilters().get("filmed_date").get("from").asString())
                .isEqualTo("2026-08-28");
    }

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
    @DisplayName("표시 값이 null 이어도 대표 결과는 key 를 생략하지 않고 null 로 낸다")
    void representativeKeepsNullValuesAsExplicitNull() {
        // 키는 전부 있고 값만 null 인 경우다 — 키 자체가 없으면 P1 규칙대로 unavailable 이다.
        String nullable = DISPLAY.replace("\"대합실 인파\"", "null").replace("\"역사 인파\"", "null");

        SearchSnapshot snapshot = SearchSnapshot.from(record(FILTERED_OK, resultRow(1, explain(nullable, MATCH))));

        assertThat(snapshot.snapshotStatus()).isEqualTo("available");
        assertThat(snapshot.representativeResult().has("scene_description")).isTrue();
        assertThat(snapshot.representativeResult().get("scene_description").isNull()).isTrue();
        assertThat(snapshot.payload().get("results").get(0).get("scene_type").isNull())
                .isTrue();
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
        return item(filteredJson, parseSource, appliedRulesJson, appliedExcludesJson, "{}");
    }

    private static SearchHistoryItem item(
            String filteredJson,
            String parseSource,
            String appliedRulesJson,
            String appliedExcludesJson,
            String explicitFiltersJson) {
        return new SearchHistoryItem(
                9701L,
                "서울역 귀성 인파",
                explicitFiltersJson,
                Instant.parse("2026-09-15T03:00:00Z"),
                "succeeded",
                "[]",
                parseSource,
                appliedRulesJson,
                appliedExcludesJson,
                filteredJson);
    }
}
