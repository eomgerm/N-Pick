package com.npick.search.application.query.fusion;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.springframework.stereotype.Service;

import com.npick.common.error.BusinessException;
import com.npick.search.application.error.SearchFusionErrorCode;
import com.npick.search.application.query.candidate.SceneCandidateResult;
import com.npick.search.application.query.dense.DenseCandidatesResult;
import com.npick.search.application.query.fusion.FusionResult.ChannelContribution;
import com.npick.search.application.query.fusion.FusionResult.ChannelState;
import com.npick.search.application.query.fusion.FusionResult.ScoredCandidate;
import com.npick.search.application.query.structured.StructuredScoresResult;
import com.npick.search.domain.model.FusionChannel;
import com.npick.search.domain.model.FusionSettings;
import com.npick.search.domain.model.LexicalSearchSettings;
import com.npick.search.domain.policy.RrfFusionPolicy;

@Service
public class SearchRankingFusionService implements FuseSearchRankingUseCase {

    private final FusionSettings settings;
    private final LexicalSearchSettings lexicalSettings;
    private final RrfFusionPolicy policy = new RrfFusionPolicy();

    public SearchRankingFusionService(FusionSettings settings, LexicalSearchSettings lexicalSettings) {
        this.settings = settings;
        this.lexicalSettings = lexicalSettings;
    }

    @Override
    public FusionResult fuse(FuseSearchRankingQuery query) {
        Objects.requireNonNull(query, "query");
        var structured = query.structuredScores();
        // 꺼진 채널의 결과를 조용히 무시하면 그 채널로만 들어온 후보가 0 점으로 살아남는다. 기여만이 아니라 유입도 막는다.
        requireInactiveChannelsWereNotQueried(query);
        // 적격 판정을 거치지 않은 후보가 섞이면 「왜 안 나왔는지」의 답이 사라진다. 조용히 떨어뜨리지 않고 배선 결함으로 드러낸다.
        requireScreened(query, structured);

        Map<Long, Integer> lexicalRanks = lexicalRanks(query.lexicalCandidates());
        DenseChannel dense = denseChannel(query.denseCandidates());
        double ceiling = policy.ceiling(settings);

        var candidates = new ArrayList<ScoredCandidate>(structured.scenes().size());
        for (var scene : structured.scenes()) {
            var lexical = contribution(FusionChannel.LEXICAL, ChannelState.MATCHED, lexicalRanks.get(scene.sceneId()));
            var denseContribution = contribution(FusionChannel.DENSE, dense.state(), dense.rankOf(scene.sceneId()));
            double rrfSum = lexical.contribution() + denseContribution.contribution();
            double structuredContribution = settings.lambda() * scene.score();
            candidates.add(new ScoredCandidate(
                    scene.sceneId(),
                    scene.clipId(),
                    policy.baseScore(settings, rrfSum, ceiling, scene.score()),
                    rrfSum / ceiling,
                    structuredContribution,
                    scene.score(),
                    List.of(lexical, denseContribution)));
        }
        // -52 가 이미 sceneId 오름차순으로 준다. 그 순서를 그대로 지키는 것이 계약이므로 다시 정렬하지 않는다.
        var snapshot = new SearchConfigSnapshot(settings, lexicalSettings, dense.settings(), structured.settings());
        return new FusionResult(candidates, snapshot, snapshot.version());
    }

    /**
     * 채널 하나의 기여.
     *
     * <p>가중치가 0 이면 조회 결과가 있어도 {@link ChannelState#OFF} 다 — 가중치 0 이 곧 off 라는 정의를 여기서도 지킨다. 활성인데 이 후보가 그 채널에 없으면
     * {@link ChannelState#MISSED} 이고 기여는 0 이다. 가짜 꼴등 순위를 만들어 넣지 않는다.
     */
    private ChannelContribution contribution(FusionChannel channel, ChannelState activeState, Integer rank) {
        double weight = settings.weightOf(channel);
        if (!settings.isActive(channel)) {
            return new ChannelContribution(channel, ChannelState.OFF, null, weight, 0);
        }
        if (activeState == ChannelState.FAILED) {
            return new ChannelContribution(channel, ChannelState.FAILED, null, weight, 0);
        }
        if (rank == null) {
            return new ChannelContribution(channel, ChannelState.MISSED, null, weight, 0);
        }
        return new ChannelContribution(
                channel, ChannelState.MATCHED, rank, weight, policy.contribution(settings, channel, rank));
    }

    /** 단어 검색 후보는 이미 점수 내림차순·동점 {@code sceneId} 오름차순이다. 그 자리 자체가 이 채널의 순위다. */
    private static Map<Long, Integer> lexicalRanks(List<SceneCandidateResult> candidates) {
        var ranks = new HashMap<Long, Integer>();
        for (int index = 0; index < candidates.size(); index++) {
            ranks.putIfAbsent(candidates.get(index).sceneId(), index + 1);
        }
        return ranks;
    }

    private DenseChannel denseChannel(DenseCandidatesResult result) {
        if (!settings.isActive(FusionChannel.DENSE)) {
            // 설정상 끈 채널이다. 조회되지 않았음은 위에서 강제했으므로 기록할 dense 설정도 없다 — 켠 실행과 다른 설정이라 버전도 달라진다.
            return new DenseChannel(ChannelState.OFF, Map.of(), null);
        }
        if (result == null) {
            throw new BusinessException(SearchFusionErrorCode.ACTIVE_CHANNEL_RESULT_MISSING);
        }
        if (result.status() == DenseCandidatesResult.Status.UNAVAILABLE) {
            return new DenseChannel(ChannelState.FAILED, Map.of(), result.settings());
        }
        var ranks = new HashMap<Long, Integer>();
        result.candidates().forEach(candidate -> ranks.putIfAbsent(candidate.sceneId(), candidate.rank()));
        return new DenseChannel(ChannelState.MATCHED, ranks, result.settings());
    }

    /**
     * 꺼진 채널은 조회되지 않았어야 한다.
     *
     * <p>{@link SearchFusionErrorCode#ACTIVE_CHANNEL_RESULT_MISSING} 의 반대쪽이다. 한쪽만 막으면 「가중치 0 = off」가 기여에만 적용되고 후보 유입에는
     * 적용되지 않는 비대칭이 남는다. 태그로 독립 유입된 후보는 채널 결과가 아니므로 이 검사에 걸리지 않는다.
     */
    private void requireInactiveChannelsWereNotQueried(FuseSearchRankingQuery query) {
        boolean lexicalQueried = !query.lexicalCandidates().isEmpty();
        if (!settings.isActive(FusionChannel.LEXICAL) && lexicalQueried) {
            throw new BusinessException(SearchFusionErrorCode.INACTIVE_CHANNEL_RESULT_PRESENT);
        }
        if (!settings.isActive(FusionChannel.DENSE) && query.denseCandidates() != null) {
            throw new BusinessException(SearchFusionErrorCode.INACTIVE_CHANNEL_RESULT_PRESENT);
        }
    }

    private static void requireScreened(FuseSearchRankingQuery query, StructuredScoresResult structured) {
        var screened = new HashSet<Long>();
        structured.scenes().forEach(scene -> screened.add(scene.sceneId()));
        structured.ineligibleScenes().forEach(scene -> screened.add(scene.sceneId()));
        query.lexicalCandidates().forEach(candidate -> requireScreened(screened, candidate.sceneId()));
        if (query.denseCandidates() != null) {
            query.denseCandidates().candidates().forEach(candidate -> requireScreened(screened, candidate.sceneId()));
        }
    }

    private static void requireScreened(Set<Long> screened, long sceneId) {
        if (!screened.contains(sceneId)) {
            throw new BusinessException(SearchFusionErrorCode.CANDIDATE_NOT_SCREENED);
        }
    }

    /** dense 채널의 상태·순위·실제 사용 설정을 한 번만 계산해 후보 전체에 재사용한다. */
    private record DenseChannel(
            ChannelState state,
            Map<Long, Integer> ranks,
            com.npick.search.application.query.dense.DenseSearchSettings.Snapshot settings) {
        Integer rankOf(long sceneId) {
            return ranks.get(sceneId);
        }
    }
}
