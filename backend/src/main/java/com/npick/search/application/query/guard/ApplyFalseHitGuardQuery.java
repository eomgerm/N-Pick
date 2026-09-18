package com.npick.search.application.query.guard;

import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.npick.search.domain.model.QueryResolution;
import com.npick.tag.domain.model.EffectiveTag;

/**
 * F-06 제외를 적용할 검색 순위와 그 판정에 쓸 태그 (S15P21A501-56).
 *
 * <p><b>태그를 여기서 받는 이유가 있다.</b> {@code ResolveSceneTagsUseCase} 는 「키가 없음」 이 ① 유효 태그 없음 ② 전부 반려됨 ③ <b>검색 대상 자체가 아님</b> 셋을
 * 함께 뜻한다고 못박고, 그 판정기를 guard 가 직접 불러 키 없음을 「검증된 날짜가 없다」 로 읽으면 <b>폐기된 처리·삭제된 클립의 장면이 제외되지 않고 통과한다</b> 고 경고한다. 검색 가능 여부는
 * 이미 {@code FindEligibleScenesQueryPort}(-52)가 확인했으므로, 그 결과를 쥔 호출자가 태그를 넘기고 guard 는 판정만 한다.
 *
 * <p>같은 이유로 <b>{@code rankedSceneIds} 의 모든 장면에 항목이 있어야 한다.</b> 유효 태그가 없는 장면은 빈 목록으로 넣는다. 빠진 키를 조용히 「태그 없음」 으로 읽으면 위 ③ 이
 * 그대로 되살아나고, 그것은 배선 실수가 검색 결과에 숨는 길이다.
 *
 * @param finalResolution 명시 필터·해석 교정까지 끝난 최종 해석
 * @param rankedSceneIds 점수·융합이 끝난 순위. guard 는 순서를 바꾸지 않고 걸러내기만 한다
 * @param sceneTags 장면 번호 → 유효 태그. 일치하지 않는 태그도 있어야 충돌을 볼 수 있다
 */
public record ApplyFalseHitGuardQuery(
        QueryResolution finalResolution, List<Long> rankedSceneIds, Map<Long, List<EffectiveTag>> sceneTags) {

    public ApplyFalseHitGuardQuery {
        Objects.requireNonNull(finalResolution, "finalResolution");
        Objects.requireNonNull(sceneTags, "sceneTags");
        rankedSceneIds = List.copyOf(rankedSceneIds);
        // 검사를 Map.copyOf 앞에 둔다. 뒤에 두면 값이 null 인 항목에서 copyOf 가 먼저 NPE 를 던져,
        // 어느 장면이 문제인지 알려 주는 이 메시지가 나오지 못한다.
        //
        // get(id) == null 하나로 「키가 없음」 과 「값이 null」 을 같이 잡는다. 둘 다 같은 배선 실수이고,
        // 어느 쪽이든 조용히 넘기면 그 장면이 판정 없이 통과한다.
        var given = sceneTags;
        var missing =
                rankedSceneIds.stream().filter(id -> given.get(id) == null).toList();
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException("순위에 있는 장면의 태그가 없다: " + missing);
        }
        sceneTags = Map.copyOf(sceneTags);
    }
}
