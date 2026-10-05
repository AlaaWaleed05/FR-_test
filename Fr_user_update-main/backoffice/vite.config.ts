/// <reference types="vitest/config" />
import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react()],
  server: {
    // Keeps the backend same-origin from the browser's perspective in dev, so cookies
    // (JSESSIONID, XSRF-TOKEN) flow with no CORS configuration needed anywhere -- the backend's
    // own CORS/SameSite story is deliberately left to AD-002d (hosting), out of scope here.
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
  test: {
    environment: 'jsdom',
    setupFiles: ['./src/test/setup.ts'],
    // v8 coverage instrumentation slows multi-field userEvent.type() interactions enough to trip
    // the 5s default under --coverage specifically (reproduced: passes at the default without
    // coverage, times out at ~5s with it) -- a perf artifact of instrumentation, not a hung test.
    testTimeout: 15000,
    coverage: {
      provider: 'v8',
      exclude: ['src/main.tsx', 'src/vite-env.d.ts'],
      thresholds: {
        lines: 80,
        statements: 80,
        branches: 80,
        functions: 80,
      },
    },
  },
})
