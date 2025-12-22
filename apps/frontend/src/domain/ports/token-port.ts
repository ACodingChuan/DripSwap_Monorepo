export type TokenDetails = {
  chainId: string;
  address: string;
  symbol: string;
  name: string;
  decimals: number;
  priceUsd: number;
  change24hPct?: number | null;
  tvlUsd: number;
  volume24hUsd: number;
  fdvUsd?: number | null;
};

export type TokenChartInterval = 'MINUTE' | 'HOUR' | 'DAY';

export type TokenOhlc = {
  timestamp: number;
  open: number;
  high: number;
  low: number;
  close: number;
  volumeUsd: number;
  tvlUsd: number;
};

export type TokenLite = {
  address: string;
  symbol: string;
  name: string;
};

export type TokenPoolRow = {
  pairAddress: string;
  token0: TokenLite;
  token1: TokenLite;
  tvlUsd: number;
  volumeUsd: number;
};

export type TokenTransactionRow = {
  id: string;
  timestamp: number;
  txHash: string;
  pairAddress: string;
  amountUsd: number;
  token0: TokenLite;
  token1: TokenLite;
  amount0In: number;
  amount1In: number;
  amount0Out: number;
  amount1Out: number;
};

export type GetTokenDetailsInput = {
  chainId: string;
  tokenAddress: string;
};

export type GetTokenPriceCandlesInput = {
  chainId: string;
  tokenAddress: string;
  interval: TokenChartInterval;
  from: number;
  to: number;
};

export type GetTokenPoolsInput = {
  chainId: string;
  tokenAddress: string;
  limit?: number;
};

export type GetTokenTransactionsInput = {
  chainId: string;
  tokenAddress: string;
  limit?: number;
};

export interface TokenPort {
  getTokenDetails(input: GetTokenDetailsInput): Promise<TokenDetails | null>;
  getTokenPriceCandles(input: GetTokenPriceCandlesInput): Promise<TokenOhlc[]>;
  getTokenPools(input: GetTokenPoolsInput): Promise<TokenPoolRow[]>;
  getTokenTransactions(input: GetTokenTransactionsInput): Promise<TokenTransactionRow[]>;
}

