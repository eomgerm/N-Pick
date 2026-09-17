package com.npick.search.domain.policy;

import java.time.LocalDate;
import java.util.List;

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

/**
 * F-06 완료 기준과 PRD 7.9 의 3 값 판정을 항목별로 확인한다.
 *
 * <p>제외는 되돌릴 수 없으므로 제외되지 않아야 하는 경우가 시험의 대부분이다. 통과한 경우에도 판정 값을 함께 본다 — explain_json 의 guard 자리가 그 값으로 채워진다.
 */
class FalseHitGuardPolicyTest {

    private static final LocalDate FROM = LocalDate.of(2026, 9, 1);
    private static final LocalDate TO_EXCLUSIVE = LocalDate.of(2026, 9, 4);

    private final FalseHitGuardPolicy policy = new FalseHitGuardPolicy();

    @Test
    @DisplayName("명시한 날짜와 같은 종류의 검증된 날짜가 충돌하면 제외한다")
    void excludesWhenStatedDateConflictsWithVerifiedSameField() {
        var verdict = policy.judge(
                withWindow(DateField.BROADCAST_DATE, Origin.EXPLICIT_FILTER), List.of(verified(7L, "2026-08-20")));

        assertThat(verdict.excluded()).isTrue();
        assertThat(verdict.exclusionReason()).isEqualTo(GuardExclusionReason.EXPLICIT_DATE_CONFLICT);
        assertThat(verdict.fields()).singleElement().satisfies(field -> {
            assertThat(field.field()).isEqualTo(DateField.BROADCAST_DATE);
            assertThat(field.judgment()).isEqualTo(GuardJudgment.VERIFIED_CONFLICT);
            assertThat(field.groundingTagIds()).containsExactly(7L);
        });
    }

    @Test
    @DisplayName("사용자가 원문에 친 조건도 제외 근거가 된다")
    void explicitQueryOriginAlsoGrounds() {
        var verdict = policy.judge(
                withWindow(DateField.BROADCAST_DATE, Origin.EXPLICIT_QUERY), List.of(verified(7L, "2026-08-20")));

        assertThat(verdict.excluded()).isTrue();
    }

    @Test
    @DisplayName("일치하면 통과하고 근거 태그를 남긴다")
    void verifiedMatchKeepsAndRecordsGrounds() {
        var verdict = policy.judge(
                withWindow(DateField.BROADCAST_DATE, Origin.EXPLICIT_FILTER), List.of(verified(7L, "2026-09-02")));

        assertThat(verdict.excluded()).isFalse();
        assertThat(verdict.fields()).singleElement().satisfies(field -> {
            assertThat(field.judgment()).isEqualTo(GuardJudgment.VERIFIED_MATCH);
            assertThat(field.groundingTagIds()).containsExactly(7L);
        });
    }

    @Test
    @DisplayName("날짜 정보가 아예 없으면 미상으로 판정하고 통과시킨다")
    void missingDateIsJudgedUnknown() {
        var verdict = policy.judge(withWindow(DateField.BROADCAST_DATE, Origin.EXPLICIT_FILTER), List.of());

        assertThat(verdict.excluded()).isFalse();
        assertThat(verdict.fields())
                .singleElement()
                .satisfies(field -> assertThat(field.judgment()).isEqualTo(GuardJudgment.UNKNOWN_OR_UNVERIFIED));
    }

    @Test
    @DisplayName("검증되지 않은 날짜는 충돌 근거가 되지 않고 미검증으로 판정된다")
    void unverifiedDateIsJudgedUnverified() {
        var tag = tag(7L, TagType.BROADCAST_DATE, "2026-08-20", EffectiveTag.Verification.UNVERIFIED);

        var verdict = policy.judge(withWindow(DateField.BROADCAST_DATE, Origin.EXPLICIT_FILTER), List.of(tag));

        assertThat(verdict.excluded()).isFalse();
        assertThat(verdict.fields())
                .singleElement()
                .satisfies(field -> assertThat(field.judgment()).isEqualTo(GuardJudgment.UNKNOWN_OR_UNVERIFIED));
    }

    @Test
    @DisplayName("AI 가 추정한 조건만 있으면 anchor 가 아니므로 판정하지 않는다")
    void inferredAnchorProducesNoJudgment() {
        var verdict = policy.judge(
                withWindow(DateField.BROADCAST_DATE, Origin.INFERRED), List.of(verified(7L, "2026-08-20")));

        assertThat(verdict.excluded()).isFalse();
        assertThat(verdict.fields()).isEmpty();
    }

    @Test
    @DisplayName("방송일 조건에 촬영일을 맞대지 않는다")
    void doesNotCompareAcrossDateKinds() {
        var filmed = tag(7L, TagType.FILMED_DATE, "2022-05-01", EffectiveTag.Verification.VERIFIED);

        var verdict = policy.judge(withWindow(DateField.BROADCAST_DATE, Origin.EXPLICIT_FILTER), List.of(filmed));

        assertThat(verdict.excluded()).isFalse();
        assertThat(verdict.fields())
                .singleElement()
                .satisfies(field -> assertThat(field.judgment()).isEqualTo(GuardJudgment.UNKNOWN_OR_UNVERIFIED));
    }

    @Test
    @DisplayName("인물·기관 불일치는 판정 대상이 아니다")
    void entityMismatchIsNotJudged() {
        var resolution = resolution(
                List.of(),
                List.of(),
                List.of(new QueryResolution.Entity(
                        QueryResolution.EntityType.PERSON, "홍길동", Origin.EXPLICIT_QUERY, null, 1.0)));
        var other = tag(9L, TagType.PERSON, "임꺽정", EffectiveTag.Verification.REVIEWER_VERIFIED);

        var verdict = policy.judge(resolution, List.of(other));

        assertThat(verdict.excluded()).isFalse();
        assertThat(verdict.fields()).isEmpty();
    }

    @Test
    @DisplayName("승인된 규칙이 없으므로 사건명 충돌 자동 제외는 일어나지 않는다")
    void incidentGuardIsInactive() {
        var resolution = resolution(
                List.of(),
                List.of(new QueryResolution.IncidentName("이태원 참사", Origin.EXPLICIT_QUERY, null, 1.0)),
                List.of());
        var otherIncident = tag(9L, TagType.EVENT, "포항 지진", EffectiveTag.Verification.REVIEWER_VERIFIED);

        assertThat(policy.incidentGuardActive()).isFalse();
        assertThat(policy.judge(resolution, List.of(otherIncident)).excluded()).isFalse();
    }

    @Test
    @DisplayName("검증된 날짜가 여럿이고 하나라도 맞으면 일치로 판정한다")
    void anyMatchingVerifiedDateWins() {
        var inherited = verified(7L, "2026-08-20");
        var own = tag(8L, TagType.BROADCAST_DATE, "2026-09-02", EffectiveTag.Verification.REVIEWER_VERIFIED);

        var verdict =
                policy.judge(withWindow(DateField.BROADCAST_DATE, Origin.EXPLICIT_FILTER), List.of(inherited, own));

        assertThat(verdict.excluded()).isFalse();
        assertThat(verdict.fields())
                .singleElement()
                .satisfies(field -> assertThat(field.groundingTagIds()).containsExactly(8L));
    }

    @Test
    @DisplayName("창은 반열린 구간이다 — 끝 날짜 당일은 조건 밖이다")
    void windowIsHalfOpen() {
        var resolution = withWindow(DateField.BROADCAST_DATE, Origin.EXPLICIT_FILTER);

        assertThat(policy.judge(resolution, List.of(verified(7L, "2026-09-03"))).excluded())
                .as("endExclusive 하루 전은 조건 안")
                .isFalse();
        assertThat(policy.judge(resolution, List.of(verified(7L, "2026-09-04"))).excluded())
                .as("endExclusive 당일은 조건 밖")
                .isTrue();
    }

    @Test
    @DisplayName("읽을 수 없는 날짜 값은 판정 근거로 쓰지 않는다")
    void unparsableDateIsNotGrounds() {
        var broken = tag(7L, TagType.BROADCAST_DATE, "이천이십육년", EffectiveTag.Verification.VERIFIED);

        var verdict = policy.judge(withWindow(DateField.BROADCAST_DATE, Origin.EXPLICIT_FILTER), List.of(broken));

        assertThat(verdict.excluded()).isFalse();
        assertThat(verdict.fields())
                .singleElement()
                .satisfies(field -> assertThat(field.judgment()).isEqualTo(GuardJudgment.UNKNOWN_OR_UNVERIFIED));
    }

    private static QueryResolution withWindow(DateField field, Origin origin) {
        return resolution(
                List.of(new QueryResolution.DateWindow(field, FROM, TO_EXCLUSIVE, origin, null, 1.0)),
                List.of(),
                List.of());
    }

    private static QueryResolution resolution(
            List<QueryResolution.DateWindow> windows,
            List<QueryResolution.IncidentName> incidents,
            List<QueryResolution.Entity> entities) {
        return new QueryResolution(
                "query-resolver/v2",
                QueryResolution.Intent.SCENE_SEARCH,
                windows,
                incidents,
                entities,
                List.of(),
                List.of(),
                List.of(),
                1.0);
    }

    private static EffectiveTag verified(long tagId, String value) {
        return tag(tagId, TagType.BROADCAST_DATE, value, EffectiveTag.Verification.VERIFIED);
    }

    private static EffectiveTag tag(long tagId, TagType type, String matchValue, EffectiveTag.Verification v) {
        return new EffectiveTag(1L, 2L, tagId, type, matchValue, matchValue, v, EffectiveTag.Scope.SCENE, "user_input");
    }
}
