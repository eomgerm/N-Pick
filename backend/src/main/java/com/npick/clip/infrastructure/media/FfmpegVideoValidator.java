package com.npick.clip.infrastructure.media;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** 영상 스트림과 존재하는 오디오 스트림을 끝까지 디코딩한다. 출력 영상 파일은 생성하지 않는다. */
public final class FfmpegVideoValidator {
    private final String executable;
    private final Duration timeout;

    public FfmpegVideoValidator(String executable, Duration timeout) {
        if (executable == null || executable.isBlank() || timeout == null || timeout.toMillis() <= 0) {
            throw new IllegalArgumentException("ffmpeg 실행 경로와 양수인 제한 시간이 필요합니다.");
        }
        this.executable = executable;
        this.timeout = timeout;
    }

    public void validate(Path file) throws IOException, InterruptedException, TimeoutException {
        Path input = file.toRealPath();
        if (!Files.isRegularFile(input)) {
            throw new IOException("영상 입력은 로컬 파일이어야 합니다.");
        }

        Process process = new ProcessBuilder(
                        executable,
                        "-nostdin",
                        "-v",
                        "error",
                        "-xerror",
                        "-max_error_rate",
                        "0",
                        "-abort_on",
                        "empty_output",
                        "-err_detect",
                        "explode",
                        "-protocol_whitelist",
                        "file",
                        "-i",
                        input.toString(),
                        "-map",
                        "0:V",
                        "-map",
                        "0:a?",
                        "-f",
                        "null",
                        "-")
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
        try {
            process.getOutputStream().close();
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new TimeoutException("전체 영상 검증 시간이 초과되어 검증을 완료하지 못했습니다.");
            }
            if (process.exitValue() != 0) {
                throw new InvalidVideoFileException("영상 전체를 디코딩할 수 없습니다.");
            }
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                process.waitFor();
            }
        }
    }
}
