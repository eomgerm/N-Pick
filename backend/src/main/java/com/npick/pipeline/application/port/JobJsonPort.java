package com.npick.pipeline.application.port;

import java.util.Map;

public interface JobJsonPort {
    String hash(Map<String, ?> json);
}
