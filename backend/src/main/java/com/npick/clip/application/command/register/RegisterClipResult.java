package com.npick.clip.application.command.register;

public record RegisterClipResult(long clipId, long pipelineRunId, String status, RegistrationOutcome outcome) {}
