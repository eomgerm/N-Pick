package com.npick.search.domain.policy;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.npick.search.domain.model.GuardExclusionReason;
import com.npick.search.domain.model.QueryResolution;
import com.npick.search.domain.model.QueryResolution.DateField;
import com.npick.search.domain.model.QueryResolution.Origin;
import com.npick.tag.domain.model.EffectiveTag;
import com.npick.tag.domain.model.TagType;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F-06 완료 기준을 항목별로 확인한다.
 *
 * <p>제외는 되돌릴 수 없으므로 <b>제외되지 않아야 하는 경우</b> 가 시험의 대부분이다. 「명시 조건과 검증된 값이 같은 필드에서 충돌」 하나만 제외이고 나머지는 전부 통과다.
 */
class FalseHitGuardPolicyTest {

    private static final LocalDate FROM = LocalDate.of(2026, 9, 1);
    private static final LocalDate TO_EXCLUSIVE = LocalDate.of(2026, 9, 4);

    private final FalseHitGuardPolicy policy = new FalseHitGuardPolicy();

    // ── 제외하는 유일한 경우 ──────────────────────────────────────────────

    @Test
    @DisplayName("명시한 날짜와 같은 종류의 검증된 날짜가 충돌하면 제외한다")
    void excludesWhenStatedDateConflictsWithVerifiedSameField() {
        var resolution = withWindow(DateField.BROADCAST_DATE, Origin.EXPLICIT_FILTER);
        var tag = tag(7L, TagType.BROADCAST_DATE, "2026-08-20", EffectiveTag.Verification.VERIFIED);

        var verdict = policy.judge(resolution, List.of(tag));

        assertThat(verdict).hasValueSatisfying(exclusion -> {
            assertThat(exclusion.reason()).isEqualTo(GuardExclusionReason.EXPLICIT_DATE_CONFLICT);
            assertThat(exclusion.field()).isEqualTo(DateField.BROADCAST_DATE);
            assertThat(exclusion.conflictingTagIds()).containsExactly(7L);
        });
    }

    @Test
    @DisplayName("사용자가 원문에 친 조건도 제외 근거가 된다")
    void explicitQueryOriginAlsoGrounds() {
        var resolution = withWindow(DateField.BROADCAST_DATE, Origin.EXPLICIT_QUERY);

        assertThat(policy.judge(resolution, List.of(verifiedBroadcast("2026-08-20"))))
                .isPresent();
    }

    // ── 제외하지 않는 경우 ────────────────────────────────────────────────

    @Test
    @DisplayName("AI 가 추정한 조건만 충돌하면 제외하지 않는다")
    void inferredAnchorNeverExcludes() {
        var resolution = withWindow(DateField.BROADCAST_DATE, Origin.INFERRED);

        assertThat(policy.judge(resolution, List.of(verifiedBroadcast("2026-08-20"))))
                .isEmpty();
    }

    @Test
    @DisplayName("검증되지 않은 날짜는 충돌 근거가 되지 않는다")
    void unverifiedDateNeverExcludes() {
        var resolution = withWindow(DateField.BROADCAST_DATE, Origin.EXPLICIT_FILTER);
        var tag = tag(7L, TagType.BROADCAST_DATE, "2026-08-20", EffectiveTag.Verification.UNVERIFIED);

        assertThat(policy.judge(resolution, List.of(tag))).isEmpty();
    }

    @Test
    @DisplayName("날짜 정보가 아예 없으면 제외하지 않는다 — 자료 영상의 방송일 부재가 여기다")
    void missingDateNeverExcludes() {
        var resolution = withWindow(DateField.BROADCAST_DATE, Origin.EXPLICIT_FILTER);
        var onlyPerson = tag(9L, TagType.PERSON, "홍길동", EffectiveTag.Verification.VERIFIED);

        assertThat(policy.judge(resolution, List.of(onlyPerson))).isEmpty();
        assertThat(policy.judge(resolution, List.of())).isEmpty();
    }

    @Test
    @DisplayName("방송일 조건에 촬영일을 맞대지 않는다")
    void doesNotCompareAcrossDateKinds() {
        var resolution = withWindow(DateField.BROADCAST_DATE, Origin.EXPLICIT_FILTER);
        // 2022 년에 찍어 2023 년에 방송한 장면. 촬영일만 조건 밖이라고 빼면 사용자가 찾던 결과가 사라진다.
        var filmed = tag(7L, TagType.FILMED_DATE, "2022-05-01", EffectiveTag.Verification.VERIFIED);

        assertThat(policy.judge(resolution, List.of(filmed))).isEmpty();
    }

    @Test
    @DisplayName("검증된 날짜가 여럿이고 하나라도 조건에 맞으면 제외하지 않는다")
    void anyMatchingVerifiedDateKeepsTheScene() {
        var resolution = withWindow(DateField.BROADCAST_DATE, Origin.EXPLICIT_FILTER);
        // 클립에서 상속한 날짜와 장면 자신의 날짜가 함께 올 수 있다.
        var inherited = tag(7L, TagType.BROADCAST_DATE, "2026-08-20", EffectiveTag.Verification.VERIFIED);
        var own = tag(8L, TagType.BROADCAST_DATE, "2026-09-02", EffectiveTag.Verification.REVIEWER_VERIFIED);

        assertThat(policy.judge(resolution, List.of(inherited, own))).isEmpty();
    }

    @Test
    @DisplayName("인물·기관 불일치는 제외 근거가 아니다")
    void entityMismatchNeverExcludes() {
        var resolution = new QueryResolution(
                "query-resolver/v2",
                QueryResolution.Intent.SCENE_SEARCH,
                List.of(),
                List.of(),
                List.of(new QueryResolution.Entity(
                        QueryResolution.EntityType.PERSON, "홍길동", Origin.EXPLICIT_QUERY, null, 1.0)),
                List.of(),
                List.of(),
                List.of(),
                1.0);
        var other = tag(9L, TagType.PERSON, "임꺽정", EffectiveTag.Verification.REVIEWER_VERIFIED);

        assertThat(policy.judge(resolution, List.of(other))).isEmpty();
    }

    @Test
    @DisplayName("승인된 규칙이 없으므로 사건명 충돌 자동 제외는 일어나지 않는다")
    void incidentGuardIsInactive() {
        var resolution = new QueryResolution(
                "query-resolver/v2",
                QueryResolution.Intent.SCENE_SEARCH,
                List.of(),
                List.of(new QueryResolution.IncidentName("이태원 참사", Origin.EXPLICIT_QUERY, null, 1.0)),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                1.0);
        var otherIncident = tag(9L, TagType.EVENT, "포항 지진", EffectiveTag.Verification.REVIEWER_VERIFIED);

        assertThat(policy.incidentGuardActive()).isFalse();
        assertThat(policy.judge(resolution, List.of(otherIncident))).isEmpty();
    }

    // ── 경계 ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("창은 반열린 구간이다 — 끝 날짜 당일은 조건 밖이다")
    void windowIsHalfOpen() {
        var resolution = withWindow(DateField.BROADCAST_DATE, Origin.EXPLICIT_FILTER);

        assertThat(policy.judge(resolution, List.of(verifiedBroadcast("2026-09-03"))))
                .as("endExclusive 하루 전은 조건 안")
                .isEmpty();
        assertThat(policy.judge(resolution, List.of(verifiedBroadcast("2026-09-04"))))
                .as("endExclusive 당일은 조건 밖")
                .isPresent();
    }

    @Test
    @DisplayName("읽을 수 없는 날짜 값은 충돌 근거로 쓰지 않는다")
    void unparsableDateIsNotGrounds() {
        var resolution = withWindow(DateField.BROADCAST_DATE, Origin.EXPLICIT_FILTER);
        var broken = tag(7L, TagType.BROADCAST_DATE, "이천이십육년", EffectiveTag.Verification.VERIFIED);

        assertThat(policy.judge(resolution, List.of(broken))).isEmpty();
    }

    // ── 헬퍼 ────────────────────────────────────────────────────────────

    private static QueryResolution withWindow(DateField field, Origin origin) {
        return new QueryResolution(
                "query-resolver/v2",
                QueryResolution.Intent.SCENE_SEARCH,
                List.of(new QueryResolution.DateWindow(field, FROM, TO_EXCLUSIVE, origin, null, 1.0)),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                1.0);
    }

    private static EffectiveTag verifiedBroadcast(String value) {
        return tag(7L, TagType.BROADCAST_DATE, value, EffectiveTag.Verification.VERIFIED);
    }

    private static EffectiveTag tag(long tagId, TagType type, String matchValue, EffectiveTag.Verification v) {
        return new EffectiveTag(1L, 2L, tagId, type, matchValue, matchValue, v, EffectiveTag.Scope.SCENE, "user_input");
    }
}
