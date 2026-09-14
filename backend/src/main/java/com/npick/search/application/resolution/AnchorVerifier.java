package com.npick.search.application.resolution;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
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
 * <h2>날짜는 왜 다르게 보나</h2>
 *
 * 값이 있는 anchor(인물·장소·사건명)는 원문에서 그 문자열을 찾으면 끝이다. 날짜는 대조할 원본이 없다 — 리졸버가 주는 {@code 2025-06-01~2025-09-01} 은 사용자가 친 문자열이
 * 아니라 해석해서 만든 값이고, 사용자는 {@code "작년 여름"} 이라고 썼다. 그래서 span 이 가리키는 원문 조각에서 <b>기간을 다시 계산해 맞춰본다</b>
 * ({@link #denotedPeriod}). {@code "작년"} 이 몇 년인지는 {@link Clock} 으로 직접 세며, 리졸버가 푼 값을 가져다 쓰지 않는다 — 그래야 검산이 된다.
 *
 * <p>얼마나 엄하게 맞춰보는지는 <b>조각이 기간을 더 좁힐 여지를 남겼는가</b>로 갈린다. {@code "작년 여름"} 에는 {@code 여름} 이라는 좁히기 근거가 글자로 있으니 리졸버가 고른 6~9월을
 * 받아들인다(포함). {@code "작년"} 에는 그런 근거가 없으니 작년 <b>전체</b>와 같아야 한다(일치) — 없는 근거로 좁힌 구간에 명시 출처를 주면, 사용자가 지정하지 않은 달의 결과가 guard
 * 에서 제외된다.
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

    /**
     * 숫자 없이 기간을 가리키는 표현. 긴 것부터 본다 — {@code "재작년"} 은 {@code "작년"} 을 품고 있다.
     *
     * <p>기간이 딱 정해지는 말만 넣는다. {@code "최근"}·{@code "연초"} 처럼 경계가 사람마다 다른 말은 일부러 뺐다 — 그런 표현은 확인할 수 없으므로 강등된다.
     */
    private static final List<String> RELATIVE_PERIOD_WORDS =
            List.of("재작년", "지난해", "작년", "올해", "금년", "내년", "그저께", "그제", "어제", "오늘", "지난달", "이번달", "이달", "지난주", "이번주");

    /**
     * 기간을 더 좁히지만 그 경계를 여기서 셀 수 없는 말. 이 말이 조각에 <b>낱말로</b> 있으면 리졸버가 좁힌 구간을 받아들인다.
     *
     * <p>목록에 없는 한정 표현은 "좁힐 근거 없음" 으로 읽혀 일치를 요구받고, 그러면 강등된다. 빠뜨려도 안전한 쪽으로 틀린다.
     */
    private static final List<String> PERIOD_QUALIFIER_WORDS =
            List.of("봄", "여름", "가을", "겨울", "초순", "중순", "하순", "초", "말", "무렵", "즈음", "쯤", "상반기", "하반기", "분기", "연휴", "명절");

    private final Clock clock;

    /**
     * @param clock {@code "작년"} 같은 상대 표현을 풀 기준 시각. 리졸버가 푼 값을 베끼지 않고 여기서 <b>따로 계산해</b> 대조하기 위한 것이다.
     *     <p><b>사용자가 있는 시간대를 명시해 넘긴다</b> — {@code Clock.system(ZoneId.of("Asia/Seoul"))}. 검색하는 사람이 {@code "작년"} 이라고 쓸
     *     때의 작년은 한국 기준이다. {@link Clock#systemDefaultZone()} 도 답이 아니다 — {@code backend/Dockerfile} 의 {@code ENTRYPOINT}
     *     가 {@code -Duser.timezone=UTC} 라 <b>운영에서는 그것도 UTC 를 준다</b>. UTC 로 세면 KST 자정부터 오전 9시까지 하루가 밀려 사용자가 맞게 쓴 조건이
     *     강등되거나 틀린 기간이 명시 조건으로 통과한다. 배선은 {@code AnchorVerificationConfiguration} 이 이미 해두었으니 그 빈을 주입받는다.
     */
    public AnchorVerifier(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * {@code "작년"} 같은 말을 어느 시간대 기준으로 푸는가.
     *
     * <p>배선이 맞는지 <b>동작 대신 이걸로</b> 확인한다. 시간대를 틀려도 답이 갈리는 것은 연말 아홉 시간뿐이라, {@code "작년"} 이 몇 년으로 풀리는지로 보면 나머지 기간에는 UTC 배선도
     * 그냥 통과한다.
     */
    public ZoneId zone() {
        return clock.getZone();
    }

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
            Checked checked = checkDateSpan(window, rawQuery, path, findings);
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
        Denoted denoted = denotedPeriod(claimedText);
        if (denoted == null) {
            // "3명 구조 현장" 의 "3명", "2023명 구조" 의 "2023명" 처럼 기간을 읽어낼 수 없는 조각이다.
            // 숫자가 있다는 것만으로는 근거가 되지 않는다 — 그것들은 인원수다.
            findings.add(new AnchorFinding(
                    path, DEMOTED, "기간을 읽어낼 수 없는 구간을 날짜의 explicit_query 근거로 주장했다: '%s'".formatted(claimedText)));
            return Checked.demoted();
        }
        String mismatch = denoted.mismatch(claimedText, window.start(), window.endExclusive());
        if (mismatch != null) {
            findings.add(new AnchorFinding(path, DEMOTED, mismatch));
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
     * 원문 조각에서 읽어낸 기간과, <b>그 조각이 기간을 더 좁힐 여지를 남겼는지</b>.
     *
     * <p>{@code narrowable} 이 이 검증의 핵심이다. {@code "2023년 여름"} 에는 {@code 여름} 이라는 좁히기 근거가 글자로 남아 있다 — 그 경계가 6월부터인지 7월부터인지만
     * 여기서 셀 수 없을 뿐이다. 반면 {@code "2023년"} 에는 월로 좁힐 근거가 <b>아무것도 없다</b>. 앞은 리졸버 판단에 맡기고 뒤는 맡기지 않는다.
     */
    private record Denoted(Period period, boolean narrowable) {

        /**
         * 구간이 이 조각과 맞지 않는 이유. 맞으면 {@code null}.
         *
         * <p>좁힐 근거가 없으면 <b>일치</b>를, 있으면 <b>포함</b>을 요구한다.
         */
        String mismatch(String text, LocalDate windowStart, LocalDate windowEndExclusive) {
            if (narrowable) {
                return period.contains(windowStart, windowEndExclusive)
                        ? null
                        : "'%s' 가 가리키는 기간 [%s, %s) 밖의 구간을 explicit_query 로 주장했다: [%s, %s)"
                                .formatted(
                                        text, period.start(), period.endExclusive(), windowStart, windowEndExclusive);
            }
            return period.sameAs(windowStart, windowEndExclusive)
                    ? null
                    : "'%s' 는 기간을 [%s, %s) 로만 짚는데 [%s, %s) 를 explicit_query 로 주장했다"
                            .formatted(text, period.start(), period.endExclusive(), windowStart, windowEndExclusive);
        }
    }

    /**
     * 이 원문 조각이 가리키는 기간. 읽어낼 수 없으면 {@code null}.
     *
     * <p>조각에 {@link #PERIOD_QUALIFIER_WORDS} 가 섞여 있으면 좁힐 여지가 있는 것으로 본다. 그 목록에 없는 한정 표현은 좁힐 여지가 없는 것으로 읽혀 일치를 요구받고, 그러면
     * 강등된다 — 목록이 비어 있어도 안전한 쪽으로 틀린다.
     */
    private Denoted denotedPeriod(String text) {
        boolean narrowable = PERIOD_QUALIFIER_WORDS.stream().anyMatch(word -> containsAsWord(text, word));

        Period numeric = numericPeriod(text);
        if (numeric != null) {
            return new Denoted(numeric, narrowable);
        }
        for (String word : RELATIVE_PERIOD_WORDS) {
            if (text.contains(word)) {
                return new Denoted(relativePeriod(word), narrowable);
            }
        }
        return null;
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
     * {@code word} 가 이 글에 <b>낱말로</b> 들어 있는가. 앞이나 뒤에 다른 한글 음절이 붙어 있으면 그 단어의 일부지 이 낱말이 아니다.
     *
     * <p>그냥 {@link String#contains} 로 보면 {@code "초등학교"} 의 {@code 초}, {@code "말레이시아"} 의 {@code 말}, {@code "돌아봄"} 의
     * {@code 봄} 이 한정어로 잡힌다. 그러면 좁힐 근거가 없는 조각이 "좁힐 근거 있음" 으로 읽혀 일치 검사가 포함 검사로 풀리고, 리졸버가 임의로 좁힌 하루짜리 구간도 명시 조건이 된다 — 이
     * 클래스가 막으려는 바로 그 구멍이다.
     *
     * <p>대신 {@code "여름철"} 처럼 뒤에 한글이 붙은 형태는 한정어로 안 쳐서 일치를 요구받고 강등된다. 못 알아보는 쪽은 hard 제외 권한만 잃으므로 안전한 방향이다.
     */
    private boolean containsAsWord(String text, String word) {
        for (int at = text.indexOf(word); at >= 0; at = text.indexOf(word, at + 1)) {
            boolean gluedBefore = at > 0 && isHangul(text.charAt(at - 1));
            int after = at + word.length();
            boolean gluedAfter = after < text.length() && isHangul(text.charAt(after));
            if (!gluedBefore && !gluedAfter) {
                return true;
            }
        }
        return false;
    }

    private boolean isHangul(char character) {
        return Character.UnicodeScript.of(character) == Character.UnicodeScript.HANGUL;
    }

    /** {@code "작년"} 처럼 오늘을 기준으로 풀어야 하는 기간. 리졸버가 푼 값을 믿지 않고 여기서 다시 센다. */
    private Period relativePeriod(String word) {
        LocalDate today = LocalDate.now(clock);
        return switch (word) {
            case "재작년" -> wholeYear(today.getYear() - 2);
            case "작년", "지난해" -> wholeYear(today.getYear() - 1);
            case "올해", "금년" -> wholeYear(today.getYear());
            case "내년" -> wholeYear(today.getYear() + 1);
            case "지난달" -> wholeMonth(today.withDayOfMonth(1).minusMonths(1));
            case "이번달", "이달" -> wholeMonth(today.withDayOfMonth(1));
            case "지난주" -> wholeWeek(today.minusWeeks(1));
            case "이번주" -> wholeWeek(today);
            case "그저께", "그제" -> new Period(today.minusDays(2), today.minusDays(1));
            case "어제" -> new Period(today.minusDays(1), today);
            case "오늘" -> new Period(today, today.plusDays(1));
            // 목록과 이 switch 가 두 군데라 어긋날 수 있다. null 을 흘려보내면 강등이 아니라
            // 검색 요청이 NPE 로 죽으므로, 빠뜨린 자리를 여기서 드러낸다.
            default -> throw new IllegalStateException("기간을 풀 수 없는 상대 표현이다: " + word);
        };
    }

    private Period wholeYear(int year) {
        return new Period(LocalDate.of(year, 1, 1), LocalDate.of(year + 1, 1, 1));
    }

    private Period wholeMonth(LocalDate firstOfMonth) {
        return new Period(firstOfMonth, firstOfMonth.plusMonths(1));
    }

    private Period wholeWeek(LocalDate dayInWeek) {
        LocalDate monday = dayInWeek.with(DayOfWeek.MONDAY);
        return new Period(monday, monday.plusWeeks(1));
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
