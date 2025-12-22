import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import path from 'node:path';

export default defineConfig({
  plugins: [react()],
  resolve: {
    alias: {
      '@': path.resolve(__dirname, 'src'),
    },
  },
  server: {
    proxy: {
      '/otlp': {
        target: 'http://localhost:4318',
        changeOrigin: true,
        rewrite: (proxyPath) => proxyPath.replace(/^\/otlp/, ''),
      },
    },
  },
  test: {
    include: ['src/**/*.{test,spec}.{ts,tsx}'],
    environment: 'jsdom',
    setupFiles: './tests/setup.ts',
    css: true,
    exclude: ['tests/e2e/**'],
  },
});
