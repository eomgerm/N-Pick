package com.npick.search.application.query.search;

import java.time.LocalDate;
import java.util.List;

/**
 * 검색 한 번의 결과 — {@code docs/contracts/web-api.md} §5 의 {@code data} 와 1:1 이다.
 *
 * <p><b>닫힌 어휘를 문자열로 들고 있다.</b> presentation 이 domain enum 을 알지 못하게 하면서 (설계 정본 §4) 같은 어휘를 표현 계층에 다시 정의하지 않기 위해서다. 변환은 이
 * 타입을 만드는 한 곳에서 일어나고, 그래서 응답과 {@code explain_json} 에 같은 문자열이 들어간다 — 둘이 갈리면 과거 기록과 당시 응답을 대조할 수 없다.
 *
 * @param executionId 기록이 저장됐을 때만. {@code null} 이면 이 결과로 신고할 수 없다 (§6.2)
 * @param degradedReasons 비어 있으면 {@code status} 는 {@code succeeded} 다 (§5.1 불변식)
 * @param resolved {@code query_resolution_status}. 거짓이면 {@code degradedReasons} 에 {@code resolver_fallback} 이 있다
 * @param shortageReasons {@code results} 가 10개 미만이면 1개 이상이다 (§5.1 불변식)
 * @param hasNext 다음 페이지가 있으면 참. 더보기(offset)로 다음 실행을 부를 수 있다 (S15P21A501-251)
 */
public record SearchExecutionResult(
        Long executionId,
        List<String> degradedReasons,
        boolean resolved,
        boolean hasAppliedReviewRule,
        GuardSummary guardSummary,
        List<String> shortageReasons,
        List<ResultCard> results,
        boolean hasNext) {

    public SearchExecutionResult {
        degradedReasons = List.copyOf(degradedReasons);
        shortageReasons = List.copyOf(shortageReasons);
        results = List.copyOf(results);
    }

    /** {@code degraded_reasons} 가 비어 있으면 성공이다. 계약이 둘의 일치를 요구한다 (§5.1). */
    public String status() {
        return degradedReasons.isEmpty() ? "succeeded" : "degraded";
    }

    /**
     * guard 가 무엇을 몇 개 걷어냈는가.
     *
     * <p>{@code excludedResultCount} 가 0 이면 {@code reasons} 도 비어 있다 (§5.1). 통과한 장면의 판정은 여기 오지 않는다 — 그건 기록
     * ({@code filtered_json}) 의 몫이고, 화면에 「존재하지 않는 충돌」을 띄우지 않기 위해서다.
     */
    public record GuardSummary(int excludedResultCount, List<String> reasons) {
        public GuardSummary {
            reasons = List.copyOf(reasons);
        }

        public static GuardSummary none() {
            return new GuardSummary(0, List.of());
        }
    }

    /**
     * 결과 카드 한 장.
     *
     * @param searchResultId 기록이 저장됐을 때만. {@code null} 이면 이 결과로 신고할 수 없다
     * @param rank 배열 위치와 같은 1부터 시작하는 연속 정수 (§5.1 불변식)
     * @param displayName {@code clip.title}. 없을 수 있고, 대체 표기는 화면이 정한다
     * @param matchedKeywords 걸린 키워드와 그 출처. 사용자가 친 말과 AI 가 넓힌 말을 구분한다 (F-05·F-07)
     * @param matchEvidence 1개 이상이다 (§5.1 불변식)
     */
    public record ResultCard(
            Long searchResultId,
            long sceneId,
            long clipId,
            int rank,
            String displayName,
            String sceneDescription,
            long startTimeMs,
            long endTimeMs,
            DateValue broadcastDate,
            DateValue filmedDate,
            String shotType,
            String sceneType,
            List<MatchedKeyword> matchedKeywords,
            List<MatchEvidence> matchEvidence) {

        public ResultCard {
            matchedKeywords = List.copyOf(matchedKeywords);
            matchEvidence = List.copyOf(matchEvidence);
        }
    }

    /**
     * 날짜 하나와 그 검증 상태.
     *
     * @param value 없으면 {@code null} 이고 그때 {@code verificationStatus} 는 {@code unknown} 이다 (§5.1 불변식)
     */
    public record DateValue(LocalDate value, String verificationStatus) {

        public static DateValue unknown() {
            return new DateValue(null, "unknown");
        }
    }

    /**
     * 걸린 키워드 하나와 그 출처.
     *
     * <p>사용자가 직접 친 말과 AI 해석기가 넓힌 확장어를 화면이 구분해 보여줘야 한다 (F-05 「사용자가 직접 명시한 내용과 AI가 추정한 내용을 구분한다」, F-07). 값만 싣고
     * 출처를 버리면 화면은 두 종류를 같은 칩으로 그릴 수밖에 없다.
     *
     * @param origin {@code user} 또는 {@code expanded}. 저장 기록을 복원할 때는 출처를 남기지 않던 시절의 항목에 한해 {@code null} 이다 — 모르는 것을 안다고
     *     기록하지 않는다 (FRD §7.2)
     */
    public record MatchedKeyword(String keyword, String origin) {

        public static final String ORIGIN_USER = "user";
        public static final String ORIGIN_EXPANDED = "expanded";
    }

    /**
     * 무엇이 걸렸는가.
     *
     * @param field {@code caption} · {@code ocr} · {@code transcript} · {@code tag} 중 하나 (§5.1)
     */
    public record MatchEvidence(String field, String value, String source, String verificationStatus) {}
}
