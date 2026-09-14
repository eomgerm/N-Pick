package com.npick.search.application.resolution;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.npick.search.application.error.QueryResolverErrorCode;
import com.npick.search.application.port.AnchorFinding;
import com.npick.search.application.port.QueryNormalization;
import com.npick.search.application.port.QueryResolution;
import com.npick.search.application.port.QueryResolution.Classification;
import com.npick.search.application.port.QueryResolution.ClassificationType;
import com.npick.search.application.port.QueryResolution.DateField;
import com.npick.search.application.port.QueryResolution.DateWindow;
import com.npick.search.application.port.QueryResolution.Entity;
import com.npick.search.application.port.QueryResolution.EntityType;
import com.npick.search.application.port.QueryResolution.IncidentName;
import com.npick.search.application.port.QueryResolution.Intent;
import com.npick.search.application.port.QueryResolution.Origin;
import com.npick.search.application.port.QueryResolution.QuerySpan;
import com.npick.search.application.port.QueryResolutionResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.groups.Tuple.tuple;

/**
 * mock 리졸버 출력으로 강등·교정 경로를 확인한다 (S15P21A501-46 완료 조건).
 *
 * <p>여기 mock 이 <b>강등되지 않은</b> explicit 주장을 보낸다는 것 자체가 이 클래스의 존재 이유다. 리졸버 쪽 validator 를 거치면 정상 경로에서는 이런 출력이 나오지 않지만, 그
 * 단계를 믿지 않는 것이 신뢰 경계의 일이다.
 */
class AnchorVerifierTest {

    private static final String RAW_QUERY = "2023년 태풍 힌남노 피해 현장";

    /** {@code "작년"} 같은 상대 표현이 테스트마다 달라지지 않게 고정한다. 이 시각 기준 작년은 2025년이다. */
    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-09-14T09:00:00Z"), ZoneId.of("Asia/Seoul"));

    private final AnchorVerifier verifier = new AnchorVerifier(FIXED_CLOCK);

    @Test
    @DisplayName("원문에 없는 값을 explicit_query 로 주장하면 inferred 로 강등한다")
    void demotesFabricatedExplicitAnchor() {
        // "포항 제철소 침수" 는 원문에 없다. LLM 이 힌남노 하면 따라오는 사건을 명시된 것처럼 얹은 경우다.
        QueryResolutionResult result = verifier.verify(
                RAW_QUERY,
                resolved(resolution()
                        .incidentNames(List.of(
                                new IncidentName("힌남노", Origin.EXPLICIT_QUERY, new QuerySpan(9, 12), 0.9),
                                new IncidentName("포항 제철소 침수", Origin.EXPLICIT_QUERY, new QuerySpan(13, 20), 0.6)))));

        List<IncidentName> incidents = result.resolution().incidentNames();
        assertThat(incidents.get(0).origin()).isEqualTo(Origin.EXPLICIT_QUERY);
        assertThat(incidents.get(1).origin()).isEqualTo(Origin.INFERRED);
        // 값은 남는다 — 빼앗는 것은 제외 권한뿐이고 관련성 점수에는 여전히 쓸 수 있다.
        assertThat(incidents.get(1).value()).isEqualTo("포항 제철소 침수");
        assertThat(incidents.get(1).querySpan()).isNull();
        assertThat(result.findings())
                .extracting(AnchorFinding::path, AnchorFinding::action)
                .containsExactly(tuple("incident_names[1]", "demoted_to_inferred"));
    }

    @Test
    @DisplayName("원문에 실제로 있는 값은 그대로 둔다")
    void keepsGroundedExplicitAnchor() {
        QueryResolutionResult result = verifier.verify(
                RAW_QUERY,
                resolved(resolution()
                        .entities(List.of(new Entity(
                                EntityType.PERSON, "힌남노", Origin.EXPLICIT_QUERY, new QuerySpan(9, 12), 0.9)))));

        assertThat(result.resolution().entities().getFirst().origin()).isEqualTo(Origin.EXPLICIT_QUERY);
        assertThat(result.findings()).isEmpty();
    }

    @Test
    @DisplayName("값은 원문에 있는데 span 만 틀리면 강등이 아니라 교정한다")
    void correctsSpanWithoutDemoting() {
        QueryResolutionResult result = verifier.verify(
                RAW_QUERY,
                resolved(resolution()
                        .locations(List.of(new QueryResolution.Location(
                                QueryResolution.LocationType.LOCATION,
                                "힌남노",
                                Origin.EXPLICIT_QUERY,
                                new QuerySpan(0, 3),
                                0.8)))));

        QueryResolution.Location location = result.resolution().locations().getFirst();
        assertThat(location.origin()).isEqualTo(Origin.EXPLICIT_QUERY);
        assertThat(location.querySpan()).isEqualTo(new QuerySpan(9, 12));
        assertThat(result.findings())
                .extracting(AnchorFinding::path, AnchorFinding::action)
                .containsExactly(tuple("locations[0]", "span_corrected"));
    }

    @Test
    @DisplayName("날짜는 대조할 문자열이 없어 span 범위만 본다 — 원문을 벗어나면 강등")
    void demotesDateWindowWithOutOfRangeSpan() {
        QueryResolutionResult result = verifier.verify(
                RAW_QUERY,
                resolved(resolution()
                        .dateWindows(List.of(new DateWindow(
                                DateField.BROADCAST_DATE,
                                LocalDate.of(2023, 1, 1),
                                LocalDate.of(2024, 1, 1),
                                Origin.EXPLICIT_QUERY,
                                new QuerySpan(0, 999),
                                0.9)))));

        assertThat(result.resolution().dateWindows().getFirst().origin()).isEqualTo(Origin.INFERRED);
        assertThat(result.findings())
                .extracting(AnchorFinding::path, AnchorFinding::action)
                .containsExactly(tuple("date_windows[0]", "demoted_to_inferred"));
    }

    @Test
    @DisplayName("explicit_query 인데 span 이 없으면 강등한다")
    void demotesExplicitDateWindowWithoutSpan() {
        QueryResolutionResult result = verifier.verify(
                RAW_QUERY,
                resolved(resolution()
                        .dateWindows(List.of(new DateWindow(
                                DateField.BROADCAST_DATE,
                                LocalDate.of(2023, 1, 1),
                                LocalDate.of(2024, 1, 1),
                                Origin.EXPLICIT_QUERY,
                                null,
                                0.9)))));

        assertThat(result.resolution().dateWindows().getFirst().origin()).isEqualTo(Origin.INFERRED);
    }

    @Test
    @DisplayName("뒤집힌 날짜 구간은 강등이 아니라 버린다")
    void dropsInvertedDateWindow() {
        QueryResolutionResult result = verifier.verify(
                RAW_QUERY,
                resolved(resolution()
                        .dateWindows(List.of(new DateWindow(
                                DateField.BROADCAST_DATE,
                                LocalDate.of(2024, 1, 1),
                                LocalDate.of(2023, 1, 1),
                                Origin.INFERRED,
                                null,
                                0.5)))));

        assertThat(result.resolution().dateWindows()).isEmpty();
        assertThat(result.findings())
                .extracting(AnchorFinding::path, AnchorFinding::action)
                .containsExactly(tuple("date_windows[0]", "dropped"));
    }

    @Test
    @DisplayName("리졸버는 explicit_filter 를 낼 수 없다 — 사용자 조건 위조이므로 강등")
    void demotesExplicitFilterFromResolver() {
        QueryResolutionResult result = verifier.verify(
                RAW_QUERY,
                resolved(resolution()
                        .classifications(List.of(new Classification(
                                ClassificationType.WEATHER, "태풍", Origin.EXPLICIT_FILTER, new QuerySpan(4, 6), 0.9)))));

        assertThat(result.resolution().classifications().getFirst().origin()).isEqualTo(Origin.INFERRED);
        assertThat(result.findings()).extracting(AnchorFinding::action).containsExactly("demoted_to_inferred");
    }

    @Test
    @DisplayName("inferred 는 대조하지 않고 그대로 통과시킨다")
    void leavesInferredAnchorAlone() {
        QueryResolutionResult result = verifier.verify(
                RAW_QUERY,
                resolved(resolution()
                        .entities(List.of(new Entity(EntityType.ORGANIZATION, "기상청", Origin.INFERRED, null, 0.4)))));

        assertThat(result.resolution().entities().getFirst().origin()).isEqualTo(Origin.INFERRED);
        assertThat(result.findings()).isEmpty();
    }

    @Test
    @DisplayName("inferred 라도 원문 범위를 벗어난 span 은 버린다 — 강등은 아니다")
    void discardsOutOfRangeSpanOnInferredAnchor() {
        QueryResolutionResult result = verifier.verify(
                RAW_QUERY,
                resolved(resolution()
                        .entities(List.of(new Entity(
                                EntityType.ORGANIZATION, "기상청", Origin.INFERRED, new QuerySpan(0, 999), 0.4)))));

        Entity entity = result.resolution().entities().getFirst();
        // 출처는 건드리지 않는다. 이 span 으로 원문을 잘라 읽는 쪽이 깨지지 않게 좌표만 버린다.
        assertThat(entity.origin()).isEqualTo(Origin.INFERRED);
        assertThat(entity.querySpan()).isNull();
        assertThat(result.findings())
                .extracting(AnchorFinding::path, AnchorFinding::action)
                .containsExactly(tuple("entities[0]", "span_corrected"));
    }

    @Test
    @DisplayName("날짜 구간도 inferred 의 범위 밖 span 을 버린다")
    void discardsOutOfRangeSpanOnInferredDateWindow() {
        QueryResolutionResult result = verifier.verify(
                RAW_QUERY,
                resolved(resolution()
                        .dateWindows(List.of(new DateWindow(
                                DateField.BROADCAST_DATE,
                                LocalDate.of(2023, 1, 1),
                                LocalDate.of(2024, 1, 1),
                                Origin.INFERRED,
                                new QuerySpan(-1, 4),
                                0.5)))));

        DateWindow window = result.resolution().dateWindows().getFirst();
        assertThat(window.origin()).isEqualTo(Origin.INFERRED);
        assertThat(window.querySpan()).isNull();
    }

    @Test
    @DisplayName("날짜로 읽히지 않는 구간을 날짜의 explicit 근거로 주장하면 강등한다")
    void demotesDateWindowAnchoredOnNonDateText() {
        // span[0,2) 는 "태풍" 을 가리킨다. 좌표는 원문 안에 있지만 사용자가 2023년을 말한 근거는 못 된다.
        QueryResolutionResult result = verifier.verify(
                "태풍 피해 현장",
                resolved(resolution()
                        .dateWindows(List.of(new DateWindow(
                                DateField.BROADCAST_DATE,
                                LocalDate.of(2023, 1, 1),
                                LocalDate.of(2024, 1, 1),
                                Origin.EXPLICIT_QUERY,
                                new QuerySpan(0, 2),
                                0.9)))));

        assertThat(result.resolution().dateWindows().getFirst().origin()).isEqualTo(Origin.INFERRED);
        assertThat(result.findings())
                .extracting(AnchorFinding::path, AnchorFinding::action)
                .containsExactly(tuple("date_windows[0]", "demoted_to_inferred"));
    }

    @Test
    @DisplayName("숫자 없는 상대 날짜 표현은 강등하지 않는다")
    void keepsRelativeDateExpression() {
        QueryResolutionResult result = verifier.verify(
                "작년 여름 침수 현장",
                resolved(resolution()
                        .dateWindows(List.of(new DateWindow(
                                DateField.BROADCAST_DATE,
                                LocalDate.of(2025, 6, 1),
                                LocalDate.of(2025, 9, 1),
                                Origin.EXPLICIT_QUERY,
                                new QuerySpan(0, 5),
                                0.8)))));

        assertThat(result.resolution().dateWindows().getFirst().origin()).isEqualTo(Origin.EXPLICIT_QUERY);
        assertThat(result.findings()).isEmpty();
    }

    @Test
    @DisplayName("상대 표현은 사용자 시간대 기준으로 푼다 — 연말 자정 전후")
    void resolvesRelativePeriodInUserZone() {
        // 2025-12-31T15:30Z 는 KST 로 2026-01-01 00:30 이다. 한국 사용자에게 "작년" 은 2025년이다.
        // UTC 로 세면 오늘이 아직 2025-12-31 이라 "작년" 이 2024년이 되고, 맞게 쓴 조건이 강등된다.
        AnchorVerifier seoulVerifier =
                new AnchorVerifier(Clock.fixed(Instant.parse("2025-12-31T15:30:00Z"), ZoneId.of("Asia/Seoul")));

        QueryResolutionResult result = seoulVerifier.verify(
                "작년 여름 침수 현장",
                resolved(resolution()
                        .dateWindows(List.of(new DateWindow(
                                DateField.BROADCAST_DATE,
                                LocalDate.of(2025, 6, 1),
                                LocalDate.of(2025, 9, 1),
                                Origin.EXPLICIT_QUERY,
                                new QuerySpan(0, 5),
                                0.8)))));

        assertThat(result.resolution().dateWindows().getFirst().origin()).isEqualTo(Origin.EXPLICIT_QUERY);
        assertThat(result.findings()).isEmpty();
    }

    @Test
    @DisplayName("숫자가 있어도 기간을 읽어낼 수 없으면 강등한다")
    void demotesDateWindowAnchoredOnNonPeriodNumber() {
        // "3명" 의 3 은 인원수다. 숫자가 있다는 것만으로는 날짜의 근거가 되지 않는다.
        QueryResolutionResult result = verifier.verify(
                "3명 구조 현장",
                resolved(resolution()
                        .dateWindows(List.of(new DateWindow(
                                DateField.BROADCAST_DATE,
                                LocalDate.of(2023, 1, 1),
                                LocalDate.of(2024, 1, 1),
                                Origin.EXPLICIT_QUERY,
                                new QuerySpan(0, 2),
                                0.9)))));

        assertThat(result.resolution().dateWindows().getFirst().origin()).isEqualTo(Origin.INFERRED);
        assertThat(result.findings())
                .extracting(AnchorFinding::path, AnchorFinding::action)
                .containsExactly(tuple("date_windows[0]", "demoted_to_inferred"));
    }

    @Test
    @DisplayName("원문이 가리키는 기간 밖의 구간은 강등한다")
    void demotesDateWindowOutsideDenotedPeriod() {
        // 사용자는 2024년이라 썼는데 리졸버가 2023년 구간을 붙였다.
        QueryResolutionResult result = verifier.verify(
                "2024년 태풍",
                resolved(resolution()
                        .dateWindows(List.of(new DateWindow(
                                DateField.BROADCAST_DATE,
                                LocalDate.of(2023, 1, 1),
                                LocalDate.of(2024, 1, 1),
                                Origin.EXPLICIT_QUERY,
                                new QuerySpan(0, 5),
                                0.9)))));

        assertThat(result.resolution().dateWindows().getFirst().origin()).isEqualTo(Origin.INFERRED);
        assertThat(result.findings())
                .extracting(AnchorFinding::path, AnchorFinding::action)
                .containsExactly(tuple("date_windows[0]", "demoted_to_inferred"));
    }

    @Test
    @DisplayName("연·월까지 짚은 구간은 그 달 안에 들면 유지한다")
    void keepsWindowInsideDenotedMonth() {
        QueryResolutionResult result = verifier.verify(
                "2023년 7월 집중호우",
                resolved(resolution()
                        .dateWindows(List.of(new DateWindow(
                                DateField.BROADCAST_DATE,
                                LocalDate.of(2023, 7, 1),
                                LocalDate.of(2023, 8, 1),
                                Origin.EXPLICIT_QUERY,
                                new QuerySpan(0, 9),
                                0.9)))));

        assertThat(result.resolution().dateWindows().getFirst().origin()).isEqualTo(Origin.EXPLICIT_QUERY);
        assertThat(result.findings()).isEmpty();
    }

    @Test
    @DisplayName("이모지가 있어도 날짜와 문자열 anchor 의 span 단위가 갈리지 않는다")
    void returnsEverySpanInJavaIndicesWhenQueryHasSurrogatePair() {
        // "😀2023년 서울" — 코드 포인트로 날짜는 [1,6), "서울" 은 [7,9).
        // Java 인덱스로는 이모지가 2칸이라 각각 [2,7) 과 [8,10) 이다.
        String rawQuery = "😀2023년 서울";
        QueryResolutionResult result = verifier.verify(
                rawQuery,
                resolved(resolution()
                        .dateWindows(List.of(new DateWindow(
                                DateField.BROADCAST_DATE,
                                LocalDate.of(2023, 1, 1),
                                LocalDate.of(2024, 1, 1),
                                Origin.EXPLICIT_QUERY,
                                new QuerySpan(1, 6),
                                0.9)))
                        .locations(List.of(new QueryResolution.Location(
                                QueryResolution.LocationType.LOCATION,
                                "서울",
                                Origin.EXPLICIT_QUERY,
                                new QuerySpan(7, 9),
                                0.9)))));

        QuerySpan dateSpan = result.resolution().dateWindows().getFirst().querySpan();
        QuerySpan locationSpan = result.resolution().locations().getFirst().querySpan();
        // 두 span 모두 Java 인덱스라 그대로 잘라 읽을 수 있다.
        assertThat(rawQuery.substring(dateSpan.start(), dateSpan.end())).isEqualTo("2023년");
        assertThat(rawQuery.substring(locationSpan.start(), locationSpan.end())).isEqualTo("서울");
        // 좌표가 맞게 옮겨졌으므로 교정할 것이 없다.
        assertThat(result.findings()).isEmpty();
    }

    @Test
    @DisplayName("이모지 원문에서 코드 포인트 범위를 넘는 span 은 강등한다")
    void demotesSpanBeyondCodePointCount() {
        // "😀서울" 은 Java 길이 4, 코드 포인트 3. end=4 는 Java 길이 안이지만 코드 포인트로는 범위 밖이다.
        QueryResolutionResult result = verifier.verify(
                "😀서울",
                resolved(resolution()
                        .dateWindows(List.of(new DateWindow(
                                DateField.BROADCAST_DATE,
                                LocalDate.of(2023, 1, 1),
                                LocalDate.of(2024, 1, 1),
                                Origin.EXPLICIT_QUERY,
                                new QuerySpan(1, 4),
                                0.9)))));

        assertThat(result.resolution().dateWindows().getFirst().origin()).isEqualTo(Origin.INFERRED);
    }

    @Test
    @DisplayName("원문 없이 부르면 거부한다 — 조용히 전부 강등하지 않는다")
    void rejectsNullRawQuery() {
        QueryResolutionResult given = resolved(resolution()
                .entities(List.of(
                        new Entity(EntityType.PERSON, "힌남노", Origin.EXPLICIT_QUERY, new QuerySpan(9, 12), 0.9))));

        assertThatNullPointerException().isThrownBy(() -> verifier.verify(null, given));
    }

    @Test
    @DisplayName("리졸버가 준 findings 를 버리지 않고 뒤에 이어 붙인다")
    void keepsResolverFindings() {
        AnchorFinding fromResolver = new AnchorFinding("entities[0]", "dropped", "locations 에 같은 값이 있다");
        QueryResolutionResult given = new QueryResolutionResult(
                normalization(),
                resolution()
                        .incidentNames(
                                List.of(new IncidentName("없는사건", Origin.EXPLICIT_QUERY, new QuerySpan(0, 4), 0.6)))
                        .build(),
                List.of(fromResolver),
                "query-resolver/v2",
                "query-resolver-prompt/v3",
                "gemma3:12b",
                null);

        QueryResolutionResult result = verifier.verify(RAW_QUERY, given);

        assertThat(result.findings()).hasSize(2).first().isEqualTo(fromResolver);
    }

    @Test
    @DisplayName("해석 실패면 손대지 않고 그대로 돌려준다")
    void passesThroughUnresolvedResult() {
        QueryResolutionResult failed = new QueryResolutionResult(
                normalization(), null, List.of(), null, null, null, QueryResolverErrorCode.RESOLVER_TIMEOUT);

        assertThat(verifier.verify(RAW_QUERY, failed)).isSameAs(failed);
    }

    private static QueryResolutionResult resolved(ResolutionBuilder builder) {
        return new QueryResolutionResult(
                normalization(),
                builder.build(),
                List.of(),
                "query-resolver/v2",
                "query-resolver-prompt/v3",
                "gemma3:12b",
                null);
    }

    private static QueryNormalization normalization() {
        return new QueryNormalization("2023 태풍 힌남노 피해 현장", List.of("2023", "태풍", "힌남노", "피해", "현장"), "query-norm/v1");
    }

    private static ResolutionBuilder resolution() {
        return new ResolutionBuilder();
    }

    /** 관심 있는 배열 하나만 채우고 나머지는 비워 두기 위한 테스트 전용 빌더. */
    private static final class ResolutionBuilder {

        private List<DateWindow> dateWindows = List.of();
        private List<IncidentName> incidentNames = List.of();
        private List<Entity> entities = List.of();
        private List<QueryResolution.Location> locations = List.of();
        private List<Classification> classifications = List.of();

        ResolutionBuilder dateWindows(List<DateWindow> value) {
            this.dateWindows = value;
            return this;
        }

        ResolutionBuilder incidentNames(List<IncidentName> value) {
            this.incidentNames = value;
            return this;
        }

        ResolutionBuilder entities(List<Entity> value) {
            this.entities = value;
            return this;
        }

        ResolutionBuilder locations(List<QueryResolution.Location> value) {
            this.locations = value;
            return this;
        }

        ResolutionBuilder classifications(List<Classification> value) {
            this.classifications = value;
            return this;
        }

        QueryResolution build() {
            return new QueryResolution(
                    "query-resolver/v2",
                    Intent.SCENE_SEARCH,
                    dateWindows,
                    incidentNames,
                    entities,
                    locations,
                    classifications,
                    List.of(),
                    0.8);
        }
    }
}
