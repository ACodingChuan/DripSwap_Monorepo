import { ChainConfig, CCIP_CHAINS, CHAIN_CONFIGS } from '@/contracts';

// Bridge 配置常量
export const BRIDGE_CONFIG = {
  // CCIP Chain Selectors (用于跨链消息)
  CHAIN_SELECTORS: {
    [CCIP_CHAINS.SEPOLIA]: '16015286601757825753',
    [CCIP_CHAINS.SCROLL_SEPOLIA]: '2279865765895943307',
  } as Record<number, string>,

  // Bridge Contract Addresses
  ADDRESSES: {
    [CCIP_CHAINS.SEPOLIA]: CHAIN_CONFIGS.sepolia.bridge,
    [CCIP_CHAINS.SCROLL_SEPOLIA]: CHAIN_CONFIGS.scroll.bridge,
  } as Record<number, `0x${string}`>,

  // Permit2 Address (Same on all chains)
  PERMIT2_ADDRESS: '0x000000000022D473030F116dDEE9F6B43aC78BA3' as `0x${string}`,

  // LINK Token Addresses (Real Chainlink LINK for CCIP fees, NOT vLINK)
  LINK_TOKEN: {
    [CCIP_CHAINS.SEPOLIA]: '0x779877A7B0D9E8603169DdbD7836e478b4624789',
    [CCIP_CHAINS.SCROLL_SEPOLIA]: '0x231d45b53C905c3d6201318156BDC725c9c3B9B1',
  } as Record<number, `0x${string}`>,

  // Fee Calculation Constants
  FEE_BUFFER_BPS: 300, // 3% buffer for variable fees (basis points)
  FIXED_FEE_USD: 0, // Currently 0, but good to have placeholder
} as const;

export type SupportedBridgeChainId = keyof typeof BRIDGE_CONFIG.ADDRESSES;
