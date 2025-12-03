import { useReadContract, useWriteContract, useWaitForTransactionReceipt } from 'wagmi';
import { ERC20_ABI } from '@/lib/swap/abis';
import { BRIDGE_CONFIG } from '../config';
import { Address, maxUint256 } from 'viem';

export function usePermit2Allowance(token: Address, owner: Address | undefined, chainId: number) {
  return useReadContract({
    address: token,
    abi: ERC20_ABI,
    functionName: 'allowance',
    args: owner ? [owner, BRIDGE_CONFIG.PERMIT2_ADDRESS] : undefined,
    chainId,
    query: {
      enabled: !!owner && !!token,
    },
  });
}

export function useApprovePermit2() {
  const { writeContractAsync, data: hash, isPending } = useWriteContract();

  const { isLoading: isConfirming, isSuccess: isConfirmed } = useWaitForTransactionReceipt({
    hash,
  });

  const approve = async (token: Address, chainId: number) => {
    return writeContractAsync({
      address: token,
      abi: ERC20_ABI,
      functionName: 'approve',
      args: [BRIDGE_CONFIG.PERMIT2_ADDRESS, maxUint256],
      chainId,
    });
  };

  return {
    approve,
    isPending,
    isConfirming,
    isConfirmed,
    hash,
  };
}
