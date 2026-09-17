package com.npick.search.application.query.guard;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.springframework.stereotype.Service;

import com.npick.search.domain.policy.FalseHitGuardPolicy;

/**
 * F-06 판정을 순위에 적용한다 (S15P21A501-56).
 *
 * <p>DB 를 부르지 않는다 — 판정에 필요한 태그는 {@link ApplyFalseHitGuardQuery} 가 들고 오고, 검색 가능 여부는 그 앞 단계(-52)가 이미 확인했다. 그래서 이
 * 서비스에는 트랜잭션도 조회 포트도 없다.
 */
@Service
public class FalseHitGuardService implements ApplyFalseHitGuardUseCase {

    private final FalseHitGuardPolicy policy = new FalseHitGuardPolicy();

    @Override
    public FalseHitGuardResult apply(ApplyFalseHitGuardQuery query) {
        Objects.requireNonNull(query, "query");
        var kept = new ArrayList<Long>();
        var excluded = new ArrayList<FalseHitGuardResult.ExcludedScene>();
        for (Long sceneId : query.rankedSceneIds()) {
            var tags = query.sceneTags().get(sceneId);
            var verdict = policy.judge(query.finalResolution(), tags);
            if (verdict.isEmpty()) {
                kept.add(sceneId);
                continue;
            }
            var exclusion = verdict.get();
            excluded.add(new FalseHitGuardResult.ExcludedScene(
                    sceneId, exclusion.reason(), exclusion.field(), exclusion.conflictingTagIds()));
        }
        return new FalseHitGuardResult(kept, excluded, policy.incidentGuardActive());
    }
}
