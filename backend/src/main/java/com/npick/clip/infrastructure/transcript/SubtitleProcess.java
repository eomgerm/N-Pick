package com.npick.clip.infrastructure.transcript;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** 셸 없이 실행하고 표준출력 크기·실행 시간을 제한한다. stderr의 미디어 원문/경로는 유출하지 않는다. */
public final class SubtitleProcess {
    public byte[] run(List<String> command, Duration timeout, int maxBytes) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(command)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            process.getOutputStream().close();
            var output = executor.submit(() -> {
                byte[] bytes = process.getInputStream().readNBytes(maxBytes + 1);
                if (bytes.length > maxBytes) throw new IOException("자막 프로세스 출력 한도 초과");
                return bytes;
            });
            try {
                byte[] bytes = output.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
                if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS) || process.exitValue() != 0) {
                    throw new IOException("자막 프로세스 실행 실패");
                }
                return bytes;
            } catch (ExecutionException | TimeoutException failure) {
                throw new IOException("자막 프로세스 출력 실패 또는 시간 초과", failure);
            } finally {
                if (process.isAlive()) process.destroyForcibly();
                process.getInputStream().close();
                output.cancel(true);
            }
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
    }
}
