import { defineConfig } from "vitest/config";
import { svelte } from "@sveltejs/vite-plugin-svelte";

const apiTarget = process.env.API_PROXY_TARGET || "http://127.0.0.1:8080";

export default defineConfig({
  plugins: [svelte()],
  build: {
    rollupOptions: { input: { main: "index.html", login: "login.html" } },
  },
  server: {
    host: "127.0.0.1",
    strictPort: true,
    proxy: {
      ...Object.fromEntries(
        ["/api", "/logout"].map((path) => [path, { target: apiTarget }]),
      ),
      "/login": {
        target: apiTarget,
        bypass(request) {
          // GET uses Vite's login entry and HMR; POST stays owned by Spring Security.
          if (request.method === "GET" || request.method === "HEAD") {
            return "/login.html";
          }
        },
      },
    },
  },
  test: {
    environment: "jsdom",
    include: ["src/**/__tests__/*.test.ts"],
    restoreMocks: true,
  },
  resolve: { conditions: ["browser"] },
});
