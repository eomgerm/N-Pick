package com.npick.search.application.query.guard;

/** 명백히 잘못된 결과를 제외한다 (FRD v3.2 F-06, S15P21A501-56). */
public interface ApplyFalseHitGuardUseCase {

    FalseHitGuardResult apply(ApplyFalseHitGuardQuery query);
}
