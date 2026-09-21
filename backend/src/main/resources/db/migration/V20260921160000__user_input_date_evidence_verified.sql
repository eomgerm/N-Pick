-- S15P21A501-231. 등록 때 입력한 방송일·촬영일 근거가 source = 'user_input' 인데 verification_status 는
-- 'unverified' 로 저장돼 있다. FalseHitGuardPolicy 는 검증된 날짜만 충돌 근거로 보므로, 범위를 지정해도
-- 범위 밖 장면이 한 건도 제외되지 않았다.
--
-- F-04 가 기본 미검증으로 두는 것은 ASR·VLM·일반 추론 규칙의 추정 근거다. 사용자 입력은 원본 정보·CC·OCR 과
-- 같은 관측 근거이므로 대상이 아니다. 재유입은 InitialClipRegistration.DateEvidence 가 막는다.
--
-- 대상을 세 가지로 좁힌다.
--   source = 'user_input'  — 추정 근거(asr·vlm·rule)와 사람 판단(reviewer_feedback)을 모두 제외한다.
--                            reviewer_feedback 은 ck_evidence_review_shape 가 이미 다른 source 로 가른다.
--   verification_status = 'unverified' — 이미 옳은 행은 건드리지 않는다.
--   tag_type IN (날짜 2종) — 같은 출처의 다른 태그 종류는 이 결함과 무관하다.
UPDATE npick.tag_evidence e
SET verification_status = 'verified'
FROM npick.tagging g
    JOIN npick.tag t USING (tag_id)
WHERE e.tagging_id = g.tagging_id
  AND e.source = 'user_input'
  AND e.verification_status = 'unverified'
  AND t.tag_type IN ('broadcast_date', 'filmed_date');
