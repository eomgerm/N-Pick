package com.npick.search.domain.model;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import com.npick.common.error.BusinessException;
import com.npick.search.domain.error.SearchErrorCode;

/**
 * 해석 규칙이 조건을 걸고 값을 바꿀 수 있는 축.
 *
 * <p>FRD v3.2 F-11 의 「대상은 사건명, 인물·기관·장소·시설, 검색 의미어, 날짜 조건, 검색 의도다」를 그대로 닫은 것이다. 여기 없는 축은 규칙이 건드릴 수 없다.
 *
 * <p><b>{@code classifications}(계절·날씨·장면 유형)는 일부러 없다.</b> 리졸버는 이 축을 내지만({@code query_resolver/schema.py} 의
 * {@code Classification}) F-11 의 대상 열거에 없다. AC 에 없는 기능을 임의로 넣지 않는다. 필요해지면 이 enum 에 상수 하나를 더하는 변경이다.
 *
 * <p>축 이름({@link #jsonName()})은 {@code condition_json}·{@code patch_json} 에 그대로 적히는 값이고 리졸버 출력 JSON 의 키와 같은 표기다. FRD
 * §11 이 「키 이름이 어긋나면 교정 규칙이 오류 없이 조용히 안 걸린다」고 경계한 지점이라 이름을 여기 한 곳에서만 정의한다.
 */
public enum ResolutionAxis {
    /** 검색 의도. 목록이 아니라 값 하나다 — {@code set}·{@code unset} 만 적용된다. */
    INTENT("intent", false),
    DATE_WINDOWS("date_windows", true),
    INCIDENT_NAMES("incident_names", true),
    ENTITIES("entities", true),
    LOCATIONS("locations", true),
    EXPANDED_TERMS("expanded_terms", true);

    private final String jsonName;
    private final boolean list;

    ResolutionAxis(String jsonName, boolean list) {
        this.jsonName = jsonName;
        this.list = list;
    }

    public String jsonName() {
        return jsonName;
    }

    /** 목록 축인가. 참이면 {@code add_item}·{@code remove_item}, 거짓이면 {@code set}·{@code unset} 이 적용된다. */
    public boolean list() {
        return list;
    }

    /** 규칙 JSON 의 축 이름을 해석한다. 모르는 이름은 비어 있는 결과다 — 호출부가 비호환으로 기록한다 (F-14). */
    public static Optional<ResolutionAxis> from(String jsonName) {
        for (ResolutionAxis axis : values()) {
            if (axis.jsonName.equals(jsonName)) {
                return Optional.of(axis);
            }
        }
        return Optional.empty();
    }

    /**
     * 목록 축의 항목을 축 종류와 무관한 하나의 모양으로 본 것.
     *
     * <p>축마다 항목 타입이 다르지만({@code IncidentName}·{@code Entity}·{@code Location}·{@code DateWindow}·{@code String}) 규칙
     * 엔진에는 「값이 무엇이고 출처가 무엇인가」만 필요하다. 축별 분기를 엔진 전체에 흘리지 않기 위해 이 한 지점에서 투영한다.
     *
     * @param type 하위 유형. {@code entities}·{@code locations} 의 유형과 {@code date_windows} 의 날짜 필드. 없는 축은 {@code null}
     * @param value 값. {@code date_windows} 만 {@code null} 이다
     * @param start {@code date_windows} 전용
     * @param endExclusive {@code date_windows} 전용
     */
    public record Item(
            String type,
            String value,
            LocalDate start,
            LocalDate endExclusive,
            QueryResolution.Origin origin,
            QueryResolution.QuerySpan querySpan,
            double confidence) {

        /**
         * 같은 항목인지 가리는 키.
         *
         * <p>충돌 판정의 쓰기 키와 {@code remove_item} 대조가 둘 다 이 값을 쓴다. 값 비교는 <b>정확 일치</b>다 — 리졸버가 낸 같은 개체명끼리 비교하는 것이라 정규화가 필요하지
         * 않고, FRD §1.2 가 금지한 유사도 매칭으로 번지지 않게 한다.
         */
        public String identity() {
            String head = type == null ? "" : type;
            return value == null ? head + "|" + start + ".." + endExclusive : head + "|" + value;
        }
    }

    /** 이 축의 현재 항목. 스칼라 축에 부르면 불변식 위반이다. */
    public List<Item> read(QueryResolution resolution) {
        return switch (this) {
            case DATE_WINDOWS ->
                resolution.dateWindows().stream()
                        .map(w -> new Item(
                                lower(w.field().name()),
                                null,
                                w.start(),
                                w.endExclusive(),
                                w.origin(),
                                w.querySpan(),
                                w.confidence()))
                        .toList();
            case INCIDENT_NAMES ->
                resolution.incidentNames().stream()
                        .map(n -> new Item(null, n.value(), null, null, n.origin(), n.querySpan(), n.confidence()))
                        .toList();
            case ENTITIES ->
                resolution.entities().stream()
                        .map(e -> new Item(
                                lower(e.type().name()),
                                e.value(),
                                null,
                                null,
                                e.origin(),
                                e.querySpan(),
                                e.confidence()))
                        .toList();
            case LOCATIONS ->
                resolution.locations().stream()
                        .map(l -> new Item(
                                lower(l.type().name()),
                                l.value(),
                                null,
                                null,
                                l.origin(),
                                l.querySpan(),
                                l.confidence()))
                        .toList();
            // expanded_terms 는 출처를 갖지 않는 문자열 목록이다. 규칙이 넣은 것과 리졸버가 낸 것을 구분할 자리가 없다.
            case EXPANDED_TERMS ->
                resolution.expandedTerms().stream()
                        .map(t -> new Item(null, t, null, null, QueryResolution.Origin.INFERRED, null, 0.0))
                        .toList();
            case INTENT -> throw new BusinessException(SearchErrorCode.RULE_AXIS_NOT_A_LIST);
        };
    }

    /** 이 축의 항목을 교체한 새 해석. 원본은 바꾸지 않는다 — 롤백이 복사본을 버리는 것으로 끝나는 근거다. */
    public QueryResolution write(QueryResolution resolution, List<Item> items) {
        return switch (this) {
            case DATE_WINDOWS ->
                new QueryResolution(
                        resolution.schemaVersion(),
                        resolution.intent(),
                        items.stream()
                                .map(i -> new QueryResolution.DateWindow(
                                        QueryResolution.DateField.valueOf(upper(i.type())),
                                        i.start(),
                                        i.endExclusive(),
                                        i.origin(),
                                        i.querySpan(),
                                        i.confidence()))
                                .toList(),
                        resolution.incidentNames(),
                        resolution.entities(),
                        resolution.locations(),
                        resolution.classifications(),
                        resolution.expandedTerms(),
                        resolution.confidence());
            case INCIDENT_NAMES ->
                new QueryResolution(
                        resolution.schemaVersion(),
                        resolution.intent(),
                        resolution.dateWindows(),
                        items.stream()
                                .map(i -> new QueryResolution.IncidentName(
                                        i.value(), i.origin(), i.querySpan(), i.confidence()))
                                .toList(),
                        resolution.entities(),
                        resolution.locations(),
                        resolution.classifications(),
                        resolution.expandedTerms(),
                        resolution.confidence());
            case ENTITIES ->
                new QueryResolution(
                        resolution.schemaVersion(),
                        resolution.intent(),
                        resolution.dateWindows(),
                        resolution.incidentNames(),
                        items.stream()
                                .map(i -> new QueryResolution.Entity(
                                        QueryResolution.EntityType.valueOf(upper(i.type())),
                                        i.value(),
                                        i.origin(),
                                        i.querySpan(),
                                        i.confidence()))
                                .toList(),
                        resolution.locations(),
                        resolution.classifications(),
                        resolution.expandedTerms(),
                        resolution.confidence());
            case LOCATIONS ->
                new QueryResolution(
                        resolution.schemaVersion(),
                        resolution.intent(),
                        resolution.dateWindows(),
                        resolution.incidentNames(),
                        resolution.entities(),
                        items.stream()
                                .map(i -> new QueryResolution.Location(
                                        QueryResolution.LocationType.valueOf(upper(i.type())),
                                        i.value(),
                                        i.origin(),
                                        i.querySpan(),
                                        i.confidence()))
                                .toList(),
                        resolution.classifications(),
                        resolution.expandedTerms(),
                        resolution.confidence());
            case EXPANDED_TERMS ->
                new QueryResolution(
                        resolution.schemaVersion(),
                        resolution.intent(),
                        resolution.dateWindows(),
                        resolution.incidentNames(),
                        resolution.entities(),
                        resolution.locations(),
                        resolution.classifications(),
                        items.stream().map(Item::value).toList(),
                        resolution.confidence());
            case INTENT -> throw new BusinessException(SearchErrorCode.RULE_AXIS_NOT_A_LIST);
        };
    }

    /** {@code intent} 를 바꾼 새 해석. {@code null} 이면 {@code unset} 이라 {@code UNKNOWN} 으로 돌린다. */
    public static QueryResolution writeIntent(QueryResolution resolution, QueryResolution.Intent intent) {
        return new QueryResolution(
                resolution.schemaVersion(),
                intent == null ? QueryResolution.Intent.UNKNOWN : intent,
                resolution.dateWindows(),
                resolution.incidentNames(),
                resolution.entities(),
                resolution.locations(),
                resolution.classifications(),
                resolution.expandedTerms(),
                resolution.confidence());
    }

    /**
     * {@code intent} 의 규칙 JSON 표기. 리졸버 출력 JSON 과 같은 표기다 ({@code query_resolver/schema.py} 의 {@code Intent}).
     *
     * <p>enum 이름을 그대로 쓰지 않고 변환을 두는 이유는 규칙 JSON 이 리졸버 출력과 같은 어휘를 써야 하기 때문이다. 표기가 갈리면 규칙이 조용히 안 걸린다 (§11).
     */
    public static String intentJson(QueryResolution.Intent intent) {
        return lower(intent.name());
    }

    /** 규칙 JSON 의 {@code intent} 값을 해석한다. 모르는 값은 비어 있는 결과다. */
    public static Optional<QueryResolution.Intent> intentFrom(String jsonName) {
        for (QueryResolution.Intent intent : QueryResolution.Intent.values()) {
            if (intentJson(intent).equals(jsonName)) {
                return Optional.of(intent);
            }
        }
        return Optional.empty();
    }

    /** 항목을 더한 목록. 이미 같은 항목이 있으면 그대로다 — 추가는 멱등이다. */
    public static List<Item> plus(List<Item> items, Item added) {
        if (items.stream().anyMatch(i -> i.identity().equals(added.identity()))) {
            return items;
        }
        List<Item> next = new ArrayList<>(items);
        next.add(added);
        return List.copyOf(next);
    }

    private static String lower(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    private static String upper(String name) {
        return name.toUpperCase(Locale.ROOT);
    }
}
