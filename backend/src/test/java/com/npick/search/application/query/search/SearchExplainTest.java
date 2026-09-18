package com.npick.search.application.query.search;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.npick.search.application.query.card.SceneCard;
import com.npick.search.application.query.fusion.FusionResult;
import com.npick.search.application.query.guard.FalseHitGuardResult;
import com.npick.search.application.query.soft.SoftRankingResult;
import com.npick.search.domain.model.GuardExclusionReason;
import com.npick.search.domain.model.GuardJudgment;
import com.npick.search.domain.model.QueryResolution;
import com.npick.search.domain.policy.FalseHitGuardPolicy;
import com.npick.tag.domain.model.EffectiveTag;
import com.npick.tag.domain.model.TagType;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 응답 카드와 {@code explain_json} 을 만드는 유일한 지점. 순수 정적이라 회귀가 무음으로 통과하기 쉽다 (S15P21A501-59 리뷰 B2).
 *
 * <p>여기서 고정하는 것은 계약 §5.1 의 불변식이다 — {@code match_evidence} 1개 이상, 날짜 {@code value} 와 {@code verification_status} 의 짝, ID
 * 의 문자열 표기.
 */
class SearchExplainTest {

    private static final List<String> QUERY_TOKENS = List.of("서울역", "귀성");

    @Test
    @DisplayName("설명에서 걸리면 caption 근거를 낸다")
    void reportsCaptionEvidence() {
        var card = SearchExplain.card(scene(cardWith("서울역 귀성 인파", List.of("서울역", "인파"))), 1, 801L, QUERY_TOKENS);

        assertThat(card.matchedKeywords()).containsExactly("서울역");
        assertThat(card.matchEvidence()).singleElement().satisfies(evidence -> {
            assertThat(evidence.field()).isEqualTo("caption");
            assertThat(evidence.value()).isEqualTo("서울역 귀성 인파");
        });
    }

    @Test
    @DisplayName("걸린 것이 없어도 근거를 비우지 않는다")
    void neverLeavesEvidenceEmpty() {
        // §5.1: match_evidence 는 1개 이상이다. dense·구조화로만 올라온 장면은 토큰 대조로
        // 되짚지 못하는데, 그렇다고 빈 배열을 내보내면 계약이 깨진다.
        var card = SearchExplain.card(scene(cardWith("아무 관련 없는 설명", List.of("무관"))), 1, 801L, QUERY_TOKENS);

        assertThat(card.matchedKeywords()).isEmpty();
        assertThat(card.matchEvidence()).hasSize(1);
    }

    @Test
    @DisplayName("텍스트가 하나도 없으면 태그를 근거로 낸다")
    void fallsBackToTagsWhenThereIsNoText() {
        SceneCard empty =
                new SceneCard(9301, 9101, "제목", null, 0, 1000, "b_roll", List.of(), null, List.of(), List.of());
        var card = SearchExplain.card(
                sceneWithTags(empty, List.of(tag(TagType.SCENE_TYPE, "역사 인파", true))), 1, null, QUERY_TOKENS);

        assertThat(card.matchEvidence()).singleElement().satisfies(evidence -> {
            assertThat(evidence.field()).isEqualTo("tag");
            assertThat(evidence.value()).isEqualTo("역사 인파");
        });
    }

    @Test
    @DisplayName("근거가 정말 없으면 지어내지 않고 null 로 둔다")
    void doesNotInventEvidence() {
        // 지어내면 사용자가 그 문자열을 근거로 판단한다. §5.1 이 이 한 경우를 위해 value 를 열어 두었다.
        SceneCard empty =
                new SceneCard(9301, 9101, "제목", null, 0, 1000, "b_roll", List.of(), null, List.of(), List.of());

        var card = SearchExplain.card(scene(empty), 1, null, QUERY_TOKENS);

        assertThat(card.matchEvidence()).singleElement().satisfies(evidence -> {
            assertThat(evidence.value()).isNull();
            assertThat(evidence.source()).isEqualTo("dense_similarity");
        });
    }

    @Test
    @DisplayName("날짜가 없으면 상태는 unknown 이다")
    void unknownWhenThereIsNoDate() {
        var card = SearchExplain.card(scene(cardWith("설명", List.of())), 1, null, QUERY_TOKENS);

        assertThat(card.broadcastDate().value()).isNull();
        assertThat(card.broadcastDate().verificationStatus()).isEqualTo("unknown");
        assertThat(card.filmedDate().verificationStatus()).isEqualTo("unknown");
    }

    @Test
    @DisplayName("같은 날짜 종류에 태그가 여럿이면 검증된 것을 앞세운다")
    void prefersTheVerifiedDate() {
        // 미검증 값을 대표로 내보내면 F-06 의 「검증된 날짜 충돌」 판정과 화면이 서로 다른 날짜를 말한다.
        var scene = sceneWithTags(
                cardWith("설명", List.of()),
                List.of(
                        tag(TagType.BROADCAST_DATE, "2026-01-01", false),
                        tag(TagType.BROADCAST_DATE, "2026-02-14", true)));

        var card = SearchExplain.card(scene, 1, null, QUERY_TOKENS);

        assertThat(card.broadcastDate().value()).isEqualTo(LocalDate.of(2026, 2, 14));
        assertThat(card.broadcastDate().verificationStatus()).isEqualTo("verified");
    }

    @Test
    @DisplayName("explain_json 은 네 덩어리이고 근거 태그 ID 는 문자열이다")
    void explainCarriesFourBlocksWithStringIds() {
        // grounding_tag_ids 가 숫자면 TSID 가 2^53 을 넘는 순간 JS 에서 값이 뭉개진다.
        var scene = sceneWithVerdict(new FalseHitGuardResult.SceneVerdict(
                9301,
                null,
                List.of(new FalseHitGuardPolicy.FieldJudgment(
                        QueryResolution.DateField.BROADCAST_DATE,
                        GuardJudgment.VERIFIED_MATCH,
                        List.of(9007199254740993L)))));

        Map<String, Object> guard = SearchExplain.guard(scene);

        assertThat(guard).containsKeys("exclusion_reason", "fields");
        assertThat(guard.get("exclusion_reason")).isNull();
        @SuppressWarnings("unchecked")
        var fields = (List<Map<String, Object>>) guard.get("fields");
        assertThat(fields.getFirst().get("grounding_tag_ids")).isEqualTo(List.of("9007199254740993"));
    }

    @Test
    @DisplayName("제외된 판정은 사유를 계약 문자열로 낸다")
    void exclusionReasonUsesTheWireValue() {
        var scene = sceneWithVerdict(
                new FalseHitGuardResult.SceneVerdict(9301, GuardExclusionReason.EXPLICIT_DATE_CONFLICT, List.of()));

        assertThat(SearchExplain.guard(scene).get("exclusion_reason")).isEqualTo("explicit_date_conflict");
    }

    @Test
    @DisplayName("display 는 카드와 같은 값을 담는다")
    void displayMirrorsTheCard() {
        // 응답과 기록이 갈리면 「그때 화면에 뭐가 떴나」를 기록으로 확인할 수 없다.
        var scene =
                sceneWithTags(cardWith("서울역 귀성 인파", List.of("서울역")), List.of(tag(TagType.SCENE_TYPE, "역사 인파", true)));

        var card = SearchExplain.card(scene, 1, 801L, QUERY_TOKENS);
        Map<String, Object> display = SearchExplain.display(scene);

        assertThat(display.get("display_name")).isEqualTo(card.displayName());
        assertThat(display.get("scene_description")).isEqualTo(card.sceneDescription());
        assertThat(display.get("shot_type")).isEqualTo(card.shotType());
        assertThat(display.get("scene_type")).isEqualTo(card.sceneType());
    }

    private static SceneCard cardWith(String caption, List<String> captionTokens) {
        return new SceneCard(
                9301, 9101, "KBC 뉴스9", caption, 42000, 49000, "b_roll", captionTokens, null, List.of(), List.of());
    }

    private static EffectiveTag tag(TagType type, String value, boolean verified) {
        return new EffectiveTag(
                9301,
                9101,
                7001,
                type,
                value,
                value,
                verified ? EffectiveTag.Verification.VERIFIED : EffectiveTag.Verification.UNVERIFIED,
                EffectiveTag.Scope.SCENE,
                "vlm");
    }

    private static SearchCandidates.ScoredScene scene(SceneCard card) {
        return sceneWithTags(card, List.of());
    }

    private static SearchCandidates.ScoredScene sceneWithTags(SceneCard card, List<EffectiveTag> tags) {
        return new SearchCandidates.ScoredScene(
                card.sceneId(),
                card.clipId(),
                card,
                tags,
                new FusionResult.ScoredCandidate(card.sceneId(), card.clipId(), 1.0, 1.0, 0.0, 0.0, List.of()),
                new SoftRankingResult.OrderedCandidate(card.sceneId(), card.clipId(), 1.0, 1.0, Map.of()),
                new FalseHitGuardResult.SceneVerdict(card.sceneId(), null, List.of()));
    }

    private static SearchCandidates.ScoredScene sceneWithVerdict(FalseHitGuardResult.SceneVerdict verdict) {
        SceneCard card = cardWith("설명", List.of());
        return new SearchCandidates.ScoredScene(
                card.sceneId(),
                card.clipId(),
                card,
                List.of(),
                new FusionResult.ScoredCandidate(card.sceneId(), card.clipId(), 1.0, 1.0, 0.0, 0.0, List.of()),
                new SoftRankingResult.OrderedCandidate(card.sceneId(), card.clipId(), 1.0, 1.0, Map.of()),
                verdict);
    }
}
