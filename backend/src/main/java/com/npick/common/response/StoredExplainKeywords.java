package com.npick.common.response;

import java.util.Set;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 저장된 {@code explain_json} 의 {@code match.matched_keywords} 를 읽는 규칙.
 *
 * <p>항목은 {@code {keyword, origin}} 객체이고 {@code origin} 은 {@code user} 또는 {@code expanded} 다 (S15P21A501-234). 출처를 남기지
 * 않던 시절의 기록은 문자열이며, 복원할 때 {@code origin} 을 {@code null} 로 둔다 — <b>{@code user} 로 채우지 않는다.</b> 그 단어를 사용자가 실제로 쳤는지는 알 수
 * 없고, 모르는 것을 안다고 기록하지 않는다 (FRD §7.2). 화면은 {@code null} 을 이 구분이 생기기 전 모든 칩이 보이던 모양 그대로 그린다 — 그 기록에는 구분이 없었으므로 당시 보이던
 * 대로다.
 *
 * <p>저장 기록을 복원하는 화면이 둘이라 (내 검색 기록·내 문의 상세) 규칙을 여기 한 곳에 둔다. 두 경로가 다른 변환을 타면 같은 기록이 화면마다 다르게 보인다.
 */
public final class StoredExplainKeywords {

    public static final String ORIGIN_USER = "user";
    public static final String ORIGIN_EXPANDED = "expanded";

    /** {@code matched_keywords[].origin} 어휘. 값이 없다는 뜻의 {@code null} 은 여기 없다. */
    public static final Set<String> ORIGINS = Set.of(ORIGIN_USER, ORIGIN_EXPANDED);

    private static final String FIELD_KEYWORD = "keyword";
    private static final String FIELD_ORIGIN = "origin";
    private static final String FIELD_MATCHED_KEYWORDS = "matched_keywords";

    private StoredExplainKeywords() {}

    /**
     * 그릴 수 있는 항목인가. 출처를 실은 객체, 출처가 {@code null} 인 객체, 또는 출처가 없던 시절의 비어 있지 않은 문자열이다.
     *
     * <p>{@code origin} 이 없을 때 기본값을 {@code null} 로 두면 {@code Set.of} 로 만든 불변 Set 의 {@code contains(null)} 이
     * {@code NullPointerException} 을 던진다. 깨진 기록은 예외가 아니라 {@code false} 로 떨어져야 하므로 null 을 먼저 가른다.
     */
    public static boolean isRenderable(JsonNode keyword) {
        if (isText(keyword)) {
            return true;
        }
        if (keyword == null || !keyword.isObject() || !isText(keyword.get(FIELD_KEYWORD))) {
            return false;
        }
        JsonNode origin = keyword.get(FIELD_ORIGIN);
        return origin != null && (origin.isNull() || ORIGINS.contains(origin.asString("")));
    }

    /**
     * 문자열 항목을 {@code {keyword, origin: null}} 으로 맞춘다. 화면이 받는 모양을 응답과 하나로 두어, 복원 화면이 옛 기록과 새 기록을 따로 다루지 않게 한다.
     *
     * <p>객체 항목과 그 밖의 모양은 건드리지 않는다 — 온전한지 판정하는 것은 읽는 쪽의 몫이고, 여기서 값을 지어내지 않는다.
     */
    public static void normalize(ObjectNode match) {
        if (!(match.get(FIELD_MATCHED_KEYWORDS) instanceof ArrayNode keywords)) {
            return;
        }
        ArrayNode normalized = match.arrayNode();
        for (JsonNode keyword : keywords) {
            if (keyword.isString()) {
                ObjectNode entry = match.objectNode();
                entry.put(FIELD_KEYWORD, keyword.asString(""));
                entry.putNull(FIELD_ORIGIN);
                normalized.add(entry);
                continue;
            }
            normalized.add(keyword);
        }
        match.set(FIELD_MATCHED_KEYWORDS, normalized);
    }

    private static boolean isText(JsonNode node) {
        return node != null && node.isString() && !node.asString("").isBlank();
    }
}
