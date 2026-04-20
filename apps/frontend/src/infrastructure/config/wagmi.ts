import { getDefaultConfig } from '@rainbow-me/rainbowkit';
import { createConfig, http, injected } from 'wagmi';
import { sepolia, scrollSepolia } from 'wagmi/chains';

const alchemyRpcUrls = {
  [sepolia.id]: import.meta.env.VITE_SEPOLIA_RPC_URL || '',
  [scrollSepolia.id]: import.meta.env.VITE_SCROLL_RPC_URL || '',
};

const walletConnectProjectId = import.meta.env.VITE_WALLETCONNECT_PROJECT_ID?.trim();

const baseConfig = {
  appName: 'DripSwap',
  // MVP: only support testnets (Sepolia + Scroll Sepolia).
  chains: [sepolia, scrollSepolia],
  transports: {
    [sepolia.id]: http(alchemyRpcUrls[sepolia.id]),
    [scrollSepolia.id]: http(alchemyRpcUrls[scrollSepolia.id]),
  },
  ssr: false,
} as const;

export const config = walletConnectProjectId
  ? getDefaultConfig({
      ...baseConfig,
      projectId: walletConnectProjectId,
    })
  : createConfig({
      ...baseConfig,
      connectors: [injected()],
    });
