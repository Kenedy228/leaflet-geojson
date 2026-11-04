import js from "@eslint/js";
import globals from "globals";
import svelte from "eslint-plugin-svelte";
import ts from "typescript-eslint";
import svelteConfig from "./svelte.config.js";

export default ts.config(
  { ignores: ["node_modules/**", "dist/**"] },
  js.configs.recommended,
  ts.configs.recommended,
  ...svelte.configs.recommended,
  {
    languageOptions: { globals: { ...globals.browser, ...globals.node } },
    rules: {
      "@typescript-eslint/no-unused-vars": [
        "error",
        { argsIgnorePattern: "^_", caughtErrorsIgnorePattern: "^_" },
      ],
    },
  },
  {
    files: ["**/*.svelte", "**/*.svelte.ts"],
    languageOptions: { parserOptions: { parser: ts.parser, svelteConfig } },
  },
  {
    files: ["tests/**/*.cjs"],
    rules: { "@typescript-eslint/no-require-imports": "off" },
  },
);
