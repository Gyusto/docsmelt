// End-to-end tests: start DocSmelt (or reuse one already running on :8099) and drive it in Chromium.
//   cd e2e && npm ci && npx playwright install chromium && npm test
// Set CHROME=/path/to/chrome to use an existing browser instead of downloading one.
const { defineConfig } = require("@playwright/test");

module.exports = defineConfig({
  testDir: "./tests",
  timeout: 120_000,
  expect: { timeout: 20_000 },
  workers: process.env.CI ? 2 : 3,
  retries: process.env.CI ? 1 : 0,
  reporter: [["list"], ["html", { open: "never" }]],
  use: {
    baseURL: process.env.BASE_URL || "http://localhost:8099",
    acceptDownloads: true,
    viewport: { width: 1280, height: 900 },
    trace: "retain-on-failure",
    launchOptions: process.env.CHROME ? { executablePath: process.env.CHROME } : {},
  },
  webServer: {
    // A high rate limit: the tests make many requests from one address, in parallel.
    command: "java -jar ../target/docsmelt.jar serve --port 8099 --rate-limit 10000",
    url: "http://localhost:8099/api/health",
    reuseExistingServer: true,
    timeout: 60_000,
  },
});
