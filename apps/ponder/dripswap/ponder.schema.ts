import { onchainTable, primaryKey, relations } from "ponder";

export const uniswapFactory = onchainTable(
  "uniswap_factory",
  (t) => ({
    chainId: t.integer().notNull(),
    id: t.hex().notNull(),
    pairCount: t.bigint().notNull(),
    totalVolumeUSD: t.text().notNull(),
    totalVolumeETH: t.text().notNull(),
    untrackedVolumeUSD: t.text().notNull(),
    totalLiquidityUSD: t.text().notNull(),
    totalLiquidityETH: t.text().notNull(),
    txCount: t.bigint().notNull(),
  }),
  (table) => ({
    pk: primaryKey({ columns: [table.chainId, table.id] }),
  })
);

export const bundle = onchainTable(
  "bundle",
  (t) => ({
    chainId: t.integer().notNull(),
    id: t.text().notNull(),
    ethPrice: t.text().notNull(),
    oracleRoundId: t.bigint().notNull(),
    updatedAt: t.bigint().notNull(),
  }),
  (table) => ({
    pk: primaryKey({ columns: [table.chainId, table.id] }),
  })
);

export const token = onchainTable(
  "tokens",
  (t) => ({
    chainId: t.integer().notNull(),
    id: t.hex().notNull(),
    symbol: t.text().notNull(),
    name: t.text().notNull(),
    decimals: t.bigint().notNull(),
    totalSupply: t.bigint().notNull(),
    tradeVolume: t.bigint().notNull(),
    tradeVolumeUSD: t.text().notNull(),
    untrackedVolumeUSD: t.text().notNull(),
    txCount: t.bigint().notNull(),
    totalLiquidity: t.bigint().notNull(),
    derivedETH: t.text().notNull(),
  }),
  (table) => ({
    pk: primaryKey({ columns: [table.chainId, table.id] }),
  })
);

export const pair = onchainTable(
  "pairs",
  (t) => ({
    chainId: t.integer().notNull(),
    id: t.hex().notNull(),
    token0: t.hex().notNull(),
    token1: t.hex().notNull(),
    reserve0: t.bigint().notNull(),
    reserve1: t.bigint().notNull(),
    totalSupply: t.bigint().notNull(),
    reserveETH: t.text().notNull(),
    reserveUSD: t.text().notNull(),
    trackedReserveETH: t.text().notNull(),
    token0Price: t.text().notNull(),
    token1Price: t.text().notNull(),
    volumeToken0: t.bigint().notNull(),
    volumeToken1: t.bigint().notNull(),
    volumeUSD: t.text().notNull(),
    untrackedVolumeUSD: t.text().notNull(),
    txCount: t.bigint().notNull(),
    createdAtTimestamp: t.bigint().notNull(),
    createdAtBlockNumber: t.bigint().notNull(),
    liquidityProviderCount: t.bigint().notNull(),
  }),
  (table) => ({
    pk: primaryKey({ columns: [table.chainId, table.id] }),
  })
);

export const pairTokenLookup = onchainTable(
  "pair_token_lookup",
  (t) => ({
    chainId: t.integer().notNull(),
    id: t.text().notNull(),
    pair: t.hex().notNull(),
  }),
  (table) => ({
    pk: primaryKey({ columns: [table.chainId, table.id] }),
  })
);

export const user = onchainTable(
  "users",
  (t) => ({
    chainId: t.integer().notNull(),
    id: t.hex().notNull(),
  }),
  (table) => ({
    pk: primaryKey({ columns: [table.chainId, table.id] }),
  })
);

export const mint = onchainTable(
  "mints",
  (t) => ({
    chainId: t.integer().notNull(),
    id: t.text().notNull(),
    timestamp: t.bigint().notNull(),
    blockNumber: t.bigint().notNull(),
    blockTimestamp: t.bigint().notNull(),
    transactionHash: t.hex().notNull(),
    transactionIndex: t.integer().notNull(),
    logIndex: t.integer().notNull(),
    pair: t.hex().notNull(),
    to: t.hex().notNull(),
    liquidity: t.bigint().notNull(),
    sender: t.hex(),
    amount0: t.bigint(),
    amount1: t.bigint(),
    amountUSD: t.text(),
    feeTo: t.hex(),
    feeLiquidity: t.bigint(),
  }),
  (table) => ({
    pk: primaryKey({ columns: [table.chainId, table.id] }),
  })
);

export const burn = onchainTable(
  "burns",
  (t) => ({
    chainId: t.integer().notNull(),
    id: t.text().notNull(),
    timestamp: t.bigint().notNull(),
    blockNumber: t.bigint().notNull(),
    blockTimestamp: t.bigint().notNull(),
    transactionHash: t.hex().notNull(),
    transactionIndex: t.integer().notNull(),
    logIndex: t.integer().notNull(),
    pair: t.hex().notNull(),
    liquidity: t.bigint().notNull(),
    sender: t.hex(),
    amount0: t.bigint(),
    amount1: t.bigint(),
    to: t.hex(),
    amountUSD: t.text(),
    feeTo: t.hex(),
    feeLiquidity: t.bigint(),
  }),
  (table) => ({
    pk: primaryKey({ columns: [table.chainId, table.id] }),
  })
);

export const swap = onchainTable(
  "swaps",
  (t) => ({
    chainId: t.integer().notNull(),
    id: t.text().notNull(),
    timestamp: t.bigint().notNull(),
    blockNumber: t.bigint().notNull(),
    blockTimestamp: t.bigint().notNull(),
    transactionHash: t.hex().notNull(),
    transactionIndex: t.integer().notNull(),
    logIndex: t.integer().notNull(),
    pair: t.hex().notNull(),
    sender: t.hex().notNull(),
    from: t.hex().notNull(),
    amount0In: t.bigint().notNull(),
    amount1In: t.bigint().notNull(),
    amount0Out: t.bigint().notNull(),
    amount1Out: t.bigint().notNull(),
    to: t.hex().notNull(),
    amountUSD: t.text().notNull(),
  }),
  (table) => ({
    pk: primaryKey({ columns: [table.chainId, table.id] }),
  })
);

export const uniswapDayData = onchainTable(
  "uniswap_day_data",
  (t) => ({
    chainId: t.integer().notNull(),
    id: t.text().notNull(),
    date: t.integer().notNull(),
    dailyVolumeETH: t.text().notNull(),
    dailyVolumeUSD: t.text().notNull(),
    dailyVolumeUntracked: t.text().notNull(),
    totalVolumeETH: t.text().notNull(),
    totalLiquidityETH: t.text().notNull(),
    totalVolumeUSD: t.text().notNull(),
    totalLiquidityUSD: t.text().notNull(),
    txCount: t.bigint().notNull(),
  }),
  (table) => ({
    pk: primaryKey({ columns: [table.chainId, table.id] }),
  })
);

export const pairHourData = onchainTable(
  "pair_hour_data",
  (t) => ({
    chainId: t.integer().notNull(),
    id: t.text().notNull(),
    hourStartUnix: t.integer().notNull(),
    pair: t.hex().notNull(),
    reserve0: t.bigint().notNull(),
    reserve1: t.bigint().notNull(),
    totalSupply: t.bigint(),
    reserveUSD: t.text().notNull(),
    hourlyVolumeToken0: t.bigint().notNull(),
    hourlyVolumeToken1: t.bigint().notNull(),
    hourlyVolumeUSD: t.text().notNull(),
    hourlyTxns: t.bigint().notNull(),
  }),
  (table) => ({
    pk: primaryKey({ columns: [table.chainId, table.id] }),
  })
);

export const pairDayData = onchainTable(
  "pair_day_data",
  (t) => ({
    chainId: t.integer().notNull(),
    id: t.text().notNull(),
    date: t.integer().notNull(),
    pairAddress: t.hex().notNull(),
    token0: t.hex().notNull(),
    token1: t.hex().notNull(),
    reserve0: t.bigint().notNull(),
    reserve1: t.bigint().notNull(),
    totalSupply: t.bigint(),
    reserveUSD: t.text().notNull(),
    dailyVolumeToken0: t.bigint().notNull(),
    dailyVolumeToken1: t.bigint().notNull(),
    dailyVolumeUSD: t.text().notNull(),
    dailyTxns: t.bigint().notNull(),
  }),
  (table) => ({
    pk: primaryKey({ columns: [table.chainId, table.id] }),
  })
);

export const tokenDayData = onchainTable(
  "token_day_data",
  (t) => ({
    chainId: t.integer().notNull(),
    id: t.text().notNull(),
    date: t.integer().notNull(),
    token: t.hex().notNull(),
    dailyVolumeToken: t.bigint().notNull(),
    dailyVolumeETH: t.text().notNull(),
    dailyVolumeUSD: t.text().notNull(),
    dailyTxns: t.bigint().notNull(),
    totalLiquidityToken: t.bigint().notNull(),
    totalLiquidityETH: t.text().notNull(),
    totalLiquidityUSD: t.text().notNull(),
    priceUSD: t.text().notNull(),
  }),
  (table) => ({
    pk: primaryKey({ columns: [table.chainId, table.id] }),
  })
);

export const tokenHourData = onchainTable(
  "token_hour_data",
  (t) => ({
    chainId: t.integer().notNull(),
    id: t.text().notNull(),
    periodStartUnix: t.integer().notNull(),
    token: t.hex().notNull(),
    volumeToken: t.bigint().notNull(),
    volumeUSD: t.text().notNull(),
    untrackedVolumeUSD: t.text().notNull(),
    totalValueLockedToken: t.bigint().notNull(),
    totalValueLockedUSD: t.text().notNull(),
    priceUSD: t.text().notNull(),
    feesUSD: t.text().notNull(),
    open: t.text().notNull(),
    high: t.text().notNull(),
    low: t.text().notNull(),
    close: t.text().notNull(),
  }),
  (table) => ({
    pk: primaryKey({ columns: [table.chainId, table.id] }),
  })
);

export const tokenMinuteData = onchainTable(
  "token_minute_data",
  (t) => ({
    chainId: t.integer().notNull(),
    id: t.text().notNull(),
    periodStartUnix: t.integer().notNull(),
    token: t.hex().notNull(),
    volumeToken: t.bigint().notNull(),
    volumeUSD: t.text().notNull(),
    untrackedVolumeUSD: t.text().notNull(),
    totalValueLockedToken: t.bigint().notNull(),
    totalValueLockedUSD: t.text().notNull(),
    priceUSD: t.text().notNull(),
    feesUSD: t.text().notNull(),
    open: t.text().notNull(),
    high: t.text().notNull(),
    low: t.text().notNull(),
    close: t.text().notNull(),
  }),
  (table) => ({
    pk: primaryKey({ columns: [table.chainId, table.id] }),
  })
);

export const tokenRelations = relations(token, ({ many }) => ({
  pairsAsToken0: many(pair, { relationName: "pair_token0" }),
  pairsAsToken1: many(pair, { relationName: "pair_token1" }),
  pairDayDataBase: many(pairDayData, { relationName: "pair_day_token0" }),
  pairDayDataQuote: many(pairDayData, { relationName: "pair_day_token1" }),
  tokenDayData: many(tokenDayData),
  tokenHourData: many(tokenHourData),
  tokenMinuteData: many(tokenMinuteData),
}));

export const pairRelations = relations(pair, ({ one, many }) => ({
  token0: one(token, {
    fields: [pair.chainId, pair.token0],
    references: [token.chainId, token.id],
    relationName: "pair_token0",
  }),
  token1: one(token, {
    fields: [pair.chainId, pair.token1],
    references: [token.chainId, token.id],
    relationName: "pair_token1",
  }),
  mints: many(mint),
  burns: many(burn),
  swaps: many(swap),
  pairHourData: many(pairHourData),
  pairDayData: many(pairDayData),
}));

export const mintRelations = relations(mint, ({ one }) => ({
  pair: one(pair, {
    fields: [mint.chainId, mint.pair],
    references: [pair.chainId, pair.id],
  }),
}));

export const burnRelations = relations(burn, ({ one }) => ({
  pair: one(pair, {
    fields: [burn.chainId, burn.pair],
    references: [pair.chainId, pair.id],
  }),
}));

export const swapRelations = relations(swap, ({ one }) => ({
  pair: one(pair, {
    fields: [swap.chainId, swap.pair],
    references: [pair.chainId, pair.id],
  }),
}));

export const pairHourDataRelations = relations(pairHourData, ({ one }) => ({
  pair: one(pair, {
    fields: [pairHourData.chainId, pairHourData.pair],
    references: [pair.chainId, pair.id],
  }),
}));

export const pairDayDataRelations = relations(pairDayData, ({ one }) => ({
  pair: one(pair, {
    fields: [pairDayData.chainId, pairDayData.pairAddress],
    references: [pair.chainId, pair.id],
  }),
  token0: one(token, {
    fields: [pairDayData.chainId, pairDayData.token0],
    references: [token.chainId, token.id],
    relationName: "pair_day_token0",
  }),
  token1: one(token, {
    fields: [pairDayData.chainId, pairDayData.token1],
    references: [token.chainId, token.id],
    relationName: "pair_day_token1",
  }),
}));

export const tokenDayDataRelations = relations(tokenDayData, ({ one }) => ({
  token: one(token, {
    fields: [tokenDayData.chainId, tokenDayData.token],
    references: [token.chainId, token.id],
  }),
}));

export const pairTokenLookupRelations = relations(pairTokenLookup, ({ one }) => ({
  pair: one(pair, {
    fields: [pairTokenLookup.chainId, pairTokenLookup.pair],
    references: [pair.chainId, pair.id],
  }),
}));

export const tokenHourDataRelations = relations(tokenHourData, ({ one }) => ({
  token: one(token, {
    fields: [tokenHourData.chainId, tokenHourData.token],
    references: [token.chainId, token.id],
  }),
}));

export const tokenMinuteDataRelations = relations(tokenMinuteData, ({ one }) => ({
  token: one(token, {
    fields: [tokenMinuteData.chainId, tokenMinuteData.token],
    references: [token.chainId, token.id],
  }),
}));
