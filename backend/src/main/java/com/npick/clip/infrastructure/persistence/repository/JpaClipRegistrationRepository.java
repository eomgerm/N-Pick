package com.npick.clip.infrastructure.persistence.repository;

import jakarta.persistence.EntityManager;

import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

import com.npick.clip.domain.model.InitialClipRegistration;
import com.npick.clip.domain.repository.ClipRegistrationRepository;
import com.npick.clip.infrastructure.persistence.mapper.ClipRegistrationPersistenceMapper;
import com.npick.common.persistence.TsidGenerator;

@Repository
public class JpaClipRegistrationRepository implements ClipRegistrationRepository {
    private final EntityManager entityManager;
    private final JsonMapper jsonMapper;

    public JpaClipRegistrationRepository(EntityManager entityManager, JsonMapper jsonMapper) {
        this.entityManager = entityManager;
        this.jsonMapper = jsonMapper;
    }

    @Override
    public void save(InitialClipRegistration registration) {
        entityManager.persist(ClipRegistrationPersistenceMapper.clip(registration));
        // ID 값으로 FK를 저장하므로 부모 INSERT를 먼저 보장한다.
        entityManager.flush();
        entityManager.persist(ClipRegistrationPersistenceMapper.run(
                registration, jsonMapper.writeValueAsString(registration.stageStates())));
        for (var date : registration.dateEvidence()) {
            // 동시 등록도 UNIQUE 위반으로 트랜잭션이 깨지지 않게 공유 태그를 확보한다.
            entityManager
                    .createNativeQuery("""
                INSERT INTO npick.tag (tag_id, tag_type, match_value, name)
                VALUES (:id, :type, :value, :value)
                ON CONFLICT (tag_type, match_value) DO NOTHING
                """)
                    .setParameter("id", TsidGenerator.generate())
                    .setParameter("type", date.tagType())
                    .setParameter("value", date.date().toString())
                    .executeUpdate();
            long tagId = ((Number) entityManager
                            .createNativeQuery("""
                SELECT tag_id FROM npick.tag WHERE tag_type = :type AND match_value = :value
                """)
                            .setParameter("type", date.tagType())
                            .setParameter("value", date.date().toString())
                            .getSingleResult())
                    .longValue();
            long taggingId = TsidGenerator.generate();
            entityManager.persist(ClipRegistrationPersistenceMapper.tagging(registration, date, taggingId, tagId));
            entityManager.flush();
            entityManager.persist(ClipRegistrationPersistenceMapper.evidence(
                    registration, date, TsidGenerator.generate(), taggingId));
        }
        entityManager.flush();
    }
}
