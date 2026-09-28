package com.npick.pipeline.application.command;

import java.util.LinkedHashMap;
import java.util.Map;

import com.npick.pipeline.application.port.ClipMediaInputPort;

/** Attach transport input after #36 commits an assignment; preserve its prepared upstream data. */
public final class WorkerInputAssembler {
    private final ClipMediaInputPort media;
    private final boolean sharedVolume;

    public WorkerInputAssembler(ClipMediaInputPort media, boolean sharedVolume) {
        this.media = media;
        this.sharedVolume = sharedVolume;
    }

    public Map<String, Object> attach(Map<String, Object> assignment, boolean workerSharesVolume) {
        if (!Boolean.TRUE.equals(assignment.get("assigned"))) return assignment;
        Map<String, Object> job = object(assignment.get("job"));
        var source = media.get(Long.parseLong((String) job.get("clipId")));
        Map<String, Object> input = object(job.get("inputs"));
        boolean shared = sharedVolume && workerSharesVolume;
        Map<String, Object> reference = new LinkedHashMap<>();
        reference.put("storageKey", source.storageKey());
        reference.put("sizeBytes", source.sizeBytes());
        reference.put("transport", shared ? "shared-volume" : "http");
        if (shared) reference.put("localPath", source.storageKey());
        input.put("media", reference);
        input.putIfAbsent("config", Map.of());
        job.put("inputs", input);
        var response = new LinkedHashMap<>(assignment);
        response.put("job", job);
        return response;
    }

    private static Map<String, Object> object(Object value) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (value instanceof Map<?, ?> map) map.forEach((key, item) -> result.put((String) key, item));
        return result;
    }
}
