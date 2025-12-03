import { useState, useEffect, useCallback } from 'react';
import { useAccount, useReadContract, useWriteContract, useWaitForTransactionReceipt } from 'wagmi';
import { parseUnits, maxUint256 } from 'viem';
import { ERC20_ABI, ROUTER_ABI } from './abis';

export interface SwapExecutionParams {
  chainId: number;
  tokenIn: `0x${string}`;
  tokenOut: `0x${string}`;
  amountIn: string;
  minAmountOut: string;
  routerAddress: `0x${string}`;
  tokenInDecimals: number;
  routePath?: `0x${string}`[]; // 支持多跳路径
}

export type ApprovalState = 'unknown' | 'approved' | 'not_approved' | 'pending';
export type SwapState = 'idle' | 'pending' | 'success' | 'failed';

export function useSwapExecution(params: SwapExecutionParams) {
  const {
    chainId,
    tokenIn,
    tokenOut,
    amountIn,
    minAmountOut,
    routerAddress,
    tokenInDecimals,
    routePath,
  } = params;
  const { address: userAddress } = useAccount();

  const [approvalState, setApprovalState] = useState<ApprovalState>('unknown');
  const [swapState, setSwapState] = useState<SwapState>('idle');
  const [txHash, setTxHash] = useState<`0x${string}` | undefined>();

  // 1. Read Allowance
  const { data: allowance, refetch: refetchAllowance } = useReadContract({
    address: tokenIn,
    abi: ERC20_ABI,
    functionName: 'allowance',
    args: userAddress && routerAddress ? [userAddress, routerAddress] : undefined,
    chainId,
  });

  // 2. Approve Write
  const { writeContractAsync: writeApprove, isPending: isApproveWriting } = useWriteContract();

  // 3. Swap Write
  const { writeContractAsync: writeSwap, isPending: isSwapWriting } = useWriteContract();

  // 4. Wait for Transaction
  const {
    isLoading: isConfirming,
    isSuccess: isConfirmed,
    isError: isConfirmError,
  } = useWaitForTransactionReceipt({
    hash: txHash,
  });

  // Handle Confirmation
  useEffect(() => {
    if (isConfirmed) {
      if (approvalState === 'pending') {
        setApprovalState('approved');
        refetchAllowance();
        // Reset txHash so we don't trigger this again
        setTxHash(undefined);
      } else if (swapState === 'pending') {
        setSwapState('success');
        refetchAllowance();
        setTxHash(undefined);
      }
    } else if (isConfirmError) {
      if (approvalState === 'pending') setApprovalState('not_approved');
      if (swapState === 'pending') setSwapState('failed');
      setTxHash(undefined);
    }
  }, [isConfirmed, isConfirmError, refetchAllowance, approvalState, swapState]);

  // Check Allowance
  useEffect(() => {
    // If we are currently approving, don't override the pending state with raw data yet
    if (approvalState === 'pending') return;

    if (!allowance || !amountIn) {
      setApprovalState('unknown');
      return;
    }

    const amountInBigInt = parseUnits(amountIn, tokenInDecimals);
    if (allowance >= amountInBigInt) {
      setApprovalState('approved');
    } else {
      setApprovalState('not_approved');
    }
  }, [allowance, amountIn, tokenInDecimals, approvalState]);

  // Handle Approve
  const handleApprove = useCallback(async () => {
    try {
      setApprovalState('pending');
      const hash = await writeApprove({
        address: tokenIn,
        abi: ERC20_ABI,
        functionName: 'approve',
        args: [routerAddress, maxUint256],
        chainId,
      });

      // Track transaction
      setTxHash(hash);
      return hash;
    } catch (error) {
      console.error('Approve failed', error);
      setApprovalState('not_approved');
      throw error;
    }
  }, [tokenIn, routerAddress, chainId, writeApprove]);

  // Handle Swap
  const handleSwap = useCallback(async () => {
    try {
      setSwapState('pending');
      const amountInBigInt = parseUnits(amountIn, tokenInDecimals);
      const minAmountOutBigInt = BigInt(minAmountOut);
      // Use provided routePath or fallback to direct [tokenIn, tokenOut]
      const path = routePath && routePath.length >= 2 ? routePath : [tokenIn, tokenOut];
      const to = userAddress!;
      const deadline = BigInt(Math.floor(Date.now() / 1000) + 60 * 20); // 20 mins

      console.log('Swapping:', {
        router: routerAddress,
        amountIn: amountInBigInt.toString(),
        minAmountOut: minAmountOutBigInt.toString(),
        path,
        to,
        deadline,
      });

      const hash = await writeSwap({
        address: routerAddress,
        abi: ROUTER_ABI,
        functionName: 'swapExactTokensForTokens',
        args: [amountInBigInt, minAmountOutBigInt, path, to, deadline],
        chainId,
      });

      setTxHash(hash);
      return hash;
    } catch (error) {
      console.error('Swap failed', error);
      setSwapState('failed');
      throw error;
    }
  }, [
    amountIn,
    tokenInDecimals,
    minAmountOut,
    tokenIn,
    tokenOut,
    routePath,
    userAddress,
    routerAddress,
    chainId,
    writeSwap,
  ]);

  return {
    approvalState: isApproveWriting ? 'pending' : approvalState,
    swapState: isSwapWriting || isConfirming ? 'pending' : swapState,
    txHash,
    handleApprove,
    handleSwap,
    refetchAllowance,
  };
}
