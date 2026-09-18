package com.npick.search.application.query.search;

import java.util.concurrent.TimeUnit;

/**
 * 검색 한 번의 소요 시간을 경로별로 남긴다 (FRD §8.2).
 *
 * <p>application 이 측정 수단을 직접 알지 않게 포트로 둔다. Micrometer 든 로그든 바뀌는 것은 infrastructure 쪽이고, 조립이 아는 것은 「이 검색은 이 경로였고 이만큼
 * 걸렸다」뿐이다.
 */
public interface SearchDurationTimer {

    void record(long duration, TimeUnit unit, SearchPath path);
}
