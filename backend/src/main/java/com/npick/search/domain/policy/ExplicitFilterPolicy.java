package com.npick.search.domain.policy;

import java.util.ArrayList;
import java.util.List;

import com.npick.search.domain.model.ExplicitDateFilters;
import com.npick.search.domain.model.QueryResolution;
import com.npick.search.domain.model.QueryResolution.DateWindow;
import com.npick.search.domain.model.QueryResolution.Origin;

/**
 * UI 에서 고른 날짜 필터를 해석 위에 얹는다 (FRD v3.2 F-05 「사용자가 직접 입력한 필터는 AI나 규칙이 바꿀 수 없다」).
 *
 * <h2>파이프라인 어디에 서는가</h2>
 *
 * <pre>리졸버 → AnchorVerifier → <b>여기</b> → ParseRulePolicy → guard</pre>
 *
 * <p><b>{@code AnchorVerifier} 뒤여야 한다.</b> 그 검증기는 리졸버가 붙여 보낸 {@link Origin#EXPLICIT_FILTER} 를 무조건 강등시킨다 — 리졸버는 사용자가 UI
 * 에서 무엇을 골랐는지 볼 수 없으므로 그 주장은 사용자 조건 위조다. 앞에 서면 여기서 붙인 출처를 그 검증기가 도로 떼어낸다.
 *
 * <p><b>{@link ParseRulePolicy} 앞이어야 한다.</b> 규칙 엔진은 이미 명시 필터 보호를 세 겹으로 구현해 뒀다 — 규칙이 제거할 수 없고, 값을 다른 축으로 옮길 수 없고, 적용
 * 결과에서 사라졌으면 그 규칙을 실패로 기록한다. 필터를 규칙 뒤에 얹으면 그 세 곳이 전부 도달하지 못하는 코드가 된다.
 *
 * <h2>같은 종류만 대체한다</h2>
 *
 * 필터에 있는 날짜 종류는 리졸버·규칙이 낸 같은 종류를 <b>덜어내고</b> 대체하고, 필터에 없는 종류는 손대지 않는다. 남겨두고 함께 걸면 두 조건을 모두 만족하는 장면만 남아 사용자가 고른 범위가
 * 좁아지고, guard 가 둘 다 hard 제외 근거로 써서 결과가 0건 나는 길이 열린다 (F-06).
 *
 * <p><b>다른 종류로 복사하거나 승격하지 않는다</b> (BR-DATE-001·003, F-06 「두 날짜를 서로 대체하지 않는다」). 방송일 필터를 촬영일 조건으로도 얹으면, 2022 년에 찍어 2023
 * 년에 방송한 장면이 촬영일 충돌로 잘려나간다 — 사용자는 방송일만 물었는데 정답이 사라진다.
 *
 * <p>설계 정본 §7 의 Policy 다 — 무상태 순수 Java 이고 Spring 을 모른다. 쓰는 쪽이 필드로 직접 생성해 보유한다.
 */
public final class ExplicitFilterPolicy {

    /**
     * 필터를 반영한 새 해석. 원본은 바꾸지 않는다.
     *
     * @param filters 사용자가 고르지 않았으면 {@link ExplicitDateFilters#none()}
     */
    public QueryResolution apply(QueryResolution resolution, ExplicitDateFilters filters) {
        if (filters.ranges().isEmpty()) {
            return resolution;
        }

        List<DateWindow> windows = new ArrayList<>(resolution.dateWindows().stream()
                .filter(window -> !filters.ranges().containsKey(window.field()))
                .toList());
        filters.ranges()
                .forEach((field, range) -> windows.add(new DateWindow(
                        field,
                        range.from(),
                        range.endExclusive(),
                        Origin.EXPLICIT_FILTER,
                        // 원문에서 온 값이 아니므로 가리킬 구간이 없다. 좌표를 지어내면 원문 근거를
                        // 잘라 읽는 쪽이 엉뚱한 글자를 근거로 보여 준다.
                        null,
                        // 사용자가 직접 고른 값이다. 추정이 아니므로 깎을 이유가 없다.
                        1.0)));

        return new QueryResolution(
                resolution.schemaVersion(),
                resolution.intent(),
                List.copyOf(windows),
                resolution.incidentNames(),
                resolution.entities(),
                resolution.locations(),
                resolution.classifications(),
                resolution.expandedTerms(),
                resolution.confidence());
    }
}
