package com.npick.tag.infrastructure;

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

// 실 PostgreSQL(paradedb) 대상. 태그 교정 후보(-160)가 쓸 tag_evidence.confirmed 컬럼이 마이그레이션으로 붙는지 검증한다.
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class TagEvidenceConfirmedMigrationDbTest {

    @Autowired
    private EntityManager em;

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry properties) {
        NpickPostgres.datasource(properties);
    }

    @Test
    @DisplayName("tag_evidence 에 후보 대기용 confirmed 컬럼이 있고 기본값은 true 다")
    void addsConfirmedColumnDefaultTrue() {
        @SuppressWarnings("unchecked")
        List<Object[]> cols = em.createNativeQuery("""
                        SELECT column_name, column_default, is_nullable FROM information_schema.columns
                        WHERE table_schema = 'npick' AND table_name = 'tag_evidence' AND column_name = 'confirmed'
                        """).getResultList();

        assertThat(cols).hasSize(1);
        assertThat((String) cols.get(0)[1]).contains("true");
        assertThat((String) cols.get(0)[2]).isEqualTo("NO");
    }
}
