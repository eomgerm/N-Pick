package com.npick.clip.infrastructure.media;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Optional;

import org.springframework.web.util.UriUtils;

import com.npick.clip.application.error.ClipMediaErrorCode;
import com.npick.clip.application.port.MediaAssetPort;
import com.npick.common.error.BusinessException;

/**
 * media root 안에서만 재생 대상을 연다 (FR-RES-013, FR-ING-009 연계).
 *
 * <p>서버 절대 경로는 이 클래스 밖으로 나가지 않는다. storage key 가 정규화 후 media root 를 벗어나거나, 심볼릭 링크를 따라간 실제 위치가 벗어나면 파일이 있어도 거부한다.
 */
public final class LocalMediaAssetAdapter implements MediaAssetPort {

    private static final String DEFAULT_CONTENT_TYPE = "video/mp4";
    private static final String QUICKTIME_CONTENT_TYPE = "video/quicktime";
    private static final int FTYP_HEADER_BYTES = 12;

    /** 경로 이탈 차단은 공유 규칙이고, 어휘만 재생의 것이다. */
    private static final MediaRootResolver.Failures FAILURES = new MediaRootResolver.Failures(
            ClipMediaErrorCode.MEDIA_ROOT_UNAVAILABLE,
            ClipMediaErrorCode.MEDIA_LOCATION_REJECTED,
            ClipMediaErrorCode.MEDIA_FILE_MISSING,
            ClipMediaErrorCode.MEDIA_READ_FAILED);

    private final MediaRootResolver paths;
    private final String internalLocationPrefix;

    /** @param internalLocationPrefix 프록시 위임에 쓰는 내부 location 접두. {@code null} 이면 직접 전송만 한다 */
    public LocalMediaAssetAdapter(Path mediaRoot, String internalLocationPrefix) {
        this.paths = new MediaRootResolver(mediaRoot, FAILURES);
        this.internalLocationPrefix = internalLocationPrefix;
    }

    @Override
    public MediaAsset resolve(String storageKey) {
        MediaRootResolver.Resolved resolved = paths.resolve(storageKey);
        Path real = resolved.real();
        if (!Files.isRegularFile(real, LinkOption.NOFOLLOW_LINKS)) {
            throw new BusinessException(ClipMediaErrorCode.MEDIA_FILE_MISSING);
        }
        long size = sizeOf(real);
        return new LocalMediaAsset(
                real, contentType(real, size), size, internalLocation(resolved.root(), resolved.candidate()));
    }

    private static long sizeOf(Path file) {
        try {
            return Files.size(file);
        } catch (IOException failure) {
            throw new BusinessException(ClipMediaErrorCode.MEDIA_READ_FAILED, failure);
        }
    }

    /** 프록시에 넘기는 값은 root 기준 상대 경로다. 절대 경로가 응답 헤더로 나가지 않는다. */
    private Optional<String> internalLocation(Path root, Path file) {
        if (internalLocationPrefix == null) {
            return Optional.empty();
        }
        String relative = root.relativize(file).toString().replace(java.io.File.separatorChar, '/');
        return Optional.of(UriUtils.encodePath(internalLocationPrefix + relative, StandardCharsets.UTF_8));
    }

    /**
     * ISO-BMFF {@code ftyp} 브랜드로 컨테이너를 판별한다.
     *
     * <p>등록이 허용하는 컨테이너는 mp4·mov 뿐이고 원본은 확장자 없이 {@code original} 이름으로 저장되므로 파일 이름으로는 구분할 수 없다. 컨테이너를 DB 에 남기지 않으므로 헤더를
     * 직접 읽는다. 판별하지 못하면 mp4 로 본다.
     */
    private static String contentType(Path file, long size) {
        if (size < FTYP_HEADER_BYTES) {
            return DEFAULT_CONTENT_TYPE;
        }
        byte[] header = new byte[FTYP_HEADER_BYTES];
        try (InputStream source = Files.newInputStream(file)) {
            if (source.readNBytes(header, 0, FTYP_HEADER_BYTES) < FTYP_HEADER_BYTES) {
                return DEFAULT_CONTENT_TYPE;
            }
        } catch (IOException unreadable) {
            return DEFAULT_CONTENT_TYPE;
        }
        if (header[4] != 'f' || header[5] != 't' || header[6] != 'y' || header[7] != 'p') {
            return DEFAULT_CONTENT_TYPE;
        }
        String brand = new String(header, 8, 4, StandardCharsets.US_ASCII);
        return brand.startsWith("qt") ? QUICKTIME_CONTENT_TYPE : DEFAULT_CONTENT_TYPE;
    }

    private record LocalMediaAsset(Path file, String contentType, long sizeBytes, Optional<String> internalLocation)
            implements MediaAsset {

        @Override
        public void writeTo(OutputStream target, long offset, long count) {
            if (offset < 0 || count < 0 || offset + count > sizeBytes) {
                throw new BusinessException(ClipMediaErrorCode.MEDIA_READ_FAILED);
            }
            if (count == 0) {
                return;
            }
            try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
                // target 을 감싼 채널은 닫지 않는다. 닫으면 응답 스트림까지 닫힌다.
                WritableByteChannel sink = Channels.newChannel(target);
                long written = 0;
                while (written < count) {
                    long transferred = channel.transferTo(offset + written, count - written, sink);
                    if (transferred <= 0) {
                        break;
                    }
                    written += transferred;
                }
                if (written < count) {
                    throw new IOException("요청 구간보다 적은 바이트를 전송했습니다.");
                }
            } catch (IOException failure) {
                throw new BusinessException(ClipMediaErrorCode.MEDIA_READ_FAILED, failure);
            }
        }
    }
}
