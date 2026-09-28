import { ApiClientError } from '@/lib/api/error';

export interface SearchErrorPresentation {
  message: string;
  followUp: string;
}

const retryableSearchError: SearchErrorPresentation = {
  message: '검색 서비스를 잠시 이용하기 어려워요.',
  followUp: '잠시 후 같은 조건으로 다시 검색할 수 있어요.',
};

const unexpectedSearchError: SearchErrorPresentation = {
  message: '검색 중 예상하지 못한 문제가 발생했어요.',
  followUp: '같은 문제가 반복되면 담당자 확인이 필요해요.',
};

const searchErrorMessages: Readonly<Partial<Record<string, SearchErrorPresentation>>> = {
  SRCH_400_101: {
    message: '입력한 내용만으로는 검색하기 어려워요.',
    followUp: '인물, 장소, 사건처럼 찾으려는 대상을 포함하면 검색할 수 있어요.',
  },
  SRCH_400_003: {
    message: '날짜 조건이 완성되지 않았어요.',
    followUp: '시작일과 종료일을 함께 선택하면 검색할 수 있어요.',
  },
  SRCH_400_004: {
    message: '날짜 범위를 확인할 부분이 있어요.',
    followUp: '시작일은 종료일과 같거나 이전 날짜여야 해요.',
  },
  // 검색 기록 상세(GET /search/history/{id})가 없는·남의 기록에 404 로 준다. 이 화면의 오류도
  // SearchErrorToast 를 타므로 여기 매핑한다. 없으면 retryable 로 떨어져 재시도를 권하는데,
  // 없는 기록엔 재시도가 소용없다 — 목록을 새로 부르라고 안내한다 (S15P21A501-262/-285 교차).
  SRCH_404_001: {
    message: '이 검색 기록을 찾을 수 없어요.',
    followUp: '목록을 새로 불러오면 최신 기록을 볼 수 있어요.',
  },
  SRCH_503_011: retryableSearchError,
  SRCH_503_012: retryableSearchError,
  SRCH_503_013: retryableSearchError,
  SRCH_503_201: retryableSearchError,
  SRCH_503_301: retryableSearchError,
  SRCH_500_002: unexpectedSearchError,
  SRCH_500_003: unexpectedSearchError,
  SRCH_500_004: unexpectedSearchError,
};

export function presentSearchError(error: unknown): SearchErrorPresentation {
  if (!(error instanceof ApiClientError)) {
    return {
      message: '검색을 완료하지 못했어요.',
      followUp: '잠시 후 같은 조건으로 다시 검색할 수 있어요.',
    };
  }

  const mapped = searchErrorMessages[error.code];
  if (mapped) return mapped;

  if (error.kind === 'network') {
    return {
      message: '검색 서버에 연결되지 않았어요.',
      followUp: '네트워크 연결이 복구되면 다시 검색할 수 있어요.',
    };
  }

  if (error.kind === 'aborted') {
    return {
      message: '검색 요청이 취소됐어요.',
      followUp: '검색어와 날짜 조건은 그대로 남아 있어요.',
    };
  }

  if (error.status === 401) {
    return {
      message: '로그인 상태를 확인하지 못했어요.',
      followUp: '다시 로그인하면 검색을 계속할 수 있어요.',
    };
  }

  if (error.status === 403) {
    return {
      message: '현재 계정으로는 검색할 수 없어요.',
      followUp: '검색 권한이 있는 계정인지 확인이 필요해요.',
    };
  }

  if (error.status >= 500 || error.kind === 'invalid-response') return retryableSearchError;

  if (error.status === 0 || error.status === 400 || error.status === 422) {
    return {
      message: '검색 조건을 확인할 부분이 있어요.',
      followUp: '검색어와 날짜 조건을 변경하면 다시 검색할 수 있어요.',
    };
  }

  return retryableSearchError;
}
