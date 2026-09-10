package com.npick.search.infrastructure.persistence.mapper;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.npick.search.domain.model.ParseRule;
import com.npick.search.domain.model.ParseRuleOutcome;
import com.npick.search.domain.model.QueryResolution;
import com.npick.search.domain.policy.ParseRulePolicy;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code condition_json}·{@code patch_json} 형식이 문서에 적힌 그대로 동작하는지, 모르는 것을 거부하는지 확인한다.
 *
 * <p>첫 테스트가 {@link ParseRule} javadoc 의 JSON 예시를 그대로 읽어 엔진까지 통과시킨다. 형식 설명과 실제 동작이 갈리면 그 테스트가 깨진다.
 */
class ParseRuleJsonMapperTest {

    private static final String FACTORY = "○○공장";

    private final ParseRuleJsonMapper mapper = new ParseRuleJsonMapper();
    private final ParseRulePolicy policy = new ParseRulePolicy();

    private static final String CONDITION_JSON = """
            {
              "syntax_version": "parse-rule/v1",
              "resolution_schema_version": "query-resolver/v2",
              "all": [
                { "axis": "locations",      "op": "has_value", "value": "○○공장" },
                { "axis": "incident_names", "op": "is_empty" },
                { "axis": "intent",         "op": "equals",    "value": "scene_search" }
              ]
            }
            """;

    private static final String PATCH_JSON = """
            {
              "syntax_version": "parse-rule/v1",
              "operations": [
                { "op": "remove_item", "axis": "locations", "type": "location", "value": "○○공장" },
                { "op": "add_item",    "axis": "incident_names",
                  "value_from": { "axis": "locations", "type": "location", "value": "○○공장" } }
              ]
            }
            """;

    @Test
    @DisplayName("문서에 적힌 JSON 을 읽어 그대로 적용한다")
    void parsesTheDocumentedFormatAndAppliesIt() {
        ParseRule rule = mapper.toDomain(10L, CONDITION_JSON, PATCH_JSON);

        assertThat(rule.parseError()).isNull();
        assertThat(rule.condition().all()).hasSize(3);
        assertThat(rule.patch().operations()).hasSize(2);

        ParseRulePolicy.Result result = policy.apply(originalWithFactoryAsLocation(), List.of(rule));

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

    @Test
    @DisplayName("본문 스냅샷에 두 컬럼이 그대로 담긴다")
    void keepsBothColumnsInTheSnapshot() {
        ParseRule rule = mapper.toDomain(10L, CONDITION_JSON, PATCH_JSON);

        assertThat(rule.bodySnapshot())
                .contains("condition_json")
                .contains("patch_json")
                .contains("has_value");
    }

    @Test
    @DisplayName("모르는 축 이름은 규칙을 통째로 비호환으로 만든다")
    void rejectsUnknownAxis() {
        String condition = CONDITION_JSON.replace("\"axis\": \"locations\"", "\"axis\": \"places\"");

        ParseRule rule = mapper.toDomain(10L, condition, PATCH_JSON);

        assertThat(rule.parseError()).contains("모르는 axis: places");
    }

    @Test
    @DisplayName("모르는 연산 이름을 거부한다")
    void rejectsUnknownOperation() {
        String patch = PATCH_JSON.replace("\"op\": \"remove_item\"", "\"op\": \"replace\"");

        ParseRule rule = mapper.toDomain(10L, CONDITION_JSON, patch);

        assertThat(rule.parseError()).contains("모르는 연산 op: replace");
    }

    @Test
    @DisplayName("정규식·스크립트를 끼워 넣을 키가 없다")
    void hasNowhereToPutRegexOrScript() {
        // F-11 「임의 JSON 편집·스크립트·정규식·외부 호출은 지원하지 않는다」. 런타임 차단이 아니라 문법에 자리가 없다.
        for (String smuggled : List.of("\"regex\": \".*공장\"", "\"script\": \"drop()\"", "\"path\": \"/locations/0\"")) {
            String patch = PATCH_JSON.replace("\"op\": \"remove_item\",", "\"op\": \"remove_item\", " + smuggled + ",");

            ParseRule rule = mapper.toDomain(10L, CONDITION_JSON, patch);

            assertThat(rule.parseError()).as(smuggled).contains("모르는 키");
        }
    }

    @Test
    @DisplayName("두 컬럼의 문법 버전이 어긋나면 거부한다")
    void rejectsMismatchedSyntaxVersions() {
        String patch = PATCH_JSON.replace("parse-rule/v1", "parse-rule/v2");

        ParseRule rule = mapper.toDomain(10L, CONDITION_JSON, patch);

        assertThat(rule.parseError()).contains("syntax_version 이 condition_json 과 patch_json 에서 다르다");
    }

    @Test
    @DisplayName("날짜 표기가 틀리면 거부한다")
    void rejectsMalformedDates() {
        String patch = """
                {
                  "syntax_version": "parse-rule/v1",
                  "operations": [
                    { "op": "add_item", "axis": "date_windows", "type": "broadcast_date",
                      "start": "2026-13-99", "end_exclusive": "2026-02-01" }
                  ]
                }
                """;

        ParseRule rule = mapper.toDomain(10L, CONDITION_JSON, patch);

        assertThat(rule.parseError()).contains("YYYY-MM-DD");
    }

    @Test
    @DisplayName("JSON 자체가 깨졌거나 비었으면 거부한다")
    void rejectsBrokenOrEmptyJson() {
        assertThat(mapper.toDomain(10L, "{", PATCH_JSON).parseError()).contains("올바른 JSON");
        assertThat(mapper.toDomain(10L, null, PATCH_JSON).parseError()).contains("비어 있다");
        assertThat(mapper.toDomain(10L, "[]", PATCH_JSON).parseError()).contains("객체가 아니다");
    }

    private static QueryResolution originalWithFactoryAsLocation() {
        return new QueryResolution(
                "query-resolver/v2",
                QueryResolution.Intent.SCENE_SEARCH,
                List.of(),
                List.of(),
                List.of(),
                List.of(new QueryResolution.Location(
                        QueryResolution.LocationType.LOCATION,
                        FACTORY,
                        QueryResolution.Origin.EXPLICIT_QUERY,
                        new QueryResolution.QuerySpan(0, 4),
                        0.9)),
                List.of(),
                List.of(),
                0.8);
    }
}
