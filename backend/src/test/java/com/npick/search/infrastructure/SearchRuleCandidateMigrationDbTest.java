package com.npick.search.infrastructure;

import java.util.List;
import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.npick.support.NpickPostgres;

import static org.assertj.core.api.Assertions.assertThat;

// 실 PostgreSQL(paradedb) 대상. patch_parse 후보(-81)가 쓸 컬럼·제약이 마이그레이션으로 붙는지 검증한다.
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class SearchRuleCandidateMigrationDbTest {

    @Autowired
    private EntityManager em;

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry properties) {
        NpickPostgres.datasource(properties);
    }

    @Test
    @DisplayName("search_rule 에 후보용 replaces_rule_id·request_key 컬럼이 있다")
    void addsCandidateColumns() {
        @SuppressWarnings("unchecked")
        List<String> columns = em.createNativeQuery("""
                        SELECT column_name FROM information_schema.columns
                        WHERE table_schema = 'npick' AND table_name = 'search_rule'
                          AND column_name IN ('replaces_rule_id', 'request_key')
                        """).getResultList();

        assertThat(columns).containsExactlyInAnyOrder("replaces_rule_id", "request_key");
    }

    @Test
    @DisplayName("같은 신고·요청키의 후보 중복을 막는 유니크 제약이 있다")
    void hasFeedbackRequestKeyUniqueConstraint() {
        Number count = (Number) em.createNativeQuery(
                        "SELECT count(*) FROM pg_constraint WHERE conname = 'uq_search_rule_feedback_request'")
                .getSingleResult();

        assertThat(count.intValue()).isEqualTo(1);
    }

    @Test
    @DisplayName("교체 대상은 patch_parse 규칙에만 지정할 수 있는 CHECK 제약이 있다")
    void hasReplacesShapeCheckConstraint() {
        Number count = (Number) em.createNativeQuery(
                        "SELECT count(*) FROM pg_constraint WHERE conname = 'ck_search_rule_replaces_shape'")
                .getSingleResult();

        assertThat(count.intValue()).isEqualTo(1);
    }
}
