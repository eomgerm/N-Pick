-- S15P21A501-321 키워드 태그 표본. tag-resolution-fixture.sql 다음에 실행한다. 테스트 트랜잭션 안에서만 존재한다.
INSERT INTO scene (scene_id,clip_id,pipeline_run_id,start_time_ms,end_time_ms,shot_type,created_at,updated_at)
VALUES (35,11,22,1000,2000,'b_roll',now(),now());

INSERT INTO tag (tag_id,tag_type,match_value,name) VALUES
 (20,'keyword','전세사기','전세 사기'),
 -- 정보 없는 위치어. 제외 목록 값이라 어떤 질의로도 편입되면 안 된다
 (21,'keyword','앞','앞'),
 (22,'keyword','포항','포항');

INSERT INTO tagging (tagging_id,clip_id,scene_id,tag_id,created_at) VALUES
 -- 캡션·대사·OCR 이 없는 장면 33 에 키워드만 있다
 (120,11,33,20,now()),
 (121,11,35,21,now()),
 (122,10,30,22,now());

INSERT INTO tag_evidence (evidence_id,tagging_id,source,confidence,verification_status,created_at) VALUES
 (220,120,'vlm',0.7000,'unverified',now()),
 (221,121,'vlm',0.7000,'unverified',now()),
 (222,122,'vlm',0.7000,'unverified',now());
