package com.npick.clip.infrastructure.transcript;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import com.npick.clip.application.command.prepare.PrepareTranscriptInputUseCase.EmbeddedInspection;
import com.npick.clip.application.command.prepare.PrepareTranscriptInputUseCase.EmbeddedStatus;
import com.npick.clip.application.command.prepare.PrepareTranscriptInputUseCase.TrackAttempt;
import com.npick.common.error.BusinessException;

public final class MovTextExtractor {
    private final SubtitleProcess process;
    private final SubtitleParser parser;
    private final ObjectMapper mapper;
    private final Duration timeout;
    private final int maxBytes;

    public MovTextExtractor(
            SubtitleProcess process, SubtitleParser parser, ObjectMapper mapper, Duration timeout, int maxBytes) {
        this.process = process;
        this.parser = parser;
        this.mapper = mapper;
        this.timeout = timeout;
        this.maxBytes = SubtitleLimits.requireValid(maxBytes);
    }

    public record Extraction(List<SubtitleParser.Cue> cues, EmbeddedInspection inspection) {
        public Extraction {
            cues = List.copyOf(cues);
        }
    }

    private record Track(int index, String language, boolean preferred) {
        int languageRank() {
            String lang = language.toLowerCase(Locale.ROOT);
            if (Set.of("ko", "kor").contains(lang) || lang.startsWith("ko-") || lang.startsWith("kor-")) return 0;
            return lang.isBlank() || Set.of("und", "unk").contains(lang) ? 1 : 2;
        }
    }

    public Extraction extract(Path video, BigDecimal duration) {
        List<TrackAttempt> attempts = new ArrayList<>();
        try {
            var probe = mapper.readTree(process.run(
                    List.of(
                            "ffprobe",
                            "-v",
                            "error",
                            "-protocol_whitelist",
                            "file",
                            "-show_entries",
                            "format=format_name:stream=index,codec_type,codec_name:stream_tags=language:stream_disposition=default",
                            "-of",
                            "json",
                            "-i",
                            video.toString()),
                    timeout,
                    maxBytes));
            if (probe == null
                    || !probe.path("streams").isArray()
                    || !probe.path("format").isObject()) {
                throw new IOException("내장 자막 탐색 형식 오류");
            }
            String container = probe.path("format").path("format_name").asText("");
            if (Arrays.stream(container.split(",")).noneMatch(s -> s.equals("mov") || s.equals("mp4"))) {
                return empty(EmbeddedStatus.UNSUPPORTED, attempts);
            }
            List<Track> tracks = new ArrayList<>();
            int subtitleCount = 0;
            for (var stream : probe.path("streams")) {
                if (!stream.path("codec_type").asText("").equals("subtitle")) continue;
                subtitleCount++;
                if (!stream.path("index").isIntegralNumber()
                        || stream.path("index").asInt(-1) < 0) {
                    throw new IOException("내장 자막 스트림 번호 오류");
                }
                int index = stream.path("index").asInt();
                if (!stream.path("codec_name").asText("").equals("mov_text")) {
                    attempts.add(new TrackAttempt(index, "UNSUPPORTED_CODEC"));
                    continue;
                }
                tracks.add(new Track(
                        index,
                        stream.path("tags").path("language").asText(""),
                        stream.path("disposition").path("default").asInt(0) == 1));
            }
            if (subtitleCount == 0) return empty(EmbeddedStatus.NO_TRACK, attempts);
            if (tracks.isEmpty()) return empty(EmbeddedStatus.UNSUPPORTED, attempts);
            tracks.sort(Comparator.comparingInt(Track::languageRank)
                    .thenComparing(t -> !t.preferred())
                    .thenComparingInt(Track::index));
            boolean failed = false;
            for (Track track : tracks) {
                try {
                    byte[] bytes = process.run(
                            List.of(
                                    "ffmpeg",
                                    "-v",
                                    "error",
                                    "-nostdin",
                                    "-protocol_whitelist",
                                    "file",
                                    "-copyts",
                                    "-i",
                                    video.toString(),
                                    "-map",
                                    "0:" + track.index(),
                                    "-c:s",
                                    "webvtt",
                                    "-f",
                                    "webvtt",
                                    "pipe:1"),
                            timeout,
                            maxBytes);
                    var cues = parser.parseExtractedVtt(bytes, duration);
                    attempts.add(new TrackAttempt(track.index(), "EXTRACTED"));
                    return new Extraction(
                            cues, new EmbeddedInspection(EmbeddedStatus.EXTRACTED, track.index(), attempts, false));
                } catch (BusinessException invalid) {
                    attempts.add(new TrackAttempt(track.index(), "NO_VALID_SEGMENTS"));
                } catch (IOException failure) {
                    failed = true;
                    attempts.add(new TrackAttempt(track.index(), "EXTRACTION_FAILED"));
                }
            }
            return empty(failed ? EmbeddedStatus.EXTRACTION_FAILED : EmbeddedStatus.NO_VALID_SEGMENTS, attempts);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return empty(EmbeddedStatus.EXTRACTION_FAILED, attempts);
        } catch (IOException | JacksonException failure) {
            return empty(EmbeddedStatus.EXTRACTION_FAILED, attempts);
        }
    }

    private Extraction empty(EmbeddedStatus status, List<TrackAttempt> attempts) {
        return new Extraction(List.of(), new EmbeddedInspection(status, null, attempts, false));
    }
}
