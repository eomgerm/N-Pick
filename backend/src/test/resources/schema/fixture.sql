-- 각 테스트의 트랜잭션 안에서만 사용하는 가상 데이터. 종료 후 롤백한다.
INSERT INTO member VALUES
 (1,'editor','test-only-not-a-real-password','편집기자','editor',now(),now()),
 (2,'reviewer','test-only-not-a-real-password','검수자','reviewer',now(),now());
INSERT INTO clip (clip_id,source_type,storage_key,content_hash,transcript_source,registered_by_id,created_at,updated_at) VALUES
 (10,'broadcast','test/10',repeat('a',64),'provided',2,now(),now()),
 (11,'archive','test/11',repeat('b',64),'none',2,now(),now());
INSERT INTO pipeline_run (pipeline_run_id,clip_id,processing_no,pipeline_version,status,stage_states_json,created_at,updated_at)
 VALUES (20,10,1,'test','succeeded','{}',now(),now());
INSERT INTO scene (scene_id,clip_id,pipeline_run_id,start_time_ms,end_time_ms,caption_tokens,shot_type,created_at,updated_at,embedding)
 VALUES (30,10,20,0,1000,'공장 화재','b_roll',now(),now(),array_fill(0.1::real,ARRAY[1024])::vector);
UPDATE clip SET active_pipeline_run_id=20 WHERE clip_id=10;
INSERT INTO keyframe VALUES (40,30,0,'test/frame');
INSERT INTO ocr_observation VALUES (50,40,'공장 화재','공장 화재',0.9,'{}');
INSERT INTO tag VALUES (60,'keyword','화재','화재');
INSERT INTO tagging VALUES (70,10,NULL,60,now()), (71,10,30,60,now());
INSERT INTO tag_evidence VALUES (80,70,'original_metadata',NULL,'verified',NULL,NULL,now(),NULL);
INSERT INTO search_execution (search_execution_id,searched_by_id,query_text,normalized_query,explicit_filters_json,
 normalized_filters_json,query_fingerprint,normalization_version,execution_type,status,degraded_reasons_json,
 applied_excludes_json,search_config_json,config_version,created_at,updated_at,applied_rules_json)
 VALUES (90,1,'화재','화재','{}','{}',repeat('c',64),'test','original','succeeded','[]','[]','{}','test',now(),now(),'[]');
INSERT INTO search_result VALUES (100,90,30,1,'{}');
INSERT INTO feedback (feedback_id,search_result_id,created_by_id,status,created_at,updated_at)
 VALUES (110,100,1,'reviewing',now(),now());
INSERT INTO search_rule VALUES
 (120,repeat('c',64),'화재','{}','test','patch_parse',NULL,110,false,now(),now(),'{}','[]');
