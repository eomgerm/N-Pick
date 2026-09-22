package com.npick.clip.application.query.list;

public interface GetClipsUseCase {
    /** registeredById 가 null 이면 등록자를 가리지 않는다. */
    GetClipsResult getClips(int page, int size, java.util.List<String> statuses, Long registeredById);

    default GetClipsResult getClips(int page, int size) {
        return getClips(page, size, java.util.List.of(), null);
    }
}
