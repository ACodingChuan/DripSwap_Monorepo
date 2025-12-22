import React from 'react';
import ReactDOM from 'react-dom/client';

import { App } from './app';

import '@/styles/globals.css';
import '@/styles/tailwind.css';

async function bootstrap() {
  if (import.meta.env.VITE_OTEL_ENABLED === 'true') {
    const { initOtel } = await import('@/shared/otel/initOtel');
    initOtel();
  }

  const rootElement = document.getElementById('root');

  if (!rootElement) {
    throw new Error('Root element not found');
  }

  ReactDOM.createRoot(rootElement).render(
    <React.StrictMode>
      <App />
    </React.StrictMode>
  );
}

bootstrap().catch((error) => {
  console.error('Failed to bootstrap application', error);
});
