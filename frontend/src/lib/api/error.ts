export type ApiClientErrorKind = 'http' | 'api' | 'network' | 'invalid-response' | 'aborted';

interface ApiErrorDetails {
  message?: unknown;
  code?: unknown;
  requestId?: unknown;
  diagnostics?: { response?: unknown; cause?: unknown };
}

const fallbackMessages: Record<ApiClientErrorKind, string> = {
  http: '요청을 처리하지 못했습니다. 잠시 후 다시 시도해 주세요.',
  api: '요청을 처리하지 못했습니다. 잠시 후 다시 시도해 주세요.',
  network: '서버에 연결할 수 없습니다. 네트워크 연결을 확인하고 다시 시도해 주세요.',
  'invalid-response': '서버 응답을 확인할 수 없습니다. 잠시 후 다시 시도해 주세요.',
  aborted: '요청이 취소되었습니다. 필요하면 다시 시도해 주세요.',
};

// The current backend still sends these fixed English messages. Translate only
// exact pairs; a new Korean server message always takes precedence.
const legacyMessages: Record<string, readonly [string, string]> = {
  COMM_400: ['Invalid request', '요청 내용을 확인해 주세요.'],
  COMM_400_001: ['Request validation failed', '입력한 내용을 확인해 주세요.'],
  COMM_401: ['Authentication is required', '로그인이 필요합니다. 로그인 후 다시 시도해 주세요.'],
  COMM_403: [
    'Access is denied',
    '권한이 없거나 요청 보안 정보가 만료되었습니다. 다시 시도해도 계속되면 담당자에게 문의해 주세요.',
  ],
  COMM_404: ['Resource not found', '요청한 항목을 찾을 수 없습니다.'],
  COMM_500: [
    'An unexpected server error occurred',
    '서버 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.',
  ],
};

export function readUserMessage(message: unknown): string | undefined {
  if (typeof message !== 'string' || !message.trim()) return undefined;
  // Only the envelope's user-facing Korean text is displayable. Diagnostic
  // payloads (HTML/JSON, paths, URLs and exception traces) violate that contract.
  const hasDiagnosticText =
    /[<>{}]|https?:\/\/|[a-z]:[\\/]|(?:^|[\s(:])\/\S+|\\|\b\w*(?:Exception|Error)\b|\bat\s+[\w.$]+\(/i.test(
      message,
    ) ||
    /\p{Cc}/u.test(message.replace(/[\r\n\t]/g, '')) ||
    /\b[A-Z][A-Z\d]*_\d{3}(?:_\d+)*\b|\b[a-z]+(?:_[a-z\d]+)+\b|\brequest[ -]?id\b/i.test(message);
  if (!/[가-힣]/.test(message) || hasDiagnosticText) return undefined;

  return message;
}

function getUserMessage(message: unknown, code: string, kind: ApiClientErrorKind): string {
  const legacy = legacyMessages[code];
  if (legacy && message === legacy[0]) return legacy[1];
  return readUserMessage(message) ?? fallbackMessages[kind];
}

export function readRequestId(value: unknown): string | undefined {
  return typeof value === 'string' && /^[a-z\d][a-z\d._:-]{0,127}$/i.test(value)
    ? value
    : undefined;
}

export class ApiClientError extends Error {
  readonly kind: ApiClientErrorKind;
  readonly status: number;
  readonly code: string;
  readonly requestId?: string;
  readonly #diagnostics: ApiErrorDetails['diagnostics'];

  constructor(kind: ApiClientErrorKind, status: number, details: ApiErrorDetails = {}) {
    const code =
      typeof details.code === 'string' && /^[A-Z][A-Z\d_.-]{0,99}$/.test(details.code)
        ? details.code
        : kind === 'http'
          ? `HTTP_${status}`
          : `CLIENT_${kind.replaceAll('-', '_').toUpperCase()}`;
    const requestId = readRequestId(details.requestId);
    const message =
      typeof details.message === 'string' &&
      ((requestId && details.message.includes(requestId)) ||
        (code.length >= 4 && details.message.includes(code)))
        ? undefined
        : details.message;
    super(getUserMessage(message, code, kind));
    this.name = 'ApiClientError';
    this.kind = kind;
    this.status = status;
    this.code = code;
    this.requestId = requestId;
    this.#diagnostics = details.diagnostics;
  }

  // Deliberate opt-in for feature diagnostics; never serialize into user state.
  get diagnostics() {
    return this.#diagnostics;
  }
}
