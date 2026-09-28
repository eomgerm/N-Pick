package com.npick.pipeline.infrastructure.json;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import com.npick.pipeline.application.port.JobJsonPort;

@Component
public class JobJsonAdapter implements JobJsonPort {
    private final JsonMapper mapper = JsonMapper.builder().build();

    public String hash(Map<String, ?> value) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256")
                            .digest(mapper.writeValueAsString(sorted(value)).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private Object sorted(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new TreeMap<>();
            map.forEach((key, item) -> result.put((String) key, sorted(item)));
            return result;
        }
        if (value instanceof List<?> list)
            return list.stream().map(this::sorted).toList();
        return value;
    }
}
