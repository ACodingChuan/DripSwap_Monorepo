import { useMemo, useState } from 'react';
import { useWriteContract, useWaitForTransactionReceipt } from 'wagmi';
import { BRIDGE_ABI } from '@/shared/abis/bridge';
import { BRIDGE_CONFIG } from '../config';
import { Address } from 'viem';
import { decodeEventLog } from 'viem';
import { PermitBatchSignature } from './usePermit2Sign';

export function useBridgeTransaction() {
  const { writeContractAsync, data: hash, isPending, error } = useWriteContract();
  const [lastBridgeAddress, setLastBridgeAddress] = useState<Address | null>(null);

  const { data: receipt, isLoading: isConfirming, isSuccess: isConfirmed } = useWaitForTransactionReceipt({
    hash,
    pollingInterval: 6000,
  });

  const messageId = useMemo(() => {
    if (!receipt || !lastBridgeAddress) return null;
    const bridgeAddr = lastBridgeAddress.toLowerCase();
    for (const log of receipt.logs ?? []) {
      // Only decode logs emitted by our Bridge contract
      if (!log.address || log.address.toLowerCase() !== bridgeAddr) continue;
      try {
        const decoded = decodeEventLog({
          abi: BRIDGE_ABI,
          data: log.data,
          topics: log.topics,
        });
        if (decoded.eventName === 'TransferInitiated') {
          const mid = (decoded.args as any).messageId as `0x${string}` | undefined;
          if (mid) return mid;
        }
      } catch {
        // ignore non-matching events
      }
    }
    return null;
  }, [receipt, lastBridgeAddress]);

  const sendToken = async (
    chainId: number,
    dstChainId: number,
    token: Address,
    amount: bigint,
    receiver: Address,
    payInLink: boolean,
    fee: bigint,
    permitData: PermitBatchSignature
  ) => {
    const bridgeAddress = BRIDGE_CONFIG.ADDRESSES[chainId];
    const dstSelector = BRIDGE_CONFIG.CHAIN_SELECTORS[dstChainId];

    if (!bridgeAddress) throw new Error('Bridge address not found for chain');
    if (!dstSelector) throw new Error('Destination chain selector not found');
    setLastBridgeAddress(bridgeAddress as Address);

    // Calculate Value:
    // If paying in Native (payInLink = false), value = fee + buffer + serviceFee
    // If paying in LINK (payInLink = true), value = serviceFee only
    let value = 0n;
    if (!payInLink) {
      // Add 3% buffer to the fee
      const feeWithBuffer = (fee * 103n) / 100n;
      // Add service fee (0.001 ETH as per contract default)
      const serviceFee = 1000000000000000n; // 0.001 ETH
      value = feeWithBuffer + serviceFee;
    } else {
      // If paying in LINK, only send service fee
      const serviceFee = 1000000000000000n; // 0.001 ETH
      value = serviceFee;
    }

    // Construct permitInput structure as expected by contract
    const permitInput = {
      permit: permitData.permit,
      signature: permitData.signature,
    };

    return writeContractAsync({
      address: bridgeAddress,
      abi: BRIDGE_ABI,
      functionName: 'sendToken',
      args: [token, BigInt(dstSelector), receiver, amount, payInLink, permitInput],
      chainId,
      value,
      gas: 500000n, // Reasonable gas limit based on actual usage (~310k-320k)
    });
  };

  return {
    sendToken,
    hash,
    receipt,
    messageId,
    isPending,
    isConfirming,
    isConfirmed,
    error,
  };
}
