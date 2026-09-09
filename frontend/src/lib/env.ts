const DEFAULT_API_BASE_URL = 'http://127.0.0.1:8080/api/v1';
const DEFAULT_APP_MODE = 'demo';

export function parseApiBaseUrl(value: string): string {
  const baseUrl = value.trim();
  const isRelative = baseUrl.startsWith('/') && !baseUrl.startsWith('//');
  let url: URL;

  try {
    url = new URL(baseUrl, isRelative ? 'http://api.local' : undefined);
  } catch {
    throw new Error(
      'NEXT_PUBLIC_API_BASE_URL must be an absolute HTTP(S) URL or a root-relative path such as /api/v1.',
    );
  }

  if (url.protocol !== 'http:' && url.protocol !== 'https:') {
    throw new Error('NEXT_PUBLIC_API_BASE_URL must use the http or https protocol.');
  }

  if (url.username || url.password || url.search || url.hash || /[?#\\]/.test(baseUrl)) {
    throw new Error(
      'NEXT_PUBLIC_API_BASE_URL must not contain credentials, query, hash or backslashes.',
    );
  }

  const pathname = url.pathname.replace(/\/+$/, '');
  return isRelative ? pathname || '/' : `${url.origin}${pathname}`;
}

function parseAppMode(value: string): string {
  const appMode = value.trim();

  if (appMode.length === 0) {
    throw new Error('NEXT_PUBLIC_APP_MODE must not be empty.');
  }

  return appMode;
}

export const env = Object.freeze({
  apiBaseUrl: parseApiBaseUrl(process.env.NEXT_PUBLIC_API_BASE_URL ?? DEFAULT_API_BASE_URL),
  appMode: parseAppMode(process.env.NEXT_PUBLIC_APP_MODE ?? DEFAULT_APP_MODE),
});
