package com.npick.search.domain.policy;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.npick.common.error.BusinessException;
import com.npick.search.domain.error.SearchErrorCode;
import com.npick.search.domain.model.ExplicitDateFilters;
import com.npick.search.domain.model.ExplicitDateFilters.ClosedRange;
import com.npick.search.domain.model.QueryResolution;
import com.npick.search.domain.model.QueryResolution.DateField;
import com.npick.search.domain.model.QueryResolution.DateWindow;
import com.npick.search.domain.model.QueryResolution.Origin;
import com.npick.search.domain.model.QueryResolution.QuerySpan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;

/**
 * UI 에서 고른 필터가 리졸버·규칙보다 우선한다 (FRD v3.2 F-05 「사용자가 직접 입력한 필터는 AI나 규칙이 바꿀 수 없다」).
 *
 * <p>{@code AnchorVerifier} 가 리졸버의 {@code explicit_filter} 주장을 전부 강등시키므로, 이 클래스가 그 출처를 만드는 유일한 통로다.
 */
class ExplicitFilterPolicyTest {

    private final ExplicitFilterPolicy policy = new ExplicitFilterPolicy();

    @Test
    @DisplayName("필터가 없으면 해석을 그대로 둔다")
    void keepsResolutionWhenNoFilter() {
        QueryResolution resolution = resolutionWith(window(DateField.BROADCAST_DATE, 2025));

        QueryResolution applied = policy.apply(resolution, ExplicitDateFilters.none());

        assertThat(applied.dateWindows())
                .extracting(DateWindow::field, DateWindow::start, DateWindow::origin)
                .containsExactly(tuple(DateField.BROADCAST_DATE, LocalDate.of(2025, 1, 1), Origin.INFERRED));
    }

    @Test
    @DisplayName("같은 종류의 리졸버 날짜 조건을 필터가 대체한다")
    void filterBeatsResolverOnSameDateField() {
        QueryResolution resolution = resolutionWith(window(DateField.BROADCAST_DATE, 2025));

        QueryResolution applied = policy.apply(resolution, broadcastFilter());

        assertThat(applied.dateWindows())
                .extracting(DateWindow::field, DateWindow::start, DateWindow::endExclusive, DateWindow::origin)
                .containsExactly(tuple(
                        DateField.BROADCAST_DATE,
                        LocalDate.of(2023, 1, 1),
                        LocalDate.of(2024, 1, 1),
                        Origin.EXPLICIT_FILTER));
    }

    @Test
    @DisplayName("필터에 없는 종류의 날짜 조건은 건드리지 않는다")
    void leavesOtherDateFieldUntouched() {
        // BR-DATE-001 — 방송일 필터를 걸었다고 사용자가 검색어에 쓴 촬영일 조건이 사라지면 안 된다.
        QueryResolution resolution = resolutionWith(explicitFilmedWindow(), window(DateField.BROADCAST_DATE, 2025));

        QueryResolution applied = policy.apply(resolution, broadcastFilter());

        assertThat(applied.dateWindows())
                .extracting(DateWindow::field, DateWindow::start, DateWindow::origin)
                .containsExactlyInAnyOrder(
                        tuple(DateField.FILMED_DATE, LocalDate.of(2022, 9, 1), Origin.EXPLICIT_QUERY),
                        tuple(DateField.BROADCAST_DATE, LocalDate.of(2023, 1, 1), Origin.EXPLICIT_FILTER));
    }

    @Test
    @DisplayName("방송일 필터를 촬영일 조건으로 복사하지 않는다")
    void neverCopiesFilterToTheOtherDateField() {
        // BR-DATE-003 — 2022 년에 찍어 2023 년에 방송한 장면이 촬영일 충돌로 잘려나가는 것을 막는다.
        QueryResolution applied = policy.apply(resolutionWith(), broadcastFilter());

        assertThat(applied.dateWindows()).extracting(DateWindow::field).containsExactly(DateField.BROADCAST_DATE);
    }

    @Test
    @DisplayName("두 종류를 모두 고르면 둘 다 대체한다")
    void appliesBothDateFields() {
        QueryResolution resolution =
                resolutionWith(window(DateField.BROADCAST_DATE, 2025), window(DateField.FILMED_DATE, 2024));

        QueryResolution applied = policy.apply(
                resolution,
                new ExplicitDateFilters(Map.of(
                        DateField.BROADCAST_DATE, new ClosedRange(LocalDate.of(2023, 1, 1), LocalDate.of(2023, 12, 31)),
                        DateField.FILMED_DATE, new ClosedRange(LocalDate.of(2022, 8, 28), LocalDate.of(2022, 8, 29)))));

        // 순서가 그대로 search_execution.parsed_query_json 에 남는다. 실행마다 달라지면 같은 검색의
        // 기록이 매번 다른 모양이 된다 — NormalizedSearch 가 지문 때문에 정렬하는 것과 같은 이유다.
        assertThat(applied.dateWindows())
                .extracting(DateWindow::field, DateWindow::start, DateWindow::endExclusive, DateWindow::origin)
                .containsExactly(
                        tuple(
                                DateField.BROADCAST_DATE,
                                LocalDate.of(2023, 1, 1),
                                LocalDate.of(2024, 1, 1),
                                Origin.EXPLICIT_FILTER),
                        tuple(
                                DateField.FILMED_DATE,
                                LocalDate.of(2022, 8, 28),
                                LocalDate.of(2022, 8, 30),
                                Origin.EXPLICIT_FILTER));
    }

    @Test
    @DisplayName("리졸버가 날짜를 내지 않았어도 필터는 조건이 된다")
    void addsFilterWhenResolverFoundNoDate() {
        QueryResolution applied = policy.apply(resolutionWith(), broadcastFilter());

        assertThat(applied.dateWindows())
                .extracting(DateWindow::origin, DateWindow::querySpan)
                .containsExactly(tuple(Origin.EXPLICIT_FILTER, null));
    }

    @Test
    @DisplayName("하루만 고르면 그 하루가 구간이 된다")
    void singleDayBecomesOneDayWindow() {
        // 계약(web-api.md §5)의 from·to 는 둘 다 포함이고 DateWindow 는 반열린이다.
        QueryResolution applied = policy.apply(
                resolutionWith(),
                new ExplicitDateFilters(Map.of(
                        DateField.BROADCAST_DATE,
                        new ClosedRange(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 1)))));

        assertThat(applied.dateWindows())
                .extracting(DateWindow::start, DateWindow::endExclusive)
                .containsExactly(tuple(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 2)));
    }

    @Test
    @DisplayName("시작일이 종료일보다 늦으면 거부한다")
    void rejectsInvertedRange() {
        assertThatThrownBy(() -> new ClosedRange(LocalDate.of(2026, 9, 3), LocalDate.of(2026, 9, 1)))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).errorCode())
                .isEqualTo(SearchErrorCode.EXPLICIT_FILTER_RANGE_INVERTED);
    }

    @Test
    @DisplayName("원본 해석을 바꾸지 않는다")
    void doesNotMutateInput() {
        QueryResolution resolution = resolutionWith(window(DateField.BROADCAST_DATE, 2025));

        policy.apply(resolution, broadcastFilter());

        assertThat(resolution.dateWindows())
                .extracting(DateWindow::start, DateWindow::origin)
                .containsExactly(tuple(LocalDate.of(2025, 1, 1), Origin.INFERRED));
    }

    @Test
    @DisplayName("필터에 널이 섞이면 거부한다")
    void rejectsNullInFilters() {
        Map<DateField, ClosedRange> withNullValue = new HashMap<>();
        withNullValue.put(DateField.BROADCAST_DATE, null);

        assertThatThrownBy(() -> new ExplicitDateFilters(withNullValue))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).errorCode())
                .isEqualTo(SearchErrorCode.FILTER_CONTAINS_NULL);
        assertThatThrownBy(() -> new ExplicitDateFilters(null))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).errorCode())
                .isEqualTo(SearchErrorCode.FILTER_CONTAINS_NULL);
    }

    @Test
    @DisplayName("구간의 양끝에 널이 오면 거부한다")
    void rejectsNullRangeBound() {
        // 뒤집힌 구간은 SRCH_400_004 로 정성껏 처리하면서 널만 타입 없는 NPE 로 새어 나가면
        // 요청 DTO 가 붙는 순간 400 이어야 할 입력이 500 이 된다.
        assertThatThrownBy(() -> new ClosedRange(null, LocalDate.of(2026, 9, 1)))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).errorCode())
                .isEqualTo(SearchErrorCode.FILTER_CONTAINS_NULL);
        assertThatThrownBy(() -> new ClosedRange(LocalDate.of(2026, 9, 1), null))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).errorCode())
                .isEqualTo(SearchErrorCode.FILTER_CONTAINS_NULL);
    }

    private static ExplicitDateFilters broadcastFilter() {
        return new ExplicitDateFilters(Map.of(
                DateField.BROADCAST_DATE, new ClosedRange(LocalDate.of(2023, 1, 1), LocalDate.of(2023, 12, 31))));
    }

    /** 리졸버가 추측으로 낸 한 해 구간. */
    private static DateWindow window(DateField field, int year) {
        return new DateWindow(
                field, LocalDate.of(year, 1, 1), LocalDate.of(year + 1, 1, 1), Origin.INFERRED, null, 0.5);
    }

    /** 사용자가 검색어에 직접 쓴 촬영일 조건. */
    private static DateWindow explicitFilmedWindow() {
        return new DateWindow(
                DateField.FILMED_DATE,
                LocalDate.of(2022, 9, 1),
                LocalDate.of(2022, 10, 1),
                Origin.EXPLICIT_QUERY,
                new QuerySpan(0, 8),
                0.9);
    }

    private static QueryResolution resolutionWith(DateWindow... windows) {
        return new QueryResolution(
                "query-resolver/v2",
                QueryResolution.Intent.SCENE_SEARCH,
                List.of(windows),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                0.8);
    }
}
