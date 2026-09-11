package com.npick.search.application.resolution;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

import com.npick.search.application.port.AnchorFinding;
import com.npick.search.application.port.QueryResolution;
import com.npick.search.application.port.QueryResolution.Classification;
import com.npick.search.application.port.QueryResolution.DateWindow;
import com.npick.search.application.port.QueryResolution.Entity;
import com.npick.search.application.port.QueryResolution.IncidentName;
import com.npick.search.application.port.QueryResolution.Location;
import com.npick.search.application.port.QueryResolution.Origin;
import com.npick.search.application.port.QueryResolution.QuerySpan;
import com.npick.search.application.port.QueryResolutionResult;

/**
 * anchor 의 출처 주장을 원문과 대조해 확정한다 (FRD v3.1 F-05).
 *
 * <p>리졸버가 {@code explicit_query} 라고 붙여 보낸 anchor 가 정말 사용자 원문에 있는지 여기서 다시 본다. 없으면 {@link Origin#INFERRED} 로 <b>강등</b>한다
 * — 값은 남기고 출처 주장만 떼어낸다. 값은 관련성 점수에 여전히 쓸모가 있고, 빼앗는 것은 결과를 제외시킬 권한뿐이다.
 *
 * <h2>왜 BE 가 또 보나</h2>
 *
 * 리졸버 쪽 {@code ai/src/npick_worker/query_resolver/validator.py} 가 같은 검사를 이미 한다. 그래도 여기서 한 번 더 하는 이유는 이곳이 <b>신뢰 경계</b>이기
 * 때문이다. hard 제외는 되돌릴 수 없고 ({@code S15P21A501-56}), 그 권한의 유일한 근거가 {@link Origin#EXPLICIT_QUERY} 다. 리졸버가 다른 판으로 갈리거나 검증
 * 단계를 건너뛴 응답을 보내도 검색이 사용자 조건을 위조하지 않아야 한다.
 *
 * <p><b>이 클래스는 제외 판정을 하지 않는다.</b> "강등된 anchor 는 hard 제외 근거가 될 수 없다" 는 규칙 자체는 guard 의 몫이다 (F-06, {@code S15P21A501-56}).
 * 여기서는 guard 가 믿고 쓸 {@code origin} 을 확정해 넘길 뿐이다.
 *
 * <h2>{@code findings} 의 {@code path} 규약</h2>
 *
 * {@code path} 는 <b>그 판정을 한 단계가 받은 배열 기준</b> 인덱스다. 리졸버가 남긴 {@code entities[0]} 은 LLM 원본 배열의 0번이고, 여기서 남기는
 * {@code entities[0]} 은 리졸버가 거르고 난 배열의 0번이다. 리졸버가 중복 entity 를 버리거나 여기서 뒤집힌 날짜 구간을 버리면 그만큼 어긋난다. 한 배열을 두 단계가 각각 줄였으니 단일
 * 인덱스로는 가리킬 수 없다 — §7.2 기록을 읽을 때 두 출처를 섞어 세지 않는다. 강등 여부 자체는 인덱스가 아니라 anchor 의 {@link Origin} 에 남으므로 guard 판정은 이 어긋남의
 * 영향을 받지 않는다.
 *
 * <p><b>span 단위:</b> 여기서 내는 {@link QuerySpan} 은 Java 문자열 인덱스(UTF-16 단위)다. 리졸버의 span 은 Python 기준이라 코드 포인트 단위이고, 원문에 BMP
 * 밖 문자(이모지 등)가 있으면 그 뒤의 좌표가 서로 어긋난다. 값으로 찾아 다시 계산하므로 강등이 잘못 일어나지는 않지만 {@code span_corrected} 가 불필요하게 남는다. 출력 계약의 단위 표기는
 * {@code query_resolver/schema.py} 가 정본이므로 여기서 임의로 맞추지 않는다 ({@code S15P21A501-101} 확인 대상).
 *
 * <p>설계 정본 §7 의 Policy 다 — 무상태 순수 Java 이고 Spring 을 모른다. 쓰는 쪽이 필드로 직접 생성해 보유한다.
 */
public final class AnchorVerifier {

    private static final String DEMOTED = "demoted_to_inferred";
    private static final String DROPPED = "dropped";
    private static final String SPAN_CORRECTED = "span_corrected";

    /**
     * 해석 안의 모든 anchor 를 {@code rawQuery} 와 대조해 출처를 확정한다.
     *
     * @param rawQuery <b>사용자가 친 원문</b>. 정규화 질의를 넣으면 span 이 전부 어긋나 모든 explicit anchor 가 강등된다 — 명시 필터를 통째로 잃고 findings 에만
     *     흔적이 남으므로, 호출부는 {@link QueryResolutionResult#normalization()} 이 아니라 리졸버에 보낸 그 문자열을 넘긴다
     * @param result 리졸버가 준 결과. 해석 실패면 손대지 않고 그대로 돌려준다 — 강등할 anchor 자체가 없다
     * @return 강등·교정을 반영한 결과. 리졸버가 준 {@code findings} 뒤에 여기서 한 판정을 이어 붙인다
     */
    public QueryResolutionResult verify(String rawQuery, QueryResolutionResult result) {
        Objects.requireNonNull(rawQuery, "rawQuery");
        if (!result.isResolved()) {
            return result;
        }

        QueryResolution resolution = result.resolution();
        List<AnchorFinding> findings = new ArrayList<>(result.findings());

        List<DateWindow> dateWindows = new ArrayList<>();
        for (int i = 0; i < resolution.dateWindows().size(); i++) {
            DateWindow window = resolution.dateWindows().get(i);
            String path = "date_windows[" + i + "]";
            if (!window.start().isBefore(window.endExclusive())) {
                // 뒤집힌 구간은 강등해도 쓸 수 없다. 남겨두면 무엇과도 맞지 않는 날짜 조건이 된다.
                findings.add(new AnchorFinding(
                        path,
                        DROPPED,
                        "start 가 end_exclusive 이상이다: %s >= %s".formatted(window.start(), window.endExclusive())));
                continue;
            }
            // 날짜 구간에는 대조할 문자열이 없다. span 을 찾아줄 수 없으니 범위만 본다.
            Checked checked = checkSpanOnly(window.origin(), window.querySpan(), rawQuery, path, findings);
            dateWindows.add(new DateWindow(
                    window.field(),
                    window.start(),
                    window.endExclusive(),
                    checked.origin(),
                    checked.span(),
                    window.confidence()));
        }

        List<IncidentName> incidentNames = new ArrayList<>();
        for (int i = 0; i < resolution.incidentNames().size(); i++) {
            IncidentName anchor = resolution.incidentNames().get(i);
            Checked checked = checkValue(
                    anchor.value(),
                    anchor.origin(),
                    anchor.querySpan(),
                    rawQuery,
                    "incident_names[" + i + "]",
                    findings);
            incidentNames.add(new IncidentName(anchor.value(), checked.origin(), checked.span(), anchor.confidence()));
        }

        List<Entity> entities = new ArrayList<>();
        for (int i = 0; i < resolution.entities().size(); i++) {
            Entity anchor = resolution.entities().get(i);
            Checked checked = checkValue(
                    anchor.value(), anchor.origin(), anchor.querySpan(), rawQuery, "entities[" + i + "]", findings);
            entities.add(
                    new Entity(anchor.type(), anchor.value(), checked.origin(), checked.span(), anchor.confidence()));
        }

        List<Location> locations = new ArrayList<>();
        for (int i = 0; i < resolution.locations().size(); i++) {
            Location anchor = resolution.locations().get(i);
            Checked checked = checkValue(
                    anchor.value(), anchor.origin(), anchor.querySpan(), rawQuery, "locations[" + i + "]", findings);
            locations.add(
                    new Location(anchor.type(), anchor.value(), checked.origin(), checked.span(), anchor.confidence()));
        }

        List<Classification> classifications = new ArrayList<>();
        for (int i = 0; i < resolution.classifications().size(); i++) {
            Classification anchor = resolution.classifications().get(i);
            Checked checked = checkValue(
                    anchor.value(),
                    anchor.origin(),
                    anchor.querySpan(),
                    rawQuery,
                    "classifications[" + i + "]",
                    findings);
            classifications.add(new Classification(
                    anchor.type(), anchor.value(), checked.origin(), checked.span(), anchor.confidence()));
        }

        return new QueryResolutionResult(
                result.normalization(),
                new QueryResolution(
                        resolution.schemaVersion(),
                        resolution.intent(),
                        dateWindows,
                        incidentNames,
                        entities,
                        locations,
                        classifications,
                        resolution.expandedTerms(),
                        resolution.confidence()),
                findings,
                result.resolutionSchemaVersion(),
                result.promptVersion(),
                result.modelVersion(),
                null);
    }

    /** 확정된 출처와 span. */
    private record Checked(Origin origin, QuerySpan span) {

        /** 강등하면 span 도 버린다. explicit 주장이 근거를 잃었는데 좌표만 남기면 뒤에서 읽는 쪽이 "원문 어디" 를 가리킨다고 오해한다. */
        static Checked demoted() {
            return new Checked(Origin.INFERRED, null);
        }
    }

    /** {@code value} 를 갖는 anchor: 그 문자열이 원문에 있어야 하고, span 은 코드가 찾는다. */
    private Checked checkValue(
            String value,
            Origin origin,
            QuerySpan claimed,
            String rawQuery,
            String path,
            List<AnchorFinding> findings) {
        if (origin == Origin.EXPLICIT_FILTER) {
            // F-05 — UI 명시 필터는 사용자만 만든다. 리졸버가 이걸 내는 것은 사용자 조건 위조다.
            // 값 자체는 원문에서 왔을 수 있으므로 검색을 실패시키지 않고 출처 주장만 떼어낸다.
            findings.add(new AnchorFinding(path, DEMOTED, "리졸버가 만들 수 없는 origin 이다: " + origin));
            return Checked.demoted();
        }
        if (origin != Origin.EXPLICIT_QUERY) {
            return keepNonExplicit(origin, claimed, rawQuery, path, findings);
        }

        QuerySpan located = locate(value, rawQuery, claimed);
        if (located == null) {
            // 창작 anchor 가 걸러지는 자리다 (F-05 "원문에서 확인되지 않는 인물·사건·날짜를
            // 사용자의 명시 조건으로 표시하지 않는다").
            findings.add(new AnchorFinding(path, DEMOTED, "원문에 없는 값을 explicit_query 로 주장했다: '%s'".formatted(value)));
            return Checked.demoted();
        }
        if (!located.equals(claimed)) {
            // 값은 원문에 있는데 좌표만 틀렸다. 주장 자체는 살아 있으므로 강등하지 않고 고친다.
            findings.add(new AnchorFinding(
                    path,
                    SPAN_CORRECTED,
                    "모델 span %s 을 원문에서 찾은 [%d, %d) 로 고쳤다"
                            .formatted(describe(claimed), located.start(), located.end())));
        }
        return new Checked(Origin.EXPLICIT_QUERY, located);
    }

    private Checked checkSpanOnly(
            Origin origin, QuerySpan span, String rawQuery, String path, List<AnchorFinding> findings) {
        if (origin != Origin.EXPLICIT_FILTER && origin != Origin.EXPLICIT_QUERY) {
            return keepNonExplicit(origin, span, rawQuery, path, findings);
        }
        String problem = spanProblem(origin, span, rawQuery);
        if (problem == null) {
            return new Checked(origin, span);
        }
        findings.add(new AnchorFinding(path, DEMOTED, problem));
        return Checked.demoted();
    }

    /**
     * explicit 이 아닌 anchor 의 span: 대조할 출처 주장이 없으니 강등할 것도 없다. 범위만 본다.
     *
     * <p>원문 밖을 가리키는 좌표를 그대로 넘기면 이 span 으로 원문을 잘라 읽는 쪽이 깨진다. 출처는 그대로 두고 좌표만 버린다.
     */
    private Checked keepNonExplicit(
            Origin origin, QuerySpan span, String rawQuery, String path, List<AnchorFinding> findings) {
        if (span == null || inRange(span, rawQuery)) {
            return new Checked(origin, span);
        }
        findings.add(
                new AnchorFinding(path, SPAN_CORRECTED, "원문 범위를 벗어난 query_span 을 버렸다: %s".formatted(describe(span))));
        return new Checked(origin, null);
    }

    private boolean inRange(QuerySpan span, String rawQuery) {
        return span.start() >= 0 && span.end() > span.start() && span.end() <= rawQuery.length();
    }

    /** span 범위만 보는 검사. 대조할 문자열이 없는 날짜 구간에만 쓴다. */
    private String spanProblem(Origin origin, QuerySpan span, String rawQuery) {
        if (origin == Origin.EXPLICIT_FILTER) {
            return "리졸버가 만들 수 없는 origin 이다: " + origin;
        }
        if (origin != Origin.EXPLICIT_QUERY) {
            return null;
        }
        if (span == null) {
            return "explicit_query 인데 query_span 이 없다";
        }
        if (span.start() < 0) {
            return "query_span 이 음수다: [%d, %d)".formatted(span.start(), span.end());
        }
        if (span.end() <= span.start()) {
            return "query_span 이 빈 구간이다: [%d, %d)".formatted(span.start(), span.end());
        }
        if (span.end() > rawQuery.length()) {
            return "query_span 이 원문 길이를 넘는다: end=%d, len=%d".formatted(span.end(), rawQuery.length());
        }
        return null;
    }

    /**
     * {@code value} 가 원문에 있으면 그 위치를, 없으면 {@code null} 을 돌려준다.
     *
     * <p>같은 문자열이 여러 번 나오면 모델이 준 위치에 <b>가장 가까운</b> 것을 고른다. 모델의 숫자를 그대로 믿지는 않지만 어느 쪽을 가리켰는지에 대한 힌트로는 쓸 수 있다.
     *
     * <p>대소문자·공백을 접지 않고 그대로 비교한다. 리졸버 쪽 {@code _locate} 와 같은 규칙이다 — 접으면 원문에 없는 표기를 "있다" 고 판정하게 된다. 검색 매칭용
     * {@code match_value} 정규화({@code S15P21A501-169})와는 목적이 다르므로 그쪽을 끌어오지 않는다.
     */
    private QuerySpan locate(String value, String rawQuery, QuerySpan hint) {
        if (value.isEmpty()) {
            // 빈 문자열은 어디서나 "찾아" 진다. 그대로 두면 빈 span 이 나오므로 못 찾은 것으로 본다.
            return null;
        }
        List<Integer> starts = new ArrayList<>();
        for (int at = rawQuery.indexOf(value); at >= 0; at = rawQuery.indexOf(value, at + 1)) {
            starts.add(at);
        }
        if (starts.isEmpty()) {
            return null;
        }
        int start = hint == null
                ? starts.getFirst()
                : starts.stream()
                        .min(Comparator.comparingInt(candidate -> Math.abs(candidate - hint.start())))
                        .orElseThrow();
        return new QuerySpan(start, start + value.length());
    }

    private String describe(QuerySpan span) {
        return span == null ? "없음" : "[%d, %d)".formatted(span.start(), span.end());
    }
}
