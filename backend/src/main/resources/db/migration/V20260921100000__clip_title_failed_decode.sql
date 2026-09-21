-- S15P21A501-226. UTF-8 아닌 본문(CP949)으로 들어온 등록 요청의 제목이 그대로 저장돼 있다.
-- dev 기준 clip 64행 중 53행이며, 읽지 못한 바이트 자리마다 U+FFFD 만 남아 있다.
--
-- **되돌릴 수 없다.** U+FFFD 하나는 사라진 바이트 하나를 뜻할 뿐 그 값을 담고 있지 않고, 원본
-- 파일명·요청 본문을 남기는 테이블도 없다(registration_request 는 해시만 가진다). 그래서 복원이
-- 아니라 제거다. title 은 nullable 이고 화면은 없는 제목을 「제목 없는 영상」으로 이미 대체하므로
-- (search-results-api.ts), 깨진 글자를 남겨 두는 것보다 없다고 말하는 편이 정확하다. 제목이
-- 필요한 클립은 등록자가 다시 넣는다.
--
-- 재유입은 InitialClipRegistration 이 막는다. 여기서 CHECK 제약을 걸지 않는 이유는 그 거절이
-- 500 이 아니라 CLIP_400_004 여야 하기 때문이다.
UPDATE npick.clip
SET title = NULL,
    updated_at = now()
WHERE title LIKE '%' || chr(65533) || '%';
