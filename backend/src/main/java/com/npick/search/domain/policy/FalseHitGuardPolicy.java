package com.npick.search.domain.policy;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import com.npick.search.domain.model.GuardExclusionReason;
import com.npick.search.domain.model.GuardJudgment;
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
 * <p>FRD 의 표는 일곱 줄이고 그중 마지막 「승인된 특정 장면 제외 규칙」 은 S15P21A501-58 소관이라 여기 없다. 나머지 여섯 줄이 이 클래스의 전부다.
 *
 * <table border="1">
 *   <caption>F-06 의 여섯 줄</caption>
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
     * 품질 리포트(S15P21A501-108)가 이 값을 읽어 0 건과 측정 불가를 가른다.
     *
     * <p><b>켜려면 여기만 바꿔서는 안 된다.</b> 지금 판정 단위인 {@link FieldJudgment} 는 {@link DateField} 에 묶여 있어 사건명 판정을 담을
     * 자리가 없다. 사건명 자동 제외를 켜는 작업은 판정 타입을 넓히고 그 판정이 자기 사유를 들고 오게 하는 것부터 시작한다.
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
    public Verdict judge(QueryResolution resolution, List<EffectiveTag> sceneTags) {
        Objects.requireNonNull(resolution, "resolution");
        var tags = sceneTags == null ? List.<EffectiveTag>of() : sceneTags;
        var fields = new ArrayList<FieldJudgment>();
        for (DateField field : DateField.values()) {
            judgeDate(resolution, tags, field).ifPresent(fields::add);
        }
        // 사건명 판정 분기는 여기다. incidentGuardActive() 가 거짓인 동안 판정을 만들지 않는다 — 승인된 규칙이 없으면
        // 사건명 태그가 있다는 사실만으로 서로 다른 사건이라고 볼 수 없고, PRD §7.9 도 그 경우를 soft 처리로 둔다.
        return new Verdict(fields);
    }

    /**
     * 날짜 종류 하나의 판정. 명시한 조건이 없으면 판정할 anchor 자체가 없으므로 비어 있다.
     *
     * <p>AI 가 추정한 창만 있는 경우가 그 「없음」 에 포함된다 — 추정은 anchor 가 아니므로 통과 근거로도 제외 근거로도 적지 않는다 (F-06).
     */
    private Optional<FieldJudgment> judgeDate(
            QueryResolution resolution, List<EffectiveTag> sceneTags, DateField field) {
        var windows = resolution.dateWindows().stream()
                .filter(window -> window.field() == field)
                .filter(window -> stated(window.origin()))
                .toList();
        if (windows.isEmpty()) return Optional.empty();

        var tagType = tagTypeOf(field);
        // 같은 태그가 입력 목록에 두 번 올 수 있다 — ResolveSceneTagsUseCase 의 결과가 그렇고,
        // StructuredScoreCalculator 도 같은 이유로 distinct() 를 건다. 근거 id 가 중복되면 -60 의
        // explain_json 에 같은 근거가 두 번 실려 충돌 태그가 실제보다 많아 보인다.
        var matched = new LinkedHashSet<Long>();
        var conflicting = new LinkedHashSet<Long>();
        for (EffectiveTag tag : sceneTags) {
            if (tag.tagType() != tagType) continue;
            // 미검증은 판정 근거가 될 수 없다. ASR·VLM·추론 규칙이 여기 온다 (F-04·F-06).
            if (!tag.verification().trustedForConflict()) continue;
            var date = parse(tag.matchValue());
            if (date.isEmpty()) continue;
            if (windows.stream().anyMatch(window -> contains(window, date.get()))) {
                matched.add(tag.tagId());
            } else {
                conflicting.add(tag.tagId());
            }
        }
        // 하나라도 맞으면 일치다. 장면이 클립에서 상속한 날짜와 자기 날짜를 함께 가질 수 있고, 그중 하나가 조건에 맞으면
        // 사용자가 찾던 장면이다. 나머지를 충돌로 세어 빼면 맞는 결과가 사라진다.
        if (!matched.isEmpty()) {
            return Optional.of(new FieldJudgment(field, GuardJudgment.VERIFIED_MATCH, List.copyOf(matched)));
        }
        // 검증된 날짜가 하나도 없으면 「정보가 없거나 미검증」 이다. 자료 영상의 방송일 부재가 여기 해당한다 (F-06).
        if (conflicting.isEmpty()) {
            return Optional.of(new FieldJudgment(field, GuardJudgment.UNKNOWN_OR_UNVERIFIED, List.of()));
        }
        return Optional.of(new FieldJudgment(field, GuardJudgment.VERIFIED_CONFLICT, List.copyOf(conflicting)));
    }

    /** 사용자가 직접 말한 조건인가. 추정({@link Origin#INFERRED})은 판정 근거가 될 수 없다 (F-06). */
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
     * <p>{@code ck_tag_date} 가 {@code YYYY-MM-DD} 와 실재하는 날짜를 강제하므로 정상 데이터에서는 실패하지 않는다. 그래도 던지지 않는 이유는 <b>제외가 되돌릴
     * 수 없기 때문</b> 이다 — 손상된 행 하나로 검색 전체를 실패시키는 것도, 읽지 못한 값을 충돌로 단정해 맞는 결과를 빼는 것도 F-06 이 막으려는 것보다 나쁘다. 읽지 못한 값은
     * 근거로 쓰지 않고 넘긴다.
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
     * 명시 anchor 하나의 판정.
     *
     * @param field 어느 종류의 날짜인가. 사유만 남기면 방송일 조건에 촬영일을 맞댔는지 알 수 없다
     * @param groundingTagIds 판정의 근거가 된 검증된 태그. 일치면 맞은 태그, 충돌이면 어긋난 태그다. -60 의
     *     {@code explain_json} 이 「무엇을 보고 그렇게 판정했는가」 를 다시 조회하지 않게 한다
     */
    public record FieldJudgment(DateField field, GuardJudgment judgment, List<Long> groundingTagIds) {
        public FieldJudgment {
            groundingTagIds = List.copyOf(groundingTagIds);
        }

        /** 이 판정이 충돌일 때의 제외 사유. 판정 단위가 날짜뿐이므로 날짜 충돌 하나다. */
        public GuardExclusionReason conflictReason() {
            return GuardExclusionReason.EXPLICIT_DATE_CONFLICT;
        }
    }

    /**
     * 한 장면의 guard 판정 전부.
     *
     * <p><b>통과한 장면도 판정을 갖는다.</b> {@code search_result.explain_json} 은 남은 장면에만 생기면서 {@code guard} 자리를 두므로,
     * 제외만 기록하면 그 자리가 늘 비어 결과 카드가 F-07 의 「미검증 표시」 를 그릴 재료를 잃는다 (PRD §7.9).
     *
     * @param fields 명시한 anchor 별 판정. 명시 조건이 없으면 비어 있고, 그것은 판정할 것이 없었다는 뜻이다
     */
    public record Verdict(List<FieldJudgment> fields) {
        public Verdict {
            fields = List.copyOf(fields);
        }

        /**
         * 제외 사유. 충돌한 anchor 가 없으면 {@code null} 이다.
         *
         * <p>사유를 여기서 정하지 않고 <b>충돌한 판정에게 묻는다.</b> 사유를 이 자리에 상수로 박으면, 나중에 사건명 판정이 생겼을 때 그 충돌까지
         * 날짜 사유로 기록된다 — 검수자가 없는 날짜 충돌을 찾게 되고 사건명 지표는 0 으로 남는다.
         */
        public GuardExclusionReason exclusionReason() {
            return fields.stream()
                    .filter(field -> field.judgment().excludes())
                    .map(FieldJudgment::conflictReason)
                    .findFirst()
                    .orElse(null);
        }

        public boolean excluded() {
            return exclusionReason() != null;
        }
    }
}
