const DEFAULT_API_BASE_URL = 'http://127.0.0.1:8080/api/v1';
const DEFAULT_APP_MODE = 'demo';

function parseApiBaseUrl(value: string): URL {
  let url: URL;

  try {
    url = new URL(value);
  } catch {
    throw new Error(
      'NEXT_PUBLIC_API_BASE_URL must be an absolute HTTP(S) URL, for example http://127.0.0.1:8080/api/v1.',
    );
  }

  if (url.protocol !== 'http:' && url.protocol !== 'https:') {
    throw new Error('NEXT_PUBLIC_API_BASE_URL must use the http or https protocol.');
  }

  return url;
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
