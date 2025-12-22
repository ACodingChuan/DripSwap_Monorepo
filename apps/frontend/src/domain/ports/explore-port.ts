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

export interface ExplorePort {
  getRecentTransactions(input: GetRecentTransactionsInput): Promise<ExploreRecentTransaction[]>;
  getExploreStats(input: GetExploreStatsInput): Promise<ExploreStats>;
  getExploreTokens(input: GetExploreTokensInput): Promise<ExploreTokenRow[]>;
}
