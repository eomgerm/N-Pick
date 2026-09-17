package com.npick.search.domain.policy;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.npick.search.domain.model.GuardExclusionReason;
import com.npick.search.domain.model.QueryResolution;
import com.npick.search.domain.model.QueryResolution.DateField;
import com.npick.search.domain.model.QueryResolution.DateWindow;
import com.npick.search.domain.model.QueryResolution.Origin;
import com.npick.tag.domain.model.EffectiveTag;
import com.npick.tag.domain.model.TagType;

/**
 * 명백히 잘못된 결과만 제외한다 (FRD v3.2 F-06, S15P21A501-56).
 *
 * <p><b>「hard 제외는 명시 조건과 검증된 값이 같은 필드에서 충돌할 때만」 이 이 클래스의 전부다.</b> 불확실한 정보를 걷어내는 곳이 아니다 — 값이 없거나 미검증이면 결과를 남긴다. 제외는
 * 되돌릴 수 없고, 사용자는 빠진 결과를 볼 방법이 없다. 그래서 판정이 갈리는 모든 자리에서 <b>남기는 쪽</b> 을 고른다.
 *
 * <h2>F-06 판정표</h2>
 *
 * <table border="1">
 *   <caption>여섯 분기</caption>
 *   <tr><th>상황</th><th>동작</th></tr>
 *   <tr><td>명시 날짜와 같은 종류의 검증된 날짜가 일치</td><td>제외하지 않는다. 점수는 구조화 축(-52)이 센다</td></tr>
 *   <tr><td>날짜·사건명 정보가 없거나 미검증</td><td>제외하지 않는다</td></tr>
 *   <tr><td>명시 날짜와 같은 종류의 검증된 날짜가 충돌</td><td><b>제외한다</b></td></tr>
 *   <tr><td>사건명 충돌</td><td>승인된 규칙이 있을 때만. 지금은 0 건이라 비활성 ({@link #incidentGuardActive()})</td></tr>
 *   <tr><td>인물·기관·장소·시설 불일치</td><td>제외하지 않는다. 관련성 점수에만 반영된다 (-52)</td></tr>
 *   <tr><td>AI 가 추정한 조건만 충돌</td><td>제외하지 않는다</td></tr>
 * </table>
 *
 * <h2>무엇을 보지 않는가</h2>
 *
 * <p><b>anchor 를 다시 검증하지 않는다.</b> 리졸버가 주장한 출처를 원문과 대조해 {@link Origin#INFERRED} 로 강등하는 일은 {@code AnchorVerifier}
 * (S15P21A501-46) 가 이미 했고, 그 결과가 {@link DateWindow#origin()} 에 들어 있다. 여기서는 그 값을 믿고 읽기만 한다 — 다시 판정하면 두 구현이 어긋나는 순간
 * 강등이 조용히 엇갈린다.
 *
 * <p>같은 이유로 <b>{@code findings} 를 보지 않는다.</b> 강등 사실은 이미 {@code origin} 에 반영돼 있고, {@code findings} 는 §7.2 기록용 감사 흔적이라
 * 조립(S15P21A501-59)이 -60 에 그대로 넘긴다. 판정이 그것을 다시 해석하면 같은 사실을 두 곳에서 다르게 읽게 된다.
 *
 * <p><b>날짜의 의미를 다시 해석하지 않는다.</b> 「연도만 주면 방송일」·「촬영이라고 하면 촬영일」 은 리졸버와 {@code ExplicitFilterPolicy}
 * (S15P21A501-47) 가 이미 정한 {@link DateField} 로 와 있다. 여기서는 <b>같은 종류끼리만</b> 맞댄다 — 방송일 조건에 촬영일을 맞대면 2022 년에 찍어 2023 년에
 * 방송한 장면이 사라진다 (F-06 「두 날짜를 서로 대체하지 않는다」).
 *
 * <p>설계 정본 §7 의 Policy 다 — 무상태 순수 Java 이고 Spring 을 모른다.
 */
public final class FalseHitGuardPolicy {

    /**
     * 사건명 충돌 자동 제외가 켜져 있는가.
     *
     * <p><b>항상 거짓이다.</b> F-06 이 「승인된 사건 충돌 규칙이 없으면 해당 자동 제외를 사용하지 않는다」 로 확정했고 승인된 규칙이 0 건이다. 규칙 저장·버전 구조는
     * S15P21A501-56 범위 밖으로 확정됐다 (-57 병합 시 결정).
     *
     * <p>상수를 그냥 쓰지 않고 이 메서드를 두는 이유는 §8.3 때문이다 — 「사건 충돌 판정 규칙이 승인되지 않았다면 해당 자동 제외를 끄고 그 지표를 <b>측정 불가</b> 로 구분한다」.
     * 품질 리포트(S15P21A501-108)가 이 값을 읽어 0 건과 측정 불가를 가른다. 켜는 날에는 여기 한 곳만 바뀐다.
     */
    public boolean incidentGuardActive() {
        return false;
    }

    /**
     * 이 장면을 제외할 것인가.
     *
     * @param resolution 명시 필터·해석 교정까지 끝난 최종 해석. {@link DateWindow#origin()} 이 판정의 근거다
     * @param sceneTags 이 장면의 유효 태그 전부. 일치하지 않는 태그도 있어야 충돌을 볼 수 있다 ({@code AxisScore.observedTags})
     * @return 제외 사유. 비어 있으면 남긴다
     */
    public Optional<GuardExclusion> judge(QueryResolution resolution, List<EffectiveTag> sceneTags) {
        if (resolution == null || sceneTags == null || sceneTags.isEmpty()) return Optional.empty();
        for (DateField field : DateField.values()) {
            var conflict = dateConflict(resolution, sceneTags, field);
            if (conflict.isPresent()) return conflict;
        }
        // 사건명 충돌 분기는 여기다. incidentGuardActive() 가 거짓인 동안 판정을 만들지 않는다 — 승인된 규칙이 없으면
        // 사건명 태그가 있다는 사실만으로 서로 다른 사건이라고 볼 수 없다 (F-06).
        return Optional.empty();
    }

    private Optional<GuardExclusion> dateConflict(
            QueryResolution resolution, List<EffectiveTag> sceneTags, DateField field) {
        var windows = resolution.dateWindows().stream()
                .filter(window -> window.field() == field)
                .filter(window -> stated(window.origin()))
                .toList();
        // 명시한 조건이 없으면 충돌할 대상이 없다. AI 가 추정한 창만 있는 경우가 여기서 걸러진다 (F-06).
        if (windows.isEmpty()) return Optional.empty();

        var tagType = tagTypeOf(field);
        var conflicting = new ArrayList<Long>();
        boolean anyTrusted = false;
        for (EffectiveTag tag : sceneTags) {
            if (tag.tagType() != tagType) continue;
            // 미검증은 충돌 근거가 될 수 없다. ASR·VLM·추론 규칙이 여기 온다 (F-04·F-06).
            if (!tag.verification().trustedForConflict()) continue;
            var date = parse(tag.matchValue());
            if (date.isEmpty()) continue;
            anyTrusted = true;
            if (windows.stream().anyMatch(window -> contains(window, date.get()))) {
                // 하나라도 맞으면 이 종류는 충돌이 아니다. 장면이 클립에서 상속한 날짜와 자기 날짜를 함께 가질 수 있고,
                // 그중 하나가 조건에 맞으면 사용자가 찾던 장면이다.
                return Optional.empty();
            }
            conflicting.add(tag.tagId());
        }
        // 검증된 날짜가 하나도 없으면 「정보가 없거나 미검증」 이다. 자료 영상의 방송일 부재가 여기 해당하며 충돌로 보지 않는다 (F-06).
        if (!anyTrusted) return Optional.empty();
        return Optional.of(new GuardExclusion(GuardExclusionReason.EXPLICIT_DATE_CONFLICT, field, List.copyOf(conflicting)));
    }

    /** 사용자가 직접 말한 조건인가. 추정({@link Origin#INFERRED})은 hard 제외 근거가 될 수 없다 (F-06). */
    private static boolean stated(Origin origin) {
        return origin == Origin.EXPLICIT_FILTER || origin == Origin.EXPLICIT_QUERY;
    }

    /**
     * 창이 이 날짜를 품는가. {@link DateWindow} 는 반열린 구간 {@code [start, endExclusive)} 이므로 그대로 비교한다.
     *
     * <p>닫힌 구간으로 바꾸지 않는다 — 그 변환은 {@code TagCondition.dates} 한 곳에만 있어야 호출부마다 경계 해석이 갈리지 않는다는 것이 그 클래스의 계약이다.
     */
    private static boolean contains(DateWindow window, LocalDate date) {
        return !date.isBefore(window.start()) && date.isBefore(window.endExclusive());
    }

    /**
     * 날짜 태그의 값. 파싱할 수 없으면 비어 있다.
     *
     * <p>{@code ck_tag_date} 가 {@code YYYY-MM-DD} 와 실재하는 날짜를 강제하므로 정상 데이터에서는 실패하지 않는다. 그래도 던지지 않는 이유는 <b>제외가 되돌릴 수
     * 없기 때문</b> 이다 — 손상된 행 하나로 검색 전체를 실패시키는 것도, 읽지 못한 값을 충돌로 단정해 맞는 결과를 빼는 것도 F-06 이 막으려는 것보다 나쁘다. 읽지 못한 값은 근거로 쓰지
     * 않고 넘긴다.
     */
    private static Optional<LocalDate> parse(String matchValue) {
        try {
            return Optional.of(LocalDate.parse(matchValue));
        } catch (DateTimeParseException | NullPointerException e) {
            return Optional.empty();
        }
    }

    /** 해석의 날짜 종류를 태그 종류로 옮긴다. 이름이 같아도 파생시키지 않는다 — 한쪽이 늘면 컴파일이 막혀야 한다. */
    private static TagType tagTypeOf(DateField field) {
        return switch (field) {
            case BROADCAST_DATE -> TagType.BROADCAST_DATE;
            case FILMED_DATE -> TagType.FILMED_DATE;
        };
    }

    /**
     * 제외 판정 하나.
     *
     * @param field 어느 종류의 날짜가 충돌했는가. 방송일 조건인데 촬영일이 빠졌다는 오해를 막으려면 사유만으로는 부족하다
     * @param conflictingTagIds 충돌한 검증된 태그. -60 의 {@code explain_json} 이 근거로 쓴다
     */
    public record GuardExclusion(GuardExclusionReason reason, DateField field, List<Long> conflictingTagIds) {
        public GuardExclusion {
            conflictingTagIds = List.copyOf(conflictingTagIds);
        }
    }
}
