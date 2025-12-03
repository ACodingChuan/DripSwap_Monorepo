import { useReadContract } from 'wagmi';
import { BRIDGE_ABI } from '@/shared/abis/bridge';
import { BRIDGE_CONFIG } from '../config';
import { Address } from 'viem';

interface UseBridgeQuoteParams {
  chainId: number;
  token: Address;
  dstChainId: number; // Destination chain ID
  receiver: Address;
  amount: bigint;
  payInLink: boolean;
  enabled?: boolean;
}

export function useBridgeQuote({
  chainId,
  token,
  dstChainId,
  receiver,
  amount,
  payInLink,
  enabled = true,
}: UseBridgeQuoteParams) {
  const bridgeAddress = BRIDGE_CONFIG.ADDRESSES[chainId];
  const dstSelector = BRIDGE_CONFIG.CHAIN_SELECTORS[dstChainId];

  return useReadContract({
    address: bridgeAddress,
    abi: BRIDGE_ABI,
    functionName: 'quoteFee',
    args: [token, BigInt(dstSelector || '0'), receiver, amount, payInLink],
    chainId: chainId,
    query: {
      enabled: enabled && !!bridgeAddress && !!dstSelector && !!receiver && amount > 0n,
      staleTime: 10_000,
    },
  });
}
