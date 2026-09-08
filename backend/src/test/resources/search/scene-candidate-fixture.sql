-- 단어 검색 실측용 표본 색인. 테스트 트랜잭션 안에서만 존재하고 종료 후 롤백한다.
-- 토큰 칸에는 워커가 Kiwi 로 만든 형태소를 공백으로 이어 넣는다(색인 토크나이저가 pdb.whitespace).
INSERT INTO member VALUES (1,'editor','test-only-not-a-real-password','편집기자','editor',now(),now());

INSERT INTO clip (clip_id,source_type,storage_key,content_hash,transcript_source,registered_by_id,created_at,updated_at) VALUES
 (10,'broadcast','test/10',repeat('a',64),'provided',1,now(),now()),
 (11,'broadcast','test/11',repeat('b',64),'provided',1,now(),now());

-- clip 10 은 재처리(processing_no 2)를 거쳤고 활성 처리는 21 이다. 20 의 장면은 검색 대상이 아니다.
INSERT INTO pipeline_run (pipeline_run_id,clip_id,processing_no,pipeline_version,status,stage_states_json,created_at,updated_at) VALUES
 (20,10,1,'test-v1','succeeded','{}',now(),now()),
 (21,10,2,'test-v1','succeeded','{}',now(),now()),
 (22,11,1,'test-v1','succeeded','{}',now(),now());
UPDATE clip SET active_pipeline_run_id=21 WHERE clip_id=10;
UPDATE clip SET active_pipeline_run_id=22 WHERE clip_id=11;

INSERT INTO scene (scene_id,clip_id,pipeline_run_id,start_time_ms,end_time_ms,caption_tokens,transcript_tokens,shot_type,created_at,updated_at) VALUES
 -- 캡션과 대사 양쪽에 '화재'
 (30,10,21,0,1000,'공장 화재 진압 소방관','소방관 이 화재 를 진압 하다','b_roll',now(),now()),
 -- '원인' 은 대사에만 있다
 (31,10,21,1000,2000,'기자 브리핑','공장 화재 원인 조사','interview',now(),now()),
 -- 폐기된 처리의 장면. '화재' 를 갖고 있지만 결과에 나오면 안 된다
 (32,10,20,0,1000,'옛 세대 공장 화재','옛 세대 화재','b_roll',now(),now()),
 (33,11,22,0,1000,'국회 본회의','예산 심의','b_roll',now(),now()),
 -- 캡션·대사에 '화재' 가 없고 화면 글자로만 걸린다
 (34,11,22,1000,2000,'거리 인터뷰',NULL,'interview',now(),now()),
 -- '제설' 은 캡션에만 있다
 (35,11,22,2000,3000,'눈 폭탄 제설',NULL,'b_roll',now(),now());

INSERT INTO keyframe VALUES (40,34,0,'test/f40'),(41,34,500,'test/f41'),(42,30,0,'test/f42');

INSERT INTO ocr_observation VALUES
 (50,40,'화재 현장','화재 현장',0.9,'{}'),
 -- '속보' 는 화면 글자에만 있다
 (51,41,'화재 속보','화재 속보',0.9,'{}'),
 (52,42,'단독','단독',0.9,'{}');
