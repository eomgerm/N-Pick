package com.npick.search.infrastructure.persistence.query;

import java.util.List;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;

import org.hibernate.SessionFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;

import com.npick.search.application.query.SearchHistoryRecord;
import com.npick.support.NpickPostgres;

import static com.npick.search.infrastructure.persistence.query.SearchHistoryFixture.OTHER;
import static com.npick.search.infrastructure.persistence.query.SearchHistoryFixture.OWNER;
import static org.assertj.core.api.Assertions.assertThat;

// 실 PostgreSQL 통합. 「내 검색 기록」 목록(S15P21A501-198) — 소유자 격리·대상 필터·정렬·페이지·N+1 부재.
@DataJpaTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(SearchHistoryListQueryAdapter.class)
class SearchHistoryListQueryAdapterDbTest {

    @Autowired
    private SearchHistoryListQueryAdapter adapter;

    @Autowired
    private EntityManager em;

    @Autowired
    private EntityManagerFactory emf;

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry properties) {
        NpickPostgres.datasource(properties);
    }

    @Test
    @Transactional
    @DisplayName("본인 original·succeeded/degraded 만 최신순으로 반환하고 헤더·결과를 채운다")
    void findsOwnOriginalNewestFirstWithResults() {
        new SearchHistoryFixture(em).seed();

        List<SearchHistoryRecord> mine = adapter.findByOwner(OWNER, 0, 20);

        assertThat(mine).extracting(record -> record.item().searchExecutionId()).containsExactly(9703L, 9702L, 9701L);

        SearchHistoryRecord oldest = mine.get(2);
        assertThat(oldest.item().queryText()).isEqualTo("가장 오래된 질의");
        assertThat(oldest.item().status()).isEqualTo("succeeded");
        assertThat(oldest.item().parseSource()).isEqualTo("resolver");
        assertThat(oldest.item().explicitFiltersJson()).contains("broadcast_date");
        assertThat(oldest.item().filteredJson()).contains("candidate_pool_exhausted");
        assertThat(oldest.item().createdAt()).isNotNull();
        assertThat(oldest.results()).extracting(row -> row.searchResultId()).containsExactly(9801L, 9802L);
        assertThat(oldest.results().get(0).sceneId()).isEqualTo(9301L);
        assertThat(oldest.results().get(0).clipId()).isEqualTo(9101L);
        assertThat(oldest.results().get(0).rank()).isEqualTo(1);
        assertThat(oldest.results().get(0).explainJson()).contains("display_name");

        // degraded 도 조회 대상이다.
        assertThat(mine.get(1).item().status()).isEqualTo("degraded");
        assertThat(mine.get(1).item().degradedReasonsJson()).contains("dense_unavailable");
    }

    @Test
    @Transactional
    @DisplayName("타인이 실행한 검색은 목록과 총계 양쪽에서 빠진다")
    void excludesOtherOwnerFromListAndCount() {
        new SearchHistoryFixture(em).seed();

        assertThat(adapter.findByOwner(OWNER, 0, 20))
                .noneMatch(record -> record.item().searchExecutionId() == 9707L);
        assertThat(adapter.countByOwner(OWNER)).isEqualTo(3);
        assertThat(adapter.countByOwner(OTHER)).isEqualTo(1);
        assertThat(adapter.countByOwner(9999L)).isZero();
    }

    @Test
    @Transactional
    @DisplayName("replay·running·failed 실행은 보존하되 이 화면에서 제외한다")
    void excludesReplayRunningFailed() {
        new SearchHistoryFixture(em).seed();

        assertThat(adapter.findByOwner(OWNER, 0, 20))
                .extracting(record -> record.item().searchExecutionId())
                .doesNotContain(9704L, 9705L, 9706L);
        assertThat(adapter.countByOwner(OWNER)).isEqualTo(3);
    }

    @Test
    @Transactional
    @DisplayName("created_at 이 같으면 search_execution_id 역순으로 결정적 순서를 낸다")
    void breaksCreatedAtTieByIdDesc() {
        new SearchHistoryFixture(em).seed();

        assertThat(adapter.findByOwner(OWNER, 0, 2))
                .extracting(record -> record.item().searchExecutionId())
                .containsExactly(9703L, 9702L);
        assertThat(adapter.findByOwner(OWNER, 1, 2))
                .extracting(record -> record.item().searchExecutionId())
                .containsExactly(9701L);
        assertThat(adapter.findByOwner(OWNER, 2, 2)).isEmpty();
    }

    @Test
    @Transactional
    @DisplayName("한 페이지의 결과를 실행마다 다시 묻지 않는다 — 실행 목록 1회 + 결과 1회")
    void loadsPageResultsWithoutNPlusOne() {
        new SearchHistoryFixture(em).seed();
        em.flush();
        var statistics = emf.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();

        List<SearchHistoryRecord> mine = adapter.findByOwner(OWNER, 0, 20);

        assertThat(mine).hasSize(3);
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(2);
    }
}
