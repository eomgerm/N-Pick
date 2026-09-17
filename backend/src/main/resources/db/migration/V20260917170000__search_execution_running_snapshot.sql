-- 리졸버 호출 전에 원문과 실행자만으로 running 기록을 남긴다.
-- 해석·필터·설정 snapshot은 이후 독립 트랜잭션에서 채워진다.
ALTER TABLE npick.search_execution
    ALTER COLUMN normalized_query DROP NOT NULL,
    ALTER COLUMN explicit_filters_json DROP NOT NULL,
    ALTER COLUMN normalized_filters_json DROP NOT NULL,
    ALTER COLUMN query_fingerprint DROP NOT NULL,
    ALTER COLUMN normalization_version DROP NOT NULL,
    ALTER COLUMN degraded_reasons_json DROP NOT NULL,
    ALTER COLUMN applied_excludes_json DROP NOT NULL,
    ALTER COLUMN search_config_json DROP NOT NULL,
    ALTER COLUMN config_version DROP NOT NULL;

-- 위 9개는 running·failed 행을 위해서만 완화한 것이다. 보상 제약이 없으면 스키마가
-- 「끝났는데 어떤 설정으로 돌았는지 모르는」 succeeded/degraded 행을 허용하게 된다.
-- F-05 완료 기준(「검색 설정이 달라지면 사용한 설정과 버전을 구분할 수 있다」)이 기대는 값이라
-- 결과를 낸 실행에 한해 baseline 의 NOT NULL 불변식을 그대로 되돌린다.
--
-- failed 를 running 과 함께 예외로 두는 이유: FRD §6.2 의 실패 중 리졸버 장애·활성 규칙 조회 실패는
-- 해석이 오기 전에 끊긴다. 그 실행도 running 으로 방치하지 않고 닫아야 하는데
-- (SearchExecutionRecordPort#fail), 그 시점에는 정규화 질의도 설정 snapshot 도 없다.
-- 없는 값을 빈 값으로 지어내 제약을 통과시키는 것보다 「결과를 낸 실행만 완전하다」를 제약으로 말한다.
ALTER TABLE npick.search_execution
    ADD CONSTRAINT ck_execution_completed_snapshot CHECK (
        status IN ('running', 'failed')
        OR (normalized_query IS NOT NULL
            AND explicit_filters_json IS NOT NULL
            AND normalized_filters_json IS NOT NULL
            AND query_fingerprint IS NOT NULL
            AND normalization_version IS NOT NULL
            AND degraded_reasons_json IS NOT NULL
            AND applied_excludes_json IS NOT NULL
            AND search_config_json IS NOT NULL
            AND config_version IS NOT NULL));

-- search_result.explain_json 의 최상위 키를 네 개로 바로잡는다 (S15P21A501-59 와 공동 결정).
--
-- baseline 주석은 score·match·guard 셋만 적었다. 그런데 내 문의 기록(S15P21A501-207)과
-- 신고 상세(S15P21A501-198)가 요구하는 「당시 표시값 그대로」(제목·장면 설명·구간·날짜·shot_type·
-- scene_type)를 담을 자리가 그 셋에 없다. match 는 「무엇이 걸렸나」라 표시값을 접으면 근거와 화면값이
-- 한 키에 섞이고, 조회 시점에 장면을 다시 읽어 그리면 그 사이 교정이 반영돼 과거 기록이 바뀐다
-- (FRD §7.2). 그래서 display 를 네 번째 키로 두고 주석을 구현에 맞춘다.
--
-- baseline 을 직접 고치지 않는 이유: 이미 적용된 migration 을 수정하면 checksum 이 달라져
-- validate-on-migrate 가 켜진 기존 DB 의 기동이 실패한다 (backend/README.md).
COMMENT ON COLUMN "search_result"."explain_json" IS '왜 이 장면이 여기 있는가. {"score":점수 내역, "match":무엇이 걸렸나, "guard":걸러내기 판정, "display":검색 당시 화면에 나간 표시값}. 결과 카드를 그릴 때 항상 같이 읽어서 한 덩어리로 묶었다. display 는 조회 시점에 다시 계산하지 않고 그대로 되돌려준다.';
