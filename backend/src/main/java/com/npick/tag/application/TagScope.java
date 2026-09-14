package com.npick.tag.application;

/** 태그 교정 적용 범위(F-10). 실제 장면·클립 id 는 신고 컨텍스트에서 오고, 검수자는 둘 중 하나만 고른다 — 임의의 장면·클립은 지정할 수 없다. */
public enum TagScope {
    SCENE,
    CLIP
}
