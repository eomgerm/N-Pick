import { defineConfig, devices } from '@playwright/test';

const frontendUrl = 'http://127.0.0.1:3116';
const mockApiUrl = 'http://127.0.0.1:18116';
process.env.API_INTERNAL_BASE_URL = `${mockApiUrl}/api/v1`;
process.env.NEXT_PUBLIC_API_BASE_URL = `${mockApiUrl}/api/v1`;

export default defineConfig({
  testDir: './e2e',
  globalSetup: './e2e/global-setup.ts',
  fullyParallel: false,
  forbidOnly: Boolean(process.env.CI),
  retries: process.env.CI ? 2 : 0,
  workers: 1,
  reporter: 'line',
  use: {
    baseURL: frontendUrl,
    trace: 'retain-on-failure',
    channel: process.env.PLAYWRIGHT_CHANNEL || undefined,
  },
  projects: [
    {
      name: 'chromium',
      use: { ...devices['Desktop Chrome'] },
    },
  ],
});
