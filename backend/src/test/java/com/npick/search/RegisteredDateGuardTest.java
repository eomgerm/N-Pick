package com.npick.search;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.npick.clip.domain.model.InitialClipRegistration;
import com.npick.clip.domain.model.InitialClipRegistration.DateEvidence;
import com.npick.clip.domain.model.InitialClipRegistration.PipelineDefinition;
import com.npick.clip.domain.model.InitialClipRegistration.SourceType;
import com.npick.search.domain.model.GuardExclusionReason;
import com.npick.search.domain.model.GuardJudgment;
import com.npick.search.domain.model.QueryResolution;
import com.npick.search.domain.model.QueryResolution.DateField;
import com.npick.search.domain.model.QueryResolution.Origin;
import com.npick.search.domain.policy.FalseHitGuardPolicy;
import com.npick.tag.domain.model.TagJudgment;
import com.npick.tag.domain.model.TagType;
import com.npick.tag.domain.policy.TagResolutionPolicy;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S15P21A501-231 재현. 등록 때 넣은 날짜가 명시 필터의 제외 판정까지 살아서 도착하는지 본다.
 *
 * <p>층별 정책 테스트가 각자 초록인데도 방송일·촬영일 필터가 아무것도 걸러내지 않았다. 따로 보면 각 층이 옳고, 등록이 저장한 근거를 그대로 태워 보낼 때만 어긋난다 — {@code user_input} 을
 * 미검증으로 저장해 {@link FalseHitGuardPolicy} 가 날짜 태그를 통째로 건너뛰었다. 그래서 이 테스트는 세 층을 실제로 이어 붙인다.
 *
 * <p>DB 를 쓰지 않는다. 사이에 있는 저장·조회는 {@code source}·{@code verification_status} 두 컬럼을 그대로 옮기는 통과 경로이고, 그 왕복은
 * {@code ClipRegistrationPersistenceTest} 가 이미 고정한다.
 */
class RegisteredDateGuardTest {

    private static final LocalDate FROM = LocalDate.of(2026, 9, 1);
    private static final LocalDate TO_EXCLUSIVE = LocalDate.of(2026, 9, 4);
    private static final long SCENE = 900L;
    private static final long CLIP = 101L;
    private static final Instant REGISTERED_AT = Instant.parse("2026-09-07T00:00:00Z");

    private final TagResolutionPolicy resolution = new TagResolutionPolicy();
    private final FalseHitGuardPolicy guard = new FalseHitGuardPolicy();

    @Test
    @DisplayName("등록 때 넣은 방송일이 지정한 범위 밖이면 그 장면을 제외한다")
    void excludesSceneWhoseRegisteredBroadcastDateIsOutsideTheRange() {
        var verdict = judge(registration(LocalDate.of(2026, 8, 20), null), DateField.BROADCAST_DATE);

        assertThat(verdict.excluded()).isTrue();
        assertThat(verdict.exclusionReason()).isEqualTo(GuardExclusionReason.EXPLICIT_DATE_CONFLICT);
        assertThat(verdict.fields())
                .singleElement()
                .satisfies(field -> assertThat(field.judgment()).isEqualTo(GuardJudgment.VERIFIED_CONFLICT));
    }

    @Test
    @DisplayName("등록 때 넣은 촬영일도 같은 경로로 제외된다")
    void excludesSceneWhoseRegisteredFilmedDateIsOutsideTheRange() {
        var verdict = judge(registration(null, LocalDate.of(2026, 8, 20)), DateField.FILMED_DATE);

        assertThat(verdict.excluded()).isTrue();
        assertThat(verdict.exclusionReason()).isEqualTo(GuardExclusionReason.EXPLICIT_DATE_CONFLICT);
    }

    @Test
    @DisplayName("등록 때 넣은 방송일이 범위 안이면 남기고 일치로 판정한다")
    void keepsSceneWhoseRegisteredBroadcastDateIsInsideTheRange() {
        var verdict = judge(registration(LocalDate.of(2026, 9, 2), null), DateField.BROADCAST_DATE);

        assertThat(verdict.excluded()).isFalse();
        assertThat(verdict.fields())
                .singleElement()
                .satisfies(field -> assertThat(field.judgment()).isEqualTo(GuardJudgment.VERIFIED_MATCH));
    }

    /**
     * 날짜를 넣지 않고 등록한 클립은 제외되지 않는다 (F-06).
     *
     * <p>자료 영상의 방송일 부재가 여기다. 등록 입력을 검증으로 올려도 <b>없는</b> 날짜가 충돌이 되지는 않는다는 것을 같이 고정한다.
     */
    @Test
    @DisplayName("등록 때 날짜를 넣지 않았으면 그 이유만으로 제외하지 않는다")
    void keepsSceneRegisteredWithoutAnyDate() {
        var verdict = judge(registration(null, null), DateField.BROADCAST_DATE);

        assertThat(verdict.excluded()).isFalse();
        assertThat(verdict.fields())
                .singleElement()
                .satisfies(field -> assertThat(field.judgment()).isEqualTo(GuardJudgment.UNKNOWN_OR_UNVERIFIED));
    }

    /** 등록 근거 → 저장 → 조회 → 태그 판정 → guard 판정. 사이의 저장·조회는 두 컬럼을 그대로 옮긴다. */
    private FalseHitGuardPolicy.Verdict judge(InitialClipRegistration registration, DateField field) {
        var judgments = new ArrayList<TagJudgment>();
        long tagId = 7000L;
        for (DateEvidence evidence : registration.dateEvidence()) {
            judgments.add(judgment(evidence, tagId++));
        }
        var sceneTags = resolution.resolve(judgments).getOrDefault(SCENE, List.of());
        return guard.judge(withWindow(field), sceneTags);
    }

    /**
     * {@code tag_evidence} 한 줄을 판정기 입력으로 옮긴다. {@code TagJudgmentQueryAdapter} 의 SELECT 가 하는 일과 같다.
     *
     * <p>{@code scene_id IS NULL} 인 클립 태그는 그 클립의 장면마다 한 줄씩 펼쳐져 들어오므로 {@code sceneScoped} 는 거짓이다 (F-10).
     */
    private static TagJudgment judgment(DateEvidence evidence, long tagId) {
        return new TagJudgment(
                SCENE,
                CLIP,
                tagId,
                TagType.from(evidence.tagType()),
                evidence.date().toString(),
                evidence.date().toString(),
                evidence.sceneId() != null,
                evidence.source(),
                evidence.verificationStatus(),
                REGISTERED_AT,
                tagId);
    }

    private static InitialClipRegistration registration(LocalDate broadcastDate, LocalDate filmedDate) {
        return new InitialClipRegistration(
                CLIP,
                201,
                SourceType.BROADCAST,
                "clips/101/original",
                "a".repeat(64),
                "등록 입력 날짜 필터 재현",
                null,
                null,
                1,
                broadcastDate,
                filmedDate,
                new PipelineDefinition("test-pipeline-v1", List.of("scene_detection")),
                REGISTERED_AT);
    }

    private static QueryResolution withWindow(DateField field) {
        return new QueryResolution(
                "query-resolver/v2",
                QueryResolution.Intent.SCENE_SEARCH,
                List.of(new QueryResolution.DateWindow(field, FROM, TO_EXCLUSIVE, Origin.EXPLICIT_FILTER, null, 1.0)),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                1.0);
    }
}
