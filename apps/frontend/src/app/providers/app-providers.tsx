import type { PropsWithChildren } from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { WagmiProvider } from 'wagmi';
import { RainbowKitProvider, darkTheme } from '@rainbow-me/rainbowkit';
import '@rainbow-me/rainbowkit/styles.css';

import { I18nProvider } from '@/shared/i18n';
import { ThemeProvider, Toaster } from '@/shared/ui';
import { config } from '@/infrastructure/config/wagmi';

const queryClient = new QueryClient();

export function AppProviders({ children }: PropsWithChildren) {
  return (
    <WagmiProvider config={config}>
      <QueryClientProvider client={queryClient}>
        <RainbowKitProvider theme={darkTheme()}>
          <I18nProvider>
            <ThemeProvider>
              {children}
              <Toaster />
            </ThemeProvider>
          </I18nProvider>
        </RainbowKitProvider>
      </QueryClientProvider>
    </WagmiProvider>
  );
}
