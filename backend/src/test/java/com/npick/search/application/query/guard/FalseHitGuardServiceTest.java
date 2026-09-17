package com.npick.search.application.query.guard;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.npick.search.domain.model.GuardExclusionReason;
import com.npick.search.domain.model.QueryResolution;
import com.npick.search.domain.model.QueryResolution.DateField;
import com.npick.search.domain.model.QueryResolution.Origin;
import com.npick.tag.domain.model.EffectiveTag;
import com.npick.tag.domain.model.TagType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 판정을 순위에 적용하는 부분만 본다. 분기별 판정은 {@code FalseHitGuardPolicyTest} 가 덮는다. */
class FalseHitGuardServiceTest {

    private final FalseHitGuardService service = new FalseHitGuardService();

    @Test
    @DisplayName("제외한 자리를 메우지 않고 남은 순서를 그대로 유지한다")
    void keepsRankingOrderWithoutBackfilling() {
        var query = new ApplyFalseHitGuardQuery(
                resolution(),
                List.of(10L, 20L, 30L),
                Map.of(
                        10L, List.of(broadcast(1L, "2026-09-02")),
                        20L, List.of(broadcast(2L, "2026-08-20")),
                        30L, List.of(broadcast(3L, "2026-09-03"))));

        var result = service.apply(query);

        assertThat(result.sceneIds()).containsExactly(10L, 30L);
        assertThat(result.excludedScenes()).singleElement().satisfies(excluded -> {
            assertThat(excluded.sceneId()).isEqualTo(20L);
            assertThat(excluded.reason()).isEqualTo(GuardExclusionReason.EXPLICIT_DATE_CONFLICT);
            assertThat(excluded.field()).isEqualTo(DateField.BROADCAST_DATE);
            assertThat(excluded.conflictingTagIds()).containsExactly(2L);
        });
    }

    @Test
    @DisplayName("유효 태그가 없는 장면은 빈 목록으로 들어오고 그대로 남는다")
    void sceneWithoutTagsSurvives() {
        var query = new ApplyFalseHitGuardQuery(resolution(), List.of(10L), Map.of(10L, List.of()));

        assertThat(service.apply(query).sceneIds()).containsExactly(10L);
    }

    @Test
    @DisplayName("순위에 있는 장면의 태그가 빠지면 거부한다 — 조용히 통과시키지 않는다")
    void rejectsMissingTagEntry() {
        assertThatThrownBy(() -> new ApplyFalseHitGuardQuery(resolution(), List.of(10L, 20L), Map.of(10L, List.of())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("20");
    }

    @Test
    @DisplayName("사건명 충돌 자동 제외는 꺼져 있다")
    void incidentGuardIsInactive() {
        assertThat(service.incidentGuardActive()).isFalse();
    }

    private static QueryResolution resolution() {
        return new QueryResolution(
                "query-resolver/v2",
                QueryResolution.Intent.SCENE_SEARCH,
                List.of(new QueryResolution.DateWindow(
                        DateField.BROADCAST_DATE,
                        LocalDate.of(2026, 9, 1),
                        LocalDate.of(2026, 9, 4),
                        Origin.EXPLICIT_FILTER,
                        null,
                        1.0)),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                1.0);
    }

    private static EffectiveTag broadcast(long tagId, String value) {
        return new EffectiveTag(
                1L,
                2L,
                tagId,
                TagType.BROADCAST_DATE,
                value,
                value,
                EffectiveTag.Verification.VERIFIED,
                EffectiveTag.Scope.SCENE,
                "user_input");
    }
}
