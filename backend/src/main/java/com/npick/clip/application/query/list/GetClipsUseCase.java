package com.npick.clip.application.query.list;

public interface GetClipsUseCase {
    GetClipsResult getClips(int page, int size, java.util.List<String> statuses);

    default GetClipsResult getClips(int page, int size) {
        return getClips(page, size, java.util.List.of());
    }
}
