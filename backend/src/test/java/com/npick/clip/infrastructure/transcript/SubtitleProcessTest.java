package com.npick.clip.infrastructure.transcript;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SubtitleProcessTest {
    @TempDir
    Path root;

    @Test
    void killsTimedOutChildAndDoesNotWaitForItsSleep() throws Exception {
        Path pid = root.resolve("timeout.pid");
        assertThatThrownBy(() -> new SubtitleProcess().run(command("sleep", pid), Duration.ofSeconds(2), 1024))
                .isInstanceOf(IOException.class);
        verifyDead(pid);
    }

    @Test
    void killsChildWhenOutputLimitIsExceeded() throws Exception {
        Path pid = root.resolve("output.pid");
        assertThatThrownBy(() -> new SubtitleProcess().run(command("flood", pid), Duration.ofSeconds(5), 32))
                .isInstanceOf(IOException.class);
        verifyDead(pid);
    }

    @Test
    void usesRemainingBudgetAfterDelayedOutputEof() throws Exception {
        Path pid = root.resolve("delayed-eof.pid");
        long started = System.nanoTime();
        assertThatThrownBy(() -> new SubtitleProcess().run(command("delayed-eof", pid), Duration.ofSeconds(2), 1024))
                .isInstanceOf(IOException.class);
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(3));
        verifyDead(pid);
    }

    private void verifyDead(Path pid) throws Exception {
        long processId = Long.parseLong(Files.readString(pid));
        var handle = ProcessHandle.of(processId);
        if (handle.isPresent()) handle.get().onExit().get(5, java.util.concurrent.TimeUnit.SECONDS);
        assertThat(ProcessHandle.of(processId).map(ProcessHandle::isAlive).orElse(false))
                .isFalse();
    }

    private List<String> command(String mode, Path pid) throws Exception {
        String classes = Path.of(Child.class
                        .getProtectionDomain()
                        .getCodeSource()
                        .getLocation()
                        .toURI())
                .toString();
        return List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp",
                classes,
                Child.class.getName(),
                mode,
                pid.toString());
    }

    public static class Child {
        public static void main(String[] args) throws Exception {
            Files.writeString(
                    Path.of(args[1]), Long.toString(ProcessHandle.current().pid()));
            if (args[0].equals("flood")) {
                while (true) {
                    System.out.write(new byte[4096]);
                    System.out.flush();
                }
            }
            if (args[0].equals("delayed-eof")) {
                Thread.sleep(1500);
                System.out.close();
            }
            Thread.sleep(30000);
        }
    }
}
