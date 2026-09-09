package com.npick.clip.infrastructure.media;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 서버가 관리하는 로컬 파일의 기술 정보를 읽는다. 허용 형식·크기·길이 판정과 전체 디코딩 검사는 별도 책임이다. */
public final class FfprobeVideoReader {
    private static final long MAX_OUTPUT_BYTES = 1024 * 1024;

    private final String executable;
    private final Duration timeout;
    private final ObjectMapper objectMapper;

    public FfprobeVideoReader(String executable, Duration timeout, ObjectMapper objectMapper) {
        if (executable == null || executable.isBlank() || timeout == null || timeout.toMillis() <= 0) {
            throw new IllegalArgumentException("ffprobe 실행 경로와 양수인 제한 시간이 필요합니다.");
        }
        this.executable = executable;
        this.timeout = timeout;
        this.objectMapper = objectMapper;
    }

    /** 정보를 추출한 뒤 영상 스트림이 있는지 검사한다. 이 검사의 통과가 등록 완료나 재생 가능을 의미하지는 않는다. */
    public Metadata readVideo(Path file) throws IOException, InterruptedException, TimeoutException {
        Metadata metadata = read(file);
        requireVideoStream(metadata);
        return metadata;
    }

    void requireVideoStream(Metadata metadata) throws IOException {
        if (metadata.videoStreams().isEmpty()) {
            throw new InvalidVideoFileException("영상 스트림이 없는 파일은 등록할 수 없습니다.");
        }
    }

    public Metadata read(Path file) throws IOException, InterruptedException, TimeoutException {
        Path input = file.toRealPath();
        if (!Files.isRegularFile(input)) {
            throw new IOException("영상 입력은 로컬 파일이어야 합니다.");
        }

        Path output = Files.createTempFile("npick-ffprobe-", ".json");
        Process process = null;
        try {
            process = new ProcessBuilder(
                            executable,
                            "-v",
                            "error",
                            "-protocol_whitelist",
                            "file",
                            "-show_entries",
                            "format=format_name,duration:stream=codec_type,codec_name,width,height:stream_disposition=attached_pic",
                            "-of",
                            "json",
                            "-i",
                            input.toString())
                    .redirectOutput(output.toFile())
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            process.getOutputStream().close();
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new TimeoutException("영상 정보 조회 시간이 초과되었습니다.");
            }
            if (process.exitValue() != 0) {
                throw new InvalidVideoFileException("영상 정보를 읽을 수 없습니다.");
            }
            if (Files.size(output) > MAX_OUTPUT_BYTES) {
                throw new IOException("영상 정보 조회 결과가 너무 큽니다.");
            }
            return parse(Files.readString(output));
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
                process.waitFor();
            }
            Files.deleteIfExists(output);
        }
    }

    Metadata parse(String json) throws IOException {
        try {
            JsonNode root = objectMapper.readTree(json);
            if (root == null
                    || !root.path("format").isObject()
                    || !root.path("streams").isArray()) {
                throw new IOException("영상 정보 조회 결과의 형식이 올바르지 않습니다.");
            }
            JsonNode format = root.path("format");
            String duration = format.path("duration").asText("");
            List<VideoStream> videos = new ArrayList<>();
            List<String> audioCodecs = new ArrayList<>();
            for (JsonNode stream : root.path("streams")) {
                String type = stream.path("codec_type").asText("");
                String codec = stream.path("codec_name").asText("");
                if ("video".equals(type)
                        && stream.path("disposition").path("attached_pic").asInt(0) == 0) {
                    videos.add(new VideoStream(
                            codec,
                            stream.path("width").asInt(0),
                            stream.path("height").asInt(0)));
                } else if ("audio".equals(type)) {
                    audioCodecs.add(codec);
                }
            }
            return new Metadata(
                    format.path("format_name").asText(""),
                    duration.isBlank() || "N/A".equals(duration) ? null : new BigDecimal(duration),
                    videos,
                    audioCodecs);
        } catch (JacksonException | NumberFormatException exception) {
            throw new IOException("영상 정보 조회 결과를 해석할 수 없습니다.", exception);
        }
    }

    public record Metadata(
            String container, BigDecimal durationSeconds, List<VideoStream> videoStreams, List<String> audioCodecs) {
        public Metadata {
            videoStreams = List.copyOf(videoStreams);
            audioCodecs = List.copyOf(audioCodecs);
        }
    }

    public record VideoStream(String codec, int width, int height) {}
}
