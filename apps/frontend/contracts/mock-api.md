# Mock API Contract (Phase 1)

All responses are served via MSW and follow the shape below unless stated otherwise.

```ts
export type ApiError = {
  code: string;
  message: string;
  details?: Record<string, unknown>;
  traceId?: string;
  retryable?: boolean;
};
```

## Endpoints

### GET /api/swap/tokens

Returns mocked swap-able tokens.

```json
{
  "tokens": [
    { "address": "0xeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee", "symbol": "ETH", "decimals": 18 },
    { "address": "0xa0b86991c6218b36c1d19d4a2e9eb0ce3606eb48", "symbol": "USDC", "decimals": 6 },
    { "address": "0x6b175474e89094c44da98b954eedeac495271d0f", "symbol": "DAI", "decimals": 18 }
  ]
}
```

### POST /api/swap/quote

Request body: `{ amountIn: string, tokenIn: string, tokenOut: string }`

```json
{
  "quote": {
    "amountOut": "1672.56",
    "priceImpactBps": 2,
    "feeBps": 15,
    "route": "DripSwap v3"
  }
}
```

### GET /api/pools/summary

```json
{
  "summary": {
    "tvlUsd": 193100000,
    "volume24hUsd": 95330000,
    "updatedAt": "2024-07-25T08:00:00.000Z"
  }
}
```

### GET /api/pools/list

```json
{
  "pools": [
    {
      "id": "weth-usdc",
      "name": "WETH / USDC",
      "tvl": "$58.07m",
      "tvlChange": "-2.61%",
      "volume24h": "$290.95k",
      "fees24h": "$2.77m",
      "transactions24h": 99,
      "apr": "0.61%"
    }
  ]
}
```

### GET /api/pools/mine

```json
{
  "positions": [
    {
      "id": "weth-usdc",
      "pool": "WETH / USDC",
      "depositedUsd": "$12,400.00",
      "share": "1.92% of pool",
      "rewards": "48.20 USDC",
      "apr": "4.12%"
    }
  ]
}
```

### POST /api/pools/add/preview

Request body: `{ network: string, poolId: string, tokenA: string, tokenB: string }`

```json
{
  "preview": {
    "estimatedShare": "0.98%",
    "priceImpact": "0.15%",
    "protocolFeeUsd": "$4.10"
  }
}
```

### POST /api/pools/remove/preview

Request body: `{ poolId: string, percentage: number }`

```json
{
  "preview": {
    "expectedPayout": "1680.96 USDC",
    "gasFeeEth": "0.00054",
    "slippage": "0.5%"
  }
}
```

### POST /api/faucet/request

Request body: `{ network: string, token: string, amount: string, recipient: string }`

```json
{
  "request": {
    "id": "0xFAUCET123",
    "status": "COMPLETED"
  }
}
```

### POST /api/bridge/transfer

Request body: `{ fromNetwork: string, toNetwork: string, token: string, amount: string }`

```json
{
  "transfer": {
    "id": "0xBRIDGE123",
    "status": "PENDING"
  }
}
```

All error responses follow the shared `ApiError` structure with English messages.

<!-- npx repomix . -o dripswap.md -i "node_modules, dist, build, .git, .next, .vscode, coverage, *.lock, .env, .DS_Store, *.min.js, *.log" -->
