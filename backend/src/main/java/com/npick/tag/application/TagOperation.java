package com.npick.tag.application;

/**
 * 태그 교정 변경안 한 줄. 작업 종류·범위와 대상 태그를 담는다.
 *
 * @param action 승인·반려·개입 해제
 * @param scope 장면 범위인가 클립 범위인가
 * @param tagType 태그 유형
 * @param matchValue 검색용 정규화 값
 * @param displayName 표시 이름
 */
public record TagOperation(
        TagCorrectionAction action, TagScope scope, String tagType, String matchValue, String displayName) {}
