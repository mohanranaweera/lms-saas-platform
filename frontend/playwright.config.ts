import { defineConfig, devices } from "@playwright/test";

export default defineConfig({
  testDir: "./e2e",
  fullyParallel: true,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 2 : 0,
  reporter: "html",
  use: {
    baseURL: "http://localhost:3000",
    trace: "on-first-retry",
  },
  projects: [
    {
      name: "chromium",
      use: { ...devices["Desktop Chrome"] },
    },
  ],
  webServer: {
    // CI already runs `npm run build` before this step (see .github/workflows/ci.yml)
    // — serve that build in CI rather than starting a fresh `next dev` (Turbopack)
    // server, which recompiles each route on first navigation. Under parallel
    // workers that on-demand compile time caused real, reproducible flakiness
    // (33/41 tests failing at default worker count, 41/41 passing at --workers=1)
    // on a route's first hit — a CI/config issue, not a test defect. Local dev
    // still gets `next dev` for fast iteration.
    command: process.env.CI ? "npm run start" : "npm run dev",
    url: "http://localhost:3000",
    reuseExistingServer: !process.env.CI,
    timeout: 120_000,
  },
});
