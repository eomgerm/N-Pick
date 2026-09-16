package com.npick.search.application.query.fusion;

import java.util.EnumMap;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.npick.common.error.BusinessException;
import com.npick.search.application.error.SearchFusionErrorCode;
import com.npick.search.application.query.candidate.SceneCandidateResult;
import com.npick.search.application.query.dense.DenseCandidatesResult;
import com.npick.search.application.query.dense.DenseQuery;
import com.npick.search.application.query.dense.DenseSearchSettings;
import com.npick.search.application.query.fusion.FusionResult.ChannelState;
import com.npick.search.application.query.structured.StructuredScoresResult;
import com.npick.search.domain.model.FusionChannel;
import com.npick.search.domain.model.FusionSettings;
import com.npick.search.domain.model.IneligibleReason;
import com.npick.search.domain.model.LexicalSearchSettings;
import com.npick.search.domain.model.QueryResolution;
import com.npick.search.domain.model.SoftRankingSettings;
import com.npick.search.domain.model.SoftSignal;
import com.npick.search.domain.model.StructuredAxis;
import com.npick.search.domain.model.StructuredScoreSettings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/** 두 RRF 채널과 구조화 점수의 결과 fixture 기반 검증. 실제 조회·트랜잭션 연결은 조립(-59)의 몫이라 여기서 다루지 않는다. */
class SearchRankingFusionServiceTest {
    private static final String MODEL = "arctic-ko@" + "a".repeat(40);
    private static final LexicalSearchSettings LEXICAL = new LexicalSearchSettings("candidate-v1", 1.0, 1.0, 1.0, 200);

    @Test
    void combinesChannelRanksAndAddsTheStructuredTermWithoutAssigningAFinalRank() {
        var result = service(settings(1.0, 1.0, 1.0))
                .fuse(new FuseSearchRankingQuery(
                        List.of(candidate(30), candidate(31)),
                        dense(denseHit(31, 1)),
                        structured(List.of(scene(30, 0.0), scene(31, 0.4)), List.of())));

        var thirty = result.candidates().get(0);
        var thirtyOne = result.candidates().get(1);
        // 정렬은 sceneId 오름차순이며 검색 순위가 아니다. 최종 rank 는 -55·guard 이후 -59 가 부여한다.
        assertThat(result.candidates())
                .extracting(FusionResult.ScoredCandidate::sceneId)
                .containsExactly(30L, 31L);
        // 30: lexical 1등만 맞았다 → (1/61) / (2/61) = 0.5
        assertThat(thirty.normalizedRrf()).isCloseTo(0.5, within(1e-12));
        assertThat(thirty.baseScore()).isCloseTo(0.5, within(1e-12));
        // 31: lexical 2등 + dense 1등. 구조화 0.4 가 λ=1 로 더해진다.
        assertThat(thirtyOne.normalizedRrf()).isCloseTo((1.0 / 62 + 1.0 / 61) / (2.0 / 61), within(1e-12));
        assertThat(thirtyOne.structuredContribution()).isCloseTo(0.4, within(1e-12));
        assertThat(thirtyOne.baseScore()).isCloseTo(thirtyOne.normalizedRrf() + 0.4, within(1e-12));
    }

    @Test
    void aCandidateMissingFromAChannelContributesZeroInsteadOfAFabricatedWorstRank() {
        var result = service(settings(1.0, 1.0, 1.0))
                .fuse(new FuseSearchRankingQuery(List.of(), dense(), structured(List.of(scene(40, 0.8)), List.of())));

        var tagOnly = result.candidates().getFirst();
        // 태그로만 들어온 후보다. 두 채널 어디에도 없으므로 R=0 이고 점수는 λ × 구조화 뿐이다 (F-10).
        assertThat(tagOnly.normalizedRrf()).isZero();
        assertThat(tagOnly.baseScore()).isCloseTo(0.8, within(1e-12));
        assertThat(state(tagOnly, FusionChannel.LEXICAL)).isEqualTo(ChannelState.MISSED);
        assertThat(state(tagOnly, FusionChannel.DENSE)).isEqualTo(ChannelState.MISSED);
        assertThat(contribution(tagOnly, FusionChannel.DENSE).rank()).isNull();
    }

    @Test
    void anOffChannelContributesNothingAndLeavesNoSettingsInTheRecord() {
        var result = service(settings(1.0, 0.0, 1.0))
                .fuse(new FuseSearchRankingQuery(
                        List.of(candidate(30)), null, structured(List.of(scene(30, 0.0)), List.of())));

        var thirty = result.candidates().getFirst();
        // dense 가 꺼져 있으면 M 은 lexical 만으로 계산된다. lexical 1등이 곧 상한이므로 1.0 이다.
        assertThat(thirty.normalizedRrf()).isCloseTo(1.0, within(1e-12));
        assertThat(state(thirty, FusionChannel.DENSE)).isEqualTo(ChannelState.OFF);
        // 끈 실행과 켠 실행은 다른 설정이다. 기록에 dense 설정이 남지 않으므로 버전도 달라진다.
        assertThat(result.config().dense()).isNull();
    }

    @Test
    void anActiveChannelWithoutAResultIsAWiringFaultNotASilentDegradation() {
        // 「실행하지 않음」과 「실행했는데 실패」는 사용자 안내가 다르다. 빈 결과로 뭉개면 구분이 사라진다.
        assertThatThrownBy(() -> service(settings(1.0, 1.0, 1.0))
                        .fuse(new FuseSearchRankingQuery(
                                List.of(), null, structured(List.of(scene(30, 0.0)), List.of()))))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", SearchFusionErrorCode.ACTIVE_CHANNEL_RESULT_MISSING);
    }

    @Test
    void anOffChannelThatWasQueriedAnywayIsRejectedSoItsCandidatesCannotLeakIn() {
        // dense 를 끄고 순수 단어 검색을 재려는데 dense 가 여전히 조회됐다면, 장면 99 는 dense 로만 들어온 후보다.
        // 기여만 0 으로 만들면 99 가 0 점으로 결과에 남고, 기록에는 dense 설정이 없어 유입 경로를 설명할 수도 없다.
        assertThatThrownBy(() -> service(settings(1.0, 0.0, 1.0))
                        .fuse(new FuseSearchRankingQuery(
                                List.of(candidate(30)),
                                dense(denseHit(99, 1)),
                                structured(List.of(scene(30, 0.0), scene(99, 0.0)), List.of()))))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", SearchFusionErrorCode.INACTIVE_CHANNEL_RESULT_PRESENT);
    }

    @Test
    void theSameRuleAppliesToAnOffLexicalChannel() {
        // 「가중치 0 = off」가 한쪽 채널에만 적용되면 비대칭이 남는다.
        assertThatThrownBy(() -> service(settings(0.0, 1.0, 1.0))
                        .fuse(new FuseSearchRankingQuery(
                                List.of(candidate(30)),
                                dense(denseHit(30, 1)),
                                structured(List.of(scene(30, 0.0)), List.of()))))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", SearchFusionErrorCode.INACTIVE_CHANNEL_RESULT_PRESENT);
    }

    @Test
    void aTagOnlyCandidateStillSurvivesWhenAChannelIsOffBecauseItDidNotComeFromThatChannel() {
        // off 검사는 「조회 결과가 왔는가」를 보는 것이지 후보 자체를 지우는 것이 아니다. 태그로 독립 유입된 후보는 남아야 한다 (F-10).
        var result = service(settings(1.0, 0.0, 1.0))
                .fuse(new FuseSearchRankingQuery(List.of(), null, structured(List.of(scene(40, 0.8)), List.of())));
        assertThat(result.candidates())
                .extracting(FusionResult.ScoredCandidate::sceneId)
                .containsExactly(40L);
        assertThat(result.candidates().getFirst().baseScore()).isCloseTo(0.8, within(1e-12));
    }

    @Test
    void aFailedDenseChannelKeepsTheCeilingAndLambdaAndIsDistinguishedFromBeingOff() {
        var failed = DenseCandidatesResult.unavailable(
                DenseCandidatesResult.Reason.DENSE_QUERY_FAILED,
                new DenseQuery(new float[] {1}, MODEL),
                new DenseSearchSettings(MODEL, 200));
        var result = service(settings(1.0, 1.0, 1.0))
                .fuse(new FuseSearchRankingQuery(
                        List.of(candidate(30)), failed, structured(List.of(scene(30, 0.6)), List.of())));

        var thirty = result.candidates().getFirst();
        assertThat(state(thirty, FusionChannel.DENSE)).isEqualTo(ChannelState.FAILED);
        // M 을 살아 있는 채널로 다시 정규화하지 않으므로 lexical 1등이 1.0 이 아니라 0.5 다.
        assertThat(thirty.normalizedRrf()).isCloseTo(0.5, within(1e-12));
        // λ 도 그대로다. 구조화 항의 상대적 영향력이 커지는 것은 감수하는 부작용이다 —
        // λ 를 같이 줄이면 dense 와 무관한 태그 전용 후보까지 dense 장애의 벌을 받는다.
        assertThat(thirty.structuredContribution()).isCloseTo(0.6, within(1e-12));
        // 실패는 설정상 off 와 다르다. 실제 사용한 dense 설정이 남아야 degraded 판정의 근거가 된다.
        assertThat(result.config().dense()).isNotNull();
    }

    @Test
    void structuredOffKeepsTheEligibilityGateAndOnlyRemovesTheStructuredTerm() {
        // 구조화 축 가중치가 전부 0 이면 -52 가 태그 후보를 찾지 않고 점수도 0 이 된다. 적격 판정은 그대로 돈다.
        var zeroed = structuredSettings(0.0);
        var result = service(settings(1.0, 0.0, 1.0))
                .fuse(new FuseSearchRankingQuery(
                        List.of(candidate(30)),
                        null,
                        new StructuredScoresResult(
                                resolution(),
                                zeroed,
                                List.of(scene(30, 0.0)),
                                List.of(new StructuredScoresResult.Ineligible(31, IneligibleReason.CLIP_DELETED)))));

        // 적격 후보만 점수화된다. 부적격으로 걸린 장면은 결과에 들어오지 않는다.
        assertThat(result.candidates())
                .extracting(FusionResult.ScoredCandidate::sceneId)
                .containsExactly(30L);
        assertThat(result.candidates().getFirst().structuredContribution()).isZero();
        // 실제 사용한 구조화 설정이 그대로 기록된다 — 환경에서 다시 읽지 않는다.
        assertThat(result.config().structured()).isSameAs(zeroed);
    }

    @Test
    void aCandidateThatSkippedTheEligibilityScreenIsRejectedInsteadOfSilentlyDropped() {
        // -52 는 요청한 sceneId 가 eligible·ineligible 중 정확히 한쪽에 나타남을 보장한다. 깨졌다면 조립 배선 결함이다.
        assertThatThrownBy(() -> service(settings(1.0, 0.0, 1.0))
                        .fuse(new FuseSearchRankingQuery(
                                List.of(candidate(30), candidate(99)),
                                null,
                                structured(List.of(scene(30, 0.0)), List.of()))))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", SearchFusionErrorCode.CANDIDATE_NOT_SCREENED);
    }

    @Test
    void recordedConfigIsTheOneActuallyUsedAndItsVersionTracksEveryRankingInput() {
        var settings = settings(1.0, 1.0, 1.0);
        var query = new FuseSearchRankingQuery(
                List.of(candidate(30)), dense(), structured(List.of(scene(30, 0.0)), List.of()));

        var result = service(settings).fuse(query);
        assertThat(result.config().fusion()).isSameAs(settings);
        assertThat(result.config().lexical()).isSameAs(LEXICAL);
        // 저장할 payload 와 해시 입력이 같아야 「기록된 설정」과 「버전이 가리키는 설정」이 갈리지 않는다.
        assertThat(result.configVersion()).isEqualTo(result.config().version());
        // 스냅샷은 fusion 만이 아니라 다섯 설정을 담으므로 fusion 의 schema 이름을 빌려 쓰지 않는다.
        assertThat(result.configVersion()).startsWith(SearchConfigSnapshot.SCHEMA + ":");
        // λ 만 바꿔도 순위가 달라지므로 버전이 달라져야 한다.
        assertThat(service(settings(1.0, 1.0, 2.0)).fuse(query).configVersion()).isNotEqualTo(result.configVersion());
        // 같은 설정의 두 실행은 같은 버전이다.
        assertThat(service(settings(1.0, 1.0, 1.0)).fuse(query).configVersion()).isEqualTo(result.configVersion());
    }

    /** 보조 신호(-55)도 순위를 바꾸므로 그 설정이 달라지면 같은 설정으로 보이면 안 된다 (F-05 완료 기준). */
    @Test
    void softRankingSettingsAreRecordedAndTrackedByTheConfigVersion() {
        var query = new FuseSearchRankingQuery(
                List.of(candidate(30)), dense(), structured(List.of(scene(30, 0.0)), List.of()));

        var result = service(settings(1.0, 1.0, 1.0)).fuse(query);
        assertThat(result.config().soft()).isSameAs(SOFT);
        assertThat(service(settings(1.0, 1.0, 1.0), soft(2.0)).fuse(query).configVersion())
                .isNotEqualTo(result.configVersion());
    }

    private SearchRankingFusionService service(FusionSettings settings) {
        return service(settings, SOFT);
    }

    private SearchRankingFusionService service(FusionSettings settings, SoftRankingSettings soft) {
        return new SearchRankingFusionService(settings, LEXICAL, soft);
    }

    private static final SoftRankingSettings SOFT = soft(1.0);

    private static SoftRankingSettings soft(double bRollWeight) {
        var weights = new EnumMap<SoftSignal, Double>(SoftSignal.class);
        for (SoftSignal signal : SoftSignal.values()) {
            weights.put(signal, signal == SoftSignal.B_ROLL ? bRollWeight : 1.0);
        }
        return new SoftRankingSettings(weights, 0, FusionSettings.WeightStatus.EXPERIMENTAL);
    }

    private static ChannelState state(FusionResult.ScoredCandidate candidate, FusionChannel channel) {
        return contribution(candidate, channel).state();
    }

    private static FusionResult.ChannelContribution contribution(
            FusionResult.ScoredCandidate candidate, FusionChannel channel) {
        return candidate.channels().stream()
                .filter(entry -> entry.channel() == channel)
                .findFirst()
                .orElseThrow();
    }

    private static FusionSettings settings(double lexical, double dense, double lambda) {
        var weights = new EnumMap<FusionChannel, Double>(FusionChannel.class);
        weights.put(FusionChannel.LEXICAL, lexical);
        weights.put(FusionChannel.DENSE, dense);
        return new FusionSettings(60, lambda, weights, FusionSettings.WeightStatus.EXPERIMENTAL);
    }

    private static SceneCandidateResult candidate(long sceneId) {
        return new SceneCandidateResult(sceneId, 10, 1, 1, 0);
    }

    private static DenseCandidatesResult.Candidate denseHit(long sceneId, int rank) {
        return new DenseCandidatesResult.Candidate(sceneId, 10, 5, rank, 0.1, 0.9, MODEL);
    }

    private static DenseCandidatesResult dense(DenseCandidatesResult.Candidate... hits) {
        return new DenseCandidatesResult(
                DenseCandidatesResult.Status.AVAILABLE,
                DenseCandidatesResult.Reason.NONE,
                List.of(hits),
                new DenseSearchSettings(MODEL, 200).snapshot(),
                MODEL,
                new DenseCandidatesResult.Coverage(1, 0, 0, 1, 0, 0, 0, 0));
    }

    private static StructuredScoresResult.SceneScore scene(long sceneId, double score) {
        return new StructuredScoresResult.SceneScore(sceneId, 10, true, false, score, 1, List.of());
    }

    private static StructuredScoresResult structured(
            List<StructuredScoresResult.SceneScore> scenes, List<StructuredScoresResult.Ineligible> ineligible) {
        return new StructuredScoresResult(resolution(), structuredSettings(1.0), scenes, ineligible);
    }

    private static StructuredScoreSettings structuredSettings(double weight) {
        var weights = new EnumMap<StructuredAxis, Double>(StructuredAxis.class);
        for (var axis : StructuredAxis.values()) weights.put(axis, weight);
        return new StructuredScoreSettings(StructuredScoreSettings.WeightStatus.EXPERIMENTAL, weights);
    }

    private static QueryResolution resolution() {
        return new QueryResolution(
                "query-resolver/v2",
                QueryResolution.Intent.SCENE_SEARCH,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                1.0);
    }
}
