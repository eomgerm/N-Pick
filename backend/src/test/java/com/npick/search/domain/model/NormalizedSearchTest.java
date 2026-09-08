package com.npick.search.domain.model;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 정규화된 검색의 동일성 계약 (S15P21A501-44).
 *
 * <p>여기서 만든 fingerprint 가 {@code search_execution.query_fingerprint} 와 {@code search_rule.query_fingerprint} 에 들어가고 장면
 * 제외 규칙의 exact 조회 키가 된다. 지문이 흔들리면 규칙이 자기가 만들어진 검색에도 안 걸린다.
 */
class NormalizedSearchTest {
    private static final String VERSION = "query-norm/v1:4bebf303:kiwi0.23.2";

    private static NormalizedSearch search(String query, Map<String, List<String>> filters) {
        return NormalizedSearch.of(query, filters, VERSION);
    }

    // ── 지문 생성 (FR-QRY-002) ──────────────────────────────────────

    @Test
    void 같은_입력은_같은_지문을_만든다() {
        Map<String, List<String>> filters = Map.of("tagType", List.of("location"));

        assertThat(search("부산 침수", filters).fingerprint())
                .isEqualTo(search("부산 침수", filters).fingerprint());
    }

    @Test
    void 지문은_hex_64자다() {
        String fingerprint = search("부산 침수", Map.of()).fingerprint();

        // search_execution.query_fingerprint 는 varchar(64) 다.
        assertThat(fingerprint).hasSize(64).matches("[0-9a-f]{64}");
    }

    @Test
    void 필터만_달라도_다른_지문이다() {
        // 티켓의 완료 조건 그대로다.
        assertThat(search("부산 침수", Map.of("tagType", List.of("location"))).fingerprint())
                .isNotEqualTo(
                        search("부산 침수", Map.of("tagType", List.of("facility"))).fingerprint());
    }

    @Test
    void 질의만_달라도_다른_지문이다() {
        assertThat(search("부산 침수", Map.of()).fingerprint())
                .isNotEqualTo(search("서울 침수", Map.of()).fingerprint());
    }

    @Test
    void 정규화_버전만_달라도_다른_지문이다() {
        // 정규화 규칙이 바뀌면 기존 규칙이 안 걸려야 한다.
        assertThat(NormalizedSearch.of("부산 침수", Map.of(), VERSION).fingerprint())
                .isNotEqualTo(NormalizedSearch.of("부산 침수", Map.of(), "query-norm/v1:00000000:kiwi0.23.2")
                        .fingerprint());
    }

    // ── 순서 비의존 ─────────────────────────────────────────────────

    @Test
    void 필터_키_순서는_지문을_바꾸지_않는다() {
        var a = search("부산", Map.of("a", List.of("1"), "b", List.of("2")));
        var b = search("부산", Map.of("b", List.of("2"), "a", List.of("1")));

        assertThat(a.fingerprint()).isEqualTo(b.fingerprint());
    }

    @Test
    void 필터_값_순서는_지문을_바꾸지_않는다() {
        var a = search("부산", Map.of("tag", List.of("홍수", "태풍")));
        var b = search("부산", Map.of("tag", List.of("태풍", "홍수")));

        assertThat(a.fingerprint()).isEqualTo(b.fingerprint());
    }

    // ── 경계 모호성 ─────────────────────────────────────────────────

    @Test
    void 값에_구분자가_들어가도_경계가_흐려지지_않는다() {
        // 구분자로 이어 붙이면 ["a,b"] 와 ["a","b"] 가 같은 지문이 된다.
        var joined = search("부산", Map.of("tag", List.of("a,b")));
        var split = search("부산", Map.of("tag", List.of("a", "b")));

        assertThat(joined.fingerprint()).isNotEqualTo(split.fingerprint());
    }

    @Test
    void 질의와_버전의_경계가_흐려지지_않는다() {
        var a = NormalizedSearch.of("ab", Map.of(), "c");
        var b = NormalizedSearch.of("a", Map.of(), "bc");

        assertThat(a.fingerprint()).isNotEqualTo(b.fingerprint());
    }

    @Test
    void 빈_필터와_빈_문자열_필터값은_다르다() {
        var empty = search("부산", Map.of());
        var blank = search("부산", Map.of("tag", List.of("")));

        assertThat(empty.fingerprint()).isNotEqualTo(blank.fingerprint());
    }

    // ── 재비교 (FR-QRY-003) ─────────────────────────────────────────

    @Test
    void 같은_값이면_matches_가_참이다() {
        Map<String, List<String>> filters = Map.of("tagType", List.of("location"));

        assertThat(search("부산 침수", filters).matches(search("부산 침수", filters))).isTrue();
    }

    @Test
    void 값이_다르면_matches_가_거짓이다() {
        var a = search("부산 침수", Map.of("tagType", List.of("location")));
        var b = search("부산 침수", Map.of("tagType", List.of("facility")));

        assertThat(a.matches(b)).isFalse();
    }

    @Test
    void matches_는_지문만_보고_판정하지_않는다() {
        // FR-QRY-003. SHA-256 충돌을 실제로 만들 수는 없으므로, 원본 비교 경로가 살아 있는지
        // 확인한다. 지문이 다른 두 값은 물론이고 자기 자신과의 비교도 통과해야 한다.
        var a = search("부산 침수", Map.of());
        var b = search("서울 침수", Map.of());

        assertThat(a.matches(b)).isFalse();
        assertThat(a.matches(a)).isTrue();
    }

    // ── 입력 방어 ───────────────────────────────────────────────────

    @Test
    void 정규화된_질의가_비어_있으면_거부한다() {
        // 정규화기가 이미 거부하지만, 지문의 재료가 비는 상태를 여기서도 막는다.
        assertThatThrownBy(() -> NormalizedSearch.of("", Map.of(), VERSION))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 정규화_버전이_비어_있으면_거부한다() {
        assertThatThrownBy(() -> NormalizedSearch.of("부산 침수", Map.of(), ""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 널_입력을_거부한다() {
        assertThatThrownBy(() -> NormalizedSearch.of(null, Map.of(), VERSION)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> NormalizedSearch.of("부산", null, VERSION)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> NormalizedSearch.of("부산", Map.of(), null)).isInstanceOf(NullPointerException.class);
    }

    // ── 불변 ────────────────────────────────────────────────────────

    @Test
    void 넘긴_필터_맵을_나중에_고쳐도_지문이_바뀌지_않는다() {
        var mutable = new HashMap<String, List<String>>();
        mutable.put("tag", new ArrayList<>(List.of("홍수")));
        var subject = search("부산", mutable);
        String before = subject.fingerprint();

        mutable.get("tag").add("태풍");
        mutable.put("extra", List.of("x"));

        assertThat(subject.fingerprint()).isEqualTo(before);
    }
}
