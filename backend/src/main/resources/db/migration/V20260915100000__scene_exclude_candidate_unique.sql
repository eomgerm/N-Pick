-- 장면 제외 후보 멱등을 (source_feedback_id, target_scene_id) 로 보장한다 (S15P21A501-82 리뷰 반영).
-- exclude_scene 후보는 내용이 전부 신고 컨텍스트에서 파생되므로(대상 장면·지문 모두 서버 상태) 한 신고에 의미 있는 후보는 장면당 하나뿐이다.
-- request_key(-81 공유 컬럼) 단위 멱등만으로는 FE 가 새 Idempotency-Key 로 재시도할 때 동일 후보가 중복 생성돼,
-- 이후 검증(-83)·확정(-85)이 어느 행을 골라야 하는지 모호해진다.
-- patch_parse 는 본문(condition/patch)이 재시도마다 달라질 수 있어 request_key 단위가 맞으므로 이 인덱스는 exclude_scene 에만 건다(부분 인덱스).
CREATE UNIQUE INDEX uq_search_rule_exclude_feedback_scene
    ON npick.search_rule (source_feedback_id, target_scene_id)
    WHERE action = 'exclude_scene';
