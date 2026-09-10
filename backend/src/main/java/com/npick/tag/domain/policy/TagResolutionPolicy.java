package com.npick.tag.domain.policy;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

import com.npick.tag.domain.model.EffectiveTag;
import com.npick.tag.domain.model.TagJudgment;

/**
 * 「이 장면에 이 태그가 지금 유효한가」 를 판정한다 (FRD v3.1 F-04·F-10, S15P21A501-161).
 *
 * <p>판정 순서는 <b>장면의 최신 사람 판단 → 클립의 최신 사람 판단 → 일반 근거</b> 다. 위에서 결론이 나면 아래는 보지 않는다.
 *
 * <p>이 계산이 SQL 이 아니라 순수 Java 에 있는 이유: 완료 조건 전부가 이 순서의 검증이고, DB 테스트는 전용 테스트 DB 환경 변수가 없으면 <b>조용히 생략</b> 된다. 생략된 테스트는 통과가
 * 아니다. 판정 규칙만은 컨테이너 없이 항상 도는 자리에 둔다.
 *
 * <p>설계 정본 §7 에 따라 상태 없는 순수 Policy 다. {@code @Service} 를 붙이지 않고 Application Service 가 필드로 직접 생성해 보유한다.
 */
public final class TagResolutionPolicy {

    /** 최신 판단이 마지막에 온다. 시각 동률은 {@code evidence_id} 로 가른다 — {@code tag_evidence.created_at} 컬럼 주석이 정한 방식이다. */
    private static final Comparator<TagJudgment> LATEST_LAST =
            Comparator.comparing(TagJudgment::createdAt).thenComparingLong(TagJudgment::evidenceId);

    /**
     * 가장 센 일반 근거가 마지막에 온다.
     *
     * <p>세 단계로 고른다. ① 검증된 관측 근거가 하나라도 있으면 그것이 대표다 — 그래서 이 정렬 하나로 <b>검증 상태와 대표 출처를 함께</b> 얻는다. ② 같으면 장면 근거가 클립 상속보다
     * 구체적이다. ③ 그래도 같으면 최신.
     */
    private static final Comparator<TagJudgment> STRONGEST_GENERAL_LAST = Comparator.comparing(
                    TagJudgment::observationVerified, Boolean::compare)
            .thenComparing(TagJudgment::sceneScoped, Boolean::compare)
            .thenComparing(LATEST_LAST);

    /** 결과 목록의 순서를 고정한다. 근거 설명과 테스트가 순서에 의존해도 되게 한다. */
    private static final Comparator<EffectiveTag> STABLE_ORDER =
            Comparator.comparing(EffectiveTag::tagType).thenComparing(EffectiveTag::matchValue);

    /**
     * 근거 줄을 장면별 유효 태그로 접는다.
     *
     * @param judgments 상속까지 펼쳐진 근거 줄. 순서는 상관없다
     * @return 장면 번호 → 유효 태그. <b>유효 태그가 하나도 없는 장면은 키 자체가 없다</b> — 호출부는 빈 목록이 아니라 부재를 다뤄야 한다
     */
    public Map<Long, List<EffectiveTag>> resolve(Collection<TagJudgment> judgments) {
        // null 은 빈 목록과 다르다. 조용히 빈 결과를 주면 호출부 배선 실수가 "태그 없음" 으로 위장된다.
        Objects.requireNonNull(judgments, "judgments");
        return judgments.stream().collect(Collectors.groupingBy(TagJudgment::key)).values().stream()
                .map(TagResolutionPolicy::resolveOne)
                .flatMap(Optional::stream)
                .collect(Collectors.groupingBy(
                        EffectiveTag::sceneId, Collectors.collectingAndThen(Collectors.toList(), sorted -> {
                            sorted.sort(STABLE_ORDER);
                            return List.copyOf(sorted);
                        })));
    }

    /** 한 장면·한 태그의 판정. 사다리 3단이 여기 다 있다. */
    private static Optional<EffectiveTag> resolveOne(List<TagJudgment> group) {
        // 1단계. 범위별로 최신 사람 판단을 하나씩만 뽑는다.
        TagJudgment sceneLatest = latestReview(group, true);
        TagJudgment clipLatest = latestReview(group, false);

        // 2단계. 개입 해제면 그 범위에 판단이 없는 것으로 만든다.
        //
        // 이 두 단계를 하나로 합치면 안 된다. "해제가 아닌 최신 판단" 을 한 번의 정렬로 찾으면
        // [승인 → 해제] 이력에서 해제를 건너뛰고 그 앞의 승인을 집어, 취소한 승인이 되살아난다.
        // F-10 이 명시적으로 금지한 동작이다.
        TagJudgment verdict = unlessWithdrawn(sceneLatest);
        if (verdict == null) {
            verdict = unlessWithdrawn(clipLatest);
        }

        if (verdict != null) {
            // 반려. 클립 상속도 여기서 끊긴다 — F-10 의 "클립에서 상속된 태그라도 해당 장면에만 부적절하면 장면별 예외로 반려".
            //
            // 승인이 아닌 나머지를 반려로 다루는 것은 ck_evidence_review_shape 가 사람 판단의
            // 상태를 verified·rejected·withdrawn 셋으로 제한하기 때문이다. 해제는 이미 위에서 걸러졌다.
            if (!verdict.approvedByReviewer()) {
                return Optional.empty();
            }
            return Optional.of(effective(verdict, EffectiveTag.Verification.REVIEWER_VERIFIED));
        }

        // 3단계. 사람 판단이 없으면 원본·상속을 다시 평가한다. 장면 근거와 클립 근거를 함께 본다.
        // 근거가 하나도 없으면 유효하지 않다 — 값만 있고 출처가 없는 태그를 검색에 쓰지 않는다 (F-04).
        return group.stream()
                .filter(judgment -> !judgment.reviewer())
                .max(STRONGEST_GENERAL_LAST)
                .map(best -> effective(
                        best,
                        best.observationVerified()
                                ? EffectiveTag.Verification.VERIFIED
                                : EffectiveTag.Verification.UNVERIFIED));
    }

    private static TagJudgment latestReview(List<TagJudgment> group, boolean sceneScoped) {
        return group.stream()
                .filter(judgment -> judgment.reviewer() && judgment.sceneScoped() == sceneScoped)
                .max(LATEST_LAST)
                .orElse(null);
    }

    private static TagJudgment unlessWithdrawn(TagJudgment latest) {
        return latest == null || latest.withdrawn() ? null : latest;
    }

    private static EffectiveTag effective(TagJudgment decided, EffectiveTag.Verification verification) {
        return new EffectiveTag(
                decided.sceneId(),
                decided.clipId(),
                decided.tagId(),
                decided.tagType(),
                decided.matchValue(),
                decided.name(),
                verification,
                decided.sceneScoped() ? EffectiveTag.Scope.SCENE : EffectiveTag.Scope.CLIP,
                decided.source());
    }
}
