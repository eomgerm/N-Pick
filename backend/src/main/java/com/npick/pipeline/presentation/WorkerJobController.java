package com.npick.pipeline.presentation;

import java.util.Map;
import java.util.UUID;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.DeferredResult;

import com.npick.common.response.ApiResponse;
import com.npick.pipeline.application.command.worker.ClaimWorkerJobUseCase;
import com.npick.pipeline.application.command.worker.CompleteWorkerJobUseCase;
import com.npick.pipeline.application.command.worker.DownloadWorkerArtifactUseCase;
import com.npick.pipeline.application.command.worker.HeartbeatWorkerJobUseCase;
import com.npick.pipeline.application.command.worker.UploadWorkerArtifactUseCase;

/** Registered explicitly only with a real execution-port binding, or a test configuration. */
@RestController
@ConditionalOnProperty(name = "npick.worker-jobs.enabled", havingValue = "true")
@RequestMapping("/api/v1/internal/jobs")
public class WorkerJobController {
    private final ClaimWorkerJobUseCase claims;
    private final HeartbeatWorkerJobUseCase heartbeats;
    private final CompleteWorkerJobUseCase completions;
    private final UploadWorkerArtifactUseCase uploads;
    private final DownloadWorkerArtifactUseCase downloads;

    public WorkerJobController(
            ClaimWorkerJobUseCase claims,
            HeartbeatWorkerJobUseCase heartbeats,
            CompleteWorkerJobUseCase completions,
            UploadWorkerArtifactUseCase uploads,
            DownloadWorkerArtifactUseCase downloads) {
        this.claims = claims;
        this.heartbeats = heartbeats;
        this.completions = completions;
        this.uploads = uploads;
        this.downloads = downloads;
    }

    @PostMapping("/claim")
    public DeferredResult<ApiResponse<Map<String, Object>>> claim(
            @RequestHeader("X-Worker-Id") String worker, @RequestBody Map<String, Object> request) {
        var response = new DeferredResult<ApiResponse<Map<String, Object>>>(30000L);
        Thread.startVirtualThread(() -> {
            try {
                response.setResult(ApiResponse.success(claims.claim(worker, request)));
            } catch (Exception failure) {
                response.setErrorResult(failure);
            }
        });
        return response;
    }

    @PostMapping("/{run}/stages/{stage}/heartbeat")
    public ApiResponse<Map<String, Object>> heartbeat(
            @PathVariable long run,
            @PathVariable String stage,
            @RequestHeader("X-Worker-Id") String worker,
            @RequestBody Map<String, Object> request) {
        return ApiResponse.success(heartbeats.heartbeat(run, stage, worker, request));
    }

    @PostMapping("/{run}/stages/{stage}/complete")
    public ApiResponse<Map<String, Object>> complete(
            @PathVariable long run,
            @PathVariable String stage,
            @RequestHeader("X-Worker-Id") String worker,
            @RequestHeader("Idempotency-Key") String key,
            @RequestBody Map<String, Object> request) {
        return ApiResponse.success(completions.complete(run, stage, worker, key, request));
    }

    @PutMapping("/{run}/artifacts/{*key}")
    @ResponseStatus(HttpStatus.CREATED)
    public void upload(
            @PathVariable long run,
            @PathVariable String key,
            @RequestHeader("X-Worker-Id") String worker,
            @RequestHeader("X-Job-Lease-Id") UUID lease,
            @RequestHeader("X-Content-SHA256") String hash,
            HttpServletRequest request)
            throws java.io.IOException {
        uploads.upload(
                run, worker, lease, key.substring(1), request.getContentLengthLong(), hash, request.getInputStream());
    }

    @GetMapping("/{run}/artifacts")
    public void download(
            @PathVariable long run,
            @RequestParam String key,
            @RequestHeader("X-Worker-Id") String worker,
            @RequestHeader("X-Job-Lease-Id") UUID lease,
            HttpServletResponse response)
            throws java.io.IOException {
        response.setContentType("application/octet-stream");
        downloads.download(run, worker, lease, key, response.getOutputStream());
    }
}
