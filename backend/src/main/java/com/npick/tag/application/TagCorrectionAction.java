package com.npick.tag.application;

/**
 * 검수자 태그 교정 작업(F-10). 각 작업은 tag_evidence 의 verification_status 하나로 저장된다.
 *
 * <p>F-10 의 네 작업과의 대응: 추가·복원(재승인)={@link #APPROVE}, 반려={@link #REJECT}, 개입 해제={@link #WITHDRAW}. 교체는 반려와 추가 두 작업을 한 요청에
 * 담아 원자적으로 처리한다.
 */
public enum TagCorrectionAction {
    APPROVE("verified"),
    REJECT("rejected"),
    WITHDRAW("withdrawn");

    private final String verificationStatus;

    TagCorrectionAction(String verificationStatus) {
        this.verificationStatus = verificationStatus;
    }

    public String verificationStatus() {
        return verificationStatus;
    }

    /** 저장된 {@code verification_status} 를 작업으로 되돌린다. 검수자 판단이 아닌 값(unverified 등)이면 {@code null}. */
    public static TagCorrectionAction fromVerificationStatus(String verificationStatus) {
        for (TagCorrectionAction action : values()) {
            if (action.verificationStatus.equals(verificationStatus)) {
                return action;
            }
        }
        return null;
    }
}
