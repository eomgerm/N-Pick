package com.npick.search.application.query.search;

import java.util.Objects;

import com.npick.search.application.port.QueryNormalization;
import com.npick.search.application.query.dense.DenseQuery;
import com.npick.search.domain.model.NormalizedSearch;
import com.npick.search.domain.model.QueryResolution;

/**
 * 후보 조회 → 점수 → 순위 → guard → 장면 제외를 <b>한 읽기 스냅샷</b>에서 끝낸다.
 *
 * <p>조립에서 이 구간만 떼어낸 이유는 트랜잭션 경계가 다르기 때문이다. 채널 셋과 태그·제외 규칙은 같은 시점을 봐야 하고(-54 의 「조립이 같은 트랜잭션 스냅샷에서 모아 넘긴다」),
 * 실행 기록은 그 밖에서 커밋돼야 한다.
 */
public interface RankSearchCandidatesUseCase {

    SearchCandidates rank(Query query);

    /**
     * @param finalResolution 규칙·명시 필터까지 적용한 해석. 해석 실패(fallback)면 {@code null} 이고, 그때는 BM25 만 돈다
     * @param queryEmbedding 리졸버가 준 질의 벡터. 없으면 dense 채널은 자기 사유로 unavailable 을 낸다
     * @param normalizedSearch 승인된 장면 제외를 exact 지문으로 조회하는 데 쓴다
     */
    record Query(
            QueryNormalization normalization,
            QueryResolution finalResolution,
            DenseQuery queryEmbedding,
            NormalizedSearch normalizedSearch) {

        public Query {
            Objects.requireNonNull(normalization, "normalization");
            Objects.requireNonNull(normalizedSearch, "normalizedSearch");
        }
    }
}
