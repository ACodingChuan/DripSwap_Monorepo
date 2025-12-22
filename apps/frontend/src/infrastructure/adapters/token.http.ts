import { gql } from '@/infrastructure/graphql/client';
import type {
  GetTokenDetailsInput,
  GetTokenPoolsInput,
  GetTokenPriceCandlesInput,
  GetTokenTransactionsInput,
  TokenDetails,
  TokenOhlc,
  TokenPoolRow,
  TokenPort,
  TokenTransactionRow,
} from '@/domain/ports/token-port';

type TokenDetailsResponse = {
  tokenDetails: {
    chainId: string;
    address: string;
    symbol: string;
    name: string;
    decimals: number;
    priceUsd: string | number;
    change24hPct?: string | number | null;
    tvlUsd: string | number;
    volume24hUsd: string | number;
    fdvUsd?: string | number | null;
  } | null;
};

type TokenPriceCandlesResponse = {
  tokenPriceCandles: Array<{
    timestamp: number;
    open: string | number;
    high: string | number;
    low: string | number;
    close: string | number;
    volumeUsd: string | number;
    tvlUsd: string | number;
  }>;
};

type TokenPoolsResponse = {
  tokenPools: Array<{
    pairAddress: string;
    token0: { address: string; symbol: string; name: string };
    token1: { address: string; symbol: string; name: string };
    tvlUsd: string | number;
    volumeUsd: string | number;
  }>;
};

type TokenTransactionsResponse = {
  tokenTransactions: Array<{
    id: string;
    timestamp: number;
    txHash: string;
    pairAddress: string;
    amountUsd: string | number;
    token0: { address: string; symbol: string; name: string };
    token1: { address: string; symbol: string; name: string };
    amount0In: string | number;
    amount1In: string | number;
    amount0Out: string | number;
    amount1Out: string | number;
  }>;
};

function asNumber(value: string | number | null | undefined): number {
  if (value === null || value === undefined) return 0;
  const n = typeof value === 'number' ? value : Number(value);
  return Number.isFinite(n) ? n : 0;
}

function asOptionalNumber(value: string | number | null | undefined): number | null {
  if (value === null || value === undefined) return null;
  const n = typeof value === 'number' ? value : Number(value);
  return Number.isFinite(n) ? n : null;
}

export class TokenHttpAdapter implements TokenPort {
  async getTokenDetails(input: GetTokenDetailsInput): Promise<TokenDetails | null> {
    const data = await gql<TokenDetailsResponse, { chainId: string; tokenAddress: string }>(
      `
        query TokenDetails($chainId: String!, $tokenAddress: String!) {
          tokenDetails(chainId: $chainId, tokenAddress: $tokenAddress) {
            chainId
            address
            symbol
            name
            decimals
            priceUsd
            change24hPct
            tvlUsd
            volume24hUsd
            fdvUsd
          }
        }
      `,
      {
        chainId: input.chainId,
        tokenAddress: input.tokenAddress,
      }
    );

    if (!data.tokenDetails) {
      return null;
    }

    return {
      chainId: data.tokenDetails.chainId,
      address: data.tokenDetails.address,
      symbol: data.tokenDetails.symbol,
      name: data.tokenDetails.name,
      decimals: data.tokenDetails.decimals,
      priceUsd: asNumber(data.tokenDetails.priceUsd),
      change24hPct: asOptionalNumber(data.tokenDetails.change24hPct),
      tvlUsd: asNumber(data.tokenDetails.tvlUsd),
      volume24hUsd: asNumber(data.tokenDetails.volume24hUsd),
      fdvUsd: asOptionalNumber(data.tokenDetails.fdvUsd),
    } satisfies TokenDetails;
  }

  async getTokenPriceCandles(input: GetTokenPriceCandlesInput): Promise<TokenOhlc[]> {
    const data = await gql<
      TokenPriceCandlesResponse,
      { chainId: string; tokenAddress: string; interval: string; from: number; to: number }
    >(
      `
        query TokenPriceCandles(
          $chainId: String!
          $tokenAddress: String!
          $interval: TokenChartInterval!
          $from: Int!
          $to: Int!
        ) {
          tokenPriceCandles(
            chainId: $chainId
            tokenAddress: $tokenAddress
            interval: $interval
            from: $from
            to: $to
          ) {
            timestamp
            open
            high
            low
            close
            volumeUsd
            tvlUsd
          }
        }
      `,
      {
        chainId: input.chainId,
        tokenAddress: input.tokenAddress,
        interval: input.interval,
        from: input.from,
        to: input.to,
      }
    );

    return data.tokenPriceCandles.map((candle) => ({
      timestamp: candle.timestamp,
      open: asNumber(candle.open),
      high: asNumber(candle.high),
      low: asNumber(candle.low),
      close: asNumber(candle.close),
      volumeUsd: asNumber(candle.volumeUsd),
      tvlUsd: asNumber(candle.tvlUsd),
    }));
  }

  async getTokenPools(input: GetTokenPoolsInput): Promise<TokenPoolRow[]> {
    const limit = input.limit ?? 10;
    const data = await gql<TokenPoolsResponse, { chainId: string; tokenAddress: string; limit: number }>(
      `
        query TokenPools($chainId: String!, $tokenAddress: String!, $limit: Int) {
          tokenPools(chainId: $chainId, tokenAddress: $tokenAddress, limit: $limit) {
            pairAddress
            tvlUsd
            volumeUsd
            token0 {
              address
              symbol
              name
            }
            token1 {
              address
              symbol
              name
            }
          }
        }
      `,
      { chainId: input.chainId, tokenAddress: input.tokenAddress, limit }
    );

    return data.tokenPools.map((row) => ({
      pairAddress: row.pairAddress,
      token0: row.token0,
      token1: row.token1,
      tvlUsd: asNumber(row.tvlUsd),
      volumeUsd: asNumber(row.volumeUsd),
    }));
  }

  async getTokenTransactions(input: GetTokenTransactionsInput): Promise<TokenTransactionRow[]> {
    const limit = input.limit ?? 25;
    const data = await gql<
      TokenTransactionsResponse,
      { chainId: string; tokenAddress: string; limit: number }
    >(
      `
        query TokenTransactions($chainId: String!, $tokenAddress: String!, $limit: Int) {
          tokenTransactions(chainId: $chainId, tokenAddress: $tokenAddress, limit: $limit) {
            id
            timestamp
            txHash
            pairAddress
            amountUsd
            token0 {
              address
              symbol
              name
            }
            token1 {
              address
              symbol
              name
            }
            amount0In
            amount1In
            amount0Out
            amount1Out
          }
        }
      `,
      { chainId: input.chainId, tokenAddress: input.tokenAddress, limit }
    );

    return data.tokenTransactions.map((row) => ({
      ...row,
      timestamp: Number(row.timestamp),
      amountUsd: asNumber(row.amountUsd),
      amount0In: asNumber(row.amount0In),
      amount1In: asNumber(row.amount1In),
      amount0Out: asNumber(row.amount0Out),
      amount1Out: asNumber(row.amount1Out),
    }));
  }
}

