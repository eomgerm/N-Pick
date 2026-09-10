-- 태그 판정 실측용 표본. 테스트 트랜잭션 안에서만 존재하고 종료 후 롤백한다.
INSERT INTO member VALUES (1,'reviewer','test-only-not-a-real-password','검수자','reviewer',now(),now());

INSERT INTO clip (clip_id,source_type,storage_key,content_hash,transcript_source,registered_by_id,created_at,updated_at,deleted_at) VALUES
 (10,'broadcast','test/10',repeat('a',64),'provided',1,now(),now(),NULL),
 (11,'broadcast','test/11',repeat('b',64),'provided',1,now(),now(),NULL),
 -- 논리 삭제된 클립. 클립 전체 태그도 활성 처리도 있지만 검색 대상이 아니다 (FRD §6.1)
 (12,'broadcast','test/12',repeat('c',64),'provided',1,now(),now(),now());

-- clip 10 은 재처리(processing_no 2)를 거쳤고 활성 처리는 21 이다. 20 의 장면은 검색 대상이 아니다.
INSERT INTO pipeline_run (pipeline_run_id,clip_id,processing_no,pipeline_version,status,stage_states_json,created_at,updated_at) VALUES
 (20,10,1,'test-v1','succeeded','{}',now(),now()),
 (21,10,2,'test-v1','succeeded','{}',now(),now()),
 (22,11,1,'test-v1','succeeded','{}',now(),now()),
 (23,12,1,'test-v1','succeeded','{}',now(),now());
UPDATE clip SET active_pipeline_run_id=21 WHERE clip_id=10;
UPDATE clip SET active_pipeline_run_id=22 WHERE clip_id=11;
UPDATE clip SET active_pipeline_run_id=23 WHERE clip_id=12;

INSERT INTO scene (scene_id,clip_id,pipeline_run_id,start_time_ms,end_time_ms,shot_type,created_at,updated_at) VALUES
 (30,10,21,0,1000,'b_roll',now(),now()),
 (31,10,21,1000,2000,'interview',now(),now()),
 -- 폐기된 처리의 장면. 클립 태그를 상속하면 안 된다
 (32,10,20,0,1000,'b_roll',now(),now()),
 (33,11,22,0,1000,'anchor',now(),now()),
 -- 삭제된 클립의 장면
 (34,12,23,0,1000,'b_roll',now(),now());

INSERT INTO tag (tag_id,tag_type,match_value,name) VALUES
 (7,'event','포항지진','포항 지진'),
 (8,'broadcast_date','2026-03-15','2026-03-15'),
 (9,'person','홍길동','홍길동'),
 (10,'broadcast_date','2026-04-20','2026-04-20');

INSERT INTO tagging (tagging_id,clip_id,scene_id,tag_id,created_at) VALUES
 -- 클립 전체 태그. 활성 처리의 장면 30·31 에 상속된다
 (100,10,NULL,7,now()),
 -- 그중 장면 31 만 반려하는 장면별 예외
 (101,10,31,7,now()),
 (102,11,33,8,now()),
 -- 삭제된 클립의 클립 전체 태그
 (103,12,NULL,7,now()),
 (104,11,NULL,10,now()),
 (105,10,30,9,now());

INSERT INTO tag_evidence (evidence_id,tagging_id,source,confidence,verification_status,source_ref_type,source_ref_id,created_at,source_feedback_id) VALUES
 (200,100,'vlm',0.8000,'unverified',NULL,NULL,now(),NULL),
 (202,102,'original_metadata',1.0000,'verified',NULL,NULL,now(),NULL),
 (203,103,'vlm',0.9000,'unverified',NULL,NULL,now(),NULL),
 (204,104,'ocr',0.9500,'verified',NULL,NULL,now(),NULL),
 -- 추정 근거인데 verified 로 저장돼 있다. ck_evidence_review_shape 가 허용하는 조합이며
 -- 판정기가 미검증으로 떨어뜨려야 한다 (F-04)
 (205,105,'asr',0.9900,'verified',NULL,NULL,now(),NULL);

-- 사람 판단은 원인 신고를 연결해야 한다 (ck_evidence_review_shape: source_feedback_id NOT NULL)
INSERT INTO search_execution (search_execution_id,searched_by_id,query_text,normalized_query,
    explicit_filters_json,normalized_filters_json,query_fingerprint,normalization_version,
    execution_type,status,degraded_reasons_json,applied_excludes_json,search_config_json,
    config_version,created_at,updated_at) VALUES
 (300,1,'포항 지진','포항 지진','{}','{}',repeat('d',64),'test-v1','original','succeeded','[]','[]','{}','test-v1',now(),now());

INSERT INTO search_result (search_result_id,search_execution_id,scene_id,result_rank,explain_json) VALUES
 (400,300,31,1,'{}');

INSERT INTO feedback (feedback_id,search_result_id,created_by_id,status,resolution,created_at,updated_at) VALUES
 (500,400,1,'CLOSED','tag_correction',now(),now());

INSERT INTO tag_evidence (evidence_id,tagging_id,source,confidence,verification_status,source_ref_type,source_ref_id,created_at,source_feedback_id) VALUES
 (201,101,'reviewer_feedback',NULL,'rejected',NULL,NULL,now(),500);
