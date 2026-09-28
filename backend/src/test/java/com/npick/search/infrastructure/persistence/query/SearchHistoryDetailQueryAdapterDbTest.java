package com.npick.search.infrastructure.persistence.query;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;

import com.npick.support.NpickPostgres;

import static com.npick.search.infrastructure.persistence.query.SearchHistoryFixture.OTHER;
import static com.npick.search.infrastructure.persistence.query.SearchHistoryFixture.OWNER;
import static org.assertj.core.api.Assertions.assertThat;

// 실 PostgreSQL 통합. 「내 검색 기록」 상세(S15P21A501-198) — 목록과 같은 소유·대상 조건.
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(SearchHistoryDetailQueryAdapter.class)
class SearchHistoryDetailQueryAdapterDbTest {

    @Autowired
    private SearchHistoryDetailQueryAdapter adapter;

    @Autowired
    private EntityManager em;

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry properties) {
        NpickPostgres.datasource(properties);
    }

    @Test
    @Transactional
    @DisplayName("본인 실행은 헤더와 결과 전량을 rank 오름차순으로 반환한다")
    void returnsOwnExecutionWithAllResults() {
        new SearchHistoryFixture(em).seed();

        var record = adapter.findByOwner(9701L, OWNER).orElseThrow();

        assertThat(record.item().searchExecutionId()).isEqualTo(9701L);
        assertThat(record.item().queryText()).isEqualTo("가장 오래된 질의");
        assertThat(record.item().filteredJson()).contains("returned_count");
        assertThat(record.results()).extracting(row -> row.rank()).containsExactly(1, 2);
        assertThat(record.results()).extracting(row -> row.searchResultId()).containsExactly(9801L, 9802L);
        assertThat(record.results().get(1).sceneId()).isEqualTo(9302L);
    }

    @Test
    @Transactional
    @DisplayName("타인 실행은 존재해도 빈 값이다 — 존재 여부를 노출하지 않는다")
    void hidesOtherOwnerExecution() {
        new SearchHistoryFixture(em).seed();

        assertThat(adapter.findByOwner(9707L, OWNER)).isEmpty();
        assertThat(adapter.findByOwner(9707L, OTHER)).isPresent();
    }

    @Test
    @Transactional
    @DisplayName("replay·running·failed 실행과 미존재는 모두 빈 값이다")
    void hidesOutOfScopeAndMissingExecutions() {
        new SearchHistoryFixture(em).seed();

        assertThat(adapter.findByOwner(9704L, OWNER)).isEmpty();
        assertThat(adapter.findByOwner(9705L, OWNER)).isEmpty();
        assertThat(adapter.findByOwner(9706L, OWNER)).isEmpty();
        assertThat(adapter.findByOwner(88888L, OWNER)).isEmpty();
    }
}
