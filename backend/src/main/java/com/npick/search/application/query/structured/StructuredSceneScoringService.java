package com.npick.search.application.query.structured;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.npick.search.domain.model.StructuredScoreSettings;
import com.npick.tag.application.query.FindTagMatchedScenesUseCase;
import com.npick.tag.application.query.ResolveSceneTagsUseCase;
import com.npick.tag.domain.model.EffectiveTag;

@Service
public class StructuredSceneScoringService implements ScoreStructuredScenesUseCase {
    private final FindTagMatchedScenesUseCase tagCandidates;
    private final ResolveSceneTagsUseCase tags;
    private final FindEligibleScenesQueryPort eligibleScenes;
    private final StructuredScoreSettings settings;
    private final StructuredScoreCalculator calculator = new StructuredScoreCalculator();

    public StructuredSceneScoringService(
            FindTagMatchedScenesUseCase tagCandidates,
            ResolveSceneTagsUseCase tags,
            FindEligibleScenesQueryPort eligibleScenes,
            StructuredScoreSettings settings) {
        this.tagCandidates = tagCandidates;
        this.tags = tags;
        this.eligibleScenes = eligibleScenes;
        this.settings = settings;
    }

    /**
     * 후보·적격·태그의 복수 조회를 같은 스냅샷으로 읽는다. 상위 검색/F-12 트랜잭션이 있으면 그대로 참여하며, 검색 전체의 처리 포인터 일관성은 그 상위 트랜잭션도 같은 격리 수준으로 보장해야 한다.
     */
    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public StructuredScoresResult score(ScoreStructuredScenesQuery query) {
        Objects.requireNonNull(query, "query");
        var conditions = calculator.conditions(query.finalResolution());
        var tagIds = new TreeSet<Long>();
        var candidateConditions = calculator.candidateConditions(conditions, settings);
        if (!candidateConditions.isEmpty()) {
            tagCandidates.find(candidateConditions).forEach(candidate -> tagIds.add(candidate.sceneId()));
        }
        var inputIds = new TreeSet<>(query.candidateSceneIds());
        var allIds = new TreeSet<>(inputIds);
        allIds.addAll(tagIds);
        if (allIds.isEmpty()) {
            return new StructuredScoresResult(query.finalResolution(), settings, List.of(), List.of());
        }
        var eligible = eligibleScenes.find(allIds);
        var eligibleIds = eligible.stream()
                .map(FindEligibleScenesQueryPort.EligibleScene::sceneId)
                .toList();
        var effective = eligibleIds.isEmpty() ? Map.<Long, List<EffectiveTag>>of() : tags.resolve(eligibleIds);
        var scores = eligible.stream()
                .map(scene -> calculator.score(
                        scene,
                        inputIds.contains(scene.sceneId()),
                        tagIds.contains(scene.sceneId()),
                        conditions,
                        effective.getOrDefault(scene.sceneId(), List.of()),
                        settings))
                .toList();
        var ineligibleIds = new TreeSet<>(allIds);
        // 후보 조회에 넘긴 컬렉션은 변경하지 않는다. List.contains 기반 O(n²) 차집합도 피한다.
        eligibleIds.forEach(ineligibleIds::remove);
        return new StructuredScoresResult(query.finalResolution(), settings, scores, List.copyOf(ineligibleIds));
    }
}
