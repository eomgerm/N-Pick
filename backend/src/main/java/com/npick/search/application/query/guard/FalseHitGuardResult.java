package com.npick.search.application.query.guard;

import java.util.List;

import com.npick.search.domain.model.GuardExclusionReason;
import com.npick.search.domain.model.QueryResolution.DateField;

/**
 * F-06 제외 결과 (S15P21A501-56).
 *
 * <p>제외한 결과를 다시 채워 넣지 않는다 — F-06 완료 기준이 「다음 유효 후보가 있으면 원래 순서를 유지해 보충하고, 부족하면 10 개 미만을 반환한다」 다. 그래서 여기서는 <b>순서만
 * 유지한 채 걸러내고</b>, 몇 개를 돌려줄지와 부족 사유({@code shortage_reasons})는 조립(S15P21A501-59)이 정한다.
 *
 * @param sceneIds 제외 후 남은 장면. 입력 순위 그대로다
 * @param excludedScenes 빠진 장면과 그 근거. -60 의 {@code explain_json}·{@code filtered_json} 재료이며, 응답의
 *     {@code guard_summary} 도 이 목록에서 나온다
 */
public record FalseHitGuardResult(List<Long> sceneIds, List<ExcludedScene> excludedScenes) {

    public FalseHitGuardResult {
        sceneIds = List.copyOf(sceneIds);
        excludedScenes = List.copyOf(excludedScenes);
    }

    /**
     * @param field 어느 종류의 날짜가 충돌했는가. 사유만 남기면 방송일 조건에 촬영일을 맞댔는지 알 수 없다
     * @param conflictingTagIds 충돌한 검증된 태그. 「무엇과 충돌했는가」 를 다시 조회하지 않게 한다
     */
    public record ExcludedScene(
            long sceneId, GuardExclusionReason reason, DateField field, List<Long> conflictingTagIds) {

        public ExcludedScene {
            conflictingTagIds = List.copyOf(conflictingTagIds);
        }
    }
}
