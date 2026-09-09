package com.npick.clip.infrastructure.transcript;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import com.npick.clip.application.error.TranscriptErrorCode;

/** UTF-8 원문을 보존하고 파싱 결과만 시작·종료 순으로 안정 정렬한다. */
public final class SubtitleParser {
    private final JsonMapper mapper = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
            .build();

    public record Cue(int originalIndex, long s, long e, String t) {}

    public String format(String filename) {
        String name = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
        for (String extension : List.of("srt", "vtt", "json")) {
            if (name.endsWith("." + extension)) return extension;
        }
        throw TranscriptErrorCode.invalid("subtitle", "SRT·VTT·npick.subtitle/v1 JSON 파일이 필요합니다.");
    }

    public List<Cue> parse(byte[] bytes, String format, BigDecimal duration) {
        if (duration == null || duration.signum() <= 0) {
            throw TranscriptErrorCode.invalid("subtitle", "실제 영상 길이가 필요합니다.");
        }
        String text;
        try {
            text = StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException failure) {
            throw TranscriptErrorCode.invalid("subtitle", "UTF-8 인코딩이 필요합니다.");
        }
        if (text.startsWith("\uFEFF")) text = text.substring(1);
        List<Cue> cues =
                switch (format) {
                    case "json" -> json(text, duration);
                    case "srt", "vtt" -> timedText(text, format.equals("vtt"), duration);
                    default -> throw TranscriptErrorCode.invalid("subtitle", "지원하지 않는 형식입니다.");
                };
        if (cues.isEmpty()) throw TranscriptErrorCode.invalid("segments", "자막 구간이 하나 이상 필요합니다.");
        return cues.stream()
                .sorted(Comparator.comparingLong(Cue::s).thenComparingLong(Cue::e))
                .toList();
    }

    private List<Cue> json(String text, BigDecimal duration) {
        JsonNode root;
        try {
            root = mapper.readTree(text);
        } catch (JacksonException failure) {
            var location = failure.getLocation();
            throw TranscriptErrorCode.invalid(
                    location == null
                            ? "JSON"
                            : "JSON line " + location.getLineNr() + ", column " + location.getColumnNr(),
                    "JSON 문법 또는 중복 필드를 확인해 주세요.");
        }
        fields(root, Set.of("schemaVersion", "segments"), "JSON");
        if (!"npick.subtitle/v1".equals(root.path("schemaVersion").asText(""))) {
            throw TranscriptErrorCode.invalid("schemaVersion", "npick.subtitle/v1만 지원합니다.");
        }
        JsonNode segments = root.path("segments");
        if (!segments.isArray()) throw TranscriptErrorCode.invalid("segments", "배열이 필요합니다.");
        List<Cue> result = new ArrayList<>();
        for (int i = 0; i < segments.size(); i++) {
            String location = "segments[" + i + "]";
            JsonNode segment = segments.get(i);
            fields(segment, Set.of("s", "e", "t"), location);
            long s = integer(segment.path("s"), location + ".s");
            long e = integer(segment.path("e"), location + ".e");
            if (!segment.path("t").isString()) throw TranscriptErrorCode.invalid(location + ".t", "문자열이 필요합니다.");
            result.add(cue(i, s, e, segment.path("t").asText(), duration, location));
        }
        return result;
    }

    private void fields(JsonNode node, Set<String> allowed, String location) {
        if (node == null || !node.isObject()) throw TranscriptErrorCode.invalid(location, "객체가 필요합니다.");
        if (!allowed.containsAll(node.propertyNames())) {
            throw TranscriptErrorCode.invalid(location, "지원하지 않는 필드가 있습니다.");
        }
    }

    private long integer(JsonNode node, String location) {
        if (!node.isIntegralNumber() || !node.canConvertToLong()) {
            throw TranscriptErrorCode.invalid(location, "정수 밀리초가 필요합니다.");
        }
        return node.longValue();
    }

    private List<Cue> timedText(String text, boolean vtt, BigDecimal duration) {
        String[] lines = text.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        int line = 0;
        if (vtt) {
            if (!lines[0].matches("WEBVTT(?:[ \\t].*)?") || lines[0].contains("-->")) {
                throw TranscriptErrorCode.invalid("line 1", "WEBVTT 헤더가 필요합니다.");
            }
            line = 1;
            while (line < lines.length && !lines[line].isBlank()) {
                if (lines[line].startsWith("X-TIMESTAMP-MAP")) {
                    throw TranscriptErrorCode.invalid(
                            "line " + (line + 1), "외부 타임라인 매핑은 지원하지 않습니다. 영상 시작 기준 자막이 필요합니다.");
                }
                if (lines[line].contains("-->"))
                    throw TranscriptErrorCode.invalid("line " + (line + 1), "헤더 뒤 빈 줄이 필요합니다.");
                line++;
            }
        }
        List<Cue> result = new ArrayList<>();
        while (line < lines.length) {
            if (lines[line].isBlank()) {
                line++;
                continue;
            }
            int start = line;
            List<String> block = new ArrayList<>();
            while (line < lines.length && !lines[line].isBlank()) block.add(lines[line++]);
            String first = block.getFirst();
            if (vtt && (first.equals("NOTE") || first.startsWith("NOTE ") || first.startsWith("NOTE\t"))) continue;
            if (vtt && (first.equals("STYLE") || first.equals("REGION"))) {
                if (!result.isEmpty() || block.stream().anyMatch(s -> s.contains("-->"))) {
                    throw TranscriptErrorCode.invalid("line " + (start + 1), "자막 메타데이터 위치·형식이 올바르지 않습니다.");
                }
                continue;
            }
            int timing = first.contains("-->") ? 0 : 1;
            if ((!vtt && (timing != 1 || !first.matches("[0-9]+"))) || block.size() <= timing) {
                throw TranscriptErrorCode.invalid("line " + (start + 1), "자막 번호와 시간 행을 확인해 주세요.");
            }
            String location = "line " + (start + timing + 1) + " (cue " + (result.size() + 1) + ")";
            var match = Pattern.compile("^(\\S+)[ \\t]+-->[ \\t]+(\\S+)(.*)$").matcher(block.get(timing));
            if (!match.matches() || (!vtt && !match.group(3).isBlank())) {
                throw TranscriptErrorCode.invalid(location, "시작 --> 종료 시간 표기가 필요합니다.");
            }
            if (vtt
                    && !match.group(3).isBlank()
                    && !match.group(3).trim().matches("(?:[a-z]+:[^\\s]+)(?:[ \\t]+[a-z]+:[^\\s]+)*")) {
                throw TranscriptErrorCode.invalid(location, "VTT cue 설정 표기가 올바르지 않습니다.");
            }
            String payload = String.join("\n", block.subList(timing + 1, block.size()));
            if (block.subList(timing + 1, block.size()).stream()
                    .anyMatch(s -> s.matches("[0-9:.,]+[ \\t]+-->[ \\t]+[0-9:.,]+.*")))
                throw TranscriptErrorCode.invalid(location, "자막 사이 빈 줄 또는 시간 표기를 확인해 주세요.");
            result.add(cue(
                    result.size(),
                    timestamp(match.group(1), vtt, location + ".s"),
                    timestamp(match.group(2), vtt, location + ".e"),
                    payload,
                    duration,
                    location));
        }
        return result;
    }

    private long timestamp(String value, boolean vtt, String location) {
        String expression = vtt
                ? "(?:(\\d{2,}):)?([0-5][0-9]):([0-5][0-9])\\.([0-9]{3})"
                : "(\\d{2,}):([0-5][0-9]):([0-5][0-9]),([0-9]{3})";
        var match = Pattern.compile(expression).matcher(value);
        if (!match.matches()) throw TranscriptErrorCode.invalid(location, "시간 표기가 올바르지 않습니다.");
        try {
            long hours = match.group(1) == null ? 0 : Long.parseLong(match.group(1));
            return Math.addExact(
                    Math.multiplyExact(hours, 3600000),
                    Long.parseLong(match.group(2)) * 60000
                            + Long.parseLong(match.group(3)) * 1000
                            + Long.parseLong(match.group(4)));
        } catch (ArithmeticException | NumberFormatException failure) {
            throw TranscriptErrorCode.invalid(location, "시간이 표현 가능한 범위를 초과했습니다.");
        }
    }

    private Cue cue(int index, long s, long e, String t, BigDecimal duration, String location) {
        if (s < 0) throw TranscriptErrorCode.invalid(location + ".s", "시작 시간은 음수일 수 없습니다.");
        if (e < 0 || s >= e) throw TranscriptErrorCode.invalid(location + ".e", "종료 시간은 시작 시간보다 커야 합니다.");
        if (t.codePoints().allMatch(c -> Character.isWhitespace(c) || Character.isSpaceChar(c))) {
            throw TranscriptErrorCode.invalid(location + ".t", "빈 문자열 또는 공백뿐인 자막은 허용하지 않습니다.");
        }
        BigDecimal durationMs = duration.movePointRight(3);
        BigDecimal excessMs = BigDecimal.valueOf(e).subtract(durationMs);
        if (excessMs.signum() > 0) {
            throw TranscriptErrorCode.invalid(
                    location + ".e",
                    "구간 [" + s + ", " + e + "] ms의 종료 시간이 검사 기준 영상 길이 "
                            + durationMs.stripTrailingZeros().toPlainString() + " ms를 "
                            + excessMs.stripTrailingZeros().toPlainString()
                            + " ms 초과합니다. 파일 전체를 거절하며 시간 보정·잘라내기·부분 적용은 하지 않습니다.");
        }
        return new Cue(index, s, e, t);
    }
}
