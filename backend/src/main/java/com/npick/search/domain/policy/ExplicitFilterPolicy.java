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
 * <pre>리졸버 → AnchorVerifier → ParseRulePolicy → <b>여기</b> → guard</pre>
 *
 * <p><b>{@code AnchorVerifier} 뒤여야 한다.</b> 그 검증기는 리졸버가 붙여 보낸 {@link Origin#EXPLICIT_FILTER} 를 무조건 강등시킨다 — 리졸버는 사용자가 UI
 * 에서 무엇을 골랐는지 볼 수 없으므로 그 주장은 사용자 조건 위조다. 앞에 서면 여기서 붙인 출처를 그 검증기가 도로 떼어낸다.
 *
 * <p><b>{@link ParseRulePolicy} 뒤여야 한다.</b> 규칙 판정의 기준이 <b>AI 원본 해석</b> 이기 때문이다 — {@code docs/frd.md} F-05 「신규 패턴 규칙은
 * 검색어 지문 일치가 아니라 AI 원본 해석의 조건으로 판정한다」. {@link ParseRulePolicy#apply} 는 조건 판정과 연산 대상에 <b>같은 해석 하나</b> 를 쓰므로, 필터를 먼저 얹으면
 * 사용자가 고른 날짜가 규칙 조건 판정에 섞여 어떤 규칙이 적용되는지가 달라진다. 마지막에 얹어도 F-05 4항은 지켜진다 — 이 클래스가 같은 날짜 종류의 창을 덜어내고 대체하므로 규칙이 무엇을 했든 사용자
 * 필터가 이긴다.
 *
 * <p><b>대신 {@link ParseRulePolicy} 의 명시 필터 보호 세 곳({@code remove_item} 거부·{@code value_from} 재사용 거부·사후 소실 검증)은 이 순서에서
 * 도달하지 않는다.</b> 규칙이 {@link Origin#EXPLICIT_FILTER} 항목을 볼 일이 없어서다. 지킬 것이 없으므로 결과는 안전하지만 <b>기록은 정확하지 않다</b> — 필터가 걸린 날짜
 * 종류를 건드린 규칙이 {@code applied} 로 남고 {@code has_applied_review_rule} 도 참이 되는데, 그 효과는 여기서 덮여 결과에 도달하지 못한다 (§7.2). 바로잡으려면
 * 조건 판정은 원본으로, 연산은 필터를 얹은 해석으로 나눠 받도록 {@code apply} 를 갈라야 한다. 별 일감이다.
 *
 * <p><b>구멍이 하나 더 있다.</b> {@code add_item} 으로 같은 날짜 종류의 <b>다른 구간</b> 창을 얹는 경로는 위 보호가 살아 있어도 통과한다 — 항목 동일성이 {@code 종류|구간}
 * 이라 중복이 아니고, 필터 창은 그대로 남아 있어 사후 검증도 지나간다. 리터럴로 추가한 값은 {@link Origin#INFERRED} 로 강제되므로 hard 제외는 못 하지만, 사용자가 고른 범위와 모순되는
 * 방송일 조건이 관련성 점수에 섞인다. 막으려면 규칙 엔진 쪽에 「명시 필터가 있는 날짜 종류에는 창을 추가할 수 없다」 를 더해야 한다 ({@code S15P21A501-49} 의 소관이라 여기서 손대지
 * 않았다).
 *
 * <h2>같은 종류만 대체한다</h2>
 *
 * 필터에 있는 날짜 종류는 리졸버·규칙이 낸 같은 종류를 <b>덜어내고</b> 대체하고, 필터에 없는 종류는 손대지 않는다. 남겨두고 함께 걸면 두 조건을 모두 만족하는 장면만 남아 사용자가 고른 범위가
 * 좁아지고, guard 가 둘 다 hard 제외 근거로 써서 결과가 0건 나는 길이 열린다 (F-06).
 *
 * <p><b>다른 종류로 복사하거나 승격하지 않는다</b> — {@code docs/frd.md} F-06 「두 날짜를 서로 대체하지 않는다」 (Notion 규칙 번호로는 BR-DATE-001·003 이다. 그
 * 번호의 본문은 저장소에 없으므로 F-06 을 근거로 읽는다). 방송일 필터를 촬영일 조건으로도 얹으면, 2022 년에 찍어 2023 년에 방송한 장면이 촬영일 충돌로 잘려나간다 — 사용자는 방송일만 물었는데
 * 정답이 사라진다.
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
