package com.npick.search.application.query.exclusion;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.npick.search.application.query.exclusion.ActiveSceneExclusionResult.ExcludedScene;
import com.npick.search.application.query.exclusion.FindActiveSceneExclusionsQueryPort.ActiveSceneExclusion;
import com.npick.search.domain.model.NormalizedSearch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ActiveSceneExclusionServiceTest {

    private final FindActiveSceneExclusionsQueryPort exclusions = mock(FindActiveSceneExclusionsQueryPort.class);
    private final ActiveSceneExclusionService service = new ActiveSceneExclusionService(exclusions);
    private final NormalizedSearch search = NormalizedSearch.of("제주 불꽃놀이", Map.of("region", List.of("제주")), "v1");

    @Test
    void appliesEveryMatchingRuleBeforeTakingTheTopTen() {
        when(exclusions.find(search))
                .thenReturn(List.of(
                        new ActiveSceneExclusion(101, 2),
                        new ActiveSceneExclusion(102, 2),
                        new ActiveSceneExclusion(103, 5)));

        var result = service.apply(new ApplyActiveSceneExclusionsQuery(
                search, List.of(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L, 11L, 12L)));

        assertThat(result.sceneIds()).containsExactly(1L, 3L, 4L, 6L, 7L, 8L, 9L, 10L, 11L, 12L);
        assertThat(result.excludedScenes())
                .containsExactly(new ExcludedScene(2, List.of(101L, 102L)), new ExcludedScene(5, List.of(103L)));
        verify(exclusions).find(search);
    }

    @Test
    void returnsFewerThanTenWithoutChangingTheRemainingOrder() {
        when(exclusions.find(search)).thenReturn(List.of(new ActiveSceneExclusion(101, 3)));

        var result = service.apply(new ApplyActiveSceneExclusionsQuery(search, List.of(5L, 3L, 4L, 2L)));

        assertThat(result.sceneIds()).containsExactly(5L, 4L, 2L);
        assertThat(result.excludedScenes()).containsExactly(new ExcludedScene(3, List.of(101L)));
    }

    @Test
    void takesTheFirstTenWhenThereIsNoApplicableRule() {
        when(exclusions.find(search)).thenReturn(List.of());

        var result = service.apply(
                new ApplyActiveSceneExclusionsQuery(search, List.of(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L, 11L)));

        assertThat(result.sceneIds()).containsExactly(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L);
        assertThat(result.excludedScenes()).isEmpty();
    }

    @Test
    void stillChecksActiveRulesWhenThereIsNoCandidate() {
        when(exclusions.find(search)).thenReturn(List.of());

        var result = service.apply(new ApplyActiveSceneExclusionsQuery(search, List.of()));

        assertThat(result.sceneIds()).isEmpty();
        assertThat(result.excludedScenes()).isEmpty();
        verify(exclusions).find(search);
    }

    @Test
    void takesTheRequestedPageOfValidCandidates() {
        when(exclusions.find(search)).thenReturn(List.of());
        List<Long> ranked = new ArrayList<>();
        for (long id = 1; id <= 25; id++) {
            ranked.add(id);
        }

        var page0 = service.apply(new ApplyActiveSceneExclusionsQuery(search, ranked, 0));
        assertThat(page0.sceneIds()).containsExactly(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L);
        assertThat(page0.hasMore()).isTrue();

        var page1 = service.apply(new ApplyActiveSceneExclusionsQuery(search, ranked, 1));
        assertThat(page1.sceneIds()).containsExactly(11L, 12L, 13L, 14L, 15L, 16L, 17L, 18L, 19L, 20L);
        assertThat(page1.hasMore()).isTrue();

        var page2 = service.apply(new ApplyActiveSceneExclusionsQuery(search, ranked, 2));
        assertThat(page2.sceneIds()).containsExactly(21L, 22L, 23L, 24L, 25L);
        assertThat(page2.hasMore()).isFalse();
    }

    @Test
    void pageOffsetCountsValidCandidatesSkippingExclusions() {
        // 3L 이 제외되면 page 0 은 유효 10개(1,2,4..11), page 1 은 12 부터다.
        when(exclusions.find(search)).thenReturn(List.of(new ActiveSceneExclusion(201, 3)));
        List<Long> ranked = new ArrayList<>();
        for (long id = 1; id <= 14; id++) {
            ranked.add(id);
        }

        var page0 = service.apply(new ApplyActiveSceneExclusionsQuery(search, ranked, 0));
        assertThat(page0.sceneIds()).containsExactly(1L, 2L, 4L, 5L, 6L, 7L, 8L, 9L, 10L, 11L);
        assertThat(page0.excludedScenes()).containsExactly(new ExcludedScene(3, List.of(201L)));
        assertThat(page0.hasMore()).isTrue();

        var page1 = service.apply(new ApplyActiveSceneExclusionsQuery(search, ranked, 1));
        assertThat(page1.sceneIds()).containsExactly(12L, 13L, 14L);
        // 3L 제외는 page 0 구간이라 page 1 사유에 다시 오르지 않는다.
        assertThat(page1.excludedScenes()).isEmpty();
        assertThat(page1.hasMore()).isFalse();
    }
}
