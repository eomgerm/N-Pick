package com.npick.tag.application.query;

import java.util.Collection;
import java.util.List;

import com.npick.tag.domain.model.TagJudgment;

/**
 * 판정에 필요한 근거 줄을 긁어오는 계약 (설계 정본 §9 의 QueryPort).
 *
 * <p><b>판정을 하지 않는다.</b> 최신 판단 고르기·개입 해제 처리·검증 상태 산출은 전부 {@code TagResolutionPolicy} 가 한다. 여기서 하는 일은 상속을 펼치고, 검색 대상이 아닌
 * 장면(폐기된 처리·논리 삭제된 클립)을 빼는 것뿐이다.
 *
 * <p>두 메서드는 같은 SQL 의 {@code WHERE} 만 다르다. 반환 형태가 같으므로 포트를 둘로 쪼개지 않는다.
 *
 * <p>구현은 주변 트랜잭션에 참여해야 한다(같은 {@code DataSource}·커넥션). 후보 검증 검색(F-12)이 한 트랜잭션 안에서 후보를 적용하고 검색한 뒤 되돌리는 방식이라(FRD §11), 다른
 * 커넥션에서 읽으면 그 적용이 보이지 않고 오류 없이 "바뀐 것이 없다" 가 된다.
 *
 * <p><b>후보 저장 방식은 S15P21A501-160 에서 바뀌었다.</b> 검수자 태그 교정 후보는 임시 {@code INSERT}→{@code ROLLBACK} 이 아니라
 * {@code tag_evidence.confirmed=false} 로 <b>내구성 있게</b> 저장된다. 이 리더는 {@code e.confirmed} 로 그 후보를 일반 검색에서 제외한다. F-12 검증
 * 검색(S15P21A501-83)이 후보를 보려면 그 필터를 여는 경로가 필요하다(현재 미구현).
 */
public interface FindTagJudgmentsQueryPort {

    /**
     * 주어진 장면들의 모든 태그 근거.
     *
     * <p>명시 필터 비교·구조화 축 점수·근거 설명이 쓰는 방향이다.
     */
    List<TagJudgment> findByScenes(Collection<Long> sceneIds);

    /**
     * 조건에 맞는 태그가 붙은 장면들의 근거.
     *
     * <p>후보 추출이 쓰는 방향이다. 조건에 맞는 태그 하나에 대해 <b>장면 태깅과 클립 태깅의 근거를 모두</b> 돌려줘야 한다 — 판정기가 두 범위를 함께 봐야 하기 때문이다.
     */
    List<TagJudgment> findByConditions(List<TagCondition> conditions);
}
