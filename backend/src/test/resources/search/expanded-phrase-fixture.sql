-- 확장어 구 단위 AND 표본 (S15P21A501-302).
-- scene-candidate-fixture.sql 위에 덧붙여 쓴다. 별도 파일인 이유는 dense 후보 조회 테스트가 같은
-- 기본 표본을 쓰면서 활성 장면 수를 절대값으로 단언하기 때문이다 — 단어 검색 사정으로 그 숫자를
-- 흔들지 않는다.
-- 기존 테스트의 토큰(화재·제설·속보·원인·단독)은 쓰지 않는다.
INSERT INTO scene (scene_id,clip_id,pipeline_run_id,start_time_ms,end_time_ms,caption_tokens,transcript_tokens,shot_type,created_at,updated_at) VALUES
 -- '중국' 만 있다. 「중국 음식」을 OR 로 쪼개면 이 장면이 후보가 된다 — 그것이 이 티켓의 결함이다
 (60,11,22,3000,4000,'중국 경제 성장',NULL,'b_roll',now(),now()),
 -- '중국' 과 '음식' 을 둘 다 가진 장면. 구 단위 AND 에서 유일하게 남아야 한다
 (61,11,22,4000,5000,'중국 음식 거리',NULL,'b_roll',now(),now()),
 -- '음식' 만 있다
 (62,11,22,5000,6000,'음식 축제',NULL,'b_roll',now(),now()),
 -- 단일 토큰 확장어가 종전대로 걸리는 자리 — 화재->불, 우천->비, 마운틴->산
 (63,11,22,6000,7000,'불 진화 현장',NULL,'b_roll',now(),now()),
 (64,11,22,7000,8000,'비 내리는 거리',NULL,'b_roll',now(),now()),
 -- 대사에만 있는 단일 토큰. 확장어 절이 캡션·대사 양쪽에 걸리는 것을 함께 본다
 (65,11,22,8000,9000,NULL,'산 능선 을 넘다','b_roll',now(),now());
