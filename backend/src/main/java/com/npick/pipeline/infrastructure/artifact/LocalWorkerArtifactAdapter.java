package com.npick.pipeline.infrastructure.artifact;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import com.npick.common.error.BusinessException;
import com.npick.pipeline.application.error.WorkerIntegrationErrorCode;
import com.npick.pipeline.application.port.WorkerArtifactPort;

/** Immutable attempt files. Authorization/fencing belongs to the execution boundary, before I/O. */
public final class LocalWorkerArtifactAdapter implements WorkerArtifactPort {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(LocalWorkerArtifactAdapter.class);
    private final Path root;

    public LocalWorkerArtifactAdapter(Path root) {
        // HTTP-disabled installations may not configure media storage at all.
        // Keep the adapter present, but never interpret an empty root as the working directory.
        if (root == null || root.toString().isBlank()) {
            this.root = null;
            return;
        }
        try {
            Files.createDirectories(root);
            this.root = root.toRealPath();
        } catch (IOException failure) {
            throw new BusinessException(WorkerIntegrationErrorCode.STORAGE_UNAVAILABLE, failure);
        }
    }

    public static void validateKey(String key) {
        if (key == null || key.isBlank() || key.startsWith("/") || key.contains("\\") || key.contains(":"))
            reject(WorkerIntegrationErrorCode.PATH_FORBIDDEN);
        for (String part : key.split("/", -1)) {
            if (part.isEmpty() || part.equals(".") || part.equals(".."))
                reject(WorkerIntegrationErrorCode.PATH_FORBIDDEN);
        }
    }

    private Path path(String key) throws IOException {
        if (root == null) reject(WorkerIntegrationErrorCode.STORAGE_UNAVAILABLE);
        validateKey(key);
        Path path = root.resolve(key);
        // Refuse links even if they currently point inside the root: their target can change.
        Path cursor = root;
        if (Files.isSymbolicLink(cursor)) reject(WorkerIntegrationErrorCode.PATH_FORBIDDEN);
        for (Path part : root.relativize(path)) {
            cursor = cursor.resolve(part);
            if (Files.isSymbolicLink(cursor)) reject(WorkerIntegrationErrorCode.PATH_FORBIDDEN);
        }
        return path;
    }

    @Override
    public PreparedUpload prepareUpload(String prefix, String key, long size, String hash, InputStream source) {
        if (prefix == null
                || !prefix.matches("runs/[1-9][0-9]*/[a-z_]+/a[1-9][0-9]*/")
                || key == null
                || !key.startsWith(prefix)) reject(WorkerIntegrationErrorCode.PATH_FORBIDDEN);
        if (size < 0 || hash == null || !hash.matches("[0-9a-f]{64}"))
            reject(WorkerIntegrationErrorCode.INVALID_OUTPUT);
        Path temporary = null;
        try {
            Path target = path(key);
            Files.createDirectories(target.getParent());
            path(key);
            temporary = Files.createTempFile(target.getParent(), ".upload-", ".tmp");
            MessageDigest digest = digest();
            long count = 0;
            try (OutputStream output = Files.newOutputStream(temporary)) {
                byte[] buffer = new byte[65536];
                int read;
                while ((read = source.read(buffer)) != -1) {
                    count += read;
                    if (count > size) reject(WorkerIntegrationErrorCode.INVALID_OUTPUT);
                    digest.update(buffer, 0, read);
                    output.write(buffer, 0, read);
                }
            }
            if (count != size) reject(WorkerIntegrationErrorCode.INVALID_OUTPUT);
            if (!HexFormat.of().formatHex(digest.digest()).equals(hash))
                reject(WorkerIntegrationErrorCode.HASH_MISMATCH);
            Path staged = temporary;
            temporary = null;
            return new PreparedUpload() {
                public void publish() {
                    synchronized (LocalWorkerArtifactAdapter.this) {
                        try {
                            path(key);
                            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) verifyFile(target, size, hash);
                            else {
                                try {
                                    Files.move(staged, target);
                                } catch (java.nio.file.FileAlreadyExistsException raced) {
                                    verifyFile(target, size, hash);
                                }
                            }
                        } catch (IOException failure) {
                            throw new BusinessException(WorkerIntegrationErrorCode.STORAGE_UNAVAILABLE, failure);
                        }
                    }
                }

                public void close() {
                    cleanup(staged);
                }
            };
        } catch (IOException failure) {
            throw new BusinessException(WorkerIntegrationErrorCode.STORAGE_UNAVAILABLE, failure);
        } finally {
            if (temporary != null) cleanup(temporary);
        }
    }

    private static void cleanup(Path temporary) {
        try {
            Files.deleteIfExists(temporary);
        } catch (IOException failure) {
            log.warn("Could not remove staged artifact {}", temporary, failure);
        }
    }

    @Override
    public void verify(Ref reference) {
        if (reference.byteSize() < 0
                || reference.contentHash() == null
                || !reference.contentHash().matches("[0-9a-f]{64}")) reject(WorkerIntegrationErrorCode.INVALID_OUTPUT);
        try {
            verifyFile(existing(reference.storageKey()), reference.byteSize(), reference.contentHash());
        } catch (IOException failure) {
            throw new BusinessException(WorkerIntegrationErrorCode.STORAGE_UNAVAILABLE, failure);
        }
    }

    @Override
    public void download(String key, OutputStream destination) {
        try {
            Files.copy(existing(key), destination);
        } catch (IOException failure) {
            throw new BusinessException(WorkerIntegrationErrorCode.STORAGE_UNAVAILABLE, failure);
        }
    }

    @Override
    public byte[] verified(Ref reference) {
        if (reference.byteSize() < 0
                || reference.contentHash() == null
                || !reference.contentHash().matches("[0-9a-f]{64}")) reject(WorkerIntegrationErrorCode.INVALID_OUTPUT);
        try {
            Path target = existing(reference.storageKey());
            if (Files.size(target) != reference.byteSize()) reject(WorkerIntegrationErrorCode.INVALID_OUTPUT);
            byte[] bytes = Files.readAllBytes(target);
            if (bytes.length != reference.byteSize()) reject(WorkerIntegrationErrorCode.INVALID_OUTPUT);
            if (!HexFormat.of().formatHex(digest().digest(bytes)).equals(reference.contentHash()))
                reject(WorkerIntegrationErrorCode.HASH_MISMATCH);
            return bytes;
        } catch (IOException failure) {
            throw new BusinessException(WorkerIntegrationErrorCode.STORAGE_UNAVAILABLE, failure);
        }
    }

    private Path existing(String key) throws IOException {
        Path target = path(key);
        if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS))
            reject(WorkerIntegrationErrorCode.ARTIFACT_MISSING);
        return target;
    }

    private static void verifyFile(Path target, long size, String hash) throws IOException {
        if (Files.size(target) != size) reject(WorkerIntegrationErrorCode.INVALID_OUTPUT);
        MessageDigest digest = digest();
        try (InputStream input = Files.newInputStream(target)) {
            byte[] buffer = new byte[65536];
            int count;
            while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
        }
        if (!HexFormat.of().formatHex(digest.digest()).equals(hash)) reject(WorkerIntegrationErrorCode.HASH_MISMATCH);
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static void reject(WorkerIntegrationErrorCode code) {
        throw new BusinessException(code);
    }
}
