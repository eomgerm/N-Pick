package com.npick.clip.infrastructure.transcript;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

import com.npick.clip.application.error.TranscriptErrorCode;
import com.npick.common.error.BusinessException;

/** 운영 계정만 변경할 수 있는 미디어 루트. 사용자 파일명은 경로 구성에 사용하지 않는다. */
final class TranscriptFiles {
    private final Path root;

    TranscriptFiles(Path root) throws IOException {
        Files.createDirectories(root);
        this.root = root.toRealPath();
    }

    Path read(String key) throws IOException {
        Path path = resolve(key, false);
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException("미디어 파일이 없습니다.");
        return path;
    }

    Owned write(String key, byte[] bytes) throws IOException {
        Path path = resolve(key, true);
        // CREATE_NEW: 중복 요청·동일 attempt 재호출도 이전 파일을 덮어쓰지 않는다.
        try (var output = Files.newOutputStream(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            try {
                output.write(bytes);
            } catch (IOException failure) {
                try {
                    output.close();
                    Files.delete(path);
                } catch (IOException cleanup) {
                    failure.addSuppressed(cleanup);
                }
                throw failure;
            }
        }
        return new Owned(key, identity(path), bytes.length, hash(bytes), this);
    }

    private Path resolve(String key, boolean createParents) throws IOException {
        if (key == null
                || key.isBlank()
                || key.startsWith("/")
                || key.contains("\\")
                || key.contains(":")
                || key.indexOf('\0') >= 0) throw new IOException("미디어 상대 키가 올바르지 않습니다.");
        String[] parts = key.split("/", -1);
        Path path = root;
        for (int i = 0; i < parts.length; i++) {
            if (parts[i].isBlank() || parts[i].equals(".") || parts[i].equals(".."))
                throw new IOException("미디어 키 이탈입니다.");
            try {
                path = path.resolve(parts[i]);
            } catch (java.nio.file.InvalidPathException invalid) {
                throw new IOException("미디어 상대 키 형식 오류", invalid);
            }
            if (i < parts.length - 1) {
                if (createParents) {
                    try {
                        Files.createDirectory(path);
                    } catch (FileAlreadyExistsException ignored) {
                    }
                }
                if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
                        || !path.toRealPath().startsWith(root)) throw new IOException("미디어 디렉터리가 변경되었습니다.");
            }
        }
        return path;
    }

    static byte[] bounded(InputStream input, int limit) throws IOException {
        byte[] bytes = input.readNBytes(limit + 1);
        if (bytes.length > limit) throw TranscriptErrorCode.invalid("subtitle", "설정된 자막 파일 크기 제한을 초과했습니다.");
        return bytes;
    }

    static String hash(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static Object identity(Path path) throws IOException {
        var attr = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attr.isRegularFile()) throw new IOException("원본이 변경되었습니다.");
        return attr.fileKey() == null
                ? new Identity(attr.creationTime(), attr.lastModifiedTime(), attr.size())
                : attr.fileKey();
    }

    private record Identity(
            java.nio.file.attribute.FileTime created, java.nio.file.attribute.FileTime modified, long size) {}

    static final class Owned implements AutoCloseable {
        final String key;
        final Object identity;
        final long byteSize;
        final String hash;
        private final TranscriptFiles files;
        private boolean retained;
        private boolean closed;

        Owned(String key, Object identity, long byteSize, String hash, TranscriptFiles files) {
            this.key = key;
            this.identity = identity;
            this.byteSize = byteSize;
            this.hash = hash;
            this.files = files;
        }

        synchronized void retain() {
            retained = true;
        }

        public synchronized void close() {
            if (retained || closed) return;
            try {
                Path checked = files.resolve(key, false);
                if (Files.exists(checked, LinkOption.NOFOLLOW_LINKS)) {
                    if (!Objects.equals(identity, identity(checked))
                            || Files.size(checked) != byteSize
                            || !hash.equals(hash(Files.readAllBytes(checked)))) {
                        throw new IOException("소유 파일이 변경되었습니다.");
                    }
                    Files.delete(checked);
                }
                closed = true;
            } catch (IOException failure) {
                throw new BusinessException(TranscriptErrorCode.CLEANUP_FAILED, failure);
            }
        }
    }
}
