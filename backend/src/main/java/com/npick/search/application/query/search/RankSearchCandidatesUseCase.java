package com.npick.search.application.query.search;

import java.util.Objects;

import com.npick.search.application.port.QueryNormalization;
import com.npick.search.application.query.dense.DenseQuery;
import com.npick.search.domain.model.NormalizedSearch;
import com.npick.search.domain.model.QueryResolution;

/**
 * 후보 조회 → 점수 → 순위 → guard → 장면 제외를 <b>한 읽기 스냅샷</b>에서 끝낸다.
 *
 * <p>조립에서 이 구간만 떼어낸 이유는 트랜잭션 경계가 다르기 때문이다. 채널 셋과 태그·제외 규칙은 같은 시점을 봐야 하고(-54 의 「조립이 같은 트랜잭션 스냅샷에서 모아 넘긴다」), 실행 기록은 그 밖에서
 * 커밋돼야 한다.
 */
public interface RankSearchCandidatesUseCase {

    SearchCandidates rank(Query query);

    /**
     * @param finalResolution 규칙·명시 필터까지 적용한 해석. 해석 실패(fallback)면 {@code null} 이고, 그때는 BM25 만 돈다
     * @param queryEmbedding 리졸버가 준 질의 벡터. 없으면 dense 채널은 자기 사유로 unavailable 을 낸다
     * @param normalizedSearch 승인된 장면 제외를 exact 지문으로 조회하는 데 쓴다
     * @param page 0-based 결과 페이지. 제외 적용 후 이 페이지 구간(page*10 .. +10)만 낸다
     */
    record Query(
            QueryNormalization normalization,
            QueryResolution finalResolution,
            DenseQuery queryEmbedding,
            NormalizedSearch normalizedSearch,
            int page) {

        public Query {
            Objects.requireNonNull(normalization, "normalization");
            Objects.requireNonNull(normalizedSearch, "normalizedSearch");
            if (page < 0) {
                throw new IllegalArgumentException("page 는 0 이상이어야 한다: " + page);
            }
        }

        /** 페이지 미지정 = 첫 페이지 (검증 검색 등 더보기가 없는 경로). */
        public Query(
                QueryNormalization normalization,
                QueryResolution finalResolution,
                DenseQuery queryEmbedding,
                NormalizedSearch normalizedSearch) {
            this(normalization, finalResolution, queryEmbedding, normalizedSearch, 0);
        }
    }
}
