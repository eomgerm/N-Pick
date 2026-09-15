package com.npick.tag.application.port;

import java.util.Optional;

/** 태그 교정 후보 생성 전제를 feedback 쪽에서 읽는 아웃바운드 포트. 단방향 읽기 전용 의존이다. */
public interface TagContextPort {

    Optional<TagContext> find(long feedbackId);
}
