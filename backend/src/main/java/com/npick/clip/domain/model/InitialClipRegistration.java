package com.npick.clip.domain.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.npick.clip.domain.error.ClipRegistrationErrorCode;
import com.npick.common.error.BusinessException;

/** 최초 등록 시의 영상·처리·입력 날짜를 함께 생성한다. 실행 중 상태 전이와 재처리는 이 모델의 범위가 아니다. */
public record InitialClipRegistration(
        long clipId,
        long pipelineRunId,
        SourceType sourceType,
        String storageKey,
        String contentHash,
        String title,
        String transcriptFileKey,
        String scriptText,
        long registeredById,
        LocalDate broadcastDate,
        LocalDate filmedDate,
        PipelineDefinition pipeline,
        Instant registeredAt) {

    /** 등록자와 같은 날짜 감각으로 "오늘"을 판단한다. UTC 로 보면 한국 아침 시간대의 등록이 하루를 앞선다. */
    private static final ZoneId REGISTRATION_ZONE = ZoneId.of("Asia/Seoul");

    public InitialClipRegistration {
        if (clipId <= 0 || pipelineRunId <= 0 || registeredById <= 0) {
            throw new IllegalArgumentException("서버가 생성한 영상·처리 ID와 인증된 등록자 ID가 필요합니다.");
        }
        Objects.requireNonNull(sourceType, "sourceType");
        Objects.requireNonNull(pipeline, "pipeline");
        Objects.requireNonNull(registeredAt, "registeredAt");
        if ((broadcastDate != null && (broadcastDate.getYear() < 1 || broadcastDate.getYear() > 9999))
                || (filmedDate != null && (filmedDate.getYear() < 1 || filmedDate.getYear() > 9999))) {
            throw new BusinessException(ClipRegistrationErrorCode.INVALID_DATE);
        }
        if (storageKey == null || storageKey.isBlank() || contentHash == null || !contentHash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("저장 결과의 키와 SHA-256 소문자 hex가 필요합니다.");
        }
        title = emptyToNull(title);
        transcriptFileKey = emptyToNull(transcriptFileKey);
        scriptText = emptyToNull(scriptText);
        if (title != null && title.length() > 50) {
            throw new BusinessException(ClipRegistrationErrorCode.TITLE_TOO_LONG);
        }
        // U+FFFD 는 「이 자리에 있던 바이트를 읽지 못했다」는 표식이다. UTF-8 아닌 본문(CP949 등)을 보낸 요청에서
        // 글자당 한 바이트씩 사라진 뒤에 남는다. 원래 글자는 복구할 수 없으므로 저장 전에 거절한다 — 받아 두면
        // 화면에 영구히 깨진 제목이 남고, 나중에 고칠 방법도 없다 (S15P21A501-226).
        if (title != null && title.indexOf(0xFFFD) >= 0) {
            throw new BusinessException(ClipRegistrationErrorCode.TITLE_NOT_UTF8);
        }
        // 일반 대본도 같은 길로 들어오는 텍스트 파트다. 컬럼이 text 라 길이로도 걸리지 않아, 막지 않으면 되돌릴 수 없는
        // 대본이 저장되고 VLM 이 그것을 참고 자료로 읽는다 (S15P21A501-258).
        if (scriptText != null && scriptText.indexOf(0xFFFD) >= 0) {
            throw new BusinessException(ClipRegistrationErrorCode.SCRIPT_TEXT_NOT_UTF8);
        }
        if (sourceType == SourceType.ARCHIVE && broadcastDate != null) {
            throw new BusinessException(ClipRegistrationErrorCode.ARCHIVE_BROADCAST_DATE);
        }
        LocalDate registeredOn = LocalDate.ofInstant(registeredAt, REGISTRATION_ZONE);
        if (broadcastDate != null && broadcastDate.isAfter(registeredOn)) {
            throw new BusinessException(ClipRegistrationErrorCode.FUTURE_BROADCAST_DATE);
        }
        if (filmedDate != null && filmedDate.isAfter(registeredOn)) {
            throw new BusinessException(ClipRegistrationErrorCode.FUTURE_FILMED_DATE);
        }
        if (broadcastDate != null && filmedDate != null && broadcastDate.isBefore(filmedDate)) {
            throw new BusinessException(ClipRegistrationErrorCode.BROADCAST_DATE_BEFORE_FILMED_DATE);
        }
    }

    public String transcriptSource() {
        return transcriptFileKey == null ? "none" : "provided";
    }

    public Long activePipelineRunId() {
        return null;
    }

    public int processingNo() {
        return 1;
    }

    public String status() {
        return "queued";
    }

    public Instant startedAt() {
        return null;
    }

    public Instant finishedAt() {
        return null;
    }

    public String errorCode() {
        return null;
    }

    public Map<String, StageState> stageStates() {
        Map<String, StageState> states = new LinkedHashMap<>();
        pipeline.stageNames().forEach(name -> states.put(name, new StageState("pending", 0)));
        return Collections.unmodifiableMap(states);
    }

    public List<DateEvidence> dateEvidence() {
        List<DateEvidence> evidence = new ArrayList<>();
        if (broadcastDate != null) evidence.add(new DateEvidence("broadcast_date", broadcastDate));
        if (filmedDate != null) evidence.add(new DateEvidence("filmed_date", filmedDate));
        return List.copyOf(evidence);
    }

    public enum SourceType {
        BROADCAST,
        ARCHIVE;

        public static SourceType fromValue(String value) {
            if ("broadcast".equals(value)) return BROADCAST;
            if ("archive".equals(value)) return ARCHIVE;
            throw new BusinessException(ClipRegistrationErrorCode.INVALID_SOURCE_TYPE);
        }
    }

    /** 실행 환경의 정의를 전달받는다. 모델에 단계 이름의 별도 정본을 만들지 않는다. */
    public record PipelineDefinition(String version, List<String> stageNames) {
        public PipelineDefinition {
            if (version == null || version.isBlank() || version.length() > 128) {
                throw new IllegalArgumentException("유효한 파이프라인 버전이 필요합니다.");
            }
            if (stageNames == null
                    || stageNames.isEmpty()
                    || stageNames.stream().anyMatch(name -> name == null || name.isBlank())
                    || stageNames.stream().distinct().count() != stageNames.size()) {
                throw new IllegalArgumentException("중복되지 않는 파이프라인 단계 목록이 필요합니다.");
            }
            stageNames = List.copyOf(stageNames);
        }
    }

    public record StageState(String status, int attempts) {}

    public record DateEvidence(String tagType, LocalDate date) {
        public String source() {
            return "user_input";
        }

        /**
         * 등록자가 직접 적어 넣은 날짜다. 관측 근거이므로 검증으로 저장한다 (F-04).
         *
         * <p>미검증으로 두면 {@code TagJudgment.observationVerified()} 가 거짓이 되고, 그 장면의 날짜 태그는 {@code FalseHitGuardPolicy} 에서
         * 통째로 건너뛰어진다. 방송일·촬영일 범위를 지정해도 범위 밖 장면이 하나도 걸러지지 않았던 이유다 (S15P21A501-231).
         *
         * <p>F-04 가 기본 미검증으로 두는 것은 ASR·VLM·일반 추론 규칙의 <b>추정</b> 근거다. 사용자 입력은 그 목록에 없다.
         */
        public String verificationStatus() {
            return "verified";
        }

        public BigDecimal confidence() {
            return null;
        }

        public Long sceneId() {
            return null;
        }

        public String sourceRefType() {
            return null;
        }

        public Long sourceRefId() {
            return null;
        }

        public Long sourceFeedbackId() {
            return null;
        }
    }

    private static String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
