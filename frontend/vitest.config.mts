import react from "@vitejs/plugin-react";
import tsconfigPaths from "vite-tsconfig-paths";
import { configDefaults, defineConfig } from "vitest/config";

export default defineConfig({
  plugins: [tsconfigPaths(), react()],
  test: {
    environment: "jsdom",
    // The large-list render tests take about a second alone but pass the 5 s default when the machine is busy (a build, a sync, Docker).
    // A wrong answer still fails at once; only a slow one gets room.
    testTimeout: 20_000,
    setupFiles: ["./vitest.setup.ts"],
    // Playwright specs need the whole stack running; they are run with `npm run e2e`.
    exclude: [...configDefaults.exclude, "e2e/**"],
  },
});
