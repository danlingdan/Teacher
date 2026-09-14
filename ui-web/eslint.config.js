// ESLint flat config (v3.4.0 REF-18 engineering base).
// Strategy: strict rules that fit incremental development, but historical code is
// not mass-fixed in this change. Rules that fire broadly on existing code are set
// to "warn" so `npm run lint` stays at 0 errors while still surfacing hot spots.
// New code should not introduce new errors (and ideally no new warnings).
import tseslint from "typescript-eslint";
import reactHooks from "eslint-plugin-react-hooks";
import reactRefresh from "eslint-plugin-react-refresh";

export default tseslint.config(
  // Generated, vendored, and build output must never be linted.
  {
    ignores: [
      "dist/**",
      "node_modules/**",
      "logs/**",
      "coverage/**",
      "src-tauri/**",
      "public/**",
    ],
  },

  // TypeScript-aware base (parser + eslint-recommended adjustments + recommended TS rules).
  ...tseslint.configs.recommended,

  // Application and test source.
  {
    files: ["src/**/*.{ts,tsx}", "vite.config.ts"],
    plugins: {
      "react-hooks": reactHooks,
      "react-refresh": reactRefresh,
    },
    rules: {
      // React hooks correctness: always errors.
      "react-hooks/rules-of-hooks": "error",
      // Dependency completeness: warn so existing code is not blocked; keep visible.
      "react-hooks/exhaustive-deps": "warn",
      // Fast-refresh boundary hygiene: page modules often export helpers alongside
      // components, so this stays a warning.
      "react-refresh/only-export-components": "warn",

      // TypeScript strictness for incremental work.
      "@typescript-eslint/no-unused-vars": [
        "error",
        { argsIgnorePattern: "^_", varsIgnorePattern: "^_", caughtErrors: "none" },
      ],
      // `any` appears in legacy spots; keep it visible as a warning, not a blocker.
      "@typescript-eslint/no-explicit-any": "warn",
      "@typescript-eslint/no-unused-expressions": [
        "error",
        { allowShortCircuit: true, allowTernary: true },
      ],
      eqeqeq: ["error", "smart"],
      "prefer-const": "error",
      "no-var": "error",
      "object-shorthand": ["warn", "properties"],
      "no-console": ["warn", { allow: ["warn", "error", "info", "debug"] }],
    },
  },

  // WebDriverIO end-to-end specs run outside the bundler with injected globals.
  {
    files: ["e2e/**/*.js", "wdio.conf.mjs"],
    languageOptions: {
      globals: {
        browser: "readonly",
        $: "readonly",
        $$: "readonly",
        expect: "readonly",
        console: "readonly",
        process: "readonly",
      },
    },
    rules: {
      "no-console": "off",
    },
  },
);
