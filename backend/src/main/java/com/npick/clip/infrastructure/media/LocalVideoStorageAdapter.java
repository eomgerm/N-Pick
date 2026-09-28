package com.npick.clip.infrastructure.media;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Objects;

import com.npick.clip.application.command.prepare.PrepareVideoResult;
import com.npick.clip.application.command.store.StoreVideoResult;
import com.npick.clip.application.error.VideoStorageErrorCode;
import com.npick.clip.application.port.VideoStoragePort;
import com.npick.common.error.BusinessException;

public final class LocalVideoStorageAdapter implements VideoStoragePort {
    private final Path mediaRoot;

    public LocalVideoStorageAdapter(Path mediaRoot) {
        this.mediaRoot = mediaRoot;
    }

    @Override
    public StoreVideoResult store(long clipId, PrepareVideoResult video) {
        if (clipId <= 0 || !(video instanceof LocalVideoInspectionAdapter.LocalPreparedVideo localVideo)) {
            throw new BusinessException(VideoStorageErrorCode.STORAGE_FAILED);
        }
        Path createdDirectory = null;
        try {
            Path source = localVideo.temporaryPath();
            if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("검증된 임시 파일이 없습니다.");
            }
            Path root = mediaRoot.toRealPath();
            Path clips = root.resolve("clips");
            try {
                Files.createDirectory(clips);
            } catch (FileAlreadyExistsException ignored) {
                // 공유 상위 디렉터리만 재사용한다. 영상별 디렉터리는 아래에서 배타적으로 생성한다.
            }
            if (!Files.isDirectory(clips, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("영상 저장 디렉터리가 올바르지 않습니다.");
            }
            try {
                createdDirectory = Files.createDirectory(clips.resolve(Long.toString(clipId)));
            } catch (FileAlreadyExistsException exception) {
                throw new BusinessException(VideoStorageErrorCode.DESTINATION_EXISTS, exception);
            }
            Files.move(source, createdDirectory.resolve("original"));
            return new LocalStoredVideo(
                    "clips/" + clipId + "/original",
                    createdDirectory,
                    identity(createdDirectory),
                    identity(createdDirectory.resolve("original")));
        } catch (IOException failure) {
            if (createdDirectory != null) {
                try {
                    Files.deleteIfExists(createdDirectory.resolve("original"));
                    Files.deleteIfExists(createdDirectory);
                } catch (IOException cleanupFailure) {
                    failure.addSuppressed(cleanupFailure);
                }
            }
            throw new BusinessException(VideoStorageErrorCode.STORAGE_FAILED, failure);
        }
    }

    private static Object identity(Path path) throws IOException {
        var attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (attributes.isSymbolicLink()) throw new IOException("저장 경로가 심볼릭 링크로 변경되었습니다.");
        // Windows 기본 provider는 fileKey를 제공하지 않아 파일 메타데이터를 함께 확인한다.
        return attributes.fileKey() != null
                ? attributes.fileKey()
                : new FileIdentity(
                        attributes.creationTime(),
                        attributes.isDirectory(),
                        attributes.isDirectory() ? null : attributes.lastModifiedTime(),
                        attributes.isDirectory() ? 0 : attributes.size());
    }

    private record FileIdentity(
            java.nio.file.attribute.FileTime createdAt,
            boolean directory,
            java.nio.file.attribute.FileTime modifiedAt,
            long size) {}

    private record LocalStoredVideo(String storageKey, Path directory, Object directoryKey, Object fileKey)
            implements StoreVideoResult {
        @Override
        public void discard() {
            try {
                if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return;
                if (!Files.isDirectory(directory.getParent(), LinkOption.NOFOLLOW_LINKS)
                        || !Objects.equals(directoryKey, identity(directory))) {
                    throw new IOException("저장 디렉터리가 변경되었습니다.");
                }
                Path original = directory.resolve("original");
                if (Files.exists(original, LinkOption.NOFOLLOW_LINKS)) {
                    if (!Objects.equals(fileKey, identity(original))) {
                        throw new IOException("저장 원본이 변경되었습니다.");
                    }
                    Files.delete(original);
                }
                Files.delete(directory);
            } catch (IOException failure) {
                throw new BusinessException(VideoStorageErrorCode.CLEANUP_FAILED, failure);
            }
        }
    }
}
