import type {
  ClipSourceType,
  RegistrationFieldErrors,
} from '@/features/wireframes/video-registration-api';
import { seoulToday } from '@/lib/seoul-date';

export interface RegistrationDates {
  sourceType: ClipSourceType;
  broadcastDate: string;
  filmedDate: string;
}

export type RegistrationDateErrors = Pick<RegistrationFieldErrors, 'broadcastDate' | 'filmedDate'>;

export interface RegistrationDateBounds {
  broadcastMax?: string;
  broadcastMin?: string;
  filmedMax?: string;
}

/**
 * 등록 서비스가 보는 오늘. 서버가 Asia/Seoul 로 판정하므로 화면도 같은 날짜를 써야 한다. 기기 시간대를
 * 그대로 쓰면 KST 보다 앞선 기기에서 서버가 거부할 날짜를 통과시킨다.
 */
export const registrationToday = seoulToday;

/**
 * 날짜 피커가 아예 고를 수 없게 막는 범위. 타이핑한 값은 여전히 validateRegistrationDates 가 잡는다.
 *
 * today 는 마운트 후에 넣는다. 렌더 중에 읽으면 서버 시간대와 브라우저 시간대가 달라 hydration 이 어긋난다.
 */
export function registrationDateBounds(
  dates: RegistrationDates,
  today: string,
): RegistrationDateBounds {
  if (!today) return {};
  const isBroadcast = dates.sourceType === 'broadcast';
  const broadcastDate = isBroadcast ? dates.broadcastDate : '';
  // 미래 촬영일을 아래 끝으로 쓰면 min > max 가 되어 방송일 피커에 고를 수 있는 날이 사라진다.
  const filmedDate = dates.filmedDate <= today ? dates.filmedDate : '';
  return {
    broadcastMax: isBroadcast ? today : undefined,
    broadcastMin: isBroadcast ? filmedDate || undefined : undefined,
    filmedMax: broadcastDate && broadcastDate < today ? broadcastDate : today,
  };
}

/**
 * YYYY-MM-DD 는 사전순 비교가 곧 날짜 비교다.
 *
 * 통과한 날짜도 키를 비운 채로 내보낸다. 두 날짜의 관계로 정해지는 오류라서, 한쪽을 고쳤을 때 반대쪽에
 * 남아 있던 이전 안내를 호출부가 덮어쓸 수 있어야 한다.
 */
export function validateRegistrationDates(
  dates: RegistrationDates,
  today: string = registrationToday(),
): RegistrationDateErrors {
  const errors: RegistrationDateErrors = { broadcastDate: undefined, filmedDate: undefined };
  const filmedDate = dates.filmedDate;
  // 자료 영상은 방송일을 전송하지 않으므로 화면에 남은 값도 검사하지 않는다.
  const broadcastDate = dates.sourceType === 'broadcast' ? dates.broadcastDate : '';

  if (filmedDate && filmedDate > today) {
    errors.filmedDate = '촬영일은 오늘 이후 날짜로 입력할 수 없습니다.';
  }
  if (broadcastDate && broadcastDate > today) {
    errors.broadcastDate = '방송일은 오늘 이후 날짜로 입력할 수 없습니다.';
  } else if (broadcastDate && filmedDate && broadcastDate < filmedDate) {
    errors.broadcastDate = '방송일은 촬영일보다 빠를 수 없습니다.';
  }
  return errors;
}
