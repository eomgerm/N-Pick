package com.npick.search.application.query.search;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.npick.search.application.query.candidate.FindSceneCandidatesQueryPort;
import com.npick.search.application.query.candidate.SceneCandidateResult;
import com.npick.search.application.query.card.FindSceneCardsQueryPort;
import com.npick.search.application.query.card.SceneCard;
import com.npick.search.application.query.dense.DenseCandidatesResult;
import com.npick.search.application.query.dense.DenseQuery;
import com.npick.search.application.query.dense.DenseSearchSettings;
import com.npick.search.application.query.dense.FindDenseCandidatesQueryPort;
import com.npick.search.application.query.exclusion.ActiveSceneExclusionResult;
import com.npick.search.application.query.exclusion.ApplyActiveSceneExclusionsQuery;
import com.npick.search.application.query.exclusion.ApplyActiveSceneExclusionsUseCase;
import com.npick.search.application.query.expansion.TokenizeExpandedTermsPort;
import com.npick.search.application.query.fusion.FuseSearchRankingQuery;
import com.npick.search.application.query.fusion.FuseSearchRankingUseCase;
import com.npick.search.application.query.fusion.FusionResult;
import com.npick.search.application.query.guard.ApplyFalseHitGuardQuery;
import com.npick.search.application.query.guard.ApplyFalseHitGuardUseCase;
import com.npick.search.application.query.guard.FalseHitGuardResult;
import com.npick.search.application.query.search.SearchCandidates.ScoredScene;
import com.npick.search.application.query.soft.AdjustSoftRankingQuery;
import com.npick.search.application.query.soft.AdjustSoftRankingUseCase;
import com.npick.search.application.query.soft.SoftRankingResult;
import com.npick.search.application.query.structured.ScoreStructuredScenesQuery;
import com.npick.search.application.query.structured.ScoreStructuredScenesUseCase;
import com.npick.search.application.query.structured.StructuredScoresResult;
import com.npick.search.application.resolution.SearchDegradedReason;
import com.npick.search.domain.model.FusionChannel;
import com.npick.search.domain.model.FusionSettings;
import com.npick.search.domain.model.ShortageReason;
import com.npick.tag.application.query.ResolveSceneTagsUseCase;
import com.npick.tag.domain.model.EffectiveTag;

/**
 * 후보 조회부터 장면 제외까지를 한 읽기 스냅샷에서 끝낸다.
 *
 * <p>순서는 F-05 3항이 정한 그대로다 — 단어·dense 후보 → 구조화 점수 → RRF 결합 → soft 조정 → F-06 guard → 승인된 장면 제외 → 최대 10개.
 *
 * <p>{@code readOnly} 트랜잭션을 여기에만 거는 이유는 채널 셋과 태그·제외 규칙이 <b>같은 시점</b>을 봐야 하기 때문이다. 실행 기록은 이 밖에서 커밋된다 — 후보 검증(F-12)이 임시
 * 반영→검색→ROLLBACK 으로 돌기 때문에 같이 묶이면 기록이 롤백과 함께 사라진다.
 */
@Service
public class SearchCandidatePipeline implements RankSearchCandidatesUseCase {

    private final FindSceneCandidatesQueryPort lexicalCandidates;
    private final FindDenseCandidatesQueryPort denseCandidates;
    private final ObjectProvider<DenseSearchSettings> denseSettings;
    private final FusionSettings fusionSettings;
    private final ScoreStructuredScenesUseCase structuredScores;
    private final FuseSearchRankingUseCase fusion;
    private final AdjustSoftRankingUseCase softRanking;
    private final ResolveSceneTagsUseCase sceneTags;
    private final ApplyFalseHitGuardUseCase guard;
    private final ApplyActiveSceneExclusionsUseCase sceneExclusions;
    private final FindSceneCardsQueryPort sceneCards;
    private final TokenizeExpandedTermsPort expandedTerms;

    public SearchCandidatePipeline(
            FindSceneCandidatesQueryPort lexicalCandidates,
            FindDenseCandidatesQueryPort denseCandidates,
            ObjectProvider<DenseSearchSettings> denseSettings,
            FusionSettings fusionSettings,
            ScoreStructuredScenesUseCase structuredScores,
            FuseSearchRankingUseCase fusion,
            AdjustSoftRankingUseCase softRanking,
            ResolveSceneTagsUseCase sceneTags,
            ApplyFalseHitGuardUseCase guard,
            ApplyActiveSceneExclusionsUseCase sceneExclusions,
            FindSceneCardsQueryPort sceneCards,
            TokenizeExpandedTermsPort expandedTerms) {
        this.lexicalCandidates = lexicalCandidates;
        this.denseCandidates = denseCandidates;
        this.denseSettings = denseSettings;
        this.fusionSettings = fusionSettings;
        this.structuredScores = structuredScores;
        this.fusion = fusion;
        this.softRanking = softRanking;
        this.sceneTags = sceneTags;
        this.guard = guard;
        this.sceneExclusions = sceneExclusions;
        this.sceneCards = sceneCards;
        this.expandedTerms = expandedTerms;
    }

    @Override
    // REPEATABLE_READ 를 여기서 지정해야 한다. 안쪽 유스케이스들이 각자 선언해 두었지만
    // 기존 트랜잭션에 참여할 때는 그 선언이 무시되고 바깥 격리수준이 적용된다. 기본
    // READ_COMMITTED 로 두면 동시 태그 교정 시 구조화 점수·soft·guard·응답 근거가 서로
    // 다른 상태를 읽어, 주석이 약속한 「같은 시점」이 성립하지 않는다.
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public SearchCandidates rank(Query query) {
        List<SearchDegradedReason> degraded = new ArrayList<>();

        List<List<String>> expandedPhrases = expandedPhrases(query);
        List<SceneCandidateResult> lexical = lexical(query, expandedPhrases);
        DenseCandidatesResult dense = dense(query, degraded);

        StructuredScoresResult structured = structuredScores.score(
                new ScoreStructuredScenesQuery(query.finalResolution(), candidateSceneIds(lexical, dense)));
        FuseSearchRankingQuery fuseQuery = new FuseSearchRankingQuery(lexical, dense, structured);
        FusionResult fused = fusion.fuse(fuseQuery);

        SoftRankingResult ordered =
                softRanking.adjust(new AdjustSoftRankingQuery(fused.candidates(), query.finalResolution()));
        List<Long> rankedSceneIds = ordered.candidates().stream()
                .map(SoftRankingResult.OrderedCandidate::sceneId)
                .toList();

        Map<Long, List<EffectiveTag>> tags = sceneTagsOf(rankedSceneIds);
        FalseHitGuardResult guarded =
                guard.apply(new ApplyFalseHitGuardQuery(query.finalResolution(), rankedSceneIds, tags));
        ActiveSceneExclusionResult excluded = sceneExclusions.apply(
                new ApplyActiveSceneExclusionsQuery(query.normalizedSearch(), guarded.sceneIds(), query.page()));

        // 부족 사유는 실제로 내보내는 수로 센다. 제외 뒤 개수로 세면 카드가 없어 빠진 장면이
        // 누락돼 «9개인데 사유 없음» 이 나가고 계약 §5.1 이 깨진다.
        List<ScoredScene> scenes = scenes(excluded.sceneIds(), ordered, fused, guarded, tags);
        return new SearchCandidates(
                scenes,
                fuseQuery,
                guarded,
                excluded.excludedScenes(),
                fused.config(),
                degraded,
                shortageReasons(scenes.size(), guarded, excluded),
                flattenForEvidence(query, expandedPhrases),
                excluded.hasMore());
    }

    /**
     * 단어 채널을 돌린다.
     *
     * <p>꺼져 있으면 <b>조회하지 않는다</b>. {@code FusionSettings} 는 「채널 하나 이상이 양수」만 강제하므로 {@code LEXICAL} 가중치 0 은 유효한 설정이고, 그때
     * 후보를 넘기면 순위 결합이 {@code INACTIVE_CHANNEL_RESULT_PRESENT} 로 검색을 죽인다. dense 와 같은 규칙이다.
     *
     * <p>dense 와 달리 사유를 남기지 않는다. 끈 채널은 장애가 아니고, 설정으로 끈 것을 사용자에게 「일부 기능 누락」으로 안내하면 매 검색이 degraded 가 된다.
     */
    private List<SceneCandidateResult> lexical(Query query, List<List<String>> expandedPhrases) {
        if (!fusionSettings.isActive(FusionChannel.LEXICAL)) {
            return List.of();
        }
        return lexicalCandidates.findByWords(query.normalization().searchTokens(), expandedPhrases);
    }

    /**
     * 순위에 든 장면의 태그. <b>태그가 없는 장면은 빈 목록으로 채워 넘긴다.</b>
     *
     * <p>{@code TagResolutionPolicy} 는 「유효 태그가 하나도 없는 장면은 키 자체가 없다 — 호출부는 빈 목록이 아니라 부재를 다뤄야 한다」로 계약을 못박아 두었고, guard 는
     * 순위에 있는 장면의 키가 없으면 던진다. 그 둘을 그대로 이으면 <b>무태그 장면 하나가 검색 전체를 500 으로 만든다.</b>
     *
     * <p>부재를 빈 목록으로 바꾸는 것이 의미를 흐리지 않는 이유는 guard 가 태그를 <b>충돌 근거</b>로만 보기 때문이다. 근거가 없으면 판정은 「확인 불가」이고, 그것은 태그가 0 개인 것과 같은
     * 결론이다 (F-06 「정보가 없거나 미검증이면 그 이유만으로 제외하지 않는다」).
     */
    private Map<Long, List<EffectiveTag>> sceneTagsOf(List<Long> rankedSceneIds) {
        Map<Long, List<EffectiveTag>> resolved = sceneTags.resolve(rankedSceneIds);
        Map<Long, List<EffectiveTag>> complete = new LinkedHashMap<>();
        for (Long sceneId : rankedSceneIds) {
            complete.put(sceneId, resolved.getOrDefault(sceneId, List.of()));
        }
        return complete;
    }

    /**
     * 확장어를 <b>구 단위 묶음</b>으로 토큰화한다 (S15P21A501-302).
     *
     * <p>평탄화하지 않는 것이 핵심이다. 펼쳐 넘기면 어댑터의 {@code term_set} 이 확장어를 OR 로 받아 「중국 음식」이 {@code 중국} OR {@code 음식} 이 되고, 짜장면 검색에
     * 중국 경제 뉴스가 올라온다 (운영 실측 19건). 확장어만 맞은 장면이 질의어가 맞은 장면을 앞지르지 않게 하는 것은 종전대로 낮은 가중의 별도 절이 맡는다.
     *
     * <p><b>원 질의 토큰만으로 이루어진 묶음은 뺀다.</b> 그 구는 원 질의 절이 이미 거는 것과 같아, 남기면 두 절에서 각각 가산돼 F-05 의 「같은 개체를 중복 계산하지 않는다」를 깬다. 일부만
     * 겹치는 묶음은 <b>통째로 남긴다</b> — 구에서 토큰 하나를 빼면 {@code must} 가 그만큼 헐거워져 이 티켓이 없애려는 넓은 매칭이 되살아난다.
     *
     * <p>규칙 적용 <b>뒤</b>의 확장어를 토큰화한다. 교정으로 검수자가 넣은 값은 리졸버가 모르고, 백엔드에는 Kiwi 가 없다 (S15P21A501-205 의 창구).
     */
    private List<List<String>> expandedPhrases(Query query) {
        if (query.finalResolution() == null
                || query.finalResolution().expandedTerms().isEmpty()) {
            return List.of();
        }
        Set<String> queryTokens = Set.copyOf(query.normalization().searchTokens());
        return expandedTerms
                .tokenize(
                        query.finalResolution().expandedTerms(),
                        query.normalization().normalizationVersion())
                .stream()
                .filter(phrase -> !queryTokens.containsAll(phrase))
                .toList();
    }

    /**
     * 근거 설명용으로 구 묶음을 편다. <b>{@code matched_keywords} 계약은 평탄화된 토큰 목록 그대로다</b> (web-api §5.1) — 구 단위 AND 는 후보 조회의 사정이고 응답
     * 형태를 바꾸지 않는다 (S15P21A501-302).
     *
     * <p>원 질의와 겹치는 토큰은 조립이 {@code distinct} 로 합치므로 여기서 다시 뺄 필요는 없지만, 「확장어가 기여한 토큰」이라는 의미를 유지하려고 뺀다.
     */
    private static List<String> flattenForEvidence(Query query, List<List<String>> phrases) {
        if (phrases.isEmpty()) return List.of();
        Set<String> queryTokens = Set.copyOf(query.normalization().searchTokens());
        return phrases.stream()
                .flatMap(List::stream)
                .filter(token -> !queryTokens.contains(token))
                .distinct()
                .toList();
    }

    /**
     * dense 채널을 돌린다.
     *
     * @return 채널이 꺼져 있으면 {@code null}. 껐다는 것과 돌렸는데 실패했다는 것은 다르고, 순위 결합이 그 둘을 구분해 받는다
     */
    private DenseCandidatesResult dense(Query query, List<SearchDegradedReason> degraded) {
        if (!fusionSettings.isActive(FusionChannel.DENSE)) {
            return null;
        }
        DenseSearchSettings settings = denseSettings.getObject();
        // 벡터가 없어도 채널을 건너뛰지 않는다. 사유를 남겨야 degraded 안내와 기록이 "왜 의미 검색이
        // 빠졌나" 에 답할 수 있다 — 조용히 넘기면 단어 검색만 돈 사실이 어디에도 남지 않는다.
        DenseQuery vector = query.queryEmbedding() == null ? new DenseQuery(null, null) : query.queryEmbedding();
        DenseCandidatesResult result = denseCandidates.find(vector, settings);
        if (result.status() != DenseCandidatesResult.Status.AVAILABLE) {
            degraded.add(SearchDegradedReason.DENSE_UNAVAILABLE);
        }
        return result;
    }

    /** 구조화 점수를 매길 대상. 두 채널이 올린 후보의 합집합이며 순서는 dense 가 아니라 단어 검색 순이다. */
    private List<Long> candidateSceneIds(List<SceneCandidateResult> lexical, DenseCandidatesResult dense) {
        Set<Long> ids = new LinkedHashSet<>();
        lexical.forEach(candidate -> ids.add(candidate.sceneId()));
        if (dense != null) {
            dense.candidates().forEach(candidate -> ids.add(candidate.sceneId()));
        }
        return List.copyOf(ids);
    }

    /**
     * 살아남은 장면을 순위 순서대로 조립한다.
     *
     * <p>카드 조회는 여기 한 번뿐이다. 후보 전체가 아니라 최대 10건에만 필요하고, 대사 원문까지 함께 읽기 때문이다.
     */
    private List<ScoredScene> scenes(
            List<Long> sceneIds,
            SoftRankingResult ordered,
            FusionResult fused,
            FalseHitGuardResult guarded,
            Map<Long, List<EffectiveTag>> tags) {
        Map<Long, SoftRankingResult.OrderedCandidate> soft = new LinkedHashMap<>();
        ordered.candidates().forEach(candidate -> soft.put(candidate.sceneId(), candidate));
        Map<Long, FusionResult.ScoredCandidate> scores = new LinkedHashMap<>();
        fused.candidates().forEach(candidate -> scores.put(candidate.sceneId(), candidate));
        Map<Long, FalseHitGuardResult.SceneVerdict> verdicts = new LinkedHashMap<>();
        guarded.verdicts().forEach(verdict -> verdicts.put(verdict.sceneId(), verdict));

        Map<Long, SceneCard> cards = sceneCards.find(sceneIds);
        List<ScoredScene> scenes = new ArrayList<>();
        for (Long sceneId : sceneIds) {
            SceneCard card = cards.get(sceneId);
            if (card == null) {
                // 순위에는 올랐는데 카드가 없다. 검색 도중 장면이 사라진 경우이고, 이름·시간 없이
                // 내보내면 화면이 빈 카드를 그린다. 결과에서 빼는 편이 낫다.
                continue;
            }
            SoftRankingResult.OrderedCandidate candidate = soft.get(sceneId);
            scenes.add(new ScoredScene(
                    sceneId,
                    candidate.clipId(),
                    card,
                    tags.getOrDefault(sceneId, List.of()),
                    scores.get(sceneId),
                    candidate,
                    verdicts.get(sceneId)));
        }
        return scenes;
    }

    /**
     * 10개를 못 채운 이유 (§5.1 「결과가 10개 미만이면 {@code shortage_reasons} 가 1개 이상」).
     *
     * <p>걷어낸 것이 있으면 그것을 먼저 든다. 후보가 원래 적었던 것과 있었는데 빠진 것은 사용자에게 다른 이야기다.
     */
    private List<ShortageReason> shortageReasons(
            int returned, FalseHitGuardResult guarded, ActiveSceneExclusionResult excluded) {
        if (returned >= 10) {
            return List.of();
        }
        List<ShortageReason> reasons = new ArrayList<>();
        if (!guarded.excluded().isEmpty() || !excluded.excludedScenes().isEmpty()) {
            reasons.add(ShortageReason.GUARD_EXCLUDED);
        }
        reasons.add(ShortageReason.CANDIDATE_POOL_EXHAUSTED);
        return reasons;
    }
}
