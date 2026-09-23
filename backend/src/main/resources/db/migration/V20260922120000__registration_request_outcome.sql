-- S15P21A501-283. 등록 응답이 신규 생성과 기존 clip 반환을 구분해 내보내는데(outcome), 같은
-- 멱등키의 재전송은 journal 을 재생하므로 그 구분을 journal 에서 되읽을 수 있어야 한다.
--
-- 재생 시점에는 판정 근거가 없다. 중복 반환도 journal 에 succeeded 로 남고(reserve 직후
-- recoverObservedResult 가 같은 content_hash 의 미해결 행을 한꺼번에 확정한다), 행에는 그것이
-- 이 요청이 만든 clip 인지 남의 clip 에 붙은 것인지 표시가 없다. clip 의 등록자만 보고 판정하면
-- 「본인이 같은 파일을 다른 키로 재등록한 뒤 그 키로 재전송」이 created 로 뒤집혀, 입력값이
-- 저장되지 않았다는 안내가 사라진다.
--
-- 그래서 확정 시점의 판정을 행에 적어 둔다. 응답 전용 표시이며 중복 판정 자체는 바꾸지 않는다
-- (같은 영상은 등록자와 무관하게 하나의 clip 으로 모인다 — S15P21A501-68, uq_clip_content_hash_alive).
--
-- nullable 이다. 값이 없는 경우는 둘이다. 이 컬럼 이전에 확정된 행, 그리고 등록자의 행이 생성 보고보다
-- 먼저 확정돼 확정 시점에 판정할 근거가 없던 행이다. 후자는 결과를 잃은 그 생성일 수 있어 비워 두며,
-- 두 경우 모두 재전송 때 clip 의 등록자로 판정한다. 그 폴백은 과도기 처리가 아니라 이 컬럼의 정상 경로다.
-- 기존 state/clip_id CHECK 와는 무관하게 더한다.
ALTER TABLE npick.registration_request ADD COLUMN outcome varchar(16)
    CHECK (outcome IN ('created', 'duplicate_own', 'duplicate_other'));

COMMENT ON COLUMN npick.registration_request.outcome IS
    '확정 당시의 등록 판정. created=이 요청이 clip 을 만듦, duplicate_own=본인이 이미 등록한 clip 을 돌려줌, duplicate_other=다른 사용자가 등록한 clip 을 돌려줌. 같은 키 재전송이 같은 안내를 재생하기 위한 응답 전용 값이다(S15P21A501-283). null 은 확정 시점에 판정할 근거가 없었다는 뜻이며(등록자의 행이 생성 보고보다 먼저 확정된 경우, 그리고 이 컬럼 이전 행) 재전송 때 clip 의 등록자로 판정한다';
