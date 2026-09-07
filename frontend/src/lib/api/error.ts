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
  COMM_403: ['Access is denied', '이 작업을 수행할 권한이 없습니다.'],
  COMM_404: ['Resource not found', '요청한 항목을 찾을 수 없습니다.'],
  COMM_500: [
    'An unexpected server error occurred',
    '서버 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.',
  ],
};

function getUserMessage(message: unknown, code: string, kind: ApiClientErrorKind): string {
  if (typeof message !== 'string' || !message.trim()) return fallbackMessages[kind];

  const legacy = legacyMessages[code];
  if (legacy && message === legacy[0]) return legacy[1];

  // Only the envelope's user-facing Korean text is displayable. Diagnostic
  // payloads (HTML/JSON, paths, URLs and exception traces) violate that contract.
  const hasDiagnosticText =
    /[<>{}]|https?:\/\/|[a-z]:[\\/]|(?:^|[\s(:])\/\S+|\\|\b\w*(?:Exception|Error)\b|\bat\s+[\w.$]+\(/i.test(
      message,
    ) || /\p{Cc}/u.test(message.replace(/[\r\n\t]/g, ''));
  if (!/[가-힣]/.test(message) || hasDiagnosticText) return fallbackMessages[kind];

  return message;
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
    super(getUserMessage(details.message, code, kind));
    this.name = 'ApiClientError';
    this.kind = kind;
    this.status = status;
    this.code = code;
    this.requestId = readRequestId(details.requestId);
    this.#diagnostics = details.diagnostics;
  }

  // Deliberate opt-in for feature diagnostics; never serialize into user state.
  get diagnostics() {
    return this.#diagnostics;
  }
}
