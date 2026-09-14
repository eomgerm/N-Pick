package com.npick.search.domain.model;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.npick.common.error.BusinessException;
import com.npick.search.domain.error.SearchErrorCode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;

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
    void 필터_엔트리_사이의_경계가_흐려지지_않는다() {
        // 문자열마다 길이를 붙여도 구조가 평평하면 키가 옆 키의 값으로 흡수된다.
        // {"a":["b"], "c":["d"]} 와 {"a":["b","c","d"]} 는 둘 다 a,b,c,d 로 흐른다.
        var split = search("q", Map.of("a", List.of("b"), "c", List.of("d")));
        var merged = search("q", Map.of("a", List.of("b", "c", "d")));

        assertThat(split.fingerprint()).isNotEqualTo(merged.fingerprint());
    }

    @Test
    void 필터_키가_옆_엔트리의_값으로_흡수되지_않는다() {
        // 필터 값은 사용자·UI 문자열이라 다른 필터의 키 이름과 겹치는 것을
        // 구조적으로 막을 수 없다.
        var split = search("q", Map.of("tagType", List.of("location"), "x", List.of("y")));
        var merged = search("q", Map.of("tagType", List.of("location", "x", "y")));

        assertThat(split.fingerprint()).isNotEqualTo(merged.fingerprint());
    }

    @Test
    void 질의와_버전의_경계가_흐려지지_않는다() {
        var a = NormalizedSearch.of("ab", Map.of(), "c");
        var b = NormalizedSearch.of("a", Map.of(), "bc");

        assertThat(a.fingerprint()).isNotEqualTo(b.fingerprint());
    }

    @Test
    void 빈_필터_리스트는_필터를_안_건_것과_같다() {
        // UI 가 선택 없는 필터를 {} 로 보내든 {"tag": []} 로 보내든 같은 검색이다.
        // 갈리면 같은 검색이 지문 두 개를 갖고 override 가 한쪽에만 걸린다.
        var absent = search("부산", Map.of());
        var emptySelection = search("부산", Map.of("tag", List.of()));

        assertThat(absent.fingerprint()).isEqualTo(emptySelection.fingerprint());
    }

    @Test
    void 같은_값을_두_번_보내도_지문이_같다() {
        // 다중 선택 UI 에서 같은 값을 두 번 고를 수는 없다. 중복은 같은 선택이다.
        var once = search("부산", Map.of("tag", List.of("홍수")));
        var twice = search("부산", Map.of("tag", List.of("홍수", "홍수")));

        assertThat(once.fingerprint()).isEqualTo(twice.fingerprint());
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
        // 불변식 위반은 BusinessException 이어야 한다 — IllegalArgumentException 이면
        // GlobalExceptionHandler 의 Exception 폴백에 걸려 입력 문제가 500 이 된다.
        assertThatThrownBy(() -> NormalizedSearch.of("", Map.of(), VERSION))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).errorCode())
                .isEqualTo(SearchErrorCode.NORMALIZED_QUERY_BLANK);
    }

    @Test
    void 정규화_버전이_비어_있으면_거부한다() {
        assertThatThrownBy(() -> NormalizedSearch.of("부산 침수", Map.of(), ""))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).errorCode())
                .isEqualTo(SearchErrorCode.NORMALIZATION_VERSION_BLANK);
    }

    @Test
    void 필터_값이_널이면_거부한다() {
        var withNull = new HashMap<String, List<String>>();
        withNull.put("tag", java.util.Collections.singletonList(null));

        assertThatThrownBy(() -> NormalizedSearch.of("부산", withNull, VERSION))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).errorCode())
                .isEqualTo(SearchErrorCode.FILTER_CONTAINS_NULL);
    }

    @Test
    void 유니코드_표기가_달라도_같은_지문이다() {
        // macOS 는 NFD, Windows 는 NFC 로 한글을 보낸다. 눈에 같은 값이 다른 지문을
        // 만들면 한쪽에서 만든 규칙이 다른 쪽에 안 걸린다.
        var nfc = search("부산", Map.of("tag", List.of("홍수")));
        var nfd = search(
                "부산", Map.of("tag", List.of(java.text.Normalizer.normalize("홍수", java.text.Normalizer.Form.NFD))));

        assertThat(nfc.fingerprint()).isEqualTo(nfd.fingerprint());
    }

    @Test
    void 필터_값의_띄어쓰기가_달라도_같은_지문이다() {
        // 필터 값은 태그 값이고, 태그 채널은 두 표기를 같은 tag.match_value 로 맞춘다.
        // 지문만 갈리면 같은 장면을 찾아오면서 쌓인 장면 제외 규칙은 공유하지 못한다.
        var spaced = search("이태원", Map.of("tag", List.of("이태원 참사")));
        var joined = search("이태원", Map.of("tag", List.of("이태원참사")));

        assertThat(spaced.fingerprint()).isEqualTo(joined.fingerprint());
    }

    @Test
    void 필터_값의_폭_없는_문자는_지문을_가르지_않는다() {
        // 붙여넣기로 섞여 들어오는 ZWSP. 유니코드 공백이 아니라 NFKC 도 \s 도 못 잡는다.
        var pasted = search("이태원", Map.of("tag", List.of("이태원" + Character.toString(0x200B) + "참사")));
        var clean = search("이태원", Map.of("tag", List.of("이태원참사")));

        assertThat(pasted.fingerprint()).isEqualTo(clean.fingerprint());
    }

    @Test
    void 필터_값의_대소문자는_여전히_지문을_가른다() {
        // 태그 정규화와 같은 이유로 casefold 를 걸지 않는다 — 필터 값은 태그·enum 이다.
        assertThat(search("방송", Map.of("tag", List.of("KBS"))).fingerprint())
                .isNotEqualTo(search("방송", Map.of("tag", List.of("kbs"))).fingerprint());
    }

    @Test
    void 질의와_버전을_그대로_돌려준다() {
        // 생성자가 위치 기반이고 String 필드가 셋이다. 선언 순서를 바꾸면 지문은
        // 그대로인 채 normalized_query 컬럼에 버전 문자열이 들어간다.
        var subject = search("부산 침수", Map.of());

        assertThat(subject.normalizedQuery()).isEqualTo("부산 침수");
        assertThat(subject.normalizationVersion()).isEqualTo(VERSION);
    }

    @Test
    void 널_입력을_거부한다() {
        assertThatThrownBy(() -> NormalizedSearch.of(null, Map.of(), VERSION)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> NormalizedSearch.of("부산", null, VERSION)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> NormalizedSearch.of("부산", Map.of(), null)).isInstanceOf(NullPointerException.class);
    }

    // ── 불변 ────────────────────────────────────────────────────────

    @Test
    void 넘긴_필터_맵을_나중에_고쳐도_값이_바뀌지_않는다() {
        var mutable = new HashMap<String, List<String>>();
        mutable.put("tag", new ArrayList<>(List.of("홍수")));
        var subject = search("부산", mutable);
        String before = subject.fingerprint();

        mutable.get("tag").add("태풍");
        mutable.put("extra", List.of("x"));

        // 지문만 보면 방어적 복사가 없어도 통과한다 — 생성 시점에 계산해 박아 두기
        // 때문이다. 보관한 필터까지 봐야 copyOf 를 실제로 고정한다.
        assertThat(subject.normalizedFilters()).containsExactly(entry("tag", List.of("홍수")));
        assertThat(subject.fingerprint()).isEqualTo(before);
    }
}
