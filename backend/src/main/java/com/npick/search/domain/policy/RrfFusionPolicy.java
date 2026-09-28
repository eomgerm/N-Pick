package com.npick.search.domain.policy;

import com.npick.search.domain.model.FusionChannel;
import com.npick.search.domain.model.FusionSettings;

/**
 * 순위 결합 산식 (FRD F-05 「BM25와 텍스트 의미 검색의 순위 결합(RRF)」).
 *
 * <pre>
 * R(d) = Σ  w_c / (k + rank_c)      후보가 나타난 채널만 더한다
 * M    = Σ  w_c / (k + 1)           설정상 활성 채널이 전부 1등일 때의 값
 * base = R(d)/M + λ × structured(d)
 * </pre>
 *
 * <p><b>M 을 관측 최고점이 아니라 설정에서 계산하는 이유.</b> 질의마다 후보 구성이 달라지므로 관측 최고점으로 나누면 후보가 빈약한 질의에서 점수가 부풀고 실행 간 비교가 깨진다. M 은 설정만으로
 * 정해지는 이론적 상한이라 같은 설정의 두 실행이 같은 척도를 쓴다.
 *
 * <p><b>채널이 실패해도 M 을 줄이지 않는 쪽을 택했다.</b> 살아 있는 채널로 다시 정규화하면 남은 채널의 기여가 자동으로 커지는데, 이는 FRD F-05 「정보가 없는 항목에 가점을 주지 않는다」가
 * 경계하는 방향이다. 재정규화가 곧바로 요구사항 위반이라는 뜻은 아니고, 두 선택지 중 이쪽을 고른 것이다. 대신 실패 사실을 기록한다. 부작용은 있다: dense 가 죽으면 모두의 {@code R/M} 이 줄어
 * {@code λ × structured} 의 상대적 영향력이 커진다. 이는 감수하는 쪽이다 — λ 를 같이 줄이면 dense 와 무관한 태그 전용 후보까지 dense 장애의 벌을 받는다.
 *
 * <p>후보가 없는 채널의 기여는 <b>0</b> 이다. 가짜 꼴등 순위를 만들어 넣지 않는다 — 목록에 없는 문서가 0 을 받는 것이 RRF 의 원래 동작이다.
 *
 * <p>가중치의 공통 배율은 {@code R/M} 에서 상쇄된다. 즉 {@code {1,1}} 과 {@code {0.5,0.5}} 는 같은 결과를 낸다. 의미를 갖는 것은 채널 사이의 비율뿐이다.
 */
public final class RrfFusionPolicy {

    /** 활성 채널이 전부 1등일 때의 RRF 점수. {@link FusionSettings} 가 전 채널 0 을 막으므로 0 이 되지 않는다. */
    public double ceiling(FusionSettings settings) {
        double ceiling = 0;
        for (FusionChannel channel : FusionChannel.values()) {
            if (settings.isActive(channel)) {
                ceiling += contribution(settings, channel, 1);
            }
        }
        if (!(ceiling > 0) || !Double.isFinite(ceiling)) {
            throw new IllegalStateException("Fusion ceiling must be a finite positive number");
        }
        return ceiling;
    }

    /**
     * 한 채널이 이 후보에게 주는 기여.
     *
     * @param rank 1 부터 시작하는 그 채널 안에서의 순위
     */
    public double contribution(FusionSettings settings, FusionChannel channel, int rank) {
        if (rank < 1) throw new IllegalArgumentException("rank is one-based: " + rank);
        if (!settings.isActive(channel)) return 0;
        return settings.weightOf(channel) / (settings.rrfK() + rank);
    }

    /**
     * 최종 base 점수.
     *
     * @param rrfSum 채널 기여의 합. 어느 채널에도 없었으면 0 이다
     * @param ceiling {@link #ceiling(FusionSettings)} 의 값
     * @param structuredScore S15P21A501-52 의 가중평균 [0,1] + S15P21A501-321 키워드 가산점 [0, keywordWeight]
     */
    public double baseScore(FusionSettings settings, double rrfSum, double ceiling, double structuredScore) {
        return rrfSum / ceiling + settings.lambda() * structuredScore;
    }
}
