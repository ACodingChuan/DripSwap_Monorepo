export type ExploreRecentTransaction = {
  id: string;
  chainId: string;
  blockNumber: number;
  txHash: string;
  eventSig?: string | null;
  decodedName?: string | null;
  decodedData?: string | null;
  status: string;
  createdAt: string;
};

export type GetRecentTransactionsInput = {
  chainId: string;
  limit?: number;
  types?: Array<'SWAP' | 'MINT' | 'BURN'>;
};

export type ExploreSeriesPoint = {
  date: number;
  valueUsd: string;
};

export type ExploreStats = {
  chainId: string;
  tvlUsd: string;
  volume24hUsd: string;
  fees24hUsd: string;
  tvlSeries: ExploreSeriesPoint[];
  volumeSeries: ExploreSeriesPoint[];
};

export type GetExploreStatsInput = {
  chainId: string;
  days?: number;
};

export type ExploreTokenRow = {
  id: string;
  symbol: string;
  name: string;
  priceUsd: number;
  change1h?: number | null;
  change1d?: number | null;
  fdvUsd?: number | null;
};

export type GetExploreTokensInput = {
  chainId: string;
  limit?: number;
  search?: string;
};

export type ExplorePoolSort = 'TVL_DESC' | 'VOLUME_24H_DESC' | 'TX_24H_DESC' | 'FEES_24H_DESC' | 'APR_DESC';

export type TokenLite = {
  address: string;
  symbol: string;
  name: string;
};

export type ExplorePoolRow = {
  pairAddress: string;
  chainId: string;
  token0: TokenLite;
  token1: TokenLite;

  tvlUsd: number;
  tvlChange1d?: number | null;

  volume24hUsd: number;
  volume1wUsd?: number | null;

  fees24hUsd: number;
  tx24hCount?: number | null;

  apr?: number | null;
};

export type GetExplorePoolsInput = {
  chainId: string;
  limit?: number;
  search?: string;
  sort?: ExplorePoolSort;
};

export interface ExplorePort {
  getRecentTransactions(input: GetRecentTransactionsInput): Promise<ExploreRecentTransaction[]>;
  getExploreStats(input: GetExploreStatsInput): Promise<ExploreStats>;
  getExploreTokens(input: GetExploreTokensInput): Promise<ExploreTokenRow[]>;
  getExplorePools(input: GetExplorePoolsInput): Promise<ExplorePoolRow[]>;
}
