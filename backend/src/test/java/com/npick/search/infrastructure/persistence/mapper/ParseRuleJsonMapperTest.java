package com.npick.search.infrastructure.persistence.mapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
    @DisplayName("ParseRule javadoc 의 예시가 그대로 통과한다")
    void theDocumentedExampleParsesAsWritten() throws Exception {
        // 정본 javadoc 과 이 테스트의 JSON 이 갈리면 예시를 그대로 베낀 규칙이 비호환으로 거부된다.
        String javadoc = Files.readString(
                Path.of("src/main/java/com/npick/search/domain/model/ParseRule.java"), StandardCharsets.UTF_8);
        String condition = extractJson(javadoc, "condition_json");
        String patch = extractJson(javadoc, "patch_json");

        ParseRule rule = mapper.toDomain(10L, condition, patch);

        assertThat(rule.parseError()).isNull();
        assertThat(rule.incompatibleReason(originalWithFactoryAsLocation())).isNull();
        ParseRulePolicy.Result result = policy.apply(originalWithFactoryAsLocation(), List.of(rule));
        assertThat(result.outcomes())
                .singleElement()
                .extracting(ParseRuleOutcome::status)
                .isEqualTo(ParseRuleOutcome.Status.APPLIED);
    }

    /** javadoc 의 {@code <pre>} 블록에서 이름 뒤에 오는 JSON 객체를 꺼낸다. 줄 앞의 {@code *} 는 지운다. */
    private static String extractJson(String javadoc, String name) {
        int start = javadoc.indexOf("{", javadoc.indexOf("\n * " + name));
        int depth = 0;
        for (int i = start; i < javadoc.length(); i++) {
            char c = javadoc.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}' && --depth == 0) {
                return javadoc.substring(start, i + 1).replaceAll("(?m)^\\s*\\*", "");
            }
        }
        throw new IllegalStateException(name + " 예시를 javadoc 에서 찾지 못했다");
    }

    @Test
    @DisplayName("본문 스냅샷이 두 컬럼을 중첩 객체로 담는다")
    void keepsBothColumnsInTheSnapshot() throws Exception {
        ParseRule rule = mapper.toDomain(10L, CONDITION_JSON, PATCH_JSON);

        // 문자열로 담기면 applied_rules_json 안에서 이스케이프된 JSON 이 되어 jsonb 연산자로 조회할 수 없다.
        com.fasterxml.jackson.databind.JsonNode snapshot =
                new com.fasterxml.jackson.databind.ObjectMapper().readTree(rule.bodySnapshot());
        assertThat(snapshot.get("condition_json").isObject()).isTrue();
        assertThat(snapshot.get("patch_json").isObject()).isTrue();
        assertThat(snapshot.at("/condition_json/all/0/op").asText()).isEqualTo("has_value");
        assertThat(snapshot.at("/patch_json/operations/0/op").asText()).isEqualTo("remove_item");
    }

    @Test
    @DisplayName("읽을 수 없는 원문은 문자열로라도 보존한다")
    void keepsUnreadableBodyAsText() throws Exception {
        ParseRule rule = mapper.toDomain(10L, "{망가진", PATCH_JSON);

        com.fasterxml.jackson.databind.JsonNode snapshot =
                new com.fasterxml.jackson.databind.ObjectMapper().readTree(rule.bodySnapshot());
        assertThat(snapshot.get("condition_json").isTextual()).isTrue();
        assertThat(snapshot.get("condition_json").asText()).isEqualTo("{망가진");
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
