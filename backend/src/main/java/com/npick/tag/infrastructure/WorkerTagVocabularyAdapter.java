package com.npick.tag.infrastructure;

import org.springframework.stereotype.Component;

import com.npick.common.error.BusinessException;
import com.npick.pipeline.application.port.TagVocabularyPort;
import com.npick.tag.domain.model.TagMatchValue;
import com.npick.tag.domain.model.TagType;

/**
 * {@link TagVocabularyPort} 구현. 표기 정규화는 백엔드에 하나뿐인 {@link TagMatchValue} 를 지난다 — 구현이 둘이 되면 같은 값이 두 표기로
 * {@code UNIQUE(tag_type, match_value)} 를 통과하고 정확 일치 조회가 조용히 0건이 된다.
 */
@Component
public class WorkerTagVocabularyAdapter implements TagVocabularyPort {

    /** {@code tag.match_value} 의 컬럼 폭. 넘기면 INSERT 가 트랜잭션을 SQL 오류로 끊어 워커가 400 이 아니라 500 을 받는다. */
    private static final int MAX_MATCH_VALUE = 255;

    @Override
    public String matchValue(String type, String value) {
        TagType tagType;
        try {
            tagType = TagType.from(type);
        } catch (BusinessException unknown) {
            // 워커가 보낸 유형이 어휘 밖이면 태그 오류가 아니라 잘못된 단계 출력이다.
            // 그 판정은 부르는 쪽이 하므로 여기서는 "쓸 수 없다" 만 알린다.
            return null;
        }
        // 화면에 날짜가 보인다는 사실과 그것이 방송일·촬영일이라는 판단은 다르다(FRD §3 F-04).
        // 워커 schema 에 그 유형이 없으므로 여기 오는 것은 계약 위반이다.
        if (tagType.date()) return null;
        String match = TagMatchValue.normalize(value);
        return match.isBlank() || match.length() > MAX_MATCH_VALUE ? null : match;
    }
}
