package com.npick.clip.application.port;

import com.npick.clip.application.command.prepare.PrepareVideoResult;
import com.npick.clip.application.command.store.StoreVideoResult;

public interface VideoStoragePort {
    StoreVideoResult store(long clipId, PrepareVideoResult video);
}
