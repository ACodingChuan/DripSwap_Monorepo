import type { TokenLite } from './token-port';

export type PoolDetails = {
  chainId: string;
  pairAddress: string;
  token0: TokenLite;
  token1: TokenLite;

  tvlUsd: number;
  volume24hUsd: number;
  fees24hUsd: number;
  tx24hCount?: number | null;

  apr?: number | null; // ratio (e.g. 0.12 => 12%)
  reserve0?: number | null;
  reserve1?: number | null;
  token0Price?: number | null;
  token1Price?: number | null;
};

export type PoolChartInterval = 'HOUR' | 'DAY';

export type PoolOhlc = {
  timestamp: number;
  tvlUsd: number;
  volumeUsd: number;
  feesUsd: number;
};

export type PoolTransactionType = 'SWAP' | 'MINT' | 'BURN';

export type PoolTransactionRow = {
  type: PoolTransactionType;
  timestamp: number;
  txHash: string;
  amountUsd?: number | null;
  token0Amount?: number | null;
  token1Amount?: number | null;
  account?: string | null;
};

export type GetPoolDetailsInput = {
  chainId: string;
  pairAddress: string;
};

export type GetPoolPriceCandlesInput = {
  chainId: string;
  pairAddress: string;
  interval: PoolChartInterval;
  from: number;
  to: number;
};

export type GetPoolTransactionsInput = {
  chainId: string;
  pairAddress: string;
  limit?: number;
  types?: PoolTransactionType[];
};

export interface PoolPort {
  getPoolDetails(input: GetPoolDetailsInput): Promise<PoolDetails | null>;
  getPoolPriceCandles(input: GetPoolPriceCandlesInput): Promise<PoolOhlc[]>;
  getPoolTransactions(input: GetPoolTransactionsInput): Promise<PoolTransactionRow[]>;
}
