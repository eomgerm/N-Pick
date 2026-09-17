package com.npick.search.domain;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.npick.search.domain.model.SearchConfigVersion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 공용 「값 해시」 규약의 Java 구현을 고정한다 (docs/contracts/README.md).
 *
 * <p>정규화 형태를 문자열로 박아 두는 이유는, 직렬화 방식이 조용히 바뀌면 같은 설정의 과거 실행과 버전이 어긋나기 때문이다. 그때 원인을 찾기 어렵다.
 */
class SearchConfigVersionTest {
    private static final char ESCAPE = (char) 0x5C;

    @Test
    void canonicalFormSortsKeysAndDropsWhitespaceLikePythonJsonDumps() {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("b", 1);
        payload.put("a", Map.of("d", true, "c", "x"));
        payload.put("z", List.of(1, 2));

        // sort_keys=True, separators=(",", ":") 와 같은 결과다. 목록 순서는 값의 일부라 정렬하지 않는다.
        assertThat(SearchConfigVersion.canonicalJson(payload))
                .isEqualTo("{\"a\":{\"c\":\"x\",\"d\":true},\"b\":1,\"z\":[1,2]}");
    }

    @Test
    void koreanStaysUnescapedAndControlCharactersUseShortForms() {
        // ensure_ascii=False 다. 한글이 이스케이프되면 해시는 같아도 사람이 읽을 수 없다.
        assertThat(SearchConfigVersion.canonicalJson(Map.of("k", "명절 교통"))).isEqualTo("{\"k\":\"명절 교통\"}");

        String escaped = SearchConfigVersion.canonicalJson(Map.of("k", "a\"b" + (char) 0x0A));
        assertThat(escaped).isEqualTo("{\"k\":\"a" + ESCAPE + "\"b" + ESCAPE + "n\"}");
    }

    @Test
    void versionChangesWhenAnyValueChangesButNotWhenKeyOrderDoes() {
        var first = new LinkedHashMap<String, Object>();
        first.put("ocrWeight", 1.0);
        first.put("poolSize", 200);
        var reordered = new LinkedHashMap<String, Object>();
        reordered.put("poolSize", 200);
        reordered.put("ocrWeight", 1.0);
        var changed = new LinkedHashMap<String, Object>();
        changed.put("ocrWeight", 1.5);
        changed.put("poolSize", 200);

        String version = SearchConfigVersion.of("search-fusion/v1", first);
        assertThat(version).isEqualTo(SearchConfigVersion.of("search-fusion/v1", reordered));
        assertThat(version).isNotEqualTo(SearchConfigVersion.of("search-fusion/v1", changed));
        // schema 가 다르면 값이 같아도 다른 버전이다.
        assertThat(version).isNotEqualTo(SearchConfigVersion.of("search-fusion/v2", first));
    }

    @Test
    void versionShapeFollowsTheSharedConvention() {
        String version = SearchConfigVersion.of("search-fusion/v1", Map.of("a", 1));
        // <schema>:<sha256 앞 8자>. 길이는 사람이 로그에서 읽기 위한 값이다.
        assertThat(version).matches("search-fusion/v1:[0-9a-f]{8}");
    }

    /**
     * 정본({@code ai/src/npick_worker/versioning.py})과 같은 바이트열을 내는지 고정한다.
     *
     * <p>{@code Double.toString} 을 그대로 쓰면 {@code 0.0001} 이 {@code 1.0E-4} 가 되어 해시가 갈린다. 「지금은 BE 만 계산하니 괜찮다」로 두면 나중에
     * 규약대로 고치는 순간 같은 설정의 과거 실행 버전이 전부 달라진다.
     */
    @Test
    void numbersAreSerializedExactlyAsCpythonReprDoes() {
        // CPython 은 소수점 위치로 가른다 — decpt <= -4 또는 decpt > 16 이면 지수 표기다.
        assertThat(SearchConfigVersion.canonicalJson(Map.of("k", 0.0001))).isEqualTo("{\"k\":0.0001}");
        assertThat(SearchConfigVersion.canonicalJson(Map.of("k", 0.00001))).isEqualTo("{\"k\":1e-05}");
        assertThat(SearchConfigVersion.canonicalJson(Map.of("k", 1e15))).isEqualTo("{\"k\":1000000000000000.0}");
        assertThat(SearchConfigVersion.canonicalJson(Map.of("k", 1e16))).isEqualTo("{\"k\":1e+16}");
        // 정수처럼 보이는 값에도 .0 을 붙이고, 최단 왕복 자릿수를 그대로 쓴다.
        assertThat(SearchConfigVersion.canonicalJson(Map.of("k", 1.0))).isEqualTo("{\"k\":1.0}");
        assertThat(SearchConfigVersion.canonicalJson(Map.of("k", 60.0))).isEqualTo("{\"k\":60.0}");
        assertThat(SearchConfigVersion.canonicalJson(Map.of("k", 0.1 + 0.2))).isEqualTo("{\"k\":0.30000000000000004}");
        assertThat(SearchConfigVersion.canonicalJson(Map.of("k", -0.5))).isEqualTo("{\"k\":-0.5}");
    }

    /**
     * subnormal 구간에서 유효 자릿수까지 정본과 같은지 고정한다.
     *
     * <p>{@link Double#toString} 을 그대로 쓰면 자릿수부터 갈린다 — JDK 21 은 {@code Double.MIN_VALUE} 를 {@code 4.9E-324} 로 쓰는데 한 자리
     * {@code 5e-324} 로도 같은 double 로 돌아온다. 「지수 표기 조건만 다르다」는 전제로는 규약을 맞출 수 없다.
     */
    @Test
    void subnormalValuesUseTheShortestRoundTripDigitsLikeTheReferenceImplementation() {
        assertThat(SearchConfigVersion.canonicalJson(Map.of("k", Double.MIN_VALUE))).isEqualTo("{\"k\":5e-324}");
        assertThat(SearchConfigVersion.canonicalJson(Map.of("k", 1e-323))).isEqualTo("{\"k\":1e-323}");
        assertThat(SearchConfigVersion.canonicalJson(Map.of("k", 1e-322))).isEqualTo("{\"k\":1e-322}");

        assertThat(version(Map.of("k", Double.MIN_VALUE))).isEqualTo("search-fusion/v1:55ecbb52");
        assertThat(version(Map.of("k", 1e-323))).isEqualTo("search-fusion/v1:8f76e124");
        assertThat(version(Map.of("k", 1e-322))).isEqualTo("search-fusion/v1:666eabfa");
    }

    /**
     * 2 의 거듭제곱 경계에서 최단 표현을 고르는지 고정한다.
     *
     * <p>여기서는 아래쪽 ulp 가 위쪽의 절반이라 정확값이 십진 동점에 놓여도 <b>한쪽만 왕복한다</b>. 각 자릿수에서 반올림 후보를 하나만 검사하면 그 자릿수를 통째로 건너뛰고 더 긴 표현을
     * 고르게 된다 — {@code 2^-24} 는 16 자리로 충분한데 17 자리가 나왔다.
     */
    @Test
    void powerOfTwoBoundariesPickTheShorterNeighbourThatRoundTrips() {
        double powerOfTwo = Math.scalb(1.0, -24);
        // 16 자리 두 후보 중 아래쪽은 왕복하지 않는다. 그래서 위쪽을 골라야 한다.
        assertThat(Double.parseDouble("5.960464477539062e-08")).isNotEqualTo(powerOfTwo);
        assertThat(Double.parseDouble("5.960464477539063e-08")).isEqualTo(powerOfTwo);

        assertThat(SearchConfigVersion.canonicalJson(Map.of("k", powerOfTwo)))
                .isEqualTo("{\"k\":5.960464477539063e-08}");
        assertThat(version(Map.of("k", powerOfTwo))).isEqualTo("search-fusion/v1:cb500cdc");
    }

    /** 위 형식이 실제 해시까지 정본과 같은지 — 문자열 비교만으로는 규약 위반을 놓친다. */
    @Test
    void fixedCrossLanguageVectorsMatchTheReferenceImplementation() {
        assertThat(version(Map.of("ocrWeight", 0.0001))).isEqualTo("search-fusion/v1:087df9b6");
        assertThat(version(Map.of("rrfK", 60.0, "lambda", 1.0))).isEqualTo("search-fusion/v1:5cb5d2e3");
        assertThat(version(Map.of("tiny", 0.00001))).isEqualTo("search-fusion/v1:f4ce1d46");
        assertThat(version(Map.of("huge", 1e16))).isEqualTo("search-fusion/v1:66f518d3");
        assertThat(version(Map.of("big", 1e15))).isEqualTo("search-fusion/v1:f5e98797");
        assertThat(version(Map.of("sum", 0.1 + 0.2))).isEqualTo("search-fusion/v1:95da12c9");
    }

    private static String version(Map<String, Object> payload) {
        return SearchConfigVersion.of("search-fusion/v1", payload);
    }

    @Test
    void nonFiniteNumbersAreRejectedInsteadOfProducingInvalidJson() {
        // Python 은 NaN 을 그대로 뱉지만 그것은 파싱되지 않는다. 기록이 깨진 채로 저장되는 것을 막는다.
        assertThatThrownBy(() -> SearchConfigVersion.canonicalJson(Map.of("k", Double.NaN)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
