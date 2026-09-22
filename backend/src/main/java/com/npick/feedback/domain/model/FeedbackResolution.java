package com.npick.feedback.domain.model;

import java.util.Locale;

/**
 * 검수 처리 결과 5종 (FRD v3.1 F-09).
 *
 * <p>{@code terminal} 은 이 판정만으로 신고가 종료(closed)되는지, {@code noteRequired} 는 사유가 필수인지를 뜻한다. no_action·deferred 는 교정 후보 없이
 * 사유만 기록하고 종료된다. 교정 3종은 resolution 만 기록하고 reviewing 을 유지하며, 종료는 F-12·F-13 을 거친다.
 */
public enum FeedbackResolution {
    TAG_CORRECTION("tag_correction", false, false),
    PATCH_PARSE("patch_parse", false, false),
    EXCLUDE_SCENE("exclude_scene", false, false),
    CORRECTION("correction", false, false),
    NO_ACTION("no_action", true, true),
    /** @deprecated 기존 데이터 행하위 호환성을 위해 유지. 신규 저장·UI에서는 사용하지 않는다. */
    @Deprecated
    DEFERRED("deferred", true, true);

    private final String value;
    private final boolean terminal;
    private final boolean noteRequired;

    FeedbackResolution(String value, boolean terminal, boolean noteRequired) {
        this.value = value;
        this.terminal = terminal;
        this.noteRequired = noteRequired;
    }

    /** DB·API 에 쓰는 소문자 값. status 컬럼과 달리 resolution 은 소문자로 저장한다. */
    public String value() {
        return value;
    }

    /** 이 판정만으로 신고가 closed 로 종료되는가. */
    public boolean isTerminal() {
        return terminal;
    }

    /** 사유(note)가 필수인가. */
    public boolean isNoteRequired() {
        return noteRequired;
    }

    /** API 입력값을 파싱한다. 모르는 값이면 null 을 돌려주고, 호출부가 400 으로 변환한다. */
    public static FeedbackResolution parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String v = raw.trim().toLowerCase(Locale.ROOT);
        for (FeedbackResolution r : values()) {
            if (r.value.equals(v)) {
                return r;
            }
        }
        return null;
    }

    /**
     * DB 저장값을 파싱하며, 기존 교정 3종(tag_correction, patch_parse, exclude_scene)을 CORRECTION 으로 단일화한다.
     * 모르는 값이면 null 을 돌려준다.
     */
    public static FeedbackResolution fromValue(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String v = value.trim().toLowerCase(Locale.ROOT);

        // 기존 교정값을 CORRECTION 으로 매핑
        if ("tag_correction".equals(v) || "patch_parse".equals(v) || "exclude_scene".equals(v)) {
            return CORRECTION;
        }

        // 다른 값은 표준 파싱
        for (FeedbackResolution r : values()) {
            if (r.value.equals(v)) {
                return r;
            }
        }
        return null;
    }
}
