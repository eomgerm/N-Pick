package com.npick.search.application.query.structured;

import java.nio.charset.StandardCharsets;
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

import com.npick.search.domain.model.KeywordTagSettings;
import com.npick.search.domain.model.QueryResolution;
import com.npick.search.domain.model.StructuredAxis;
import com.npick.search.domain.model.StructuredScoreSettings;
import com.npick.support.NpickPostgres;
import com.npick.tag.application.query.SceneTagResolutionService;
import com.npick.tag.domain.model.EffectiveTag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** 실제 Flyway·PostgreSQL·#161 판정기로 키워드 태그 편입을 확인한다 (S15P21A501-321). */
@SpringJUnitConfig(KeywordTagScoringIntegrationTest.Wiring.class)
@Transactional(isolation = Isolation.REPEATABLE_READ)
class KeywordTagScoringIntegrationTest {
    @Autowired
    ScoreStructuredScenesUseCase scoring;

    @Autowired
    NamedParameterJdbcTemplate jdbc;

    @BeforeEach
    void fixture() throws Exception {
        for (var path : List.of("tag/tag-resolution-fixture.sql", "tag/keyword-tag-fixture.sql")) {
            jdbc.getJdbcTemplate().execute(new ClassPathResource(path).getContentAsString(StandardCharsets.UTF_8));
        }
    }

    @Test
    void keywordOnlySceneIsAdmittedWithoutAnyText() {
        var result = scoring.score(query(List.of("전세/nng", "사기/nng"), List.of()));

        assertThat(result.scenes()).singleElement().satisfies(scene -> {
            assertThat(scene.sceneId()).isEqualTo(33);
            assertThat(scene.tagCandidate()).isTrue();
            assertThat(scene.inputCandidate()).isFalse();
            assertThat(scene.score()).isCloseTo(0.5 / 3, within(1e-12));
            assertThat(scene.keyword().matchedTags())
                    .singleElement()
                    .satisfies(tag -> assertThat(tag.name()).isEqualTo("전세 사기"));
        });
    }

    @Test
    void stoplistedKeywordNeverAdmits() {
        // Review Focus 2: 제외 목록 명사는 명사 연속을 끊는다 — 「건물 앞」 → 건물 만. 「앞」 태그 장면 35 는 들어오지 않는다.
        var result = scoring.score(query(List.of("건물/nng", "앞/nng"), List.of()));

        assertThat(result.scenes())
                .extracting(StructuredScoresResult.SceneScore::sceneId)
                .doesNotContain(35L);
        assertThat(result.scenes()).isEmpty();
    }

    @Test
    void keywordConditionsDoNotMatchOtherTagTypes() {
        // 장면 30 에는 인물 태그 홍길동이 있지만 keyword 조건은 keyword 태그만 본다 (고유 조건 = 타입 + 값, F-05).
        var result = scoring.score(query(List.of("홍길동/nnp"), List.of()));

        assertThat(result.scenes()).isEmpty();
    }

    @Test
    void keywordTagDoesNotSatisfyEntityAxisCondition() {
        // 반대 방향: 사건 조건 전세사기는 같은 값의 keyword 태그(장면 33)로 채워지지 않는다 (고유 조건 = 타입 + 값, F-05).
        var result = scoring.score(new ScoreStructuredScenesQuery(incident("전세 사기"), List.of(), List.of(), List.of()));

        assertThat(result.scenes())
                .extracting(StructuredScoresResult.SceneScore::sceneId)
                .doesNotContain(33L);
        assertThat(result.scenes()).isEmpty();
    }

    @Test
    void keywordAdmittedSceneGetsNoEntityAxisCreditFromKeywordTag() {
        // 키워드로 들어온 장면 33 이라도 사건 축 점수는 keyword 태그로 받지 않는다 — 가산점만 붙는다.
        var result = scoring.score(
                new ScoreStructuredScenesQuery(incident("전세 사기"), List.of(), List.of("전세/nng", "사기/nng"), List.of()));

        assertThat(result.scenes()).singleElement().satisfies(scene -> {
            assertThat(scene.sceneId()).isEqualTo(33);
            assertThat(scene.denominator()).isEqualTo(1);
            assertThat(scene.axes().getFirst().axis()).isEqualTo(StructuredAxis.EVENT);
            assertThat(scene.axes().getFirst().contribution()).isZero();
            assertThat(scene.score()).isCloseTo(0.5 / 3, within(1e-12));
        });
    }

    private static QueryResolution incident(String name) {
        return new QueryResolution(
                "query-resolver/v2",
                QueryResolution.Intent.SCENE_SEARCH,
                List.of(),
                List.of(new QueryResolution.IncidentName(name, QueryResolution.Origin.EXPLICIT_QUERY, null, 1)),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                1);
    }

    @Test
    void eventAxisScoreIsKeptAndKeywordBonusIsAdded() {
        // 사건 태그로 1.0 인 장면 30 에 키워드 포항이 더해진다. 가중평균이었다면 분모가 늘어 1.0 아래로 떨어진다.
        var event = new QueryResolution(
                "query-resolver/v2",
                QueryResolution.Intent.SCENE_SEARCH,
                List.of(),
                List.of(new QueryResolution.IncidentName("포항 지진", QueryResolution.Origin.EXPLICIT_QUERY, null, 1)),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                1);

        var result =
                scoring.score(new ScoreStructuredScenesQuery(event, List.of(), List.of("포항/nnp", "지진/nng"), List.of()));

        var thirty = result.scenes().stream()
                .filter(s -> s.sceneId() == 30)
                .findFirst()
                .orElseThrow();
        assertThat(thirty.denominator()).isEqualTo(1);
        assertThat(thirty.axes().getFirst().contribution()).isEqualTo(1);
        assertThat(thirty.score()).isCloseTo(1 + 0.5 / 3, within(1e-12));
    }

    @Test
    void expandedPhraseAdmitsWithoutScore() {
        var result = scoring.score(query(List.of(), List.of(List.of("전세/nng", "사기/nng"))));

        assertThat(result.scenes()).singleElement().satisfies(scene -> {
            assertThat(scene.sceneId()).isEqualTo(33);
            assertThat(scene.tagCandidate()).isTrue();
            assertThat(scene.score()).isZero();
        });
    }

    @Test
    void pendingReviewerKeywordCandidateCountsOnlyAfterFlip() {
        // 검증 A/B 는 후보를 같은 트랜잭션에서 confirmed=true 로 바꾸고 검색한 뒤 되돌린다 (F-12). 키워드도 그 흐름을 따라야 한다.
        jdbc.getJdbcTemplate().execute("""
                INSERT INTO tagging (tagging_id,clip_id,scene_id,tag_id,created_at) VALUES (123,10,31,20,now());
                INSERT INTO tag_evidence (evidence_id,tagging_id,source,confidence,verification_status,created_at,source_feedback_id,confirmed)
                VALUES (223,123,'reviewer_feedback',NULL,'verified',now() + interval '1 second',500,false);
                """);
        assertThat(scoring.score(query(List.of("전세/nng", "사기/nng"), List.of())).scenes())
                .extracting(StructuredScoresResult.SceneScore::sceneId)
                .containsExactly(33L);

        jdbc.getJdbcTemplate().execute("UPDATE tag_evidence SET confirmed = true WHERE evidence_id = 223");

        var flipped = scoring.score(query(List.of("전세/nng", "사기/nng"), List.of()));
        assertThat(flipped.scenes())
                .extracting(StructuredScoresResult.SceneScore::sceneId)
                .containsExactly(31L, 33L);
        assertThat(flipped.scenes()
                        .getFirst()
                        .keyword()
                        .matchedTags()
                        .getFirst()
                        .verification())
                .isEqualTo(EffectiveTag.Verification.REVIEWER_VERIFIED);
    }

    private static ScoreStructuredScenesQuery query(List<String> tokens, List<List<String>> phrases) {
        return new ScoreStructuredScenesQuery(QueryResolution.withoutAiInterpretation(), List.of(), tokens, phrases);
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
            String url = NpickPostgres.freshDatabase("keyword_tag_score_test");
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
            return new StructuredScoreSettings(
                    StructuredScoreSettings.WeightStatus.EXPERIMENTAL,
                    weights,
                    new KeywordTagSettings(0.5, 12, List.of("앞")));
        }
    }
}
