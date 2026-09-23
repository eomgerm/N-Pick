import type { InquiryResolution } from '@/features/wireframes/inquiry-state';

/** 처리 판정 토글이 다룰 수 있는 두 상태. 구 세부 교정값과 deferred는 UI에서 선택할 수 없다. */
export type ResolutionToggleMode = 'no_action' | 'correction';

/** ON(교정) 여부. 교정 편집 영역을 보여줄지도 이 값으로 판단한다. */
export function isCorrectionMode(mode: ResolutionToggleMode): boolean {
  return mode === 'correction';
}

/** 스위치 on/off → 저장할 resolution 값. ON=correction, OFF=no_action. */
export function resolutionModeFromChecked(checked: boolean): ResolutionToggleMode {
  return checked ? 'correction' : 'no_action';
}

/**
 * 문의에 이미 저장된 resolution 값 → 토글 초기 상태.
 * 레거시 교정 3종은 ON으로, 종료 판정인 no_action·deferred와 미판정은 OFF로 취급한다.
 */
export function resolutionModeFromValue(
  resolution: InquiryResolution | null,
): ResolutionToggleMode {
  return resolution === 'no_action' || resolution === 'deferred' || resolution === null
    ? 'no_action'
    : 'correction';
}
