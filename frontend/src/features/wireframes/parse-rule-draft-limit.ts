import type { Chip } from '@/features/wireframes/interpretation-edit';

/** 한 번에 담을 수 있는 해석 교정 수. 서버도 같은 상한을 넘는 후보를 거부한다 (SRCH_400_203). */
export const MAX_PARSE_RULE_DRAFTS = 10;

/** 지금 담길 교정 수. 값을 입력 중인 새 빈 칩도 한 자리를 차지해, 추가 버튼 상한에 함께 센다. */
export function countParseRuleDrafts(ruleCount: number, chips: Chip[]): number {
  return ruleCount + chips.filter((chip) => chip.isNew && !chip.value.trim()).length;
}

export interface DraftLimitStatus {
  /** 버튼 옆에 보이는 `3/10`. */
  label: string;
  isAtLimit: boolean;
  /** 상한에 닿거나 넘었을 때 알릴 문구. 여유가 있으면 빈 문자열. */
  message: string;
}

export function draftLimitStatus(count: number): DraftLimitStatus {
  const max = MAX_PARSE_RULE_DRAFTS;
  let message = '';
  if (count > max) {
    message = `교정은 한 번에 ${max}개까지 담을 수 있습니다. 편집을 ${count - max}개 줄여 주세요.`;
  } else if (count === max) {
    message = `교정을 ${max}개까지 모두 채웠습니다. 더 추가하려면 기존 편집을 줄여 주세요.`;
  }
  return { label: `${count}/${max}`, isAtLimit: count >= max, message };
}
