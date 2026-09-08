package com.npick.clip.domain.repository;

import com.npick.clip.domain.model.InitialClipRegistration;

public interface ClipRegistrationRepository {
    void save(InitialClipRegistration registration);
}
