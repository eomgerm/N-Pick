package com.npick.search.application.query.guard;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.npick.search.domain.model.GuardExclusionReason;
import com.npick.search.domain.model.GuardJudgment;
import com.npick.search.domain.model.QueryResolution;
import com.npick.search.domain.model.QueryResolution.DateField;
import com.npick.search.domain.model.QueryResolution.Origin;
import com.npick.tag.domain.model.EffectiveTag;
import com.npick.tag.domain.model.TagType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 판정을 순위에 적용하는 부분만 본다. 분기별 판정은 FalseHitGuardPolicyTest 가 덮는다. */
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
        assertThat(result.incidentGuardActive())
                .as("승인된 사건 충돌 규칙이 없으므로 이 실행의 지표는 측정 불가다")
                .isFalse();
        assertThat(result.excluded()).singleElement().satisfies(verdict -> {
            assertThat(verdict.sceneId()).isEqualTo(20L);
            assertThat(verdict.exclusionReason()).isEqualTo(GuardExclusionReason.EXPLICIT_DATE_CONFLICT);
            assertThat(verdict.fields()).singleElement().satisfies(field -> {
                assertThat(field.field()).isEqualTo(DateField.BROADCAST_DATE);
                assertThat(field.judgment()).isEqualTo(GuardJudgment.VERIFIED_CONFLICT);
                assertThat(field.groundingTagIds()).containsExactly(2L);
            });
        });
    }

    @Test
    @DisplayName("통과한 장면도 판정을 남긴다 — explain_json 의 guard 자리가 비면 안 된다")
    void keptScenesAlsoCarryAVerdict() {
        var query = new ApplyFalseHitGuardQuery(
                resolution(),
                List.of(10L, 20L),
                Map.of(10L, List.of(broadcast(1L, "2026-09-02")), 20L, List.of(broadcast(2L, "2026-08-20"))));

        var result = service.apply(query);

        assertThat(result.verdicts()).hasSize(2).extracting("sceneId").containsExactly(10L, 20L);
        assertThat(result.verdicts().getFirst().fields())
                .singleElement()
                .satisfies(field -> assertThat(field.judgment()).isEqualTo(GuardJudgment.VERIFIED_MATCH));
    }

    @Test
    @DisplayName("유효 태그가 없는 장면은 빈 목록으로 들어오고 그대로 남는다")
    void sceneWithoutTagsSurvives() {
        var query = new ApplyFalseHitGuardQuery(resolution(), List.of(10L), Map.of(10L, List.of()));

        var result = service.apply(query);

        assertThat(result.sceneIds()).containsExactly(10L);
        assertThat(result.verdicts().getFirst().fields())
                .singleElement()
                .satisfies(field -> assertThat(field.judgment()).isEqualTo(GuardJudgment.UNKNOWN_OR_UNVERIFIED));
    }

    @Test
    @DisplayName("순위에 있는 장면의 태그가 빠지면 거부한다 — 조용히 통과시키지 않는다")
    void rejectsMissingTagEntry() {
        assertThatThrownBy(() -> new ApplyFalseHitGuardQuery(resolution(), List.of(10L, 20L), Map.of(10L, List.of())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("20");
    }

    @Test
    @DisplayName("통과한 판정은 제외 사유를 내지 않는다")
    void nonExcludingJudgmentHasNoReason() {
        var query = new ApplyFalseHitGuardQuery(
                resolution(), List.of(10L), Map.of(10L, List.of(broadcast(1L, "2026-09-02"))));

        var verdict = service.apply(query).verdicts().getFirst();

        assertThat(verdict.exclusionReason()).isNull();
        assertThat(verdict.fields())
                .singleElement()
                .satisfies(field -> assertThat(field.conflictReason())
                        .as("통과한 판정에서 사유를 꺼내도 제외 사유가 나오면 안 된다")
                        .isNull());
    }

    @Test
    @DisplayName("태그 값이 null 이어도 누락과 같은 메시지로 거부한다")
    void rejectsNullTagValueWithTheSameMessage() {
        var withNull = new HashMap<Long, List<EffectiveTag>>();
        withNull.put(10L, null);

        assertThatThrownBy(() -> new ApplyFalseHitGuardQuery(resolution(), List.of(10L), withNull))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("10");
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
