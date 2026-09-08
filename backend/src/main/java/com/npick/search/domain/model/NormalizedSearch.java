package com.npick.search.domain.model;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.SortedMap;
import java.util.TreeMap;

import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.ToString;
import lombok.experimental.Accessors;

/**
 * 정규화된 검색 하나. "이 검색과 저 검색이 같은가" 를 판정한다 (FR-QRY-002, FR-QRY-003).
 *
 * <p>{@code search_execution} 과 {@code search_rule} 이 똑같은 네 컬럼(normalized_query, normalized_filters_json,
 * normalization_version, query_fingerprint)을 각각 들고 있다. 그 네 컬럼이 이 값 객체 하나에 대응한다.
 *
 * <p>정규화 자체는 여기서 하지 않는다. 형태소 분석이 필요해 질의 리졸버(Python)가 담당하며, 이 객체는 이미 정규화된 값을 받아 지문만 만든다.
 *
 * <p>지문이 달라지면 그때까지 쌓인 장면 제외 규칙이 통째로 안 걸린다. 해시 입력 방식을 바꾸는 것은 {@code normalization_version} 을 바꾸는 것과 같은 무게의 변경이다.
 */
@Getter
@Accessors(fluent = true)
@EqualsAndHashCode
@ToString(onlyExplicitlyIncluded = true)
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
public final class NormalizedSearch {
    private static final String HASH_ALGORITHM = "SHA-256";

    /** {@code query_fingerprint} 컬럼에 그대로 들어가는 SHA-256 hex 64자. */
    @ToString.Include
    private final String fingerprint;

    private final String normalizedQuery;

    /** 호출자가 원본 맵을 고쳐도 이 값은 영향받지 않는다. */
    private final SortedMap<String, List<String>> normalizedFilters;

    private final String normalizationVersion;

    /**
     * 필터는 <b>값</b> 만 정규화한다(정렬·중복 제거·빈 선택 제거). 키 표기는 손대지 않으며 빈 문자열 키도 통과한다 — 질의는 blank 를 거부하는 것과 비대칭이다. UI 명시 필터의 스키마가
     * 아직 정해지지 않아 키 규칙을 발명하지 않았다. 스키마가 확정되면 키 표기 정규화와 blank 키 거부를 함께 정한다.
     *
     * @param normalizedQuery 질의 리졸버가 만든 정규화 질의. 비어 있을 수 없다
     * @param normalizedFilters 사용자가 명시한 필터. 없으면 빈 맵
     * @param normalizationVersion 그 질의를 만든 정규화 규칙의 버전
     */
    public static NormalizedSearch of(
            @NonNull String normalizedQuery,
            @NonNull Map<String, List<String>> normalizedFilters,
            @NonNull String normalizationVersion) {
        requireNotBlank(normalizedQuery, "정규화된 질의가 비어 있다");
        requireNotBlank(normalizationVersion, "정규화 버전이 비어 있다");
        SortedMap<String, List<String>> filters = copyOf(normalizedFilters);
        // 지문은 나머지 세 값에서 파생된다. Lombok 생성자로는 이 계산을 표현할 수 없어
        // 정적 메서드에서 만들어 넘긴다.
        String fingerprint = computeFingerprint(normalizedQuery, filters, normalizationVersion);
        return new NormalizedSearch(fingerprint, normalizedQuery, filters, normalizationVersion);
    }

    /**
     * 같은 검색인가 (FR-QRY-003).
     *
     * <p>지문이 같아도 원본 값을 한 번 더 비교한다. 해시 충돌로 남의 규칙이 걸리는 일을 막기 위해서다. 유사 질의로의 확장은 금지되어 있으므로(FR-OVR-009) 정확히 같을 때만 참이다.
     */
    public boolean matches(NormalizedSearch other) {
        // @EqualsAndHashCode 가 지문과 원본 세 값을 모두 비교한다. 비교 순서는
        // 요구사항과 무관하다 — 전부 같아야 참이기 때문이다.
        return equals(other);
    }

    /**
     * 해시 입력은 길이 접두 방식이다.
     *
     * <p>구분자로 이어 붙이면 값 안에 그 구분자가 들어갔을 때 경계가 흐려진다 — {@code ["a,b"]} 와 {@code ["a","b"]} 가 같은 지문이 된다. 각 문자열 앞에 바이트 길이를
     * 붙이면 이스케이프 없이 경계가 확정된다.
     *
     * <p>길이 접두만으로는 <b>문자열</b> 경계만 잡힌다. 구조가 평평하면 키가 옆 엔트리의 값으로 흡수된다 — {@code {"a":["b"], "c":["d"]}} 와
     * {@code {"a":["b","c","d"]}} 가 둘 다 {@code a,b,c,d} 로 흘러 같은 지문이 됐다. 필터 값은 사용자·UI 문자열이라 다른 필터의 키 이름과 겹치는 것을 막을 수 없다.
     * 그래서 맵의 엔트리 수와 각 값 리스트의 원소 수도 함께 먹인다.
     */
    private static String computeFingerprint(
            String normalizedQuery, SortedMap<String, List<String>> normalizedFilters, String normalizationVersion) {
        MessageDigest digest = newDigest();
        update(digest, normalizedQuery);
        update(digest, normalizationVersion);
        updateCount(digest, normalizedFilters.size());
        // 정렬은 SortedMap 이 보장한다. 필터를 넣은 순서가 지문을 바꾸면 안 된다.
        for (Map.Entry<String, List<String>> entry : normalizedFilters.entrySet()) {
            update(digest, entry.getKey());
            updateCount(digest, entry.getValue().size());
            for (String value : entry.getValue()) {
                update(digest, value);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void update(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        updateCount(digest, bytes.length);
        digest.update(bytes);
    }

    private static void updateCount(MessageDigest digest, int count) {
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(count).array());
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance(HASH_ALGORITHM);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 은 모든 JVM 이 제공한다.
            throw new IllegalStateException(HASH_ALGORITHM + " 을 사용할 수 없다", e);
        }
    }

    /**
     * 같은 선택을 나타내는 여러 표기를 하나로 모은다. 여기서 모으지 못하면 같은 검색이 지문을 두 개 갖고, 사람이 만든 규칙이 그중 한쪽에만 걸린다.
     *
     * <ul>
     *   <li>키 정렬 — 필터를 넣은 순서는 선택이 아니다
     *   <li>값 정렬 — 다중 선택의 순서는 선택이 아니다
     *   <li>값 중복 제거 — 다중 선택에서 같은 값을 두 번 고를 수는 없다
     *   <li>빈 선택 제거 — {@code {"tag": []}} 는 tag 필터를 안 건 것이다
     * </ul>
     *
     * <p>여기의 널 검사만 손으로 쓴다. Lombok {@code @NonNull} 은 메서드 파라미터와 필드에만 붙고 맵 항목·리스트 원소에는 붙일 수 없다.
     */
    private static SortedMap<String, List<String>> copyOf(Map<String, List<String>> filters) {
        SortedMap<String, List<String>> copy = new TreeMap<>();
        filters.forEach((key, values) -> {
            Objects.requireNonNull(key, "필터 키가 널이다");
            Objects.requireNonNull(values, "필터 값이 널이다");
            List<String> normalized = values.stream()
                    .map(value -> Objects.requireNonNull(value, "필터 값 항목이 널이다"))
                    .distinct()
                    .sorted()
                    .toList();
            if (!normalized.isEmpty()) {
                copy.put(key, normalized);
            }
        });
        return Collections.unmodifiableSortedMap(copy);
    }

    private static void requireNotBlank(String value, String message) {
        if (value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
    }
}
