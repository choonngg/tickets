import react from '@vitejs/plugin-react';
import { defineConfig } from 'vitest/config';

// WSL drvfs (/mnt/*) does not deliver file events, so HMR misses changes; poll there or when forced.
const usePolling = process.cwd().startsWith('/mnt/') || process.env.VITE_USE_POLLING === 'true';

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    watch: usePolling ? { usePolling: true, interval: 300 } : undefined,
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
  test: {
    include: ['src/**/*.test.{ts,tsx}'],
    environment: 'jsdom',
    setupFiles: './src/setupTests.ts',
    globals: true,
  },
});
