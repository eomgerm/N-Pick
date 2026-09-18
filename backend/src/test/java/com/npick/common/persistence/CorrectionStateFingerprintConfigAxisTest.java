package com.npick.common.persistence;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CorrectionStateFingerprintConfigAxisTest {

    @Test
    void configAxisReflectsSupplier() {
        EntityManager em = mock(EntityManager.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        when(em.createNativeQuery(org.mockito.ArgumentMatchers.anyString()).getResultList())
                .thenReturn(java.util.List.of());
        SearchConfigVersionSupplier v1 = () -> "search-config/v1:aaaa";
        SearchConfigVersionSupplier v2 = () -> "search-config/v1:bbbb";
        assertThat(new CorrectionStateFingerprint(em, v1).compute(1L))
                .isNotEqualTo(new CorrectionStateFingerprint(em, v2).compute(1L));
        assertThat(new CorrectionStateFingerprint(em, v1).compute(1L)).contains("config=search-config/v1:aaaa");
    }
}
