package com.npick.search.application.resolution;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

import com.npick.search.application.port.AnchorFinding;
import com.npick.search.application.port.QueryResolutionResult;
import com.npick.search.domain.model.QueryResolution;
import com.npick.search.domain.model.QueryResolution.Classification;
import com.npick.search.domain.model.QueryResolution.DateField;
import com.npick.search.domain.model.QueryResolution.DateWindow;
import com.npick.search.domain.model.QueryResolution.Entity;
import com.npick.search.domain.model.QueryResolution.IncidentName;
import com.npick.search.domain.model.QueryResolution.Location;
import com.npick.search.domain.model.QueryResolution.Origin;
import com.npick.search.domain.model.QueryResolution.QuerySpan;

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
 * <h2>날짜는 왜 다르게 보나</h2>
 *
 * 값이 있는 anchor(인물·장소·사건명)는 원문에서 그 문자열을 찾으면 끝이다. 날짜는 대조할 원본이 없다 — 리졸버가 주는 {@code 2023-01-01~2024-01-01} 은 사용자가 친 문자열이
 * 아니라 해석해서 만든 값이다. 그래서 span 이 가리키는 원문 조각을 <b>숫자로 파싱해 기간을 만들고 구간과 일치하는지</b> 본다 ({@link #numericPeriod}).
 *
 * <p><b>숫자로 짚은 날짜만 통과한다.</b> {@code "2023년"}·{@code "2023년 7월"}·{@code "2023-07-15"} 는 살고, {@code "작년"}·{@code "이번 여름"}
 * 같은 상대 표현은 강등된다. 막으려는 것이 <b>날조</b>(원문에 없는 날짜를 명시라고 주장)이지 <b>산수 오류</b>({@code "작년"} 을 한 해 잘못 셈)가 아니기 때문이다. 앞은 흔하고 되돌릴 수
 * 없지만, 뒤는 리졸버가 틀릴 일이 거의 없고 검산하려면 기준 시각·시간대·상대 표현 어휘를 BE 가 떠안아야 한다 — 막는 위험보다 들여온 복잡도가 컸다.
 *
 * <p>상대 표현이 강등되면 그 날짜는 hard 제외 근거로 못 쓰이고 관련성 점수에만 반영된다. {@code "작년 태풍"} 검색이 실패하지는 않고, 다른 해의 결과를 칼같이 잘라내지 못할 뿐이다 (F-06
 * "불확실한 정보를 모두 제거하는 것이 아니다").
 *
 * <h2>{@code findings} 의 {@code path} 규약</h2>
 *
 * {@code path} 는 <b>그 판정을 한 단계가 받은 배열 기준</b> 인덱스다. 리졸버가 남긴 {@code entities[0]} 은 LLM 원본 배열의 0번이고, 여기서 남기는
 * {@code entities[0]} 은 리졸버가 거르고 난 배열의 0번이다. 리졸버가 중복 entity 를 버리거나 여기서 뒤집힌 날짜 구간을 버리면 그만큼 어긋난다. 한 배열을 두 단계가 각각 줄였으니 단일
 * 인덱스로는 가리킬 수 없다 — §7.2 기록을 읽을 때 두 출처를 섞어 세지 않는다. 강등 여부 자체는 인덱스가 아니라 anchor 의 {@link Origin} 에 남으므로 guard 판정은 이 어긋남의
 * 영향을 받지 않는다.
 *
 * <h2>{@code query_span} 의 좌표 단위</h2>
 *
 * <b>들어오는 span 은 코드 포인트 단위, 나가는 span 은 Java 문자열 인덱스(UTF-16 단위)다.</b> 리졸버는 Python 이라 코드 포인트로 세고, 원문에 BMP 밖 문자(이모지 등)가
 * 있으면 그 뒤 좌표가 두 단위에서 갈린다. 그래서 범위 검사는 들어온 단위로 하고, 통과한 span 만 {@link #toJavaIndices} 로 옮겨 내보낸다.
 *
 * <p>날짜 구간처럼 span 을 그대로 통과시키는 자리에도 이 변환을 건다. 한 {@link QueryResolution} 안에서 날짜 span 만 코드 포인트로 남으면 그 결과를 어느 단위로 읽어도 절반이
 * 틀린다 — 원문 근거를 일관되게 잘라낼 수 없게 된다.
 *
 * <p>설계 정본 §7 의 Policy 다 — 무상태 순수 Java 이고 Spring 을 모른다. 쓰는 쪽이 필드로 직접 생성해 보유한다.
 */
public final class AnchorVerifier {

    private static final String DEMOTED = "demoted_to_inferred";
    private static final String DROPPED = "dropped";
    private static final String SPAN_CORRECTED = "span_corrected";
    private static final String DATE_FIELD_CORRECTED = "date_field_corrected";

    /**
     * 원문이 촬영 의미를 명시했는지 가리는 낱말.
     *
     * <p>리졸버 프롬프트({@code config/query_resolver.v1.toml} 규칙 6)의 「"촬영", "찍힌", "촬영 당시"」와 같은 어휘다. {@code "찍"} 하나로
     * {@code 찍힌}·{@code 찍은}·{@code 찍었던} 을 모두 잡는다 — 활용형을 늘어놓으면 빠뜨린 어미가 곧 오판이 된다.
     */
    private static final List<String> FILMING_MARKERS = List.of("촬영", "찍");

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
            if (inverted(window)) {
                // 뒤집힌 구간은 강등해도 쓸 수 없다. 남겨두면 무엇과도 맞지 않는 날짜 조건이 된다.
                findings.add(new AnchorFinding(
                        path,
                        DROPPED,
                        "start 가 end_exclusive 이상이다: %s >= %s".formatted(window.start(), window.endExclusive())));
                continue;
            }
            CheckedField field = verifyDateField(i, window, resolution.dateWindows(), rawQuery, path, findings);
            // 강등이 정해졌으면 span 검사를 건너뛴다. 결과가 같고, 사유가 둘 기록되면 §7.2 기록을 읽는
            // 쪽이 강등이 두 번 일어난 것으로 센다.
            Checked checked = field.demote() ? Checked.demoted() : checkDateSpan(window, rawQuery, path, findings);
            dateWindows.add(new DateWindow(
                    field.field(),
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

    /**
     * 날짜가 방송일인지 촬영일인지 확정한다 (FRD v3.2 F-06 「별도 표현 없이 연도·날짜만 입력하면 방송일 기준으로 해석한다」).
     *
     * <p><b>한 방향으로만 강제한다.</b> 원문에 촬영 어휘가 없는데 촬영일을 주장하면 방송일로 되돌리고, 그 반대는 손대지 않는다. 촬영 명시는 촬영일이 되기 위한 <b>필요조건</b>이지 충분조건이
     * 아니다 — {@code "2023년 방송분 중 촬영지가 궁금한 산불"} 처럼 촬영 어휘가 날짜와 무관하게 섞이면 낱말 하나로 날짜 종류를 뒤집는 쪽이 더 자주 틀린다.
     *
     * <p>{@link #checkDateSpan} 과 같은 이유로 이 검사가 필요하다. 리졸버 프롬프트가 이 규칙을 글로 적어 두지만 리졸버 쪽 {@code validator.py} 는 검사하지 않아,
     * 지금은 모델이 지킬 때만 지켜지는 규칙이다. 종류를 잘못 붙이면 guard 가 <b>다른 종류의 날짜와 비교해</b> 맞는 장면을 잘라낸다 (F-06 「명시한 날짜와 같은 종류의 검증된 날짜가 충돌」,
     * {@code S15P21A501-56}).
     *
     * <p>출처를 가리지 않고 모든 날짜에 건다. 추측 날짜는 결과를 제외시키지 못하지만 관련성 점수에는 쓰이고, 종류가 틀리면 엉뚱한 칸과 비교된다.
     *
     * <h3>교정이 손해를 내는 경우가 있다</h3>
     *
     * 고치고 나면 다투게 될 창이 <b>다른 구간으로 이미 있으면</b> 교정하지 않고 강등한다. {@code "2022년 태풍이 2023년 뉴스에 나온 장면"} 에서 촬영일 2022 를 방송일로 고치면 서로
     * 다른 구간의 방송일 조건이 둘 생기고, 둘 다 명시 출처로 살아남아 guard 가 어느 장면을 넣어도 하나와는 충돌한다 — 결과가 0 건이 된다. 교정 전이었다면 촬영일 2022 는 장면의 촬영일과
     * 비교되고, 그 값이 없거나 미검증이면 「그 이유만으로 제외하지 않음」 으로 빠져나갔다. 즉 교정이 결과를 살리던 길을 닫는다.
     *
     * <p>강등을 고르는 이유는 이 클래스가 원래 하는 일이기 때문이다 — 값은 남기고 제외 권한만 뺏는다. 추측 조건은 hard 제외 근거가 아니므로(F-06) 충돌이 결과를 죽이지 않는다.
     */
    private CheckedField verifyDateField(
            int index,
            DateWindow window,
            List<DateWindow> siblings,
            String rawQuery,
            String path,
            List<AnchorFinding> findings) {
        DateField claimed = window.field();
        if (claimed != DateField.FILMED_DATE || FILMING_MARKERS.stream().anyMatch(rawQuery::contains)) {
            return new CheckedField(claimed, false);
        }
        // 제외 권한이 없는 창은 강등해도 잃을 것이 없다. 사칭 창까지 충돌 검사를 걸면 강등 사유가
        // 「충돌」 로 덮여 §7.2 기록에서 진짜 원인(만들 수 없는 출처)이 사라진다.
        if (window.origin() == Origin.EXPLICIT_QUERY && rivalWindowExists(index, window, siblings)) {
            findings.add(new AnchorFinding(
                    path,
                    DEMOTED,
                    "원문에 촬영 명시가 없는데 방송일로 고치면 다른 구간의 방송일 조건과 충돌한다: [%s, %s)"
                            .formatted(window.start(), window.endExclusive())));
            return new CheckedField(claimed, true);
        }
        findings.add(new AnchorFinding(path, DATE_FIELD_CORRECTED, "원문에 촬영 명시가 없어 방송일로 되돌렸다"));
        return new CheckedField(DateField.BROADCAST_DATE, false);
    }

    /**
     * 방송일로 고쳤을 때 다투게 될 창이 이미 있는가.
     *
     * <p>세는 기준은 하나다 — <b>결과에 남아 hard 제외 권한을 가질 창 중, 구간이 다른 것</b>. 셋 다 이유가 따로 있다.
     *
     * <ul>
     *   <li><b>{@link Origin#EXPLICIT_QUERY} 만 센다.</b> 이 단계에서 제외 권한을 가질 수 있는 출처가 그것뿐이다. {@link Origin#INFERRED} 는 애초에
     *       권한이 없고(F-06 「AI가 추정한 조건만 충돌 → 강제 제외 근거로 사용하지 않음」), {@link Origin#EXPLICIT_FILTER} 는 리졸버가 만들 수 없는 출처라
     *       {@link #spanProblem} 이 반드시 강등시킨다. 권한이 없을 창 때문에 정말 명시였을 수 있는 창의 권한을 먼저 뺏을 이유가 없다.
     *   <li><b>버려질 창은 세지 않는다.</b> {@link #inverted} 인 창은 {@link #verify} 가 결과에서 뺀다. 결과에 없는 창은 무엇과도 충돌하지 않는다.
     *   <li><b>구간이 다른 것만 센다.</b> 구간이 같으면 조건이 겹치는 것이지 충돌이 아니다 — 같은 기간을 두 번 요구해도 통과하는 장면은 달라지지 않는다.
     * </ul>
     *
     * <p><b>원본 종류는 보지 않는다.</b> 이 메서드에 오는 것은 원문에 촬영 어휘가 없는 질의이고, 그 질의의 촬영일 창은 하나같이 교정 대상이다. 종류로 걸러 방송일 창만 세면 함께 교정될 촬영일
     * 창끼리 서로를 「아직 촬영일」 로만 보고 각자 교정돼, 막으려던 충돌이 그대로 생긴다.
     *
     * <p>자기 자신은 <b>인덱스</b>로 뺀다. 참조 비교로 빼면 같은 인스턴스가 두 자리에 들어온 목록에서 두 번째 자리도 자기 자신으로 오인된다.
     *
     * <p><b>알려진 한계.</b> {@link Origin#EXPLICIT_QUERY} 지만 자기 span 검사에서 강등될 창은 걸러내지 못한다 — {@code origin} 만으로는 알 수 없고
     * {@link #checkDateSpan} 을 실제로 돌려봐야 갈린다. 그 경우 상대가 사라질 창인데도 이쪽을 강등하므로 <b>과하게</b> 강등된다. 0 건을 만드는 방향은 아니라 한 번의 순회로 끝내는
     * 구조를 유지했다. 닫으려면 span 검사를 먼저 다 돌리고 충돌을 나중에 보는 2-pass 가 필요하다.
     */
    private boolean rivalWindowExists(int index, DateWindow window, List<DateWindow> siblings) {
        for (int i = 0; i < siblings.size(); i++) {
            if (i == index) {
                continue;
            }
            DateWindow other = siblings.get(i);
            if (other.origin() != Origin.EXPLICIT_QUERY || inverted(other)) {
                continue;
            }
            if (!other.start().equals(window.start()) || !other.endExclusive().equals(window.endExclusive())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 뒤집힌 구간인가. 강등해도 쓸 수 없어 {@link #verify} 가 버린다.
     *
     * <p>{@link #rivalWindowExists} 가 같은 기준을 쓴다 — 두 곳이 갈리면 버려질 창이 상대로 남아 엉뚱한 강등을 만든다.
     */
    private boolean inverted(DateWindow window) {
        return !window.start().isBefore(window.endExclusive());
    }

    /**
     * 확정된 날짜 종류.
     *
     * @param demote 종류를 고치는 대신 출처를 내려놓아야 하는가
     */
    private record CheckedField(DateField field, boolean demote) {}

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

        // 힌트도 Java 인덱스로 옮겨 쓴다. 범위 밖 좌표는 힌트로도 못 쓰므로 버린다.
        QuerySpan hint = claimed != null && inRange(claimed, rawQuery) ? toJavaIndices(claimed, rawQuery) : null;
        QuerySpan located = locate(value, rawQuery, hint);
        if (located == null) {
            // 창작 anchor 가 걸러지는 자리다 (F-05 "원문에서 확인되지 않는 인물·사건·날짜를
            // 사용자의 명시 조건으로 표시하지 않는다").
            findings.add(new AnchorFinding(path, DEMOTED, "원문에 없는 값을 explicit_query 로 주장했다: '%s'".formatted(value)));
            return Checked.demoted();
        }
        if (!located.equals(hint)) {
            // 값은 원문에 있는데 좌표만 틀렸다. 주장 자체는 살아 있으므로 강등하지 않고 고친다.
            findings.add(new AnchorFinding(
                    path,
                    SPAN_CORRECTED,
                    // 모델이 보낸 좌표를 그대로 남긴다. hint 는 범위 밖이면 비워둔 값이라, 그걸 찍으면
                    // §7.2 기록에서 "모델이 무엇을 주장했는지" 가 사라진다.
                    "모델 span %s 을 원문에서 찾은 [%d, %d) 로 고쳤다"
                            .formatted(describe(claimed), located.start(), located.end())));
        }
        return new Checked(Origin.EXPLICIT_QUERY, located);
    }

    /**
     * 날짜 구간의 출처 주장: span 이 가리키는 원문 조각이 그 구간을 <b>설명하는지</b> 본다.
     *
     * <p>값 anchor 는 쉽다 — {@code "힌남노"} 가 원문에 있는지 글자로 찾으면 끝이다. 날짜는 다르다. 리졸버가 주는 {@code 2025-06-01~2025-09-01} 은 사용자가 친 적
     * 없는 문자열이라 대조할 원본이 아예 없다. 그래서 찾는 대신 <b>원문 조각에서 기간을 계산해 맞춰본다</b>.
     */
    private Checked checkDateSpan(DateWindow window, String rawQuery, String path, List<AnchorFinding> findings) {
        Origin origin = window.origin();
        if (origin != Origin.EXPLICIT_FILTER && origin != Origin.EXPLICIT_QUERY) {
            return keepNonExplicit(origin, window.querySpan(), rawQuery, path, findings);
        }
        String problem = spanProblem(origin, window.querySpan(), rawQuery);
        if (problem != null) {
            findings.add(new AnchorFinding(path, DEMOTED, problem));
            return Checked.demoted();
        }

        // 범위 검사를 통과했으니 옮기는 것이 안전하다.
        QuerySpan span = toJavaIndices(window.querySpan(), rawQuery);
        String claimedText = rawQuery.substring(span.start(), span.end());
        Period denoted = numericPeriod(claimedText);
        if (denoted == null) {
            // 숫자로 짚은 날짜가 아니다. "3명"·"2023명" 의 인원수, "태풍" 같은 낱말,
            // 그리고 "작년"·"이번 여름" 처럼 여기서 셀 수 없는 상대 표현이 모두 여기로 온다.
            findings.add(new AnchorFinding(
                    path, DEMOTED, "숫자로 짚은 날짜가 아닌 구간을 explicit_query 근거로 주장했다: '%s'".formatted(claimedText)));
            return Checked.demoted();
        }
        if (!denoted.sameAs(window.start(), window.endExclusive())) {
            // "2024년" 을 근거로 2023년을 주장하거나, "2023년" 을 근거로 7월 한 달로 좁히는 출력이 걸린다.
            findings.add(new AnchorFinding(
                    path,
                    DEMOTED,
                    "'%s' 는 기간을 [%s, %s) 로 짚는데 [%s, %s) 를 explicit_query 로 주장했다"
                            .formatted(
                                    claimedText,
                                    denoted.start(),
                                    denoted.endExclusive(),
                                    window.start(),
                                    window.endExclusive())));
            return Checked.demoted();
        }
        return new Checked(origin, span);
    }

    /** 원문 조각이 가리키는 기간. {@code [start, endExclusive)} 반열린 구간이다. */
    private record Period(LocalDate start, LocalDate endExclusive) {

        boolean sameAs(LocalDate windowStart, LocalDate windowEndExclusive) {
            return start.equals(windowStart) && endExclusive.equals(windowEndExclusive);
        }

        boolean contains(LocalDate windowStart, LocalDate windowEndExclusive) {
            return !start.isAfter(windowStart) && !endExclusive.isBefore(windowEndExclusive);
        }
    }

    /**
     * {@code 2023} · {@code 2023년 7월} · {@code 2023-07-15} 처럼 숫자로 짚은 기간.
     *
     * <p>숫자 뒤에 <b>단위나 구분자가 와야</b> 날짜로 읽는다. 자릿수만 보면 {@code "2023명 구조"} 의 인원수가 연도가 된다.
     */
    private Period numericPeriod(String text) {
        List<String> runs = dateNumbers(text);
        // 첫 덩어리가 네 자리여야 연도로 읽는다. "3명" 의 3 을 연도나 월로 읽지 않기 위한 문턱이다.
        if (runs.isEmpty() || runs.getFirst().length() != 4 || runs.size() > 3) {
            return null;
        }
        try {
            int year = Integer.parseInt(runs.getFirst());
            if (runs.size() == 1) {
                return new Period(LocalDate.of(year, 1, 1), LocalDate.of(year + 1, 1, 1));
            }
            if (runs.get(1).length() > 2) {
                return null;
            }
            LocalDate firstOfMonth = LocalDate.of(year, Integer.parseInt(runs.get(1)), 1);
            if (runs.size() == 2) {
                return new Period(firstOfMonth, firstOfMonth.plusMonths(1));
            }
            if (runs.get(2).length() > 2) {
                return null;
            }
            LocalDate day = firstOfMonth.withDayOfMonth(Integer.parseInt(runs.get(2)));
            return new Period(day, day.plusDays(1));
        } catch (DateTimeException ex) {
            // 13월·32일 같은 값이다. 기간으로 읽을 수 없으니 근거가 못 된다.
            return null;
        }
    }

    /**
     * 날짜로 읽을 수 있는 숫자 덩어리를 순서대로 뽑는다. {@code "2023년 7월"} → {@code ["2023", "7"]}.
     *
     * <p>덩어리마다 <b>바로 뒤 글자</b>를 본다. 날짜 단위({@code 년}·{@code 월}·{@code 일})나 구분자({@code -}·{@code .}·{@code /}·공백)가 아니고 문자열
     * 끝도 아니면 날짜가 아니다 — 하나라도 걸리면 전부 버린다. {@code "2023명"} 의 {@code 명} 이 여기서 걸린다.
     */
    private List<String> dateNumbers(String text) {
        List<String> runs = new ArrayList<>();
        int index = 0;
        while (index < text.length()) {
            if (!Character.isDigit(text.charAt(index))) {
                index++;
                continue;
            }
            int start = index;
            while (index < text.length() && Character.isDigit(text.charAt(index))) {
                index++;
            }
            // 날짜가 아닌 덩어리는 그것만 건너뛴다. "2023년 태풍 3명 사망" 에서 "3명" 때문에
            // "2023년" 까지 버리면 사용자가 직접 친 조건이 사라진다. "2023명 구조" 는 남는
            // 덩어리가 없어 그대로 강등된다.
            if (index == text.length() || isDateUnitOrSeparator(text.charAt(index))) {
                runs.add(text.substring(start, index));
            }
        }
        return runs;
    }

    private boolean isDateUnitOrSeparator(char character) {
        return character == '년'
                || character == '월'
                || character == '일'
                || character == '-'
                || character == '.'
                || character == '/'
                || Character.isWhitespace(character);
    }

    /**
     * explicit 이 아닌 anchor 의 span: 대조할 출처 주장이 없으니 강등할 것도 없다. 범위만 본다.
     *
     * <p>원문 밖을 가리키는 좌표를 그대로 넘기면 이 span 으로 원문을 잘라 읽는 쪽이 깨진다. 출처는 그대로 두고 좌표만 버린다.
     */
    private Checked keepNonExplicit(
            Origin origin, QuerySpan claimed, String rawQuery, String path, List<AnchorFinding> findings) {
        if (claimed == null || inRange(claimed, rawQuery)) {
            // 강등할 것은 없어도 단위는 맞춰 내보낸다 — 한 결과 안에서 span 단위가 갈리면 안 된다.
            return new Checked(origin, toJavaIndices(claimed, rawQuery));
        }
        findings.add(new AnchorFinding(
                path, SPAN_CORRECTED, "원문 범위를 벗어난 query_span 을 버렸다: %s".formatted(describe(claimed))));
        return new Checked(origin, null);
    }

    /** 들어온 span 은 코드 포인트 단위이므로 상한도 코드 포인트 수로 본다. */
    private boolean inRange(QuerySpan span, String rawQuery) {
        return span.start() >= 0 && span.end() > span.start() && span.end() <= codePointCount(rawQuery);
    }

    /**
     * 리졸버 span(코드 포인트 단위)을 Java 문자열 인덱스(UTF-16 단위)로 옮긴다.
     *
     * <p>범위 검사를 통과한 span 에만 쓴다 — 그러지 않으면 {@link String#offsetByCodePoints} 가 던진다.
     *
     * <p>원문에 BMP 밖 문자가 없으면 두 단위가 같으므로 그대로 돌려준다.
     */
    private QuerySpan toJavaIndices(QuerySpan span, String rawQuery) {
        if (span == null || codePointCount(rawQuery) == rawQuery.length()) {
            return span;
        }
        return new QuerySpan(rawQuery.offsetByCodePoints(0, span.start()), rawQuery.offsetByCodePoints(0, span.end()));
    }

    private int codePointCount(String rawQuery) {
        return rawQuery.codePointCount(0, rawQuery.length());
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
        if (span.end() > codePointCount(rawQuery)) {
            return "query_span 이 원문 길이를 넘는다: end=%d, len=%d".formatted(span.end(), codePointCount(rawQuery));
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
