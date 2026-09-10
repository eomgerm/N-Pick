package com.npick.clip.infrastructure.transcript;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import com.npick.clip.application.command.prepare.PrepareTranscriptInputUseCase;
import com.npick.clip.application.error.TranscriptErrorCode;
import com.npick.common.error.BusinessException;

public final class LocalTranscriptInputPreparation implements PrepareTranscriptInputUseCase {
    private final Path mediaRoot;
    private final int maxBytes;
    private final SubtitleParser parser;
    private final MovTextExtractor extractor;
    private final ObjectMapper mapper;

    public LocalTranscriptInputPreparation(
            Path mediaRoot, int maxBytes, SubtitleParser parser, MovTextExtractor extractor, ObjectMapper mapper) {
        this.mediaRoot = mediaRoot;
        this.maxBytes = SubtitleLimits.requireValid(maxBytes);
        this.parser = parser;
        this.extractor = extractor;
        this.mapper = mapper;
    }

    public Prepared prepare(Command command) {
        // Prefix는 배정 주체가 현재 run/stage/attempt에서 가져온다. fencing 자체는 #36/#70 책임이다.
        if (command.outputKeyPrefix() == null
                || !command.outputKeyPrefix().matches("runs/[1-9][0-9]*/transcript_selection/a[1-9][0-9]*/")
                || command.videoDuration() == null
                || command.videoDuration().signum() <= 0) {
            throw new BusinessException(TranscriptErrorCode.STORAGE_FAILED);
        }
        try {
            var files = new TranscriptFiles(mediaRoot);
            var video = files.read(command.videoStorageKey());
            List<Segment> segments = new ArrayList<>();
            if (command.transcriptFileKey() != null) {
                try (var input = Files.newInputStream(files.read(command.transcriptFileKey()))) {
                    var cues = parser.parse(
                            TranscriptFiles.bounded(input, maxBytes),
                            parser.format(command.transcriptFileKey()),
                            command.videoDuration());
                    segments.addAll(segments(cues, "uploaded"));
                }
            }
            var embedded = extractor.extract(video, command.videoDuration());
            segments.addAll(segments(embedded.cues(), "embedded"));
            byte[] bytes = mapper.writeValueAsBytes(new Segments("npick.transcript.segments/v1", segments));
            var file = files.write(
                    command.outputKeyPrefix() + "transcript-segments-" + UUID.randomUUID() + ".json", bytes);
            var artifact = new ArtifactRef("transcript_segments", file.key, file.byteSize, file.hash);
            var transcript = new TranscriptInput(artifact, embedded.inspection());
            return new Prepared() {
                public TranscriptInput transcript() {
                    return transcript;
                }

                public List<ArtifactRef> artifacts() {
                    return List.of(artifact);
                }

                public void retain() {
                    file.retain();
                }

                public void close() {
                    file.close();
                }
            };
        } catch (IOException | JacksonException | BusinessException failure) {
            // 등록 후 보관 파일의 손상/누락은 업로드 요청의 400 오류와 구분한다.
            throw new BusinessException(TranscriptErrorCode.STORAGE_FAILED, failure);
        }
    }

    private List<Segment> segments(List<SubtitleParser.Cue> cues, String source) {
        return cues.stream()
                .map(c -> new Segment(source + "-" + c.originalIndex(), c.s(), c.e(), c.t(), source))
                .toList();
    }
}
