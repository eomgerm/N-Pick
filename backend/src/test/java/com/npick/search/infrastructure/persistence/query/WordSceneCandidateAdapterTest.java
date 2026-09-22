package com.npick.search.infrastructure.persistence.query;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import com.npick.common.error.BusinessException;
import com.npick.search.application.query.candidate.SceneCandidateResult;
import com.npick.search.infrastructure.config.SceneCandidateProperties;
import com.npick.support.NpickPostgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 실제 pg_search 색인에 질의해 단어 검색을 검증한다 (S15P21A501-51 완료 조건).
 *
 * <p>BM25 는 pg_search 확장의 기능이라 인메모리 DB 로 대체할 수 없다. {@link NpickPostgres} 가 compose 와 같은 paradedb 이미지를 띄우고, 도커가 없으면 스킵이
 * 아니라 실패한다 — 검증하지 않은 것을 초록불로 위장하지 않기 위함이다.
 *
 * <p>공유 DB 대신 {@link NpickPostgres#freshDatabase} 로 전용 DB 를 받는다. {@code FlywayBaselineTest} 가 «빈 스키마일 때만» 실행되도록 자신을
 * 보호하는데, 같은 스키마를 쓰면 실행 순서에 따라 그 검사가 깨진다.
 *
 * <p>표본은 커밋하지 않고 테스트 트랜잭션에서 롤백한다. 어댑터가 커밋 전 데이터를 보게 하려고 같은 커넥션을 {@link SingleConnectionDataSource} 로 감싼다 — 색인이 정본과 같은
 * 트랜잭션에 있다는 것이 이 구성의 전제다 (FRD §11).
 */
class WordSceneCandidateAdapterTest {
    private static String url;

    private Connection connection;
    private SingleConnectionDataSource dataSource;

    @BeforeAll
    static void migrateOwnDatabase() {
        url = NpickPostgres.freshDatabase("npick_scene_candidate_test");
        NpickPostgres.migrate(url);
    }

    @BeforeEach
    void loadFixture() throws Exception {
        connection = DriverManager.getConnection(url, NpickPostgres.username(), NpickPostgres.password());
        connection.setAutoCommit(false);
        try (var statement = connection.createStatement()) {
            statement.execute("SET LOCAL search_path = npick, public");
            statement.execute(resource("search/scene-candidate-fixture.sql"));
            // 확장어 구·문서빈도 표본은 별도 파일이다. dense 후보 조회 테스트가 같은 기본 표본을
            // 쓰면서 활성 장면 수를 절대값으로 단언하므로 그쪽 숫자를 흔들지 않는다.
            statement.execute(resource("search/expanded-phrase-fixture.sql"));
        }
        dataSource = new SingleConnectionDataSource(connection, true);
    }

    @AfterEach
    void rollbackFixture() throws Exception {
        if (dataSource != null) dataSource.destroy();
        if (connection != null && !connection.isClosed()) {
            try {
                connection.rollback();
            } finally {
                connection.close();
            }
        }
    }

    /** 완료 조건: 질의어가 캡션에 매칭된다. 제설 은 표본에서 캡션에만 있다. */
    @Test
    void matchesCaptionOnlyToken() {
        assertThat(sceneIds(adapter(1, 1, 1, 10).findByWords(List.of("제설"), List.of())))
                .containsExactly(35L);
    }

    /** 완료 조건: 질의어가 화면 글자(OCR)에 매칭된다. 속보 는 표본에서 OCR 에만 있다. */
    @Test
    void matchesOcrOnlyToken() {
        var candidates = adapter(1, 1, 1, 10).findByWords(List.of("속보"), List.of());
        assertThat(sceneIds(candidates)).containsExactly(34L);
        var only = candidates.getFirst();
        assertThat(only.clipId()).isEqualTo(11L);
        assertThat(only.textScore()).isZero();
        assertThat(only.ocrScore()).isPositive();
        assertThat(only.score()).isEqualTo(only.ocrScore());
    }

    /** 원인 은 표본에서 대사에만 있다. */
    @Test
    void matchesTranscriptOnlyToken() {
        assertThat(sceneIds(adapter(1, 1, 1, 10).findByWords(List.of("원인"), List.of())))
                .containsExactly(31L);
    }

    /** 재처리로 폐기된 처리의 장면은 같은 토큰을 갖고 있어도 나오지 않는다 (F-14). */
    @Test
    void excludesScenesOfNonActivePipelineRun() {
        assertThat(sceneIds(adapter(1, 1, 1, 10).findByWords(List.of("화재"), List.of())))
                .containsExactlyInAnyOrder(30L, 31L, 34L)
                .doesNotContain(32L);
    }

    /** 캡션·OCR 양쪽에 걸린 장면은 한 행으로 합쳐지고 채널 점수가 둘 다 남는다. */
    @Test
    void mergesBothChannelsIntoOneCandidate() {
        var scene30 = adapter(1, 1, 1, 10).findByWords(List.of("화재", "단독"), List.of()).stream()
                .filter(candidate -> candidate.sceneId() == 30L)
                .toList();
        assertThat(scene30).hasSize(1);
        assertThat(scene30.getFirst().textScore()).isPositive();
        assertThat(scene30.getFirst().ocrScore()).isPositive();
    }

    /** 가중치 0 은 그 필드를 검색 대상에서 뺀다 — 설정으로 대상 필드를 정한다는 요구의 실동작. */
    @Test
    void zeroWeightRemovesFieldFromSearch() {
        assertThat(sceneIds(adapter(1, 1, 0, 10).findByWords(List.of("속보"), List.of())))
                .as("OCR 가중치 0 이면 화면 글자로만 걸리는 장면은 후보가 아니다")
                .isEmpty();
        assertThat(sceneIds(adapter(1, 0, 1, 10).findByWords(List.of("원인"), List.of())))
                .as("대사 가중치 0 이면 대사로만 걸리는 장면은 후보가 아니다")
                .isEmpty();
        assertThat(sceneIds(adapter(0, 1, 1, 10).findByWords(List.of("제설"), List.of())))
                .as("캡션 가중치 0 이면 캡션으로만 걸리는 장면은 후보가 아니다")
                .isEmpty();
    }

    /** 가중치가 순위를 실제로 바꾼다. 대사에만 원인 이 있는 31 번이 대사 가중치를 올리면 앞으로 온다. */
    @Test
    void weightsChangeRanking() {
        var tokens = List.of("화재", "원인");
        assertThat(sceneIds(adapter(10, 1, 1, 10).findByWords(tokens, List.of()))
                        .getFirst())
                .as("캡션 가중치가 크면 캡션에 화재 가 있는 30 번이 위")
                .isEqualTo(30L);
        assertThat(sceneIds(adapter(1, 10, 1, 10).findByWords(tokens, List.of()))
                        .getFirst())
                .as("대사 가중치가 크면 대사에 두 토큰이 다 있는 31 번이 위")
                .isEqualTo(31L);
    }

    /** 후보 pool 크기는 설정이 정한다. */
    @Test
    void limitsCandidatePoolToConfiguredSize() {
        assertThat(adapter(1, 1, 1, 10).findByWords(List.of("화재"), List.of())).hasSize(3);
        assertThat(adapter(1, 1, 1, 1).findByWords(List.of("화재"), List.of())).hasSize(1);
    }

    /** 내용어가 없으면 DB 를 부르지 않는다. 커넥션을 닫아 두면 질의가 일어났는지 알 수 있다. */
    @Test
    void doesNotQueryWhenNoUsableToken() throws Exception {
        var adapter = adapter(1, 1, 1, 10);
        connection.rollback();
        connection.close();
        assertThat(adapter.findByWords(List.of(), List.of())).isEmpty();
        assertThat(adapter.findByWords(List.of("  ", ""), List.of())).isEmpty();
    }

    /** 토큰에 공백이 있으면 DB 에서 두 토큰으로 쪼개져 검색어가 조용히 달라진다. 우리 토크나이저가 만든 값이므로 5xx 로 거부한다. */
    @Test
    void rejectsTokenContainingWhitespace() {
        assertThatThrownBy(() -> adapter(1, 1, 1, 10).findByWords(List.of("공장 화재"), List.of()))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        failure -> assertThat(failure.errorCode().code()).isEqualTo("SRCH_500_001"));
    }

    /** 논리 삭제된 클립의 장면은 색인에 남아 있어도 후보가 아니다 (FRD §6.1 "active_pipeline_run_id 와 논리 삭제 여부"). */
    @Test
    void excludesScenesOfSoftDeletedClip() {
        assertThat(sceneIds(adapter(1, 1, 1, 10).findByWords(List.of("화재"), List.of())))
                .doesNotContain(36L);
        assertThat(sceneIds(adapter(1, 1, 1, 10).findByWords(List.of("제설"), List.of())))
                .as("삭제된 클립만 갖고 있던 토큰이면 결과가 비어야 한다")
                .containsExactly(35L);
    }

    /** 화면 글자는 장면당 max 로 모은다. 합으로 모으면 키프레임이 많은 장면이 내용과 무관하게 이긴다. */
    @Test
    void aggregatesOcrScoreByMaxNotSum() {
        // 장면 34 는 키프레임 40·41 이 둘 다 '화재' 에 걸린다. 합이면 한 건짜리의 두 배가 된다.
        var twoHits = onlyCandidate(adapter(0, 0, 1, 10).findByWords(List.of("화재"), List.of()), 34L);
        var oneHit = onlyCandidate(adapter(0, 0, 1, 10).findByWords(List.of("속보"), List.of()), 34L);

        assertThat(twoHits.ocrScore()).as("같은 토큰이 키프레임 둘에 걸려도 최대값 하나만 쓴다").isEqualTo(twoHits.score());
        assertThat(twoHits.ocrScore()).isLessThan(oneHit.ocrScore() * 2);
    }

    /** 가중치 0 인 채널의 점수는 결과에 남지 않는다 — explain·재순위가 끈 채널을 되살리면 안 된다. */
    @Test
    void doesNotReportScoreOfDisabledChannel() {
        // 장면 30 은 캡션('화재')과 화면 글자('단독') 양쪽에 걸린다.
        var candidate = onlyCandidate(adapter(1, 1, 0, 10).findByWords(List.of("화재", "단독"), List.of()), 30L);

        assertThat(candidate.textScore()).isPositive();
        assertThat(candidate.ocrScore()).isZero();
        assertThat(candidate.score()).isEqualTo(candidate.textScore());
    }

    /** null 은 빈 목록과 다르다. 호출부 배선 실수를 "결과 없음" 으로 위장하지 않는다. */
    @Test
    void rejectsNullTokenList() {
        assertThatThrownBy(() -> adapter(1, 1, 1, 10).findByWords(null, List.of()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> adapter(1, 1, 1, 10).findByWords(List.of("화재"), null))
                .isInstanceOf(NullPointerException.class);
    }

    /**
     * 다어절 확장어는 구 단위로 건다 (S15P21A501-302).
     *
     * <p>평탄화하면 「중국 음식」이 {@code 중국} OR {@code 음식} 이 되어 짜장면 검색에 중국 경제 뉴스가 올라온다. 운영 실측 19건이었다.
     */
    @Test
    void multiTokenExpandedPhraseDoesNotMatchOnOneTokenAlone() {
        var candidates = adapter(1, 1, 1, 10).findByWords(List.of("짜장면"), List.of(List.of("중국", "음식")));

        assertThat(sceneIds(candidates))
                .as("'중국' 만 있는 장면과 '음식' 만 있는 장면은 후보가 아니다")
                .doesNotContain(60L, 62L);
    }

    /** 구의 토큰을 모두 가진 장면은 후보가 된다 — 구 단위 AND 가 확장어를 통째로 죽이는 것이 아니다. */
    @Test
    void multiTokenExpandedPhraseMatchesSceneHavingEveryToken() {
        var candidates = adapter(1, 1, 1, 10).findByWords(List.of("짜장면"), List.of(List.of("중국", "음식")));

        assertThat(sceneIds(candidates)).containsExactly(61L);
    }

    /** 단일 토큰 확장어(화재→불)는 종전대로 걸린다. 구 단위 AND 는 토큰이 하나면 그 토큰 하나다. */
    @Test
    void singleTokenExpandedPhraseStillMatches() {
        assertThat(sceneIds(adapter(1, 1, 1, 10).findByWords(List.of("짜장면"), List.of(List.of("불")))))
                .containsExactly(63L);
    }

    /** 구가 둘 이상이면 구 사이는 OR 다. 확장어 항목끼리는 서로 다른 동의어 후보이므로 함께 요구하면 안 된다. */
    @Test
    void phrasesAreOrredWithEachOther() {
        var candidates = adapter(1, 1, 1, 10).findByWords(List.of("짜장면"), List.of(List.of("중국", "음식"), List.of("불")));

        assertThat(sceneIds(candidates)).containsExactlyInAnyOrder(61L, 63L);
    }

    /** 문서빈도가 임계를 넘는 확장어 토큰은 후보 조회에서 빠진다. 표본의 '장면' 은 활성 장면 20개 중 10개(0.50)다. */
    @Test
    void dropsExpandedTokenAboveDocumentFrequencyCut() {
        assertThat(sceneIds(adapter(1, 1, 1, 10, 0.25).findByWords(List.of("짜장면"), List.of(List.of("장면")))))
                .as("변별력이 사실상 없는 토큰은 후보를 끌어오지 못한다")
                .isEmpty();
        assertThat(sceneIds(adapter(1, 1, 1, 10, 0.50).findByWords(List.of("짜장면"), List.of(List.of("장면")))))
                .as("임계와 같은 것은 «넘는» 것이 아니다")
                .isNotEmpty();
    }

    /**
     * 임계 이하의 토큰은 남는다 — 정상 동의어(우천→비)가 죽지 않아야 한다.
     *
     * <p>경계 양쪽을 둘 다 못 박는다. 코퍼스가 자라 '비' 가 임계를 넘는 날 이 테스트가 먼저 깨져야, 우천→비 가 조용히 죽는 사고를 배포 뒤에 발견하지 않는다.
     */
    @Test
    void keepsExpandedTokenAtOrBelowDocumentFrequencyCut() {
        // 표본의 '비' 는 활성 장면 20개 중 2개(0.10)다.
        assertThat(sceneIds(adapter(1, 1, 1, 10, 0.10).findByWords(List.of("짜장면"), List.of(List.of("비")))))
                .containsExactlyInAnyOrder(64L, 65L);
        assertThat(sceneIds(adapter(1, 1, 1, 10, 0.09).findByWords(List.of("짜장면"), List.of(List.of("비")))))
                .as("임계 바로 위면 같은 토큰도 빠진다")
                .isEmpty();
    }

    /** 컷은 토큰 단위다. 구에서 흔한 토큰만 빠지고 남은 토큰으로 계속 건다 — 잡토큰 하나가 정상 확장어를 통째로 죽이지 않는다. */
    @Test
    void cutsOnlyTheCommonTokenOutOfAPhrase() {
        var candidates = adapter(1, 1, 1, 10, 0.25).findByWords(List.of("짜장면"), List.of(List.of("장면", "중국")));

        assertThat(sceneIds(candidates)).containsExactlyInAnyOrder(60L, 61L);
    }

    /** 확장어가 전부 잘려 빈 목록이 돼도 원 질의 토큰 검색은 그대로 돈다 (S15P21A501-48 계약 9). */
    @Test
    void keepsSearchingOriginalTokensWhenEveryExpandedTokenIsCut() {
        var candidates = adapter(1, 1, 1, 10, 0.01).findByWords(List.of("화재"), List.of(List.of("장면")));

        assertThat(sceneIds(candidates)).containsExactlyInAnyOrder(30L, 31L, 34L);
    }

    private static SceneCandidateResult onlyCandidate(List<SceneCandidateResult> candidates, long sceneId) {
        var matched = candidates.stream()
                .filter(candidate -> candidate.sceneId() == sceneId)
                .toList();
        assertThat(matched).as("장면 %d 가 후보에 있어야 한다", sceneId).hasSize(1);
        return matched.getFirst();
    }

    private WordSceneCandidateAdapter adapter(double caption, double transcript, double ocr, int poolSize) {
        return adapter(caption, transcript, ocr, poolSize, 1.0);
    }

    /** {@code maxDf} 1.0 은 어떤 문서빈도도 넘지 못하는 값이라 확장어를 하나도 버리지 않는다. */
    private WordSceneCandidateAdapter adapter(
            double caption, double transcript, double ocr, int poolSize, double maxDf) {
        return new WordSceneCandidateAdapter(
                new NamedParameterJdbcTemplate(dataSource),
                new SceneCandidateProperties("test-candidate", caption, transcript, ocr, 0.3, maxDf, poolSize));
    }

    private static List<Long> sceneIds(List<SceneCandidateResult> candidates) {
        return candidates.stream().map(SceneCandidateResult::sceneId).toList();
    }

    private static String resource(String path) throws Exception {
        try (var stream = WordSceneCandidateAdapterTest.class.getClassLoader().getResourceAsStream(path)) {
            if (stream == null) throw new IllegalArgumentException("Missing resource: " + path);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
