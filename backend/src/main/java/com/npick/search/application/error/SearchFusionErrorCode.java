package com.npick.search.application.error;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;

import com.npick.common.error.ErrorCode;
import com.npick.common.error.ErrorType;

/**
 * 순위 결합의 입력 계약 위반 (설계 정본 §13 의 application ErrorCode).
 *
 * <p>{@code SceneCandidateErrorCode} 와 같은 이유로 전부 5xx 다. 여기 걸리는 값은 사용자가 준 것이 아니라 <b>검색 조립이 만든 것</b>이고, 400 으로 두면 배선 결함이
 * "검색어가 잘못됐다" 로 위장된다.
 */
@Getter
@Accessors(fluent = true)
@RequiredArgsConstructor
public enum SearchFusionErrorCode implements ErrorCode {

    /**
     * 후보 장면이 구조화 적격 판정의 어느 목록에도 없다.
     *
     * <p>S15P21A501-52 는 「요청한 sceneId 는 eligible 과 ineligible 중 정확히 한쪽에 한 번 나타난다」를 계약으로 보장한다. 이것이 깨졌다는 것은 조립이 단어·dense
     * 후보와 다른 집합을 구조화 점수 계산에 넘겼다는 뜻이다. 조용히 떨어뜨리면 「내가 아는 그 영상이 왜 안 나왔는지」에 답할 수 없다 (F-06).
     */
    CANDIDATE_NOT_SCREENED(ErrorType.INTERNAL_SERVER_ERROR, "SRCH_500_002", "후보 적격 판정이 빠졌다"),

    /**
     * 활성 dense 채널인데 결과 객체가 없다.
     *
     * <p>설정상 켜진 채널은 조립이 반드시 실행해 결과를 넘겨야 한다. 결과가 없으면 「실행하지 않음」과 「실행했는데 실패」를 구분할 수 없고, 둘은 사용자 안내가 다르다 (degraded 판정).
     *
     * <p><b>이 검사는 dense 에만 적용된다.</b> dense 는 {@code DenseCandidatesResult} 라는 결과 객체를 돌려주므로, 그 자리가 {@code null} 이면 정상적인 조회 결과가
     * 전달되지 않은 것으로 판단한다 — 조회를 하지 않았는지 조회는 했는데 조립이 전달을 빠뜨렸는지까지는 여기서 알 수 없다. 반면 단어 검색은 목록만 돌려주고 빈 목록은 「일치가 없었다」는 정상
     * 결과다 — 전달 누락과 구분할 방법이 없으므로 여기서 잡을 수 없다.
     */
    ACTIVE_CHANNEL_RESULT_MISSING(ErrorType.INTERNAL_SERVER_ERROR, "SRCH_500_003", "활성 검색 채널의 결과가 없다"),

    /**
     * 꺼진 채널인데 조회 결과가 들어왔다.
     *
     * <p>가중치 0 은 그 채널을 쓰지 않는다는 뜻이므로 조립이 조회 자체를 하지 않아야 한다. 결과를 받아 기여만 0 으로 만들면 <b>그 채널로만 들어온 후보가 0 점으로 살아남는다</b> — 순수 단어
     * 검색 실험을 돌렸는데 dense 가 끌어온 장면이 결과에 섞이고, 기록에는 dense 설정이 없어 유입 경로를 설명할 수도 없다 (F-06 「왜 이 결과가 나왔는가」).
     */
    INACTIVE_CHANNEL_RESULT_PRESENT(ErrorType.INTERNAL_SERVER_ERROR, "SRCH_500_004", "꺼진 검색 채널의 결과가 들어왔다");

    private final ErrorType type;
    private final String code;
    private final String message;
}
