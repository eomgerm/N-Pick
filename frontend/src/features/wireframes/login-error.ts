import { ApiClientError } from '@/lib/api/error';
import { isInvalidCredentials } from '@/lib/auth/member';

export interface LoginErrorDialogContent {
  eyebrow: string;
  message: string;
  title: string;
}

export type LoginErrorPresentation =
  | { kind: 'invalid-credentials' }
  | { kind: 'dialog'; content: LoginErrorDialogContent }
  | { kind: 'inline' };

export function getLoginErrorPresentation(error: unknown): LoginErrorPresentation {
  if (isInvalidCredentials(error)) return { kind: 'invalid-credentials' };
  if (!(error instanceof ApiClientError)) return { kind: 'inline' };
  if (error.status === 403) {
    return {
      kind: 'dialog',
      content: {
        eyebrow: 'REQUEST EXPIRED',
        title: '로그인 요청 오류',
        message:
          '로그인 요청의 보안 정보가 만료되었습니다. 비밀번호를 다시 입력하고 시도해 주세요.',
      },
    };
  }
  if (error.status === 500) {
    return {
      kind: 'dialog',
      content: {
        eyebrow: 'SERVER ERROR',
        title: '서버 오류',
        message: '서버 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.',
      },
    };
  }
  return { kind: 'inline' };
}
