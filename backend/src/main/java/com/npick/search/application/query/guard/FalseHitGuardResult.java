package com.npick.search.application.query.guard;

import java.util.List;

import com.npick.search.domain.model.GuardExclusionReason;
import com.npick.search.domain.policy.FalseHitGuardPolicy.FieldJudgment;

/**
 * F-06 판정 결과 (S15P21A501-56).
 *
 * <p>제외한 결과를 다시 채워 넣지 않는다 — F-06 완료 기준이 「다음 유효 후보가 있으면 원래 순서를 유지해 보충하고, 부족하면 10 개 미만을 반환한다」 다. 그래서 여기서는 <b>순서만 유지한 채
 * 걸러내고</b>, 몇 개를 돌려줄지와 부족 사유({@code shortage_reasons})는 조립(S15P21A501-59)이 정한다.
 *
 * @param sceneIds 제외 후 남은 장면. 입력 순위 그대로다
 * @param verdicts <b>순위에 있던 모든 장면</b> 의 판정. 제외된 것만이 아니다 — {@code search_result.explain_json} 은 남은 장면에만 생기면서
 *     {@code guard} 자리를 두므로, 통과한 장면의 판정이 없으면 그 자리가 늘 빈다 (PRD §7.9)
 * @param incidentGuardActive 사건명 충돌 자동 제외가 켜져 있었는가. 실행마다 싣는다 — §8.3 이 「규칙이 승인되지 않았다면 해당 자동 제외를 끄고 그 지표를 측정 불가로 구분한다」 를
 *     요구하는데, 이 값이 없으면 품질 리포트가 「꺼져서 0 건」 과 「켜져 있는데 0 건」 을 가를 수 없다
 */
public record FalseHitGuardResult(List<Long> sceneIds, List<SceneVerdict> verdicts, boolean incidentGuardActive) {

    public FalseHitGuardResult {
        sceneIds = List.copyOf(sceneIds);
        verdicts = List.copyOf(verdicts);
    }

    /** 제외된 장면만. {@code guard_summary.excluded_result_count} 와 {@code reasons} 가 여기서 나온다. */
    public List<SceneVerdict> excluded() {
        return verdicts.stream().filter(SceneVerdict::excluded).toList();
    }

    /**
     * 한 장면의 판정.
     *
     * @param exclusionReason 제외 사유. 통과했으면 {@code null} 이다
     * @param fields 명시한 anchor 별 3 값 판정. 명시 조건이 없었으면 비어 있다
     */
    public record SceneVerdict(long sceneId, GuardExclusionReason exclusionReason, List<FieldJudgment> fields) {

        public SceneVerdict {
            fields = List.copyOf(fields);
        }

        public boolean excluded() {
            return exclusionReason != null;
        }
    }
}
