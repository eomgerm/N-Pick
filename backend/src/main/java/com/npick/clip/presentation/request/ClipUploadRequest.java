package com.npick.clip.presentation.request;

import java.io.InputStream;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.List;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.springframework.web.bind.annotation.BindParam;
import org.springframework.web.multipart.MultipartFile;

import com.npick.clip.application.command.register.UploadClipCommand;

public record ClipUploadRequest(
        @NotEmpty(message = "영상 파일을 첨부해 주세요.") @Size(max = 1, message = "영상 파일은 1개만 첨부할 수 있습니다.") List<MultipartFile> video,

        @BindParam("source_type")
        @NotNull(message = "영상 종류를 선택해 주세요.") @Pattern(regexp = "broadcast|archive", message = "영상 종류는 broadcast 또는 archive여야 합니다.") String sourceType,

        @Size(max = 500, message = "제목은 500자 이내로 입력해 주세요.") String title,
        @BindParam("broadcast_date") String broadcastDate,
        @BindParam("filmed_date") String filmedDate,
        @Size(max = 1, message = "자막 파일은 1개만 첨부할 수 있습니다.") List<MultipartFile> subtitle,
        @BindParam("script_text") String scriptText,
        @BindParam("rights_confirmed") Boolean rightsConfirmed,
        @BindParam("external_processing_confirmed") Boolean externalProcessingConfirmed) {

    /** 등록자와 같은 날짜 감각으로 "오늘"을 판단한다. UTC 로 보면 한국 아침 시간대의 오늘이 미래가 된다. */
    private static final ZoneId REQUEST_ZONE = ZoneId.of("Asia/Seoul");

    public ClipUploadRequest {
        sourceType = emptyToNull(sourceType);
        title = emptyToNull(title);
        broadcastDate = emptyToNull(broadcastDate);
        filmedDate = emptyToNull(filmedDate);
        scriptText = emptyToNull(scriptText);
    }

    /** Bean Validation을 통과한 요청을 HTTP 타입이 없는 명령으로 변환한다. */
    public UploadClipCommand toCommand(InputStream content) {
        return toCommand(content, null, null);
    }

    public UploadClipCommand toCommand(InputStream content, InputStream subtitleContent, String requestKey) {
        return new UploadClipCommand(
                content,
                sourceType,
                title,
                broadcastDate == null ? null : LocalDate.parse(broadcastDate),
                filmedDate == null ? null : LocalDate.parse(filmedDate),
                requestKey,
                scriptText,
                subtitleContent == null
                        ? null
                        : new UploadClipCommand.Subtitle(
                                subtitleContent,
                                subtitle.getFirst().getOriginalFilename(),
                                subtitle.getFirst().getContentType()),
                Boolean.TRUE.equals(rightsConfirmed),
                Boolean.TRUE.equals(externalProcessingConfirmed));
    }

    @AssertTrue(message = "자막 파일은 비어 있을 수 없습니다.") public boolean isSubtitleContentPresent() {
        return subtitle == null
                || subtitle.isEmpty()
                || subtitle.size() != 1
                || (subtitle.getFirst() != null && !subtitle.getFirst().isEmpty());
    }

    @AssertTrue(message = "영상 파일의 Content-Type이 올바르지 않습니다.") public boolean isVideoMediaTypeValid() {
        if (video == null || video.size() != 1 || video.getFirst() == null) return true;
        String type = video.getFirst().getContentType();
        if (type == null) return true;
        type = type.split(";", 2)[0].trim().toLowerCase(java.util.Locale.ROOT);
        return type.startsWith("video/") || type.equals("application/octet-stream") || type.equals("application/ogg");
    }

    @AssertTrue(message = "내용이 비어 있는 영상 파일은 등록할 수 없습니다.") public boolean isVideoContentPresent() {
        if (video == null || video.size() != 1) {
            return true;
        }
        return video.getFirst() != null && !video.getFirst().isEmpty();
    }

    @AssertTrue(message = "방송일은 실제 존재하는 YYYY-MM-DD 날짜로 입력해 주세요.") public boolean isBroadcastDateValid() {
        return isValidDate(broadcastDate);
    }

    @AssertTrue(message = "촬영일은 실제 존재하는 YYYY-MM-DD 날짜로 입력해 주세요.") public boolean isFilmedDateValid() {
        return isValidDate(filmedDate);
    }

    @AssertTrue(message = "자료 영상에는 방송일을 입력할 수 없습니다.") public boolean isBroadcastDateAllowed() {
        return !"archive".equals(sourceType) || broadcastDate == null;
    }

    @AssertTrue(message = "방송일은 오늘 이후 날짜로 입력할 수 없습니다.") public boolean isBroadcastDateNotFuture() {
        return isNotFuture(broadcastDate);
    }

    @AssertTrue(message = "촬영일은 오늘 이후 날짜로 입력할 수 없습니다.") public boolean isFilmedDateNotFuture() {
        return isNotFuture(filmedDate);
    }

    @AssertTrue(message = "방송일은 촬영일보다 빠를 수 없습니다.") public boolean isBroadcastDateNotBeforeFilmedDate() {
        LocalDate broadcast = parseOrNull(broadcastDate);
        LocalDate filmed = parseOrNull(filmedDate);
        return broadcast == null || filmed == null || !broadcast.isBefore(filmed);
    }

    private static boolean isNotFuture(String date) {
        LocalDate parsed = parseOrNull(date);
        return parsed == null || !parsed.isAfter(LocalDate.now(REQUEST_ZONE));
    }

    /** 형식·달력 오류는 isValidDate 가 따로 보고한다. 여기서는 읽을 수 없는 값을 검사 대상에서 뺀다. */
    private static LocalDate parseOrNull(String date) {
        if (date == null || !isValidDate(date)) {
            return null;
        }
        return LocalDate.parse(date);
    }

    private static boolean isValidDate(String date) {
        if (date == null) {
            return true;
        }
        if (!date.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) {
            return false;
        }
        try {
            return LocalDate.parse(date).getYear() > 0;
        } catch (DateTimeParseException exception) {
            return false;
        }
    }

    private static String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
