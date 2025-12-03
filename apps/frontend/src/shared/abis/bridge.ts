export const BRIDGE_ABI = [
  // View Functions
  {
    inputs: [
      { internalType: 'address', name: 'token', type: 'address' },
      { internalType: 'uint64', name: 'dstSelector', type: 'uint64' },
      { internalType: 'address', name: 'receiver', type: 'address' },
      { internalType: 'uint256', name: 'amount', type: 'uint256' },
      { internalType: 'bool', name: 'payInLink', type: 'bool' },
    ],
    name: 'quoteFee',
    outputs: [{ internalType: 'uint256', name: 'ccipFee', type: 'uint256' }],
    stateMutability: 'view',
    type: 'function',
  },
  // Write Functions - sendToken (not bridgeToken!)
  {
    inputs: [
      { internalType: 'address', name: 'token', type: 'address' },
      { internalType: 'uint64', name: 'dstSelector', type: 'uint64' },
      { internalType: 'address', name: 'receiver', type: 'address' },
      { internalType: 'uint256', name: 'amount', type: 'uint256' },
      { internalType: 'bool', name: 'payInLink', type: 'bool' },
      {
        components: [
          {
            components: [
              {
                components: [
                  { internalType: 'address', name: 'token', type: 'address' },
                  { internalType: 'uint256', name: 'amount', type: 'uint256' },
                ],
                internalType: 'struct ISignatureTransfer.TokenPermissions[]',
                name: 'permitted',
                type: 'tuple[]',
              },
              { internalType: 'uint256', name: 'nonce', type: 'uint256' },
              { internalType: 'uint256', name: 'deadline', type: 'uint256' },
            ],
            internalType: 'struct ISignatureTransfer.PermitBatchTransferFrom',
            name: 'permit',
            type: 'tuple',
          },
          { internalType: 'bytes', name: 'signature', type: 'bytes' },
        ],
        internalType: 'struct Bridge.PermitInput',
        name: 'permitInput',
        type: 'tuple',
      },
    ],
    name: 'sendToken',
    outputs: [{ internalType: 'bytes32', name: 'messageId', type: 'bytes32' }],
    stateMutability: 'payable',
    type: 'function',
  },
] as const;

export const PERMIT2_ABI = [
  {
    inputs: [
      { internalType: 'address', name: 'owner', type: 'address' },
      { internalType: 'address', name: 'token', type: 'address' },
      { internalType: 'address', name: 'spender', type: 'address' },
    ],
    name: 'allowance',
    outputs: [
      { internalType: 'uint160', name: 'amount', type: 'uint160' },
      { internalType: 'uint48', name: 'expiration', type: 'uint48' },
      { internalType: 'uint48', name: 'nonce', type: 'uint48' },
    ],
    stateMutability: 'view',
    type: 'function',
  },
  {
    inputs: [
      { internalType: 'address', name: 'token', type: 'address' },
      { internalType: 'address', name: 'spender', type: 'address' },
      { internalType: 'uint160', name: 'amount', type: 'uint160' },
      { internalType: 'uint48', name: 'expiration', type: 'uint48' },
    ],
    name: 'approve',
    outputs: [],
    stateMutability: 'nonpayable',
    type: 'function',
  },
] as const;
