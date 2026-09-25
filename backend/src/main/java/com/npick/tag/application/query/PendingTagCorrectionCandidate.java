package com.npick.tag.application.query;

import com.npick.tag.application.TagCorrectionAction;
import com.npick.tag.application.TagScope;

/**
 * 확정 전 대기 중인 검수자 태그 교정 근거 하나 (S15P21A501-317). 생성 요청의 변경안 한 줄({@code TagOperation})을 되살릴 수 있는 형태다.
 *
 * @param evidenceId 대기 근거 id. 개별 취소({@code DELETE …/tag-correction-candidate/{evidenceId}})에 쓴다
 * @param taggingId 대상 태깅
 * @param action 저장된 판단(verified/rejected/withdrawn)을 되돌린 작업
 * @param scope 장면 태깅이면 SCENE, 클립 태깅이면 CLIP
 * @param tagType 태그 유형
 * @param matchValue 정규화된 검색용 값
 * @param displayName 태그 사전의 표시 이름. 이미 있던 태그를 재사용했으면 요청 때 보낸 표시 이름이 아니라 사전 값이다
 */
public record PendingTagCorrectionCandidate(
        long evidenceId,
        long taggingId,
        TagCorrectionAction action,
        TagScope scope,
        String tagType,
        String matchValue,
        String displayName) {}
