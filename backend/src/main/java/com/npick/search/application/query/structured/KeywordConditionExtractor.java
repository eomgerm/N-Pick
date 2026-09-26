package com.npick.search.application.query.structured;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.npick.search.domain.model.KeywordTagSettings;
import com.npick.tag.application.query.TagCondition;
import com.npick.tag.domain.model.EffectiveTag;
import com.npick.tag.domain.model.TagMatchValue;
import com.npick.tag.domain.model.TagType;

/**
 * 검색어 토큰에서 {@code keyword} 태그 조건을 만든다 (S15P21A501-321). DB·리졸버 호출 없이 검증 가능하다.
 *
 * <p>입력 토큰은 워커 {@code encode_token} 이 만든 {@code 형태/품사} 소문자다. 명사류(nng·nnp·sl·sn)만 쓰고, 그 밖의 품사나 품사가 없는 옛 형식 토큰은 명사 연결을
 * 끊는다. 제외 목록 명사도 연결을 끊는다 — 「건물앞」 같은 키워드 태그는 거의 없고, 위치어를 붙인 값은 정보가 없다.
 *
 * <p>순서가 곧 상한이 자르는 우선순위다 — 질의 명사(단독 → 인접 2개 → 인접 3개) 다음에 확장어 명사. 확장어 조건은 편입 전용이며 점수를 받지 않는다 (S15P21A501-48).
 *
 * <p>값은 태그 {@code match_value} 와 같은 {@link TagMatchValue#normalize} 로 접는다. 판정 규칙을 둘로 만들지 않는다 (S15P21A501-169).
 *
 * <p>조건은 대소문자를 무시한다({@link TagCondition#exactIgnoreCase}). 워커가 질의 토큰을 소문자로 접으므로({@code kbs/sl}) 그렇지 않으면 대문자로 저장된
 * {@code KBS} 태그에 닿지 못한다. 같은 이유로 중복 제거도 대소문자를 무시한다 — {@code kbs} 와 전각에서 접힌 {@code KBS} 가 둘이면 분모가 부푼다.
 */
final class KeywordConditionExtractor {

    /** 워커 query_normalization keep_pos 중 명사류. VV·VA 는 여기 없고 연결을 끊는다. */
    private static final Set<String> NOUN_TAGS = Set.of("nng", "nnp", "sl", "sn");

    /** 인접 명사를 최대 몇 개까지 붙이는가. 「전세 사기 피해」 → 전세사기피해. */
    private static final int MAX_JOINED = 3;

    /** @param query 질의 명사 조건. 점수 분모다 @param expanded 확장어 명사 조건. 편입 전용 */
    record Conditions(List<TagCondition> query, List<TagCondition> expanded, List<ExpandedPhrase> expandedPhrases) {
        static final Conditions NONE = new Conditions(List.of(), List.of(), List.of());

        Conditions {
            query = List.copyOf(query);
            expanded = List.copyOf(expanded);
            expandedPhrases = List.copyOf(expandedPhrases);
        }

        boolean isEmpty() {
            return query.isEmpty() && expanded.isEmpty();
        }

        List<TagCondition> all() {
            return Stream.concat(query.stream(), expanded.stream()).toList();
        }

        boolean admitsExpanded(List<EffectiveTag> matchedTags) {
            return expandedPhrases.stream().anyMatch(phrase -> phrase.matches(matchedTags));
        }
    }

    /** 확장어 한 구. 구 안은 최대 3개 인접 명사 태그의 조합으로 끝까지 덮여야 후보가 된다. */
    record ExpandedPhrase(List<List<String>> nounRuns) {
        ExpandedPhrase {
            nounRuns = nounRuns.stream().map(List::copyOf).toList();
        }

        boolean matches(List<EffectiveTag> tags) {
            Set<String> values = tags.stream()
                    .filter(tag -> tag.tagType() == TagType.KEYWORD)
                    .map(tag -> tag.matchValue().toLowerCase(Locale.ROOT))
                    .collect(Collectors.toSet());
            return nounRuns.stream().allMatch(run -> covered(run, values));
        }

        private static boolean covered(List<String> run, Set<String> values) {
            boolean[] covered = new boolean[run.size() + 1];
            covered[0] = true;
            for (int start = 0; start < run.size(); start++) {
                if (!covered[start]) continue;
                for (int width = 1; width <= MAX_JOINED && start + width <= run.size(); width++) {
                    String value = TagMatchValue.normalize(String.join("", run.subList(start, start + width)))
                            .toLowerCase(Locale.ROOT);
                    if (values.contains(value)) covered[start + width] = true;
                }
            }
            return covered[run.size()];
        }
    }

    Conditions extract(List<String> searchTokens, List<List<String>> expandedPhrases, KeywordTagSettings settings) {
        if (!settings.enabled()) {
            return Conditions.NONE;
        }
        var queryValues = new LinkedHashMap<String, String>();
        addValues(queryValues, searchTokens, settings);
        var expandedValues = new LinkedHashMap<String, String>();
        var expandedPhraseGroups = new ArrayList<ExpandedPhrase>();
        for (List<String> phrase : expandedPhrases) {
            // 제외 대상 명사를 빼고 남은 토큰만 쓰면 구 전체 충족 조건이 단일 명사로 약해진다.
            if (phrase.stream()
                    .map(KeywordConditionExtractor::nounForm)
                    .filter(Objects::nonNull)
                    .map(TagMatchValue::normalize)
                    .anyMatch(settings::stopped)) continue;
            var runs = nounRuns(phrase, settings);
            if (!runs.isEmpty()) {
                expandedPhraseGroups.add(new ExpandedPhrase(runs));
                addRunValues(expandedValues, runs, settings);
            }
        }
        // 상한으로 잘린 질의 명사도 확장어로 되살리지 않는다 — 사용자가 친 말을 확장어 출처로 표시하게 된다.
        expandedValues.keySet().removeAll(queryValues.keySet());
        List<TagCondition> query = queryValues.values().stream()
                .limit(settings.conditionCap())
                .map(value -> TagCondition.exactIgnoreCase(TagType.KEYWORD, value))
                .toList();
        List<TagCondition> expanded = expandedValues.values().stream()
                .limit(Math.max(0, settings.conditionCap() - query.size()))
                .map(value -> TagCondition.exactIgnoreCase(TagType.KEYWORD, value))
                .toList();
        return new Conditions(query, expanded, expandedPhraseGroups);
    }

    /** 단독 → 인접 2개 → 인접 3개 순으로 {@code values} 에 더한다. 빈 값과 제외 목록 값은 버린다. 키는 소문자 값이고, 같은 키가 이미 있으면 먼저 본 표기를 남긴다. */
    private static void addValues(
            LinkedHashMap<String, String> values, List<String> tokens, KeywordTagSettings settings) {
        addRunValues(values, nounRuns(tokens, settings), settings);
    }

    private static void addRunValues(
            LinkedHashMap<String, String> values, List<List<String>> runs, KeywordTagSettings settings) {
        for (int width = 1; width <= MAX_JOINED; width++) {
            for (List<String> run : runs) {
                for (int start = 0; start + width <= run.size(); start++) {
                    String value = TagMatchValue.normalize(String.join("", run.subList(start, start + width)));
                    if (!value.isEmpty() && !settings.stopped(value)) {
                        values.putIfAbsent(value.toLowerCase(Locale.ROOT), value);
                    }
                }
            }
        }
    }

    /** 명사류가 아니거나, 정규화하면 빈 값이거나, 제외 목록에 걸린 명사에서 실을 끊는다. 빈 값을 실에 끼우면 폭만 먹는 유령 원소가 된다. */
    private static List<List<String>> nounRuns(List<String> tokens, KeywordTagSettings settings) {
        var runs = new ArrayList<List<String>>();
        var current = new ArrayList<String>();
        for (String token : tokens) {
            String form = nounForm(token);
            String normalized = form == null ? "" : TagMatchValue.normalize(form);
            if (normalized.isEmpty() || settings.stopped(normalized)) {
                if (!current.isEmpty()) {
                    runs.add(List.copyOf(current));
                    current.clear();
                }
                continue;
            }
            current.add(form);
        }
        if (!current.isEmpty()) {
            runs.add(List.copyOf(current));
        }
        return runs;
    }

    /** 명사류 토큰의 형태. 품사가 없거나(옛 형식) 형태·품사가 비었거나 명사류가 아니면 {@code null}. */
    private static String nounForm(String token) {
        if (token == null) return null;
        int slash = token.lastIndexOf('/');
        if (slash <= 0 || slash == token.length() - 1) return null;
        String tag = token.substring(slash + 1).toLowerCase(Locale.ROOT);
        return NOUN_TAGS.contains(tag) ? token.substring(0, slash) : null;
    }
}
