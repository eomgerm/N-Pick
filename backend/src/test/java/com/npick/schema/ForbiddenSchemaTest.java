package com.npick.schema;

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

/**
 * FRD 가 「없어야 한다」고 못박은 스키마 객체가 실제로 없는지 검증한다 (S15P21A501-146, 데이터 구조 부정형 AC).
 *
 * <p>부정형 조건은 잊히기 쉬워, 삭제·범위밖 확정된 객체가 실수로 되살아나면 CI 가 잡도록 명시 검사로 고정한다. FlywayBaselineTest 가 테이블·컬럼·FK 를 정확한 수(13·134·23)로
 * 고정하지만, 여기서는 개별 이름을 FRD 근거와 함께 명시해 어떤 객체가 왜 금지인지 문서화하고 이름 바꿔치기(하나 추가·하나 삭제로 총수 유지)도 막는다.
 *
 * <p>범위: 스키마로 강제 가능한 항목(테이블·컬럼)만. {@code crowd_density} 태그 유형은 {@code tag_type} 이 자유 {@code varchar} 라 DB 로 강제되지 않아(코멘트
 * 규약) 여기서 다루지 않는다. 동작·UI 부정형 AC 는 검색 실행·FE 선행이 필요해 별도다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class ForbiddenSchemaTest {

    @Autowired
    private EntityManager em;

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry properties) {
        NpickPostgres.datasource(properties);
    }

    @Test
    @DisplayName("FRD 범위 밖으로 확정된 테이블은 존재하지 않는다 (§1.2·§6.4·§9.1·§11, F-13)")
    void forbiddenTablesDoNotExist() {
        @SuppressWarnings("unchecked")
        List<String> present = em.createNativeQuery("""
                        SELECT table_name FROM information_schema.tables
                        WHERE table_schema = 'npick'
                          AND table_name IN ('index_generation', 'index_outbox', 'external_call_audit',
                                             'external_provider_profile', 'deployment_external_policy')
                        """).getResultList();

        assertThat(present).isEmpty();
    }

    @Test
    @DisplayName("S15P21A501-153 에서 제거한 컬럼은 되살아나지 않았다 (search_rule.parsed_query_json·applied_rule_id)")
    void removedColumnsDoNotExist() {
        @SuppressWarnings("unchecked")
        List<Object[]> present = em.createNativeQuery("""
                        SELECT table_name, column_name FROM information_schema.columns
                        WHERE table_schema = 'npick'
                          AND ( (table_name = 'search_rule' AND column_name = 'parsed_query_json')
                             OR (column_name = 'applied_rule_id') )
                        """).getResultList();

        assertThat(present).isEmpty();
    }

    @Test
    @DisplayName("검사가 유효함을 보증한다 — search_execution.parsed_query_json 은 정상 존재한다")
    void guardIsMeaningfulBecauseTheQuerySeesRealColumns() {
        @SuppressWarnings("unchecked")
        List<String> present = em.createNativeQuery("""
                        SELECT column_name FROM information_schema.columns
                        WHERE table_schema = 'npick' AND table_name = 'search_execution'
                          AND column_name = 'parsed_query_json'
                        """).getResultList();

        assertThat(present).containsExactly("parsed_query_json");
    }
}
