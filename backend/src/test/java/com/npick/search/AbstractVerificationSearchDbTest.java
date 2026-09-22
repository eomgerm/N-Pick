package com.npick.search;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.npick.search.application.port.QueryNormalization;
import com.npick.search.application.port.QueryResolutionResult;
import com.npick.search.application.port.QueryResolverPort;
import com.npick.search.domain.model.QueryResolution;
import com.npick.support.NpickPostgres;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

/**
 * 후보 검증 재검색(S15P21A501-83) DB 테스트가 공유하는 기반.
 *
 * <p>{@code VerificationSearchService.verify()} 는 일반 검색과 <b>같은 코드</b>({@code interpret}+{@code rank})를 태운다(FRD §11) — 이
 * 클래스는 그 계약을 지키면서도 질의 리졸버라는 <b>외부 AI 경계</b>만 대체한다. 리졸버는 {@code application-test.yml} 이 이미 통째로 죽여 놓은 대상이다(테스트가 실제 리졸버에
 * 닿지 않게 base-url 을 존재하지 않는 주소로 고정) — 여기서 {@link QueryResolverPort} 를 스텁하는 것은 그 경계를 다시 한 번 명시적으로 대체할 뿐,
 * {@code interpret()} 안의 {@code AnchorVerifier}·{@code ParseRulePolicy}·{@code ExplicitFilterPolicy}나 {@code rank()}의
 * 후보 조회·구조화 점수·순위 결합·guard·장면 제외는 전부 실제 코드 그대로 돈다.
 *
 * <p>캔 응답은 <b>정상 해석</b> 모양이다({@code resolution != null}, {@code intent=SCENE_SEARCH}) — 미해석/실패 모양을 주면
 * {@code interpret()}이 조기에 fallback 경로로 빠져 검증이 실제로 검사하려는 「flip 이 rank() 재계산에 반영되는가」를 검증하지 못한다. 검색어 토큰은 원문을 공백으로 쪼갠 것을
 * 그대로 쓴다 — {@code TestGraph.insertSearchableReportedScene} 이 심어 둔 {@code caption_tokens}가 같은 원문('원본질의')을 포함하므로 BM25 단어
 * 채널이 실제로 후보를 찾을 수 있다.
 */
@SpringBootTest
abstract class AbstractVerificationSearchDbTest {

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        NpickPostgres.datasource(registry);
    }

    @MockitoBean
    protected QueryResolverPort resolver;

    @Autowired
    protected JdbcTemplate jdbc;

    @BeforeEach
    void stubResolverToResolveNormally() {
        given(resolver.resolve(any())).willAnswer(invocation -> resolvedResult(invocation.getArgument(0)));
    }

    protected QueryResolutionResult resolvedResult(String rawQuery) {
        QueryNormalization normalization = new QueryNormalization(rawQuery, tokens(rawQuery), "normalizer/v1");
        QueryResolution resolution = new QueryResolution(
                "query-resolver/v2",
                QueryResolution.Intent.SCENE_SEARCH,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                0.9);
        return new QueryResolutionResult(
                normalization, resolution, List.of(), null, "query-resolver/v2", "prompt/v1", "model/v1", null);
    }

    private List<String> tokens(String rawQuery) {
        return List.of(rawQuery.split(" "));
    }
}
