package com.npick.clip.application.port;

import java.util.List;

/** 파일 검사 전에 현재 사용자의 reviewer 권한을 확인하고 서버 생성 ID와 활성 실행 정의를 제공한다. */
public interface ClipRegistrationContextPort {
    Context requireAuthorizedContext();

    record Context(
            long registeredById, long clipId, long pipelineRunId, String pipelineVersion, List<String> stageNames) {
        public Context {
            if (registeredById <= 0 || clipId <= 0 || pipelineRunId <= 0) {
                throw new IllegalArgumentException("인증된 등록자와 서버 생성 ID가 필요합니다.");
            }
            if (pipelineVersion == null
                    || pipelineVersion.isBlank()
                    || pipelineVersion.length() > 128
                    || stageNames == null
                    || stageNames.isEmpty()
                    || stageNames.stream().anyMatch(name -> name == null || name.isBlank())
                    || stageNames.stream().distinct().count() != stageNames.size()) {
                throw new IllegalArgumentException("활성 파이프라인 실행 정의가 필요합니다.");
            }
            stageNames = List.copyOf(stageNames);
        }
    }
}
