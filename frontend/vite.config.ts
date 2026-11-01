import { defineConfig } from "vitest/config";
import { svelte } from "@sveltejs/vite-plugin-svelte";

export default defineConfig({
  plugins: [svelte()],
  server: {
    host: "127.0.0.1",
    strictPort: true,
    proxy: Object.fromEntries(
      ["/api", "/login", "/logout"].map((path) => [
        path,
        { target: process.env.API_PROXY_TARGET || "http://127.0.0.1:8080" },
      ]),
    ),
  },
  test: {
    environment: "jsdom",
    include: ["src/**/__tests__/*.test.ts"],
    restoreMocks: true,
  },
  resolve: { conditions: ["browser"] },
});
