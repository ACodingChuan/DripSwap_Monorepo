import { useSignTypedData } from 'wagmi';
import { Address, Hex } from 'viem';
import { BRIDGE_CONFIG } from '../config';

// Permit2 EIP-712 Domain
const PERMIT2_DOMAIN = {
  name: 'Permit2',
  chainId: 0, // Will be set dynamically
  verifyingContract: BRIDGE_CONFIG.PERMIT2_ADDRESS,
} as const;

// PermitBatchTransferFrom Type Definition
const PERMIT_TYPES = {
  PermitBatchTransferFrom: [
    { name: 'permitted', type: 'TokenPermissions[]' },
    { name: 'spender', type: 'address' },
    { name: 'nonce', type: 'uint256' },
    { name: 'deadline', type: 'uint256' },
  ],
  TokenPermissions: [
    { name: 'token', type: 'address' },
    { name: 'amount', type: 'uint256' },
  ],
} as const;

export interface PermitBatchSignature {
  permit: {
    permitted: {
      token: Address;
      amount: bigint;
    }[];
    nonce: bigint;
    deadline: bigint;
  };
  signature: Hex;
}

export function usePermit2Sign() {
  const { signTypedDataAsync } = useSignTypedData();

  const signPermit = async (
    chainId: number,
    token: Address,
    amount: bigint,
    spender: Address,
    nonce: bigint,
    feeConfig?: { token: Address; amount: bigint }, // Optional fee token (e.g. LINK)
    deadline: bigint = BigInt(Math.floor(Date.now() / 1000) + 3600) // 1 hour default
  ): Promise<PermitBatchSignature> => {
    const permitted = [{ token, amount }];

    // If fee token is provided, add it to permissions
    // Note: If fee token is same as transfer token, we sum them up or add distinct entry?
    // Permit2 allows distinct entries for same token, but usually better to sum.
    // For Bridge spec, it's simpler to just push.
    // However, if token == feeToken (unlikely for LINK vs vUSDC, but possible if bridging LINK),
    // we should handle it.
    // If bridging LINK and paying in LINK:
    if (feeConfig) {
      const existingIndex = permitted.findIndex(
        (p) => p.token.toLowerCase() === feeConfig.token.toLowerCase()
      );
      if (existingIndex >= 0) {
        permitted[existingIndex].amount += feeConfig.amount;
      } else {
        permitted.push({ token: feeConfig.token, amount: feeConfig.amount });
      }
    }

    // Construct the permit object (WITHOUT spender - it's not part of the struct)
    const permit = {
      permitted,
      nonce,
      deadline,
    };

    try {
      // Sign with spender included in the EIP-712 message
      const signature = await signTypedDataAsync({
        domain: {
          ...PERMIT2_DOMAIN,
          chainId,
        },
        types: PERMIT_TYPES,
        primaryType: 'PermitBatchTransferFrom',
        message: {
          permitted,
          spender, // spender is used in signing but NOT in the returned permit struct
          nonce,
          deadline,
        },
      });

      return { permit, signature };
    } catch (error) {
      console.error('Permit2 Signing Failed:', error);
      throw error;
    }
  };

  return { signPermit };
}
