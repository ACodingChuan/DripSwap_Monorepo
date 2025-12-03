import { defineConfig, devices } from '@playwright/test';

export default defineConfig({
  testDir: './tests/e2e',
  fullyParallel: true,
  reporter: 'list',
  use: {
    baseURL: 'http://127.0.0.1:5173',
    trace: 'retain-on-failure',
  },
  projects: [
    {
      name: 'chromium',
      use: { ...devices['Desktop Chrome'] },
    },
  ],
  webServer: {
    command: 'pnpm dev -- --host 0.0.0.0 --port 5173 --strictPort',
    url: 'http://127.0.0.1:5173',
    timeout: 120000,
    reuseExistingServer: !process.env.CI,
  },
});
