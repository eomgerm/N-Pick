package com.npick.search.application.port;

import java.util.Optional;

/** 후보 생성 전제를 feedback 쪽에서 읽는 아웃바운드 포트. rule → feedback 단방향 읽기 전용 의존이다(설계 §3). */
public interface ParseContextPort {

    Optional<ParseContext> find(long feedbackId);
}
