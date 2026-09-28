package com.npick.tag.application.query;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.npick.tag.domain.model.EffectiveTag;
import com.npick.tag.domain.policy.TagResolutionPolicy;

/**
 * 유효 태그 판정의 유일한 진입점 (S15P21A501-161).
 *
 * <p>검색의 네 곳(후보 추출·명시 필터 비교·구조화 축 점수·근거 설명)이 모두 이 클래스를 경유한다. 두 UseCase 를 한 Service 가 구현하는 이유는 둘이 같은 조회 포트와 같은 판정 규칙을
 * 공유하기 때문이다 (설계 정본 §6).
 *
 * <p>판정 규칙은 상태 없는 순수 Policy 라 필드로 직접 생성해 보유한다 (정본 §7).
 *
 * <p>여기에 {@code @Transactional} 을 붙이지 않는다. 단건 Projection 조회에는 붙이지 않는다는 정본 §5 의 규칙이고, 후보 검증 검색(F-12)은 <b>호출자</b> 가 후보
 * 적용과 검색을 한 트랜잭션으로 묶어야 하는 쪽이다. 여기서 새 트랜잭션을 열면 그 묶음을 방해한다. (후보는 S15P21A501-160 이후 {@code confirmed=false} 로 저장되고 조회 어댑터가
 * {@code e.confirmed} 로 일반 검색에서 제외한다 — F-12 검증 검색이 후보를 보려면 그 필터를 여는 경로가 필요하다.)
 */
@Service
public class SceneTagResolutionService implements ResolveSceneTagsUseCase, FindTagMatchedScenesUseCase {

    private final FindTagJudgmentsQueryPort judgments;
    private final TagResolutionPolicy policy = new TagResolutionPolicy();

    public SceneTagResolutionService(FindTagJudgmentsQueryPort judgments) {
        this.judgments = judgments;
    }

    @Override
    public Map<Long, List<EffectiveTag>> resolve(Collection<Long> sceneIds) {
        // 빈 목록은 DB 를 부르지 않는다. null 은 통과시켜 포트의 requireNonNull 이 터지게 한다 —
        // 여기서 Map.of() 로 삼키면 아래 두 계층이 일부러 세운 널 가드가 무력해지고,
        // 호출부 배선 실수가 "태그 없음" 으로 위장된다.
        if (sceneIds != null && sceneIds.isEmpty()) {
            return Map.of();
        }
        return policy.resolve(judgments.findByScenes(sceneIds));
    }

    @Override
    public Map<Long, List<EffectiveTag>> resolveForRun(long clipId, long pipelineRunId, Collection<Long> sceneIds) {
        if (sceneIds != null && sceneIds.isEmpty()) return Map.of();
        return policy.resolve(judgments.findByRunScenes(clipId, pipelineRunId, sceneIds));
    }

    @Override
    public List<TagMatchedScene> find(List<TagCondition> conditions) {
        if (conditions != null && conditions.isEmpty()) {
            return List.of();
        }
        // 조회가 조건에 맞는 태그만 가져오므로, 판정을 통과한 태그는 모두 조건에 맞는 태그다.
        // 반려·해제로 죽은 태그는 판정에서 빠지고, 그 태그뿐이던 장면은 키 자체가 사라진다.
        return policy.resolve(judgments.findByConditions(conditions)).entrySet().stream()
                .map(scene -> new TagMatchedScene(
                        scene.getKey(), scene.getValue().getFirst().clipId(), scene.getValue()))
                .sorted(Comparator.comparingLong(TagMatchedScene::sceneId))
                .toList();
    }
}
