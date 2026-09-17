package com.npick.search.infrastructure.persistence.repository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import com.npick.common.persistence.TsidGenerator;
import com.npick.search.application.port.CompleteSearchExecution;
import com.npick.search.application.port.SearchExecutionRecordPort;
import com.npick.search.application.port.SearchRecordingException;
import com.npick.search.application.port.StartSearchExecution;
import com.npick.search.application.resolution.SearchDegradedReason;
import com.npick.search.domain.model.ParseRuleOutcome;
import com.npick.search.domain.model.QueryResolution;

/** 검색 자체의 성공/롤백과 실행 기록의 성공/실패를 분리하는 JDBC 어댑터. */
@Repository
public class JdbcSearchExecutionRecordAdapter implements SearchExecutionRecordPort {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public JdbcSearchExecutionRecordAdapter(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public long start(StartSearchExecution command) {
        long id = TsidGenerator.generate();
        try {
            jdbc.update(
                    """
                    INSERT INTO npick.search_execution (
                        search_execution_id, searched_by_id, query_text, normalized_query,
                        explicit_filters_json, normalized_filters_json, query_fingerprint,
                        normalization_version, execution_type, replay_of_feedback_id, status,
                        degraded_reasons_json, parse_source, parser_version, parse_ms,
                        applied_excludes_json, search_config_json, config_version,
                        resolver_output_json, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?, ?, ?, 'running', ?::jsonb, ?, ?, ?,
                            '[]'::jsonb, '{}'::jsonb, 'pending', ?::jsonb, now(), now())
                    """,
                    id,
                    command.searchedById(),
                    command.rawQuery(),
                    command.normalizedSearch().normalizedQuery(),
                    json(explicitFilters(command)),
                    json(command.normalizedSearch().normalizedFilters()),
                    command.normalizedSearch().fingerprint(),
                    command.normalizedSearch().normalizationVersion(),
                    command.executionType().databaseValue(),
                    command.replayOfFeedbackId(),
                    json(degraded(command.degradedReasons(), List.of())),
                    command.parseSource().databaseValue(),
                    parserVersion(command.resolverOutput()),
                    command.parseMs(),
                    nullableJson(resolverOutput(command)));
            return id;
        } catch (RuntimeException failure) {
            throw recordingFailure("검색 실행 시작 기록을 저장하지 못했다", failure);
        }
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<Long> complete(CompleteSearchExecution command) {
        validateResults(command.rankedScenes());
        rejectSensitiveFields(command.candidates() == null ? null : command.candidates().payload());
        rejectSensitiveFields(command.filtered() == null ? null : command.filtered().payload());
        command.rankedScenes().forEach(scene -> rejectSensitiveFields(scene.explain()));
        rejectSensitiveFields(command.verificationContext());
        try {
            String executionType = jdbc.queryForObject(
                    "SELECT execution_type FROM npick.search_execution WHERE search_execution_id=? FOR UPDATE",
                    String.class,
                    command.searchExecutionId());
            if (executionType == null) {
                throw new SearchRecordingException("시작되지 않은 검색 실행이다");
            }
            if (command.verificationContext() != null && !"replay".equals(executionType)) {
                throw new SearchRecordingException("검증 맥락은 replay 실행에만 저장할 수 있다");
            }
            String parseSource =
                    command.appliedRules().stream().anyMatch(rule -> rule.status() == ParseRuleOutcome.Status.APPLIED)
                            ? "resolver_rule"
                            : null;
            int updated = jdbc.update(
                    """
                    UPDATE npick.search_execution
                       SET status=?, degraded_reasons_json=?::jsonb, error_code=?,
                           parsed_query_json=?::jsonb, applied_rules_json=?::jsonb,
                           candidates_json=?::jsonb, filtered_json=?::jsonb,
                           applied_excludes_json=?::jsonb, search_config_json=?::jsonb,
                           config_version=?, execution_ms=?, verification_context_json=?::jsonb,
                           parse_source=COALESCE(?, parse_source), updated_at=now()
                     WHERE search_execution_id=? AND status='running'
                    """,
                    command.status().databaseValue(),
                    json(degraded(command.degradedReasons(), command.appliedRules())),
                    command.errorCode(),
                    nullableJson(resolution(command.finalResolution())),
                    json(rules(command.appliedRules())),
                    nullableJson(
                            command.candidates() == null
                                    ? null
                                    : command.candidates().payload()),
                    nullableJson(
                            command.filtered() == null
                                    ? null
                                    : command.filtered().payload()),
                    json(excludes(command.appliedExcludes())),
                    json(command.config().payload()),
                    command.config().version(),
                    command.executionMs(),
                    nullableJson(command.verificationContext()),
                    parseSource,
                    command.searchExecutionId());
            if (updated != 1) {
                throw new SearchRecordingException("검색 실행은 한 번만 완결할 수 있다");
            }
            List<Long> resultIds = new ArrayList<>();
            for (CompleteSearchExecution.RankedScene scene : command.rankedScenes()) {
                long resultId = TsidGenerator.generate();
                jdbc.update(
                        """
                        INSERT INTO npick.search_result
                            (search_result_id, search_execution_id, scene_id, result_rank, explain_json)
                        VALUES (?, ?, ?, ?, ?::jsonb)
                        """,
                        resultId,
                        command.searchExecutionId(),
                        scene.sceneId(),
                        scene.rank(),
                        json(scene.explain()));
                resultIds.add(resultId);
            }
            return List.copyOf(resultIds);
        } catch (SearchRecordingException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw recordingFailure("검색 실행 완료 기록을 저장하지 못했다", failure);
        }
    }

    private Map<String, Object> explicitFilters(StartSearchExecution command) {
        var value = new LinkedHashMap<String, Object>();
        command.explicitFilters()
                .ranges()
                .forEach((field, range) -> value.put(
                        field.name().toLowerCase(java.util.Locale.ROOT),
                        Map.of("from", range.from(), "to", range.to())));
        return value;
    }

    private Map<String, Object> resolverOutput(StartSearchExecution command) {
        if (command.resolverOutput() == null) return null;
        var versions = new LinkedHashMap<String, Object>();
        versions.put("schema", command.resolverOutput().resolutionSchemaVersion());
        versions.put("prompt", command.resolverOutput().promptVersion());
        versions.put("model", command.resolverOutput().modelVersion());
        var value = new LinkedHashMap<String, Object>();
        value.put("raw", resolution(command.resolverOutput().rawResolution()));
        value.put("verified", resolution(command.resolverOutput().verifiedResolution()));
        value.put("findings", command.findings());
        value.put("versions", versions);
        return value;
    }

    private static String parserVersion(StartSearchExecution.ResolverOutput output) {
        if (output == null) return null;
        String joined = String.join(
                "|", safe(output.resolutionSchemaVersion()), safe(output.promptVersion()), safe(output.modelVersion()));
        if (joined.length() <= 128) return joined;
        try {
            return "sha256:"
                    + java.util.HexFormat.of()
                            .formatHex(MessageDigest.getInstance("SHA-256")
                                    .digest(joined.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256을 사용할 수 없다", impossible);
        }
    }

    private static Map<String, Object> resolution(QueryResolution value) {
        if (value == null) return null;
        var result = new LinkedHashMap<String, Object>();
        result.put("schema_version", value.schemaVersion());
        result.put("intent", enumName(value.intent()));
        result.put(
                "date_windows",
                value.dateWindows().stream()
                        .map(window -> {
                            var item = new LinkedHashMap<String, Object>();
                            item.put("field", enumName(window.field()));
                            item.put("start", window.start());
                            item.put("end_exclusive", window.endExclusive());
                            item.put("origin", enumName(window.origin()));
                            item.put("query_span", span(window.querySpan()));
                            item.put("confidence", window.confidence());
                            return item;
                        })
                        .toList());
        result.put(
                "incident_names",
                value.incidentNames().stream()
                        .map(item -> sourced(item.value(), item.origin(), item.querySpan(), item.confidence()))
                        .toList());
        result.put(
                "entities",
                value.entities().stream()
                        .map(item ->
                                typed(item.type(), item.value(), item.origin(), item.querySpan(), item.confidence()))
                        .toList());
        result.put(
                "locations",
                value.locations().stream()
                        .map(item ->
                                typed(item.type(), item.value(), item.origin(), item.querySpan(), item.confidence()))
                        .toList());
        result.put(
                "classifications",
                value.classifications().stream()
                        .map(item ->
                                typed(item.type(), item.value(), item.origin(), item.querySpan(), item.confidence()))
                        .toList());
        result.put("expanded_terms", value.expandedTerms());
        result.put("confidence", value.confidence());
        return result;
    }

    private static Map<String, Object> sourced(
            String value, QueryResolution.Origin origin, QueryResolution.QuerySpan querySpan, double confidence) {
        var item = new LinkedHashMap<String, Object>();
        item.put("value", value);
        item.put("origin", enumName(origin));
        item.put("query_span", span(querySpan));
        item.put("confidence", confidence);
        return item;
    }

    private static Map<String, Object> typed(
            Enum<?> type,
            String value,
            QueryResolution.Origin origin,
            QueryResolution.QuerySpan querySpan,
            double confidence) {
        var item = new LinkedHashMap<>(sourced(value, origin, querySpan, confidence));
        item.put("type", enumName(type));
        return item;
    }

    private static Map<String, Object> span(QueryResolution.QuerySpan value) {
        return value == null ? null : Map.of("start", value.start(), "end", value.end());
    }

    private static String enumName(Enum<?> value) {
        return value == null ? null : value.name().toLowerCase(java.util.Locale.ROOT);
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static List<Map<String, Object>> rules(List<ParseRuleOutcome> outcomes) {
        return outcomes.stream()
                .map(outcome -> {
                    var value = new LinkedHashMap<String, Object>();
                    value.put("rule_id", Long.toString(outcome.ruleId()));
                    value.put("body", outcome.bodySnapshot());
                    value.put("status", outcome.status().jsonName());
                    value.put("applied_order", outcome.appliedOrder());
                    value.put("reason", outcome.reason());
                    value.put("conflict_group", outcome.conflictGroup());
                    return java.util.Collections.unmodifiableMap(value);
                })
                .toList();
    }

    private static List<Map<String, Object>> excludes(List<CompleteSearchExecution.AppliedSceneExclusion> values) {
        return values.stream()
                .map(value -> Map.<String, Object>of(
                        "scene_id", Long.toString(value.sceneId()),
                        "rule_ids",
                                value.ruleIds().stream().map(String::valueOf).toList()))
                .toList();
    }

    private static List<String> degraded(List<SearchDegradedReason> reasons, List<ParseRuleOutcome> ruleOutcomes) {
        var values = new ArrayList<String>();
        reasons.forEach(reason -> values.add(reason.jsonName()));
        ruleOutcomes.stream()
                .filter(outcome -> outcome.status().degraded())
                .map(outcome -> outcome.status().jsonName() + ":" + outcome.ruleId())
                .forEach(values::add);
        return List.copyOf(values);
    }

    private static void validateResults(List<CompleteSearchExecution.RankedScene> scenes) {
        var sceneIds = new HashSet<Long>();
        var ranks = new HashSet<Integer>();
        for (int index = 0; index < scenes.size(); index++) {
            CompleteSearchExecution.RankedScene scene = scenes.get(index);
            if (!sceneIds.add(scene.sceneId()) || !ranks.add(scene.rank())) {
                throw new SearchRecordingException("검색 결과 장면과 순위는 중복될 수 없다");
            }
            if (scene.rank() != index + 1) {
                throw new SearchRecordingException("검색 결과 순위는 목록 순서대로 1부터 연속이어야 한다");
            }
        }
    }

    private static void rejectSensitiveFields(Object value) {
        if (value instanceof Map<?, ?> map) {
            map.forEach((key, nested) -> {
                String name = String.valueOf(key).toLowerCase(java.util.Locale.ROOT);
                if (name.equals("file_path")
                        || name.equals("filesystem_path")
                        || name.equals("storage_path")
                        || name.equals("artifact_path")
                        || name.equals("storage_key")
                        || name.equals("raw_model_output")
                        || name.equals("model_raw_output")
                        || name.equals("prompt_body")) {
                    throw new SearchRecordingException("검색 snapshot에 경로 또는 원본 모델 출력은 저장할 수 없다");
                }
                rejectSensitiveFields(nested);
            });
        } else if (value instanceof Iterable<?> values) {
            values.forEach(JdbcSearchExecutionRecordAdapter::rejectSensitiveFields);
        }
    }

    private String nullableJson(Object value) {
        return value == null ? null : json(value);
    }

    private String json(Object value) {
        return mapper.writeValueAsString(value);
    }

    private static SearchRecordingException recordingFailure(String message, RuntimeException failure) {
        if (failure instanceof DataAccessException || !(failure instanceof SearchRecordingException)) {
            return new SearchRecordingException(message, failure);
        }
        return (SearchRecordingException) failure;
    }
}
