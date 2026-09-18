package com.npick.search.application.query.search;

import java.util.List;

import com.npick.search.application.query.card.SceneCard;
import com.npick.search.application.query.exclusion.ActiveSceneExclusionResult;
import com.npick.search.application.query.fusion.FuseSearchRankingQuery;
import com.npick.search.application.query.fusion.FusionResult;
import com.npick.search.application.query.fusion.SearchConfigSnapshot;
import com.npick.search.application.query.guard.FalseHitGuardResult;
import com.npick.search.application.query.soft.SoftRankingResult;
import com.npick.search.application.resolution.SearchDegradedReason;
import com.npick.search.domain.model.ShortageReason;
import com.npick.tag.domain.model.EffectiveTag;

/**
 * 후보 조회부터 제외까지 한 스냅샷에서 끝낸 결과.
 *
 * <p>여기까지가 읽기 트랜잭션 안이다. 실행 기록 저장은 이 밖에서 따로 커밋된다 — 후보 검증(F-12)이 임시 반영→검색→ROLLBACK 으로 돌기 때문에, 기록이 같은 트랜잭션에 묶이면 롤백과 함께
 * 사라진다 (baseline 주석).
 *
 * @param scenes rank 순, 최대 10개. 제외된 장면은 들어 있지 않다
 * @param candidates 거르기 전 후보 전체. 순위 계산에 넘긴 것과 같은 객체를 기록에도 쓴다
 * @param guard 통과한 장면의 판정까지 들어 있다. 응답의 {@code guard_summary} 는 이 중 제외 건만 센다
 * @param degradedReasons 이 구간이 낸 사유. 해석 단계가 낸 것과 합쳐야 최종 {@code status} 가 된다
 * @param shortageReasons 10개를 못 채웠으면 1개 이상. 왜 못 채웠는지는 이 구간만 안다
 * @param expandedTokens 이 검색이 실제로 쓴 확장어 토큰. 근거 설명이 원 질의 토큰만 보면 확장어로만 걸린 장면의 {@code matched_keywords} 가 비어 「왜 나왔는지 모르는
 *     결과」가 된다
 */
public record SearchCandidates(
        List<ScoredScene> scenes,
        FuseSearchRankingQuery candidates,
        FalseHitGuardResult guard,
        List<ActiveSceneExclusionResult.ExcludedScene> appliedExcludes,
        SearchConfigSnapshot config,
        List<SearchDegradedReason> degradedReasons,
        List<ShortageReason> shortageReasons,
        List<String> expandedTokens) {

    public SearchCandidates {
        scenes = List.copyOf(scenes);
        appliedExcludes = List.copyOf(appliedExcludes);
        degradedReasons = List.copyOf(degradedReasons);
        shortageReasons = List.copyOf(shortageReasons);
        expandedTokens = expandedTokens == null ? List.of() : List.copyOf(expandedTokens);
    }

    /**
     * 살아남은 장면 하나와 그것이 거기 있는 이유 전부.
     *
     * <p>카드와 태그를 점수·판정과 함께 들고 다니는 이유는 {@code explain_json} 과 응답이 <b>같은 값</b>이어야 하기 때문이다. 나중에 다시 읽으면 그 사이 태그 교정이 반영돼 과거
     * 근거가 바뀐다 (§7.2 「지금의 교정 상태로 다시 계산해 덮어쓰지 않는다」).
     *
     * @param verdict guard 판정. 살아남았으므로 {@code exclusionReason} 은 {@code null} 이다
     */
    public record ScoredScene(
            long sceneId,
            long clipId,
            SceneCard card,
            List<EffectiveTag> tags,
            FusionResult.ScoredCandidate score,
            SoftRankingResult.OrderedCandidate soft,
            FalseHitGuardResult.SceneVerdict verdict) {

        public ScoredScene {
            tags = List.copyOf(tags);
        }
    }
}
