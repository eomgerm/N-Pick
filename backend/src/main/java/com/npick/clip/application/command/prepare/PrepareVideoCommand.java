package com.npick.clip.application.command.prepare;

import java.io.InputStream;
import java.util.Objects;

/** content의 수명은 호출자가 관리한다. HTTP 파일 객체와 원본 파일명은 전달하지 않는다. */
public record PrepareVideoCommand(InputStream content) {
    public PrepareVideoCommand {
        Objects.requireNonNull(content, "content");
    }
}
