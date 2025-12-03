// 多链配置 - 从 DripSwap_Contract/deployments 迁移

export const SUPPORTED_CHAINS = {
  sepolia: {
    id: 11155111,
    name: 'Sepolia',
    network: 'sepolia',
    nativeCurrency: { name: 'Sepolia ETH', symbol: 'ETH', decimals: 18 },
    rpcUrls: {
      default: {
        http: [import.meta.env.VITE_SEPOLIA_RPC_URL || 'https://rpc.sepolia.org'],
      },
      public: { http: ['https://rpc.sepolia.org'] },
    },
    blockExplorers: {
      default: { name: 'Etherscan', url: 'https://sepolia.etherscan.io' },
    },
    testnet: true,
  },
  scroll: {
    id: 534351, // Scroll Sepolia Testnet
    name: 'Scroll Sepolia',
    network: 'scroll-sepolia',
    nativeCurrency: { name: 'ETH', symbol: 'ETH', decimals: 18 },
    rpcUrls: {
      default: {
        http: [import.meta.env.VITE_SCROLL_RPC_URL || 'https://sepolia-rpc.scroll.io'],
      },
      public: { http: ['https://sepolia-rpc.scroll.io'] },
    },
    blockExplorers: {
      default: { name: 'Scrollscan', url: 'https://sepolia.scrollscan.com' },
    },
    testnet: true,
  },
} as const;

export type SupportedChainId = keyof typeof SUPPORTED_CHAINS;

export function getChainById(chainId: number) {
  return Object.values(SUPPORTED_CHAINS).find((chain) => chain.id === chainId);
}
