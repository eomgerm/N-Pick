package com.npick.tag.domain.policy;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.npick.tag.domain.model.EffectiveTag;
import com.npick.tag.domain.model.TagJudgment;
import com.npick.tag.domain.model.TagType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * S15P21A501-161 완료 조건의 전수 검증.
 *
 * <p>DB 를 쓰지 않는다. 이 순서가 맞는지가 일감의 전부이고, DB 테스트는 전용 테스트 DB 환경 변수가 없으면 조용히 생략되기 때문이다. 생략된 테스트는 통과가 아니다.
 */
class TagResolutionPolicyTest {
    private static final long CLIP = 10L;
    private static final long SCENE = 100L;
    private static final long TAG = 7L;
    private static final Instant BASE = Instant.parse("2026-02-01T00:00:00Z");

    private static final String REVIEWER = "reviewer_feedback";
    private static final String VERIFIED = "verified";
    private static final String UNVERIFIED = "unverified";
    private static final String REJECTED = "rejected";
    private static final String WITHDRAWN = "withdrawn";

    private final TagResolutionPolicy policy = new TagResolutionPolicy();

    // ---------------------------------------------------------------- 우선순위

    @Test
    @DisplayName("장면의 사람 판단이 클립의 사람 판단을 이긴다 - 클립 승인 + 장면 반려는 죽는다")
    void sceneReviewBeatsClipReview() {
        var resolved = policy.resolve(List.of(clipReview(VERIFIED, 10), sceneReview(REJECTED, 5)));

        assertThat(resolved).as("장면 반려가 최신이 아니어도 범위가 이긴다").isEmpty();
    }

    @Test
    @DisplayName("장면 승인은 클립 반려를 이긴다 - 범위가 시각보다 먼저다")
    void sceneApprovalBeatsClipRejection() {
        var resolved = policy.resolve(List.of(clipReview(REJECTED, 10), sceneReview(VERIFIED, 5)));

        assertThat(only(resolved).verification()).isEqualTo(EffectiveTag.Verification.REVIEWER_VERIFIED);
        assertThat(only(resolved).scope()).isEqualTo(EffectiveTag.Scope.SCENE);
    }

    @Test
    @DisplayName("클립의 사람 판단이 일반 근거를 이긴다 - 클립 반려는 장면의 OCR 근거를 죽인다")
    void clipReviewBeatsGeneralEvidence() {
        var resolved = policy.resolve(List.of(clipReview(REJECTED, 10), sceneEvidence("ocr", VERIFIED)));

        assertThat(resolved).isEmpty();
    }

    @Test
    @DisplayName("사람 판단은 같은 태그의 최신 1건만 본다 - 반려 후 승인이면 승인이다")
    void usesOnlyLatestReviewInScope() {
        var resolved = policy.resolve(List.of(sceneReview(REJECTED, 5), sceneReview(VERIFIED, 9)));

        assertThat(only(resolved).verification()).isEqualTo(EffectiveTag.Verification.REVIEWER_VERIFIED);
    }

    @Test
    @DisplayName("판단 시각이 같으면 evidence_id 가 큰 쪽이 최신이다")
    void breaksTimeTieByEvidenceId() {
        var earlier = row(true, REVIEWER, VERIFIED, 5, 100L);
        var later = row(true, REVIEWER, REJECTED, 5, 101L);

        assertThat(policy.resolve(List.of(earlier, later))).isEmpty();
    }

    // ---------------------------------------------------------------- 상속

    @Test
    @DisplayName("클립 태그는 장면에 상속된다 - 판단이 없으면 클립의 추정 근거로 유효하다")
    void clipTagInheritsToScene() {
        var effective = only(policy.resolve(List.of(clipEvidence("vlm", UNVERIFIED))));

        assertThat(effective.sceneId()).isEqualTo(SCENE);
        assertThat(effective.scope()).as("판정이 클립 상속에서 나왔음을 남긴다").isEqualTo(EffectiveTag.Scope.CLIP);
        assertThat(effective.verification()).isEqualTo(EffectiveTag.Verification.UNVERIFIED);
    }

    @Test
    @DisplayName("장면별 예외는 그 장면만 끊는다 - 같은 클립의 다른 장면은 상속을 유지한다")
    void sceneExceptionSeversOnlyThatScene() {
        long otherScene = 101L;
        var resolved = policy.resolve(List.of(
                clipEvidence("vlm", UNVERIFIED),
                row(SCENE, true, REVIEWER, REJECTED, 5, 200L),
                new TagJudgment(
                        otherScene, CLIP, TAG, TagType.EVENT, "포항지진", "포항 지진", false, "vlm", UNVERIFIED, BASE, 201L)));

        assertThat(resolved).containsOnlyKeys(otherScene);
        assertThat(resolved.get(otherScene))
                .singleElement()
                .satisfies(tag -> assertThat(tag.scope()).isEqualTo(EffectiveTag.Scope.CLIP));
    }

    // ---------------------------------------------------------------- 개입 해제

    @Test
    @DisplayName("개입 해제하면 원본 근거로 되돌아가 평가된다")
    void withdrawalFallsBackToOriginalEvidence() {
        var effective = only(policy.resolve(
                List.of(sceneEvidence("ocr", VERIFIED), sceneReview(VERIFIED, 5), sceneReview(WITHDRAWN, 9))));

        assertThat(effective.verification())
                .as("사람 판단이 사라졌으므로 관측 근거의 검증 상태를 쓴다")
                .isEqualTo(EffectiveTag.Verification.VERIFIED);
        assertThat(effective.source()).isEqualTo("ocr");
    }

    @Test
    @DisplayName("개입 해제는 과거 승인을 되살리지 않는다 - 클립 반려로 내려가 죽는다")
    void withdrawalDoesNotResurrectEarlierApproval() {
        var resolved =
                policy.resolve(List.of(clipReview(REJECTED, 1), sceneReview(VERIFIED, 5), sceneReview(WITHDRAWN, 9)));

        assertThat(resolved).as("해제를 건너뛰고 5분의 승인을 집으면 이 테스트가 깨진다").isEmpty();
    }

    @Test
    @DisplayName("클립 개입 해제도 같다 - 클립 판단이 없는 것으로 보고 일반 근거로 내려간다")
    void clipWithdrawalFallsBackToGeneralEvidence() {
        var effective = only(policy.resolve(
                List.of(clipEvidence("vlm", UNVERIFIED), clipReview(REJECTED, 5), clipReview(WITHDRAWN, 9))));

        assertThat(effective.verification()).isEqualTo(EffectiveTag.Verification.UNVERIFIED);
    }

    @Test
    @DisplayName("해제만 남고 원본 근거가 없으면 유효하지 않다")
    void withdrawalWithoutOriginalEvidenceIsNotEffective() {
        assertThat(policy.resolve(List.of(sceneReview(WITHDRAWN, 9)))).isEmpty();
    }

    // ---------------------------------------------------------------- 검증 상태

    @Test
    @DisplayName("추정 근거는 verified 로 저장돼 있어도 미검증이다 - AI 신뢰도만으로 승격되지 않는다")
    void inferenceEvidenceIsNeverPromotedToVerified() {
        assertThat(only(policy.resolve(List.of(clipEvidence("asr", VERIFIED)))).verification())
                .isEqualTo(EffectiveTag.Verification.UNVERIFIED);
        assertThat(only(policy.resolve(List.of(clipEvidence("vlm", VERIFIED)))).verification())
                .isEqualTo(EffectiveTag.Verification.UNVERIFIED);
        assertThat(only(policy.resolve(List.of(clipEvidence("rule", VERIFIED)))).verification())
                .isEqualTo(EffectiveTag.Verification.UNVERIFIED);
    }

    @Test
    @DisplayName("검증된 관측 근거가 하나라도 있으면 그것이 대표 출처가 된다")
    void verifiedObservationBecomesRepresentative() {
        var effective = only(policy.resolve(List.of(clipEvidence("vlm", UNVERIFIED), sceneEvidence("cc", VERIFIED))));

        assertThat(effective.verification()).isEqualTo(EffectiveTag.Verification.VERIFIED);
        assertThat(effective.source()).isEqualTo("cc");
    }

    @Test
    @DisplayName("미검증만 여러 개여도 검증됨으로 올라가지 않는다")
    void manyUnverifiedEvidencesStayUnverified() {
        assertThat(only(policy.resolve(List.of(clipEvidence("vlm", UNVERIFIED), sceneEvidence("asr", UNVERIFIED))))
                        .verification())
                .isEqualTo(EffectiveTag.Verification.UNVERIFIED);
    }

    @Test
    @DisplayName("미검증도 F-06 충돌 판정에는 쓸 수 없다")
    void unverifiedIsNotTrustedForConflict() {
        assertThat(EffectiveTag.Verification.UNVERIFIED.trustedForConflict()).isFalse();
        assertThat(EffectiveTag.Verification.VERIFIED.trustedForConflict()).isTrue();
        assertThat(EffectiveTag.Verification.REVIEWER_VERIFIED.trustedForConflict())
                .isTrue();
    }

    // ---------------------------------------------------------------- 경계

    @Test
    @DisplayName("근거 줄이 없으면 빈 결과다")
    void emptyInputYieldsEmptyResult() {
        assertThat(policy.resolve(List.of())).isEmpty();
    }

    @Test
    @DisplayName("null 은 빈 목록과 다르다 - 배선 실수를 태그 없음으로 위장하지 않는다")
    void rejectsNullInput() {
        assertThatThrownBy(() -> policy.resolve(null)).isInstanceOf(NullPointerException.class);
    }

    // ---------------------------------------------------------------- 표본

    private static EffectiveTag only(Map<Long, List<EffectiveTag>> resolved) {
        assertThat(resolved).as("장면 %d 의 유효 태그", SCENE).containsOnlyKeys(SCENE);
        assertThat(resolved.get(SCENE)).hasSize(1);
        return resolved.get(SCENE).getFirst();
    }

    private static TagJudgment sceneReview(String status, long minutes) {
        return row(true, REVIEWER, status, minutes, minutes);
    }

    private static TagJudgment clipReview(String status, long minutes) {
        return row(false, REVIEWER, status, minutes, minutes);
    }

    private static TagJudgment sceneEvidence(String source, String status) {
        return row(true, source, status, 0, 1L);
    }

    private static TagJudgment clipEvidence(String source, String status) {
        return row(false, source, status, 0, 2L);
    }

    private static TagJudgment row(boolean sceneScoped, String source, String status, long minutes, long evidenceId) {
        return row(SCENE, sceneScoped, source, status, minutes, evidenceId);
    }

    private static TagJudgment row(
            long sceneId, boolean sceneScoped, String source, String status, long minutes, long evidenceId) {
        return new TagJudgment(
                sceneId,
                CLIP,
                TAG,
                TagType.EVENT,
                "포항지진",
                "포항 지진",
                sceneScoped,
                source,
                status,
                BASE.plus(minutes, ChronoUnit.MINUTES),
                evidenceId);
    }
}
