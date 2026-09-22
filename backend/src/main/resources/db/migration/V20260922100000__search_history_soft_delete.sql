-- S15P21A501-276. 「내 검색 기록」에서 기록을 지우는 수단. 행을 지우지 않고 숨긴다.
--
-- search_execution 은 기록 화면 전용 표가 아니다. 감사 조회(GET /search/executions/{id})가 같은 행을 읽고,
-- 신고·검수가 문의 당시 실행과 result_snapshot·explain_json 을 참조한다. 행을 지우면 문의가 당시 결과를 잃는다.
-- FRD v3.2 도 「참조 중인 … 검색 … 기록을 지우는 하드 삭제 화면은 만들지 않는다」로 같은 원칙을 쓴다.
--
-- clip.deleted_at 과 같은 수단이다. boolean 이 아니라 시각으로 두는 이유는 FRD 가 운영 보존기간·삭제 절차를
-- 운영 전에 확정하기로 열어 두었기 때문이다 — 그때 기준이 될 값이 필요하다.
ALTER TABLE "search_execution" ADD COLUMN "deleted_at" timestamptz;

COMMENT ON COLUMN "search_execution"."deleted_at" IS
    '내 검색 기록에서 숨긴 시각. 하드 삭제를 하지 않으므로 유일한 삭제 수단이다. 감사 조회와 신고·검수의 참조는 이 값과 무관하게 보존한다';

-- 목록 조회는 소유자별 최신순 고정이며(ORDER BY created_at DESC, search_execution_id DESC) 여기에 숨김 제외가
-- 붙는다. 숨긴 행은 색인에 넣지 않는다 — clip 의 uq_clip_content_hash_alive 와 같은 방식이다.
CREATE INDEX ix_search_execution_owner_alive
    ON search_execution (searched_by_id, created_at DESC, search_execution_id DESC)
    WHERE deleted_at IS NULL;

-- baseline 300행의 ix_execution_searcher_created (searched_by_id, created_at DESC) 를 지운다.
-- 위 인덱스가 같은 선두 컬럼에 정렬 컬럼을 하나 더 얹은 형태라 기록 목록은 이제 그쪽으로 간다.
--
-- searched_by_id 를 조건으로 쓰는 나머지 네 곳은 모두 PK 로 search_execution 에 닿은 뒤 값을 확인하는
-- 경로라 이 인덱스를 드라이빙으로 쓰지 않는다.
--   MyInquiryListQueryAdapter    — feedback.created_by_id 로 출발(ix_feedback_creator), se 는 PK 조인
--   MyInquiryDetailQueryAdapter  — feedback_id PK
--   FeedbackJpaRepository        — search_result_id PK
--   JdbcSearchExecutionDetailQueryAdapter — search_execution_id PK
--
-- search_execution 은 검색 1회마다 INSERT 와 UPDATE 가 붙는 표라 읽히지 않는 색인을 남길 이유가 없다.
-- 되살릴 일이 생기면 baseline 300행과 같은 정의로 다시 만들면 된다.
DROP INDEX ix_execution_searcher_created;
