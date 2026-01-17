import { defineConfig, loadEnv } from 'vite';
import react from '@vitejs/plugin-react';
import path from 'node:path';

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, __dirname, '');
  const otlpProxyTarget = (env.VITE_OTEL_PROXY_TARGET ?? '').trim();

  return {
    plugins: [
      react(),
      {
        // If no OTLP collector is configured, swallow OTLP trace export requests in dev
        // to avoid noisy Vite proxy ECONNREFUSED logs.
        name: 'dripswap-drop-otlp-traces',
        configureServer(server) {
          if (otlpProxyTarget) {
            return;
          }

          server.middlewares.use((req, res, next) => {
            const url = req.url ?? '';
            if (url.startsWith('/otlp/v1/traces') || url.startsWith('/v1/traces')) {
              res.statusCode = 204;
              res.end();
              return;
            }
            next();
          });
        },
      },
    ],
    resolve: {
      alias: {
        '@': path.resolve(__dirname, 'src'),
      },
    },
    server: {
      proxy: otlpProxyTarget
        ? {
            '/otlp': {
              target: otlpProxyTarget,
              changeOrigin: true,
              rewrite: (proxyPath) => proxyPath.replace(/^\/otlp/, ''),
            },
          }
        : undefined,
    },
    test: {
      include: ['src/**/*.{test,spec}.{ts,tsx}'],
      environment: 'jsdom',
      setupFiles: './tests/setup.ts',
      css: true,
      exclude: ['tests/e2e/**'],
    },
  };
});
