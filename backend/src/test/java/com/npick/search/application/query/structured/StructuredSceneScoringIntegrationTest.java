package com.npick.search.application.query.structured;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.List;
import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.npick.search.domain.model.QueryResolution;
import com.npick.search.domain.model.StructuredAxis;
import com.npick.search.domain.model.StructuredScoreSettings;
import com.npick.support.NpickPostgres;
import com.npick.tag.application.query.SceneTagResolutionService;
import com.npick.tag.domain.model.EffectiveTag;

import static org.assertj.core.api.Assertions.assertThat;

/** 실제 Flyway·PostgreSQL·#161 판정기·트랜잭션 프록시 연결. 리졸버/#47/#48 실행을 대신하지 않는다. */
@SpringJUnitConfig(StructuredSceneScoringIntegrationTest.Wiring.class)
@Transactional(isolation = Isolation.REPEATABLE_READ)
class StructuredSceneScoringIntegrationTest {
    @Autowired
    ScoreStructuredScenesUseCase scoring;

    @Autowired
    NamedParameterJdbcTemplate jdbc;

    @BeforeEach
    void fixture() throws Exception {
        jdbc.getJdbcTemplate()
                .execute(new ClassPathResource("tag/tag-resolution-fixture.sql")
                        .getContentAsString(StandardCharsets.UTF_8));
        jdbc.getJdbcTemplate().execute("""
                INSERT INTO scene (scene_id,clip_id,pipeline_run_id,start_time_ms,end_time_ms,shot_type,created_at,updated_at)
                VALUES (35,11,22,1000,2000,'b_roll',now(),now());
                """);
    }

    @Test
    void correctedTagOnlyCandidatesAreScoredWithInheritedEvidenceAndInactiveScenesAreExcluded() {
        // 캡션·대사·OCR가 없는 장면 30은 사건 태그 경로로만 들어온다.
        var result = scoring.score(new ScoreStructuredScenesQuery(eventQuery(), List.of(31L, 32L, 34L, 35L)));
        assertThat(result.scenes())
                .extracting(StructuredScoresResult.SceneScore::sceneId)
                .containsExactly(30L, 31L, 35L);
        assertThat(result.ineligibleSceneIds()).containsExactly(32L, 34L);
        var thirty = result.scenes().getFirst();
        assertThat(thirty.inputCandidate()).isFalse();
        assertThat(thirty.tagCandidate()).isTrue();
        assertThat(thirty.score()).isEqualTo(1);
        var evidence =
                thirty.axes().getFirst().conditions().getFirst().matchedTags().getFirst();
        assertThat(evidence.scope()).isEqualTo(EffectiveTag.Scope.CLIP);
        assertThat(evidence.verification()).isEqualTo(EffectiveTag.Verification.UNVERIFIED);
        assertThat(evidence.source()).isEqualTo("vlm");
        assertThat(result.scenes().get(1).score()).as("장면 사람 반려가 클립 근거보다 우선").isZero();
        assertThat(result.scenes().get(2).score()).as("해당 태그가 없어도 적격 장면은 유지").isZero();
        assertThat(result.scenes().get(2).axes().getFirst().missing()).isTrue();
    }

    @Test
    void latestSceneJudgmentAndWithdrawalReachScoresInsideTheSameTransaction() {
        jdbc.getJdbcTemplate().execute("""
                INSERT INTO tag_evidence (evidence_id,tagging_id,source,verification_status,created_at,source_feedback_id)
                VALUES (210,101,'reviewer_feedback','verified',now() + interval '1 second',500);
                """);
        var approved = scoring.score(new ScoreStructuredScenesQuery(eventQuery(), List.of()));
        assertThat(approved.scenes())
                .extracting(StructuredScoresResult.SceneScore::sceneId)
                .containsExactly(30L, 31L);
        var evidence = approved.scenes().get(1).axes().getFirst().observedTags().getFirst();
        assertThat(evidence.verification()).isEqualTo(EffectiveTag.Verification.REVIEWER_VERIFIED);
        assertThat(evidence.scope()).isEqualTo(EffectiveTag.Scope.SCENE);

        jdbc.getJdbcTemplate().execute("""
                INSERT INTO tag_evidence (evidence_id,tagging_id,source,verification_status,created_at,source_feedback_id)
                VALUES (211,101,'reviewer_feedback','withdrawn',now() + interval '2 second',500);
                """);
        var withdrawn = scoring.score(new ScoreStructuredScenesQuery(eventQuery(), List.of()));
        var inherited =
                withdrawn.scenes().get(1).axes().getFirst().observedTags().getFirst();
        assertThat(inherited.scope()).isEqualTo(EffectiveTag.Scope.CLIP);
        assertThat(inherited.verification()).isEqualTo(EffectiveTag.Verification.UNVERIFIED);
        assertThat(withdrawn.scenes().get(1).score()).isEqualTo(1);
    }

    @Test
    void clipReviewerCorrectionOverridesGeneralEvidenceAndIsUsedForCandidatesAndExplanation() {
        jdbc.getJdbcTemplate().execute("""
                INSERT INTO tag_evidence (evidence_id,tagging_id,source,verification_status,created_at,source_feedback_id)
                VALUES (210,100,'reviewer_feedback','verified',now() + interval '1 second',500);
                """);
        var result = scoring.score(new ScoreStructuredScenesQuery(eventQuery(), List.of()));
        assertThat(result.scenes()).singleElement().satisfies(scene -> {
            assertThat(scene.sceneId()).isEqualTo(30);
            assertThat(scene.score()).isEqualTo(1);
            var evidence = scene.axes()
                    .getFirst()
                    .conditions()
                    .getFirst()
                    .matchedTags()
                    .getFirst();
            assertThat(evidence.verification()).isEqualTo(EffectiveTag.Verification.REVIEWER_VERIFIED);
            assertThat(evidence.scope()).isEqualTo(EffectiveTag.Scope.CLIP);
        });
    }

    @Test
    void datesDoNotPullNewCandidatesOrCrossFieldsAndAbsentTagsAreNotEligibilityEvidence() {
        var dates = List.of(
                new QueryResolution.DateWindow(
                        QueryResolution.DateField.BROADCAST_DATE,
                        LocalDate.parse("2026-03-01"),
                        LocalDate.parse("2026-04-01"),
                        QueryResolution.Origin.EXPLICIT_FILTER,
                        null,
                        1),
                new QueryResolution.DateWindow(
                        QueryResolution.DateField.FILMED_DATE,
                        LocalDate.parse("2026-03-01"),
                        LocalDate.parse("2026-04-01"),
                        QueryResolution.Origin.EXPLICIT_QUERY,
                        null,
                        1));
        var query = new QueryResolution(
                "query-resolver/v2",
                QueryResolution.Intent.SCENE_SEARCH,
                dates,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                1);
        var result = scoring.score(new ScoreStructuredScenesQuery(query, List.of(32L, 33L, 34L)));
        assertThat(result.scenes()).singleElement().satisfies(scene -> {
            assertThat(scene.sceneId()).isEqualTo(33);
            assertThat(scene.score()).isEqualTo(.5);
            assertThat(scene.denominator()).isEqualTo(2);
        });
        assertThat(result.finalResolution()).isEqualTo(query);
        assertThat(scoring.score(new ScoreStructuredScenesQuery(query, List.of()))
                        .scenes())
                .isEmpty();
    }

    @Test
    void taglessEligibleSceneSurvivesButDeletedAndInactiveTaglessScenesDoNot() {
        jdbc.getJdbcTemplate().execute("""
                INSERT INTO clip (clip_id,source_type,storage_key,content_hash,transcript_source,registered_by_id,created_at,updated_at)
                VALUES (13,'archive','test/13',repeat('e',64),'none',1,now(),now());
                INSERT INTO pipeline_run (pipeline_run_id,clip_id,processing_no,pipeline_version,status,stage_states_json,created_at,updated_at)
                VALUES (24,13,1,'test-v1','succeeded','{}',now(),now()), (25,13,2,'test-v1','succeeded','{}',now(),now());
                UPDATE clip SET active_pipeline_run_id=25 WHERE clip_id=13;
                INSERT INTO scene (scene_id,clip_id,pipeline_run_id,start_time_ms,end_time_ms,shot_type,created_at,updated_at)
                VALUES (36,13,25,0,1000,'unknown',now(),now()), (37,13,24,0,1000,'unknown',now(),now());
                """);
        var result = scoring.score(new ScoreStructuredScenesQuery(eventQuery(), List.of(36L, 37L)));
        assertThat(result.scenes())
                .extracting(StructuredScoresResult.SceneScore::sceneId)
                .contains(36L)
                .doesNotContain(37L);
        jdbc.getJdbcTemplate().execute("UPDATE clip SET deleted_at=now() WHERE clip_id=13");
        var deleted = scoring.score(new ScoreStructuredScenesQuery(eventQuery(), List.of(36L, 37L)));
        assertThat(deleted.ineligibleSceneIds()).containsExactly(36L, 37L);
    }

    private static QueryResolution eventQuery() {
        return new QueryResolution(
                "query-resolver/v2",
                QueryResolution.Intent.SCENE_SEARCH,
                List.of(),
                List.of(new QueryResolution.IncidentName("포항 지진", QueryResolution.Origin.EXPLICIT_QUERY, null, 1)),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                1);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    @Import({StructuredSceneScoringService.class, SceneTagResolutionService.class})
    @ComponentScan(
            basePackages = {
                "com.npick.search.infrastructure.persistence.query",
                "com.npick.tag.infrastructure.persistence.query"
            },
            useDefaultFilters = false,
            includeFilters =
                    @ComponentScan.Filter(
                            type = FilterType.REGEX,
                            pattern = {".*EligibleScenesQueryAdapter", ".*TagJudgmentQueryAdapter"}))
    static class Wiring {
        @Bean
        DataSource dataSource() {
            String url = NpickPostgres.freshDatabase("structured_score_test");
            NpickPostgres.migrate(url);
            return new DriverManagerDataSource(
                    url + "?currentSchema=npick,public", NpickPostgres.username(), NpickPostgres.password());
        }

        @Bean
        NamedParameterJdbcTemplate jdbc(DataSource dataSource) {
            return new NamedParameterJdbcTemplate(dataSource);
        }

        @Bean
        PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }

        @Bean
        StructuredScoreSettings settings() {
            var weights = new EnumMap<StructuredAxis, Double>(StructuredAxis.class);
            for (var axis : StructuredAxis.values()) weights.put(axis, 1.0);
            return new StructuredScoreSettings(StructuredScoreSettings.WeightStatus.EXPERIMENTAL, weights);
        }
    }
}
