package com.npick.clip.application.port;

public interface RegistrationPermissionPort {
    void verify(boolean rightsConfirmed, boolean externalProcessingConfirmed);
}
