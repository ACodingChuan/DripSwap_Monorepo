import { getDefaultConfig } from '@rainbow-me/rainbowkit';
import { http } from 'wagmi';
import { mainnet, sepolia, scrollSepolia } from 'wagmi/chains';

const alchemyRpcUrls = {
  [sepolia.id]: import.meta.env.VITE_SEPOLIA_RPC_URL || '',
  [scrollSepolia.id]: import.meta.env.VITE_SCROLL_RPC_URL || '',
};

export const config = getDefaultConfig({
  appName: 'DripSwap',
  projectId: import.meta.env.VITE_WALLETCONNECT_PROJECT_ID || 'demo_project_id',
  chains: [mainnet, sepolia, scrollSepolia],
  transports: {
    [mainnet.id]: http(),
    [sepolia.id]: http(alchemyRpcUrls[sepolia.id]),
    [scrollSepolia.id]: http(alchemyRpcUrls[scrollSepolia.id]),
  },
  ssr: false,
});
