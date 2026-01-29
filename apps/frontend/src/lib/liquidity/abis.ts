// Minimal ABIs for Uniswap V2-style liquidity actions (frontend reads + writes).
// Keep these small to reduce bundle size and avoid ABI drift.

export const ERC20_ABI = [
  {
    type: 'function',
    stateMutability: 'view',
    name: 'decimals',
    inputs: [],
    outputs: [{ name: '', type: 'uint8' }],
  },
  {
    type: 'function',
    stateMutability: 'view',
    name: 'symbol',
    inputs: [],
    outputs: [{ name: '', type: 'string' }],
  },
  {
    type: 'function',
    stateMutability: 'view',
    name: 'balanceOf',
    inputs: [{ name: 'owner', type: 'address' }],
    outputs: [{ name: '', type: 'uint256' }],
  },
  {
    type: 'function',
    stateMutability: 'view',
    name: 'allowance',
    inputs: [
      { name: 'owner', type: 'address' },
      { name: 'spender', type: 'address' },
    ],
    outputs: [{ name: '', type: 'uint256' }],
  },
  {
    type: 'function',
    stateMutability: 'nonpayable',
    name: 'approve',
    inputs: [
      { name: 'spender', type: 'address' },
      { name: 'amount', type: 'uint256' },
    ],
    outputs: [{ name: '', type: 'bool' }],
  },
] as const;

export const UNISWAP_V2_FACTORY_ABI = [
  {
    type: 'function',
    stateMutability: 'view',
    name: 'getPair',
    inputs: [
      { name: 'tokenA', type: 'address' },
      { name: 'tokenB', type: 'address' },
    ],
    outputs: [{ name: 'pair', type: 'address' }],
  },
] as const;

export const UNISWAP_V2_PAIR_ABI = [
  {
    type: 'function',
    stateMutability: 'view',
    name: 'token0',
    inputs: [],
    outputs: [{ name: '', type: 'address' }],
  },
  {
    type: 'function',
    stateMutability: 'view',
    name: 'token1',
    inputs: [],
    outputs: [{ name: '', type: 'address' }],
  },
  {
    type: 'function',
    stateMutability: 'view',
    name: 'getReserves',
    inputs: [],
    outputs: [
      { name: '_reserve0', type: 'uint112' },
      { name: '_reserve1', type: 'uint112' },
      { name: '_blockTimestampLast', type: 'uint32' },
    ],
  },
  {
    type: 'function',
    stateMutability: 'view',
    name: 'totalSupply',
    inputs: [],
    outputs: [{ name: '', type: 'uint256' }],
  },
  // LP token is also an ERC20.
  ...ERC20_ABI.filter((f) => f.name !== 'decimals' && f.name !== 'symbol'),
] as const;

export const UNISWAP_V2_ROUTER_ABI = [
  {
    type: 'function',
    stateMutability: 'nonpayable',
    name: 'addLiquidity',
    inputs: [
      { name: 'tokenA', type: 'address' },
      { name: 'tokenB', type: 'address' },
      { name: 'amountADesired', type: 'uint256' },
      { name: 'amountBDesired', type: 'uint256' },
      { name: 'amountAMin', type: 'uint256' },
      { name: 'amountBMin', type: 'uint256' },
      { name: 'to', type: 'address' },
      { name: 'deadline', type: 'uint256' },
    ],
    outputs: [
      { name: 'amountA', type: 'uint256' },
      { name: 'amountB', type: 'uint256' },
      { name: 'liquidity', type: 'uint256' },
    ],
  },
  {
    type: 'function',
    stateMutability: 'nonpayable',
    name: 'removeLiquidity',
    inputs: [
      { name: 'tokenA', type: 'address' },
      { name: 'tokenB', type: 'address' },
      { name: 'liquidity', type: 'uint256' },
      { name: 'amountAMin', type: 'uint256' },
      { name: 'amountBMin', type: 'uint256' },
      { name: 'to', type: 'address' },
      { name: 'deadline', type: 'uint256' },
    ],
    outputs: [
      { name: 'amountA', type: 'uint256' },
      { name: 'amountB', type: 'uint256' },
    ],
  },
] as const;

