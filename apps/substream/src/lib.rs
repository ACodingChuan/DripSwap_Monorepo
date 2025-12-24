extern crate core;

pub mod abi;
pub mod pb;
pub mod price;
pub mod utils;
pub mod constants;  // V2: 新增常量配置
mod ast;
mod db;
mod eth;
mod filtering;
mod math;
mod rpc;
mod storage;

use crate::ethpb::v2::Block;
use crate::pb::uniswap;
// V2: 保留 pool_event 因为我们复用了 Swap/Mint/Burn 事件类型
// V2: 删除未使用的 Type 导入
use crate::pb::uniswap::events::pool_event::Type::{Burn as BurnEvent, Mint as MintEvent, Swap as SwapEvent};
// V2: 删除 Position 相关导入（V3 特有）
// use crate::pb::uniswap::events::position_event::Type::{...};
use crate::pb::uniswap::events::PoolSqrtPrice;  // V2: 改为 PairReserves
use crate::pb::uniswap::{events, Events};
use crate::pb::uniswap::{Erc20Token, Erc20Tokens, Pool, Pools};  // V2: Pool 改为 Pair
use crate::price::WHITELIST_TOKENS;
use crate::utils::UNISWAP_V2_FACTORY;  // V2: 使用 V2 Factory
// V2: 删除未使用的 constants 导入
use std::collections::{HashMap, HashSet};
use std::ops::{Div, Mul, Sub};
use std::str::FromStr;
use substreams::errors::Error;
use substreams::key;
use substreams::pb::substreams::{store_delta, Clock};
use substreams::prelude::*;
use substreams::scalar::{BigDecimal, BigInt};
use substreams::store::{
    DeltaArray, DeltaBigDecimal, DeltaBigInt, DeltaExt, DeltaProto, StoreAddBigDecimal, StoreAddBigInt, StoreAppend,
    StoreGetBigDecimal, StoreGetBigInt, StoreGetProto, StoreGetRaw, StoreSetBigDecimal, StoreSetBigInt, StoreSetProto,
};
use substreams::{log, Hex};
use substreams_entity_change::pb::entity::EntityChanges;
use substreams_entity_change::tables::Tables;
use substreams_ethereum::{pb::eth as ethpb};  // V2: 删除未使用的 Event trait

#[substreams::handlers::map]
pub fn map_pools_created(block: Block) -> Result<Pools, Error> {
    use abi::factory::events::PairCreated;  // V2: 使用 PairCreated 事件

    Ok(Pools {
        pools: block
            .events::<PairCreated>(&[&UNISWAP_V2_FACTORY])  // V2: 监听 V2 Factory
            .filter_map(|(event, log)| {
                log::info!("pair addr: {}", Hex(&event.pair));

                let token0_address = Hex(&event.token0).to_string();
                let token1_address = Hex(&event.token1).to_string();

                // V2: 创建 Pair 数据结构
                Some(Pool {
                    address: Hex(&event.pair).to_string(),  // V2: 直接从事件获取 pair 地址
                    transaction_id: Hex(&log.receipt.transaction.hash).to_string(),
                    created_at_block_number: block.number,
                    created_at_timestamp: block.timestamp_seconds(),
                    fee_tier: "3000".to_string(),  // V2: 固定费率 0.3% = 3000
                    tick_spacing: 0,  // V2: 无 tick spacing 概念
                    log_ordinal: log.ordinal(),
                    ignore_pool: false,  // V2: 移除 ERROR_POOL 检查
                    token0: Some(match rpc::create_uniswap_token(&token0_address) {
                        Some(mut token) => {
                            token.total_supply = rpc::token_total_supply_call(&token0_address)
                                .unwrap_or(BigInt::zero())
                                .to_string();
                            token
                        }
                        None => {
                            log::info!("ignoring creating of pair addr: {}", Hex(&event.pair));
                            return None;
                        }
                    }),
                    token1: Some(match rpc::create_uniswap_token(&token1_address) {
                        Some(mut token) => {
                            token.total_supply = rpc::token_total_supply_call(&token1_address)
                                .unwrap_or(BigInt::zero())
                                .to_string();
                            token
                        }
                        None => {
                            log::info!("ignoring creating of pair addr: {}", Hex(&event.pair));
                            return None;
                        }
                    }),
                    ..Default::default()
                })
            })
            .collect(),
    })
}

#[substreams::handlers::store]
pub fn store_pools_created(pools: Pools, store: StoreSetProto<Pool>) {
    for pool in pools.pools {
        let pool_address = &pool.address;
        store.set(pool.log_ordinal, format!("pair:{pool_address}"), &pool);  // V2: 使用 pair: 前缀
    }
}

#[substreams::handlers::store]
pub fn store_tokens(pools: Pools, store: StoreAddInt64) {
    for pool in pools.pools {
        let token0_addr = pool.token0_ref().address();
        let token1_addr = pool.token1_ref().address();

        store.add_many(
            pool.log_ordinal,
            &vec![format!("token:{token0_addr}"), format!("token:{token1_addr}")],
            1,
        );
    }
}

#[substreams::handlers::store]
pub fn store_pool_count(pools: Pools, store: StoreAddBigInt) {
    for pool in pools.pools {
        store.add(pool.log_ordinal, format!("factory:pairCount"), &BigInt::one())  // V2: 使用 pairCount
    }
}

#[substreams::handlers::map]
pub fn map_tokens_whitelist_pools(pools: Pools) -> Result<Erc20Tokens, Error> {
    let mut tokens = vec![];

    for pool in pools.pools {
        let mut token0 = pool.token0();
        let mut token1 = pool.token1();

        let token0_whitelisted = WHITELIST_TOKENS.contains(&token0.address.as_str());
        let token1_whitelisted = WHITELIST_TOKENS.contains(&token1.address.as_str());

        if token0_whitelisted {
            log::info!("adding pair: {} to token: {}", pool.address, token1.address);  // V2: pair 而非 pool
            token1.whitelist_pools.push(pool.address.to_string());
            tokens.push(token1);
        }

        if token1_whitelisted {
            log::info!("adding pair: {} to token: {}", pool.address, token0.address);  // V2: pair 而非 pool
            token0.whitelist_pools.push(pool.address.to_string());
            tokens.push(token0);
        }
    }

    Ok(Erc20Tokens { tokens })
}

#[substreams::handlers::store]
pub fn store_tokens_whitelist_pools(tokens: Erc20Tokens, output_append: StoreAppend<String>) {
    for token in tokens.tokens {
        output_append.append_all(1, format!("token:{}", token.address), token.whitelist_pools);
    }
}

#[substreams::handlers::map]
pub fn map_extract_data_types(block: Block, pools_store: StoreGetProto<Pool>) -> Result<Events, Error> {
    let mut events = Events::default();

    // V2: 保留基础事件，删除 V3 特有内容
    let mut pool_sqrt_prices: Vec<events::PoolSqrtPrice> = vec![];  // V2: 后续改为 PairReserves
    let mut pool_liquidities: Vec<events::PoolLiquidity> = vec![];  // V2: 后续改为存储 reserves
    let mut pool_events: Vec<events::PoolEvent> = vec![];  // V2: 保留 Swap/Mint/Burn
    let mut transactions: Vec<events::Transaction> = vec![];
    // V2: 删除 Tick 和 Position 相关
    // let mut ticks_created: Vec<events::TickCreated> = vec![];
    // let mut ticks_updated: Vec<events::TickUpdated> = vec![];
    // let mut positions_created: Vec<events::CreatedPosition> = vec![];
    // ...

    let timestamp = block.timestamp_seconds();

    for trx in block.transactions() {
        let mut transfer_ctx = filtering::TransferContext::default();
        for (log, _) in trx.logs_with_calls() {
            let pool_address = &Hex(log.clone().address).to_string();
            if pools_store.get_last(format!("pair:{pool_address}")).is_none() {
                continue;
            }
            transfer_ctx.record_transfer(pool_address, log);
        }
        for (log, call_view) in trx.logs_with_calls() {
            let pool_address = &Hex(log.clone().address).to_string();
            let transactions_id = Hex(&trx.hash).to_string();

            let pool_opt = pools_store.get_last(format!("pair:{pool_address}"));  // V2: 使用 pair: 前缀
            if pool_opt.is_none() {
                continue;
            }
            let pool = pool_opt.unwrap();
            
            // V2: 保留基础事件提取，移除 Tick 和 Position
            filtering::extract_pool_sqrt_prices(&mut pool_sqrt_prices, log, pool_address, &pool);
            filtering::extract_pool_liquidities(&mut pool_liquidities, log, &pool);  // V2: 简化函数签名
            
            // V2: 简化事件提取，只保留 pool_events
            filtering::extract_pool_events(
                &mut pool_events,
                &transactions_id,
                &Hex(&trx.from).to_string(),
                log,
                &call_view,
                &pool,
                timestamp,
                block.number,
                &mut transfer_ctx,
            );

            filtering::extract_transactions(&mut transactions, log, &trx, timestamp, block.number);
        }
    }

    events.pool_sqrt_prices = pool_sqrt_prices;
    events.pool_liquidities = pool_liquidities;
    events.pool_events = pool_events;
    events.transactions = transactions;
    // V2: 不填充 Tick 和 Position 数据

    Ok(events)
}

#[substreams::handlers::store]
pub fn store_pool_sqrt_price(events: Events, store: StoreSetProto<PoolSqrtPrice>) {
    for sqrt_price in events.pool_sqrt_prices {
        let pool_address = &sqrt_price.pool_address;
        store.set(sqrt_price.ordinal, format!("pool:{pool_address}"), &sqrt_price)
    }
}

#[substreams::handlers::store]
pub fn store_prices(clock: Clock, events: Events, pools_store: StoreGetProto<Pool>, store: StoreSetBigDecimal) {
    let timestamp_seconds = clock.timestamp.unwrap().seconds;
    let day_id: i64 = timestamp_seconds / 86400;
    let hour_id: i64 = timestamp_seconds / 3600;
    let prev_day_id = day_id - 1;
    let prev_hour_id = hour_id - 1;

    store.delete_prefix(0, &format!("PoolDayData:{prev_day_id}:"));
    store.delete_prefix(0, &format!("PoolHourData:{prev_hour_id}:"));

    for sqrt_price_update in events.pool_sqrt_prices {
        let pool_address = &sqrt_price_update.pool_address;
        match pools_store.get_last(format!("pair:{pool_address}")) {
            None => {
                log::info!("skipping pool {}", &pool_address);
                continue;
            }
            Some(pool) => {
                let token0 = pool.token0.as_ref().unwrap();
                let token1 = pool.token1.as_ref().unwrap();
                log::debug!(
                    "pool addr: {}, token 0 addr: {}, token 1 addr: {}",
                    pool.address,
                    token0.address,
                    token1.address
                );

                let reserve0_raw = BigInt::from_str(&sqrt_price_update.sqrt_price).unwrap_or_default();
                let reserve1_raw = BigInt::from_str(&sqrt_price_update.tick).unwrap_or_default();
                let reserve0 = reserve0_raw.to_decimal(token0.decimals);
                let reserve1 = reserve1_raw.to_decimal(token1.decimals);

                if reserve0 == BigDecimal::zero() || reserve1 == BigDecimal::zero() {
                    continue;
                }

                let token0_price = reserve0.clone().div(reserve1.clone());
                let token1_price = reserve1.clone().div(reserve0.clone());
                log::debug!("token prices: {} {}", token0_price, token1_price);

                let token0_addr = &token0.address;
                let token1_addr = &token1.address;
                store.set_many(
                    sqrt_price_update.ordinal,
                    &vec![
                        format!("pool:{pool_address}:{token0_addr}:token0"),
                        format!("pair:{token0_addr}:{token1_addr}"), // used for find_eth_per_token
                    ],
                    &token0_price,
                );

                store.set_many(
                    sqrt_price_update.ordinal,
                    &vec![
                        format!("pool:{pool_address}:{token1_addr}:token1"),
                        format!("pair:{token1_addr}:{token0_addr}"), // used for find_eth_per_token
                    ],
                    &token1_price,
                );

                // We only want to set the prices of PoolDayData and PoolHourData when
                // the pool is post-initialized, not on the initialized event.
                if sqrt_price_update.initialized {
                    continue;
                }

                store.set_many(
                    sqrt_price_update.ordinal,
                    &vec![
                        // We only need the token0Prices to compute the open, high, low and close
                        format!("PoolDayData:{day_id}:{pool_address}:token0"),
                        format!("PoolHourData:{hour_id}:{pool_address}:token0"),
                    ],
                    &token0_price,
                );

                store.set_many(
                    sqrt_price_update.ordinal,
                    &vec![
                        format!("PoolDayData:{day_id}:{pool_address}:token1"),
                        format!("PoolHourData:{hour_id}:{pool_address}:token1"),
                    ],
                    &token1_price,
                );
            }
        }
    }
}

#[substreams::handlers::store]
pub fn store_pool_liquidities(
    clock: Clock,
    events: Events,
    pools_store: StoreGetProto<Pool>,
    store: StoreSetBigInt,
) {
    let timestamp_seconds = clock.timestamp.unwrap().seconds;
    let day_id: i64 = timestamp_seconds / 86400;
    let hour_id: i64 = timestamp_seconds / 3600;
    let prev_day_id = day_id - 1;
    let prev_hour_id = hour_id - 1;

    store.delete_prefix(0, &format!("PoolDayData:{prev_day_id}:"));
    store.delete_prefix(0, &format!("PoolHourData:{prev_hour_id}:"));

    for pool_reserves in events.pool_sqrt_prices {
        let pool_address = &pool_reserves.pool_address;
        let pool = match pools_store.get_last(format!("pair:{pool_address}")) {
            Some(pool) => pool,
            None => continue,
        };
        let token0_address = &pool.token0.as_ref().unwrap().address;
        let token1_address = &pool.token1.as_ref().unwrap().address;

        let reserve0 = BigInt::from_str(&pool_reserves.sqrt_price).unwrap_or_default();
        let reserve1 = BigInt::from_str(&pool_reserves.tick).unwrap_or_default();
        let total_liquidity = reserve0 + reserve1;
        store.set_many(
            pool_reserves.ordinal,
            &vec![
                format!("pool:{pool_address}"),
                format!("pair:{token0_address}:{token1_address}"),
                format!("pair:{token1_address}:{token0_address}"),
                format!("PoolDayData:{day_id}:{pool_address}"),
                format!("PoolHourData:{hour_id}:{pool_address}"),
            ],
            &total_liquidity,
        );
    }
}

#[substreams::handlers::store]
pub fn store_total_tx_counts(clock: Clock, events: Events, output: StoreAddBigInt) {
    let timestamp_seconds = clock.timestamp.unwrap().seconds;
    let day_id = timestamp_seconds / 86400;
    let hour_id = timestamp_seconds / 3600;
    let minute_id = timestamp_seconds / 60;
    let prev_day_id = day_id - 1;
    let prev_hour_id = hour_id - 1;
    let prev_minute_id = minute_id - 1;
    let factory_addr = Hex(UNISWAP_V2_FACTORY);  // V2: 使用 V2 Factory 地址

    output.delete_prefix(0, &format!("UniswapDayData:{prev_day_id}:"));
    output.delete_prefix(0, &format!("PoolDayData:{prev_day_id}:"));
    output.delete_prefix(0, &format!("PoolHourData:{prev_hour_id}:"));
    output.delete_prefix(0, &format!("TokenDayData:{prev_day_id}:"));
    output.delete_prefix(0, &format!("TokenHourData:{prev_hour_id}:"));
    output.delete_prefix(0, &format!("TokenMinuteData:{prev_minute_id}:"));
    output.delete_prefix(0, &format!("TokenMinuteData:{prev_minute_id}:"));
    output.delete_prefix(0, &format!("TokenMinuteData:{prev_minute_id}:"));

    for event in events.pool_events {
        let pool_address = &event.pool_address;
        let token0_addr = &event.token0;
        let token1_addr = &event.token1;

        output.add_many(
            event.log_ordinal,
            &vec![
                format!("pool:{pool_address}"),
                format!("token:{token0_addr}"),
                format!("token:{token1_addr}"),
                format!("factory:{factory_addr}"),
                format!("UniswapDayData:{day_id}"),
                format!("PoolDayData:{day_id}:{pool_address}"),
                format!("PoolHourData:{hour_id}:{pool_address}"),
                format!("TokenDayData:{day_id}:{token0_addr}"),
                format!("TokenDayData:{day_id}:{token1_addr}"),
                format!("TokenHourData:{hour_id}:{token0_addr}"),
                format!("TokenHourData:{hour_id}:{token1_addr}"),
                format!("TokenMinuteData:{minute_id}:{token0_addr}"),
                format!("TokenMinuteData:{minute_id}:{token1_addr}"),
            ],
            &BigInt::from(1 as i32),
        );
    }
}

#[substreams::handlers::store]
pub fn store_swaps_volume(
    clock: Clock,
    events: Events,
    store_pool: StoreGetProto<Pool>,
    store_total_tx_counts: StoreGetBigInt,
    store_eth_prices: StoreGetBigDecimal,
    output: StoreAddBigDecimal,
) {
    let timestamp_seconds = clock.timestamp.unwrap().seconds;
    let day_id = timestamp_seconds / 86400;
    let hour_id = timestamp_seconds / 3600;
    let minute_id = timestamp_seconds / 60;
    let prev_day_id = day_id - 1;
    let prev_hour_id = hour_id - 1;
    let prev_minute_id = minute_id - 1;

    output.delete_prefix(0, &format!("UniswapDayData:{prev_day_id}:"));
    output.delete_prefix(0, &format!("PoolDayData:{prev_day_id}:"));
    output.delete_prefix(0, &format!("PoolHourData:{prev_hour_id}:"));
    output.delete_prefix(0, &format!("TokenDayData:{prev_day_id}:"));
    output.delete_prefix(0, &format!("TokenHourData:{prev_hour_id}:"));
    output.delete_prefix(0, &format!("TokenMinuteData:{prev_minute_id}:"));

    for event in events.pool_events {
        let ord = event.log_ordinal;
        let pool_address = &event.pool_address;
        let pool = store_pool.must_get_last(format!("pair:{pool_address}"));
        if !store_total_tx_counts.has_last(format!("pool:{pool_address}")) {
            continue;
        }

        let token0_addr = &event.token0;
        let token1_addr = &event.token1;
        match event.r#type.unwrap() {
            MintEvent(_) => output.add(
                ord,
                format!("pool:{pool_address}:liquidityProviderCount"),
                &BigDecimal::one(),
            ),
            SwapEvent(swap) => {
                log::info!("transaction: {}", pool.transaction_id);
                let eth_price_in_usd: BigDecimal = match store_eth_prices.get_at(ord, "bundle") {
                    None => {
                        panic!("bundle eth price not found")
                    }
                    Some(price) => price,
                };

                let token0_derived_eth_price =
                    match store_eth_prices.get_at(ord, format!("token:{token0_addr}:dprice:eth")) {
                        None => continue,
                        Some(price) => price,
                    };

                let token1_derived_eth_price =
                    match store_eth_prices.get_at(ord, format!("token:{token1_addr}:dprice:eth")) {
                        None => continue,
                        Some(price) => price,
                    };

                log::info!("token0_derived_eth_price {}", token0_derived_eth_price);
                log::info!("token1_derived_eth_price {}", token1_derived_eth_price);

                let amount0_abs = BigDecimal::try_from(swap.amount_0).unwrap().absolute();
                let amount1_abs = BigDecimal::try_from(swap.amount_1).unwrap().absolute();

                log::info!("amount0_abs {}", amount0_abs);
                log::info!("amount1_abs {}", amount1_abs);

                let volume_amounts = utils::get_adjusted_amounts(
                    token0_addr,
                    token1_addr,
                    &amount0_abs,
                    &amount1_abs,
                    &token0_derived_eth_price,
                    &token1_derived_eth_price,
                    &eth_price_in_usd,
                );

                log::info!("volumeAmounts.eth {}", volume_amounts.delta_tvl_eth);
                log::info!("volumeAmounts.usd {}", volume_amounts.delta_tvl_usd);
                log::info!("volumeAmounts.untrackedETH {}", volume_amounts.stable_eth_untracked);
                log::info!("volumeAmounts.untrackedUSD {}", volume_amounts.stable_usd_untracked);

                let volume_eth = volume_amounts.delta_tvl_eth.clone().div(BigDecimal::from(2 as i32));
                let volume_usd = volume_amounts.delta_tvl_usd.clone().div(BigDecimal::from(2 as i32));
                let volume_usd_untracked = volume_amounts
                    .stable_usd_untracked
                    .clone()
                    .div(BigDecimal::from(2 as i32));

                let fee_tier = BigDecimal::try_from(pool.fee_tier).unwrap();
                let fee_eth: BigDecimal = volume_eth
                    .clone()
                    .mul(fee_tier.clone())
                    .div(BigDecimal::from(1000000 as u64));
                let fee_usd: BigDecimal = volume_usd
                    .clone()
                    .mul(fee_tier.clone())
                    .div(BigDecimal::from(1000000 as u64));

                log::info!("volume_eth {}", volume_eth);
                log::info!("volume_usd {}", volume_usd);
                log::info!("volume_usd_untracked {}", volume_usd_untracked);
                log::info!("fee_eth {}", fee_eth);
                log::info!("fee_usd {}", fee_usd);
                log::info!("fee_tier {}", fee_tier);

                output.add_many(
                    ord,
                    &vec![
                        format!("pool:{pool_address}:volumeToken0"),
                        // FIXME: why compute volumes only for one side of the tokens?!  We should compute them for both sides no?
                        //  Does it really matter which side the volume comes from?
                        format!("token:{token0_addr}:volume"),
                        format!("PoolDayData:{day_id}:{pool_address}:{token0_addr}:volumeToken0"),
                        format!("TokenDayData:{day_id}:{token0_addr}:volume"),
                        format!("PoolHourData:{hour_id}:{pool_address}:{token0_addr}:volumeToken0"),
                        format!("TokenHourData:{hour_id}:{token0_addr}:volume"),
                        format!("TokenMinuteData:{minute_id}:{token0_addr}:volume"),
                    ],
                    &amount0_abs,
                );
                output.add_many(
                    ord,
                    &vec![
                        format!("pool:{pool_address}:volumeToken1"),
                        format!("token:{token1_addr}:volume"),
                        format!("PoolDayData:{day_id}:{pool_address}:{token1_addr}:volumeToken1"),
                        format!("TokenDayData:{day_id}:{token1_addr}:volume"),
                        format!("PoolHourData:{hour_id}:{pool_address}:{token1_addr}:volumeToken1"),
                        format!("TokenHourData:{hour_id}:{token1_addr}:volume"),
                        format!("TokenMinuteData:{minute_id}:{token1_addr}:volume"),
                    ],
                    &amount1_abs,
                );
                output.add_many(
                    ord,
                    &vec![
                        format!("pool:{pool_address}:volumeUSD"),
                        format!("token:{token0_addr}:volume:usd"), // TODO: does this make sens that the volume usd is the same
                        format!("token:{token1_addr}:volume:usd"), // TODO: does this make sens that the volume usd is the same
                        format!("factory:totalVolumeUSD"),
                        format!("UniswapDayData:{day_id}:volumeUSD"),
                        format!("PoolDayData:{day_id}:{pool_address}:volumeUSD"),
                        format!("TokenDayData:{day_id}:{token0_addr}:volumeUSD"),
                        format!("TokenDayData:{day_id}:{token1_addr}:volumeUSD"),
                        format!("PoolHourData:{hour_id}:{pool_address}:volumeUSD"),
                        format!("TokenHourData:{hour_id}:{token0_addr}:volumeUSD"),
                        format!("TokenHourData:{hour_id}:{token1_addr}:volumeUSD"),
                        format!("TokenMinuteData:{minute_id}:{token0_addr}:volumeUSD"),
                        format!("TokenMinuteData:{minute_id}:{token1_addr}:volumeUSD"),
                    ],
                    //TODO: CONFIRM EQUALS -> IN THE SUBGRAPH THIS IS THE VOLUME USD
                    &volume_usd,
                );
                output.add_many(
                    ord,
                    &vec![
                        format!("factory:untrackedVolumeUSD"),
                        format!("pool:{pool_address}:volumeUntrackedUSD"),
                        format!("token:{token0_addr}:volume:untrackedUSD"),
                        format!("token:{token1_addr}:volume:untrackedUSD"),
                        format!("TokenDayData:{day_id}:{token0_addr}:volume:untrackedUSD"),
                        format!("TokenDayData:{day_id}:{token1_addr}:volume:untrackedUSD"),
                        format!("TokenHourData:{hour_id}:{token0_addr}:volume:untrackedUSD"),
                        format!("TokenHourData:{hour_id}:{token1_addr}:volume:untrackedUSD"),
                        format!("TokenMinuteData:{minute_id}:{token0_addr}:volume:untrackedUSD"),
                        format!("TokenMinuteData:{minute_id}:{token1_addr}:volume:untrackedUSD"),
                    ],
                    &volume_usd_untracked,
                );
                output.add_many(
                    ord,
                    &vec![
                        format!("factory:totalVolumeETH"),
                        format!("UniswapDayData:{day_id}:volumeETH"),
                    ],
                    &volume_eth.clone(),
                );
                output.add_many(
                    ord,
                    &vec![
                        format!("pool:{pool_address}:feesUSD"),
                        format!("token:{token0_addr}:feesUSD"),
                        format!("token:{token1_addr}:feesUSD"),
                        format!("factory:totalFeesUSD"),
                        format!("UniswapDayData:{day_id}:feesUSD"),
                        format!("PoolDayData:{day_id}:{pool_address}:feesUSD"),
                        format!("TokenDayData:{day_id}:{token0_addr}:feesUSD"),
                        format!("TokenDayData:{day_id}:{token1_addr}:feesUSD"),
                        format!("PoolHourData:{hour_id}:{pool_address}:feesUSD"),
                        format!("TokenHourData:{hour_id}:{token0_addr}:feesUSD"),
                        format!("TokenHourData:{hour_id}:{token1_addr}:feesUSD"),
                        format!("TokenMinuteData:{minute_id}:{token0_addr}:feesUSD"),
                        format!("TokenMinuteData:{minute_id}:{token1_addr}:feesUSD"),
                    ],
                    &fee_usd,
                );
                output.add(ord, format!("factory:totalFeesETH"), &fee_eth);
            }
            _ => {}
        }
    }
}

/**
 * STORE NATIVE AMOUNTS -> spits out any mint, swap and burn amounts
 */
#[substreams::handlers::store]
pub fn store_native_amounts(events: Events, pools_store: StoreGetProto<Pool>, store: StoreSetBigDecimal) {
    for reserves in events.pool_sqrt_prices {
        let pool_address = &reserves.pool_address;
        let pool = match pools_store.get_last(format!("pair:{pool_address}")) {
            Some(pool) => pool,
            None => continue,
        };

        let token0 = pool.token0.as_ref().unwrap();
        let token1 = pool.token1.as_ref().unwrap();

        let reserve0_raw = BigInt::from_str(&reserves.sqrt_price).unwrap_or_default();
        let reserve1_raw = BigInt::from_str(&reserves.tick).unwrap_or_default();
        let reserve0 = reserve0_raw.to_decimal(token0.decimals);
        let reserve1 = reserve1_raw.to_decimal(token1.decimals);

        store.set(
            reserves.ordinal,
            format!("pool:{pool_address}:{token0_addr}:native", token0_addr = token0.address),
            &reserve0,
        );
        store.set(
            reserves.ordinal,
            format!("pool:{pool_address}:{token1_addr}:native", token1_addr = token1.address),
            &reserve1,
        );
    }
}

#[substreams::handlers::store]
pub fn store_eth_prices(
    clock: Clock,
    events: Events,                                /* map_extract_data_types */
    pools_store: StoreGetProto<Pool>,              /* store_pools_created */
    prices_store: StoreGetBigDecimal,              /* store_prices */
    tokens_whitelist_pools_store: StoreGetRaw,     /* store_tokens_whitelist_pools */
    total_native_amount_store: StoreGetBigDecimal, /* store_native_amounts */
    pool_liquidities_store: StoreGetBigInt,        /* store_pool_liquidities */
    output: StoreSetBigDecimal,
) {
    let timestamp_seconds = clock.timestamp.unwrap().seconds;
    let day_id = timestamp_seconds / 86400;
    let hour_id = timestamp_seconds / 3600;
    let minute_id = timestamp_seconds / 60;
    let prev_day_id = day_id - 1;
    let prev_hour_id = hour_id - 1;
    let prev_minute_id = minute_id - 1;

    output.delete_prefix(0, &format!("TokenDayData:{prev_day_id}:"));
    output.delete_prefix(0, &format!("TokenHourData:{prev_hour_id}:"));
    output.delete_prefix(0, &format!("TokenMinuteData:{prev_minute_id}:"));

    let oracle_price_opt = price::get_eth_price_from_oracle();

    for pool_sqrt_price in events.pool_sqrt_prices {
        let ord = pool_sqrt_price.ordinal;
        log::debug!(
            "handling pool price update - addr: {} price: {}",
            pool_sqrt_price.pool_address,
            pool_sqrt_price.sqrt_price
        );
        let pool_address = &pool_sqrt_price.pool_address;
        let pool = pools_store.must_get_last(format!("pair:{pool_address}"));
        let token0 = pool.token0.as_ref().unwrap();
        let token1 = pool.token1.as_ref().unwrap();
        let token0_addr = &token0.address;
        let token1_addr = &token1.address;

        token0.log();
        token1.log();

        let bundle_eth_price_usd = match &oracle_price_opt {
            Some(oracle_price) => oracle_price.price.clone(),
            None => price::get_eth_price_in_usd(&prices_store, ord),
        };
        log::info!("bundle_eth_price_usd: {}", bundle_eth_price_usd);

        let token0_derived_eth_price: BigDecimal = price::find_eth_per_token(
            ord,
            &pool.address,
            token0_addr,
            &pools_store,
            &pool_liquidities_store,
            &tokens_whitelist_pools_store,
            &total_native_amount_store,
            &prices_store,
            &bundle_eth_price_usd,
        );
        log::info!(format!(
            "token 0 {token0_addr} derived eth price: {token0_derived_eth_price}"
        ));

        let token1_derived_eth_price: BigDecimal = price::find_eth_per_token(
            ord,
            &pool.address,
            token1_addr,
            &pools_store,
            &pool_liquidities_store,
            &tokens_whitelist_pools_store,
            &total_native_amount_store,
            &prices_store,
            &bundle_eth_price_usd,
        );
        log::info!(format!(
            "token 1 {token1_addr} derived eth price: {token1_derived_eth_price}"
        ));

        output.set(ord, "bundle", &bundle_eth_price_usd);
        if let Some(oracle_price) = &oracle_price_opt {
            let round_id_decimal =
                BigDecimal::from_str(&oracle_price.round_id.to_string()).unwrap_or_default();
            output.set(ord, "bundle:roundId", &round_id_decimal);
        } else {
            output.set(ord, "bundle:roundId", &BigDecimal::zero());
        }
        output.set(
            ord,
            format!("token:{token0_addr}:dprice:eth"),
            &token0_derived_eth_price,
        );
        output.set(
            ord,
            format!("token:{token1_addr}:dprice:eth"),
            &token1_derived_eth_price,
        );

        let token0_price_usd = token0_derived_eth_price.clone().mul(bundle_eth_price_usd.clone());
        let token1_price_usd = token1_derived_eth_price.clone().mul(bundle_eth_price_usd);

        log::info!("token0 price usd: {}", token0_price_usd);
        log::info!("token1 price usd: {}", token1_price_usd);

        // We only want to set the prices of TokenDayData and TokenHourData when
        // the pool is post-initialized, not on the initialized event.
        if pool_sqrt_price.initialized {
            continue;
        }

        output.set_many(
            ord,
            &vec![
                format!("TokenDayData:{day_id}:{token0_addr}"),
                format!("TokenHourData:{hour_id}:{token0_addr}"),
                format!("TokenMinuteData:{minute_id}:{token0_addr}"),
            ],
            &token0_price_usd,
        );
        output.set_many(
            ord,
            &vec![
                format!("TokenDayData:{day_id}:{token1_addr}"),
                format!("TokenHourData:{hour_id}:{token1_addr}"),
                format!("TokenMinuteData:{minute_id}:{token1_addr}"),
            ],
            &token1_price_usd,
        );
    }
}

#[substreams::handlers::store]
pub fn store_token_tvl(
    reserve_deltas: Deltas<DeltaBigDecimal>, /* store_native_amounts */
    pools_store: StoreGetProto<Pool>,        /* store_pools_created */
    output: StoreAddBigDecimal,
) {
    for delta in reserve_deltas.deltas {
        if !delta.key.starts_with("pool:") || !delta.key.ends_with(":native") {
            continue;
        }
        let mut parts = delta.key.split(':');
        let _ = parts.next();
        let pool_address = match parts.next() {
            Some(value) => value,
            None => continue,
        };
        let token_addr = match parts.next() {
            Some(value) => value,
            None => continue,
        };

        let pool = match pools_store.get_last(format!("pair:{pool_address}")) {
            Some(pool) => pool,
            None => continue,
        };
        let token0_addr = pool.token0.as_ref().unwrap().address.as_str();
        let token1_addr = pool.token1.as_ref().unwrap().address.as_str();
        let token_key = if token_addr == token0_addr {
            "token0"
        } else if token_addr == token1_addr {
            "token1"
        } else {
            continue;
        };

        let delta_diff = calculate_diff(&delta);
        output.add_many(
            delta.ordinal,
            &vec![
                format!("pool:{pool_address}:{token_addr}:{token_key}"),
                format!("token:{token_addr}"),
            ],
            &delta_diff,
        );
    }
}

#[substreams::handlers::store]
pub fn store_derived_tvl(
    clock: Clock,
    reserve_deltas: Deltas<DeltaBigDecimal>,      /* store_native_amounts */
    token_total_value_locked: StoreGetBigDecimal, /* store_token_tvl  */
    pools_store: StoreGetProto<Pool>,
    eth_prices_store: StoreGetBigDecimal,
    reserves_store: StoreGetBigDecimal,           /* store_native_amounts */
    output: StoreSetBigDecimal,
) {
    let timestamp_seconds = clock.timestamp.unwrap().seconds;
    let day_id: i64 = timestamp_seconds / 86400;
    let hour_id: i64 = timestamp_seconds / 3600;
    let minute_id: i64 = timestamp_seconds / 60;
    let prev_day_id = day_id - 1;
    let prev_hour_id = hour_id - 1;
    let prev_minute_id = minute_id - 1;

    output.delete_prefix(0, &format!("PoolDayData:{prev_day_id}:"));
    output.delete_prefix(0, &format!("PoolHourData:{prev_hour_id}:"));
    output.delete_prefix(0, &format!("TokenDayData:{prev_day_id}:"));
    output.delete_prefix(0, &format!("TokenHourData:{prev_hour_id}:"));
    output.delete_prefix(0, &format!("TokenMinuteData:{prev_minute_id}:"));

    for delta in reserve_deltas.deltas {
        if !delta.key.starts_with("pool:") || !delta.key.ends_with(":native") {
            continue;
        }
        let mut parts = delta.key.split(':');
        let _ = parts.next();
        let pool_address = match parts.next() {
            Some(value) => value,
            None => continue,
        };

        let ord = delta.ordinal;
        let eth_price_usd = match &eth_prices_store.get_at(ord, "bundle") {
            None => continue,
            Some(price) => price.with_prec(100),
        };
        log::info!("eth_price_usd {}", eth_price_usd);

        let pool = pools_store.must_get_last(format!("pair:{pool_address}"));
        let token0_addr = &pool.token0.as_ref().unwrap().address();
        let token1_addr = &pool.token1.as_ref().unwrap().address();

        log::info!("pool address {}", pool_address);
        log::info!("token0 address {}", token0_addr);
        log::info!("token1 address {}", token1_addr);

        let token0_derive_eth = utils::get_derived_eth_price(ord, token0_addr, &eth_prices_store);
        let token1_derive_eth = utils::get_derived_eth_price(ord, token1_addr, &eth_prices_store);

        let reserve0 = match reserves_store.get_at(ord, format!("pool:{pool_address}:{token0_addr}:native")) {
            Some(value) => value,
            None => continue,
        };
        let reserve1 = match reserves_store.get_at(ord, format!("pool:{pool_address}:{token1_addr}:native")) {
            Some(value) => value,
            None => continue,
        };

        let tvl_for_token0 = utils::get_token_tvl(ord, token0_addr, &token_total_value_locked);
        let tvl_for_token1 = utils::get_token_tvl(ord, token1_addr, &token_total_value_locked);

        log::info!("reserve0 in pool: {}", reserve0);
        log::info!("reserve1 in pool: {}", reserve1);
        log::info!("total_value_locked_token0 for token: {}", tvl_for_token0);
        log::info!("total_value_locked_token1 for token: {}", tvl_for_token1);

        // // not sure about this part
        // let derived_token0_eth = tvl_token0_in_pool.clone().mul(token0_derive_eth.clone());
        // let derived_token1_eth = tvl_token1_in_pool.clone().mul(token1_derive_eth.clone());
        // log::info!("derived_token0_eth: {}", derived_token0_eth);
        // log::info!("derived_token1_eth: {}", derived_token1_eth);

        let amounts_in_pool = utils::get_adjusted_amounts(
            token0_addr,
            token1_addr,
            &reserve0,
            &reserve1,
            &token0_derive_eth,
            &token1_derive_eth,
            &eth_price_usd,
        );
        // let amounts_for_token = utils::get_adjusted_amounts(
        //     token0_addr,
        //     token1_addr,
        //     &tvl_for_token0,
        //     &tvl_for_token1,
        //     &token0_derive_eth,
        //     &token1_derive_eth,
        //     &eth_price_usd,
        // );

        let derived_tvl_usd_for_token0 = tvl_for_token0
            .clone()
            .mul(token0_derive_eth.clone().mul(eth_price_usd.clone()));
        let derived_tvl_usd_for_token1 = tvl_for_token1
            .clone()
            .mul(token1_derive_eth.clone().mul(eth_price_usd.clone()));

        output.set_many(
            ord,
            &vec![
                format!("token:{token0_addr}:totalValueLockedUSD"),
                format!("TokenDayData:{day_id}:{token0_addr}:totalValueLockedUSD"),
                format!("TokenHourData:{hour_id}:{token0_addr}:totalValueLockedUSD"),
                format!("TokenMinuteData:{minute_id}:{token0_addr}:totalValueLockedUSD"),
            ],
            &derived_tvl_usd_for_token0, // token0.totalValueLockedUSD
        );
        output.set_many(
            ord,
            &vec![
                format!("token:{token1_addr}:totalValueLockedUSD"),
                format!("TokenDayData:{day_id}:{token1_addr}:totalValueLockedUSD"),
                format!("TokenHourData:{hour_id}:{token1_addr}:totalValueLockedUSD"),
                format!("TokenMinuteData:{minute_id}:{token1_addr}:totalValueLockedUSD"),
            ],
            &derived_tvl_usd_for_token1, // token1.totalValueLockedUSD
        );

        output.set(
            ord,
            format!("pool:{pool_address}:totalValueLockedETH"),
            &amounts_in_pool.delta_tvl_eth, // pool.totalValueLockedETH
        );

        output.set_many(
            ord,
            &vec![
                format!("pool:{pool_address}:totalValueLockedUSD"),
                format!("PoolDayData:{day_id}:{pool_address}:totalValueLockedUSD"),
                format!("PoolHourData:{hour_id}:{pool_address}:totalValueLockedUSD"),
            ],
            &amounts_in_pool.delta_tvl_usd, // pool.totalValueLockedUSD
        );

        // pool.totalValueLockedETHUntracked
        output.set(
            pool_event.log_ordinal,
            format!("pool:{pool_address}:totalValueLockedETHUntracked"),
            &amounts_in_pool.stable_eth_untracked,
        );

        // pool.totalValueLockedUSDUntracked
        output.set(
            ord,
            format!("pool:{pool_address}:totalValueLockedUSDUntracked"),
            &amounts_in_pool.stable_usd_untracked,
        );
    }
}

#[substreams::handlers::store]
pub fn store_derived_factory_tvl(
    clock: Clock,
    derived_tvl_deltas: Deltas<DeltaBigDecimal>,
    output: StoreAddBigDecimal,
) {
    let timestamp_seconds = clock.timestamp.unwrap().seconds;
    let day_id: i64 = timestamp_seconds / 86400;
    let prev_day_id = day_id - 1;
    output.delete_prefix(0, &format!("UniswapDayData:{prev_day_id}:"));

    for delta in derived_tvl_deltas.into_iter().key_first_segment_eq("pool") {
        log::info!("delta key {}", delta.key);
        log::info!("delta old {}", delta.old_value);
        log::info!("delta new {}", delta.new_value);
        let delta_diff = &calculate_diff(&delta);
        let ord = delta.ordinal;

        log::info!("delta diff {}", delta_diff);

        // why do we calculate the diff
        match key::last_segment(&delta.key) {
            "totalValueLockedETH" => {
                log::info!("adding factory:totalValueLockedETH {}", delta_diff);
                output.add(ord, &format!("factory:totalValueLockedETH"), delta_diff)
            }
            "totalValueLockedETHUntracked" => {
                output.add(ord, &format!("factory:totalValueLockedETHUntracked"), delta_diff)
            }
            "totalValueLockedUSD" => output.add_many(
                ord,
                &vec![
                    format!("factory:totalValueLockedUSD"),
                    format!("UniswapDayData:{day_id}:totalValueLockedUSD"),
                ],
                delta_diff,
            ),
            "totalValueLockedUSDUntracked" => {
                output.add(ord, &format!("factory:totalValueLockedUSDUntracked"), delta_diff)
            }
            _ => {}
        }
    }
}

fn calculate_diff(delta: &DeltaBigDecimal) -> BigDecimal {
    let old_value = delta.old_value.clone();
    let new_value = delta.new_value.clone();
    return new_value.clone().sub(old_value);
}

#[derive(Debug, Clone)]
pub struct FeeMint {
    pub fee_to: String,
    pub fee_liquidity: BigDecimal,
}

fn collect_transfer_users(block: &Block, pools_store: &StoreGetProto<Pool>) -> Vec<String> {
    let mut users: HashSet<String> = HashSet::new();
    let zero_addr = constants::ZERO_ADDRESS.trim_start_matches("0x").to_lowercase();

    for trx in block.transactions() {
        for (log, _) in trx.logs_with_calls() {
            let pool_address = Hex(log.address.clone()).to_string();
            if pools_store.get_last(format!("pair:{pool_address}")).is_none() {
                continue;
            }
            if !abi::pair::events::Transfer::match_log(&log) {
                continue;
            }
            let transfer = abi::pair::events::Transfer::decode(&log).unwrap();
            let from = Hex(&transfer.from).to_string().trim_start_matches("0x").to_lowercase();
            let to = Hex(&transfer.to).to_string().trim_start_matches("0x").to_lowercase();

            if !from.is_empty() && from != zero_addr {
                users.insert(from);
            }
            if !to.is_empty() && to != zero_addr {
                users.insert(to);
            }
        }
    }

    users.into_iter().collect()
}

fn collect_fee_mints(
    block: &Block,
    pools_store: &StoreGetProto<Pool>,
    events: &Events,
) -> HashMap<String, FeeMint> {
    let mut mint_logs_by_tx_pool: HashMap<String, Vec<u64>> = HashMap::new();

    for pool_event in &events.pool_events {
        if let Some(MintEvent(_)) = pool_event.r#type.as_ref() {
            let key = format!("{}:{}", pool_event.transaction_id, pool_event.pool_address);
            mint_logs_by_tx_pool
                .entry(key)
                .or_default()
                .push(pool_event.log_index);
        }
    }

    let mut fee_mints: HashMap<String, FeeMint> = HashMap::new();
    let zero_addr = constants::ZERO_ADDRESS.trim_start_matches("0x").to_lowercase();

    for trx in block.transactions() {
        let tx_id = Hex(&trx.hash).to_string();
        let mut transfers_by_pool: HashMap<String, Vec<(u64, String, BigInt)>> = HashMap::new();

        for (log, _) in trx.logs_with_calls() {
            let pool_address = Hex(log.address.clone()).to_string();
            if pools_store.get_last(format!("pair:{pool_address}")).is_none() {
                continue;
            }
            if !abi::pair::events::Transfer::match_log(&log) {
                continue;
            }
            let transfer = abi::pair::events::Transfer::decode(&log).unwrap();
            let from = Hex(&transfer.from).to_string().trim_start_matches("0x").to_lowercase();
            if from != zero_addr {
                continue;
            }
            let to = Hex(&transfer.to).to_string();
            let to_normalized = to.trim_start_matches("0x").to_lowercase();
            if to_normalized == zero_addr {
                continue;
            }

            transfers_by_pool
                .entry(pool_address)
                .or_default()
                .push((log.block_index as u64, to, transfer.value));
        }

        for (pool_address, mut transfers) in transfers_by_pool {
            transfers.sort_by_key(|entry| entry.0);
            let key = format!("{tx_id}:{pool_address}");
            let mut mint_logs = mint_logs_by_tx_pool.get(&key).cloned().unwrap_or_default();
            mint_logs.sort();

            let mut used = vec![false; transfers.len()];
            for mint_log in mint_logs {
                let mut selected: Option<usize> = None;
                for (idx, (log_index, _, _)) in transfers.iter().enumerate() {
                    if used[idx] || *log_index > mint_log {
                        continue;
                    }
                    if selected.is_none() || *log_index > transfers[selected.unwrap()].0 {
                        selected = Some(idx);
                    }
                }
                if let Some(idx) = selected {
                    used[idx] = true;
                }
            }

            let mut selected_fee: Option<(u64, String, BigInt)> = None;
            for (idx, transfer) in transfers.into_iter().enumerate() {
                if used[idx] {
                    continue;
                }
                if selected_fee.is_none() || transfer.0 > selected_fee.as_ref().unwrap().0 {
                    selected_fee = Some(transfer);
                }
            }

            if let Some((_, fee_to, fee_liquidity_raw)) = selected_fee {
                let fee_liquidity = fee_liquidity_raw.to_decimal(18);
                fee_mints.insert(
                    key,
                    FeeMint {
                        fee_to: fee_to.trim_start_matches("0x").to_lowercase(),
                        fee_liquidity,
                    },
                );
            }
        }
    }

    fee_mints
}

// V2: 删除 store_ticks_liquidities - V2 无 Tick 概念

// V2: 删除 store_positions - V2 使用 LP Token 而非 Position NFT

#[substreams::handlers::store]
pub fn store_min_windows(
    clock: Clock,
    prices_deltas: Deltas<DeltaBigDecimal>,     /* store_prices */
    eth_prices_deltas: Deltas<DeltaBigDecimal>, /* store_eth_prices */
    output: StoreMinBigDecimal,
) {
    let mut deltas = prices_deltas.deltas;
    let mut eth_deltas = eth_prices_deltas.deltas;
    deltas.append(&mut eth_deltas);
    deltas.sort_by(|x, y| x.ordinal.cmp(&y.ordinal));

    let timestamp_seconds = clock.timestamp.unwrap().seconds;
    let day_id = timestamp_seconds / 86400;
    let hour_id = timestamp_seconds / 3600;
    let minute_id = timestamp_seconds / 60;
    let prev_day_id = day_id - 1;
    let prev_hour_id = hour_id - 1;
    let prev_minute_id = minute_id - 1;

    output.delete_prefix(0, &format!("PoolDayData:{prev_day_id}:"));
    output.delete_prefix(0, &format!("PoolHourData:{prev_hour_id}:"));
    output.delete_prefix(0, &format!("TokenDayData:{prev_day_id}:"));
    output.delete_prefix(0, &format!("TokenHourData:{prev_hour_id}:"));
    output.delete_prefix(0, &format!("TokenMinuteData:{prev_minute_id}:"));

    for delta in deltas {
        if delta.operation == store_delta::Operation::Delete {
            continue;
        }

        let table_name = match key::first_segment(&delta.key) {
            "PoolDayData" => {
                if key::last_segment(&delta.key) != "token0" {
                    continue;
                }
                "PoolDayData"
            }
            "PoolHourData" => {
                if key::last_segment(&delta.key) != "token0" {
                    continue;
                }
                "PoolHourData"
            }
            "TokenDayData" => "TokenDayData",
            "TokenHourData" => "TokenHourData",
            "TokenMinuteData" => "TokenMinuteData",
            _ => continue,
        };

        let time_id = key::segment_at(&delta.key, 1);
        let address = key::segment_at(&delta.key, 2);

        if delta.operation == store_delta::Operation::Create {
            output.min(
                delta.ordinal,
                format!("{table_name}:{time_id}:{address}:open"),
                &delta.new_value,
            );
        }

        output.min(
            delta.ordinal,
            format!("{table_name}:{time_id}:{address}:low"),
            &delta.new_value,
        );
    }
}

#[substreams::handlers::store]
pub fn store_max_windows(
    clock: Clock,
    prices_deltas: Deltas<DeltaBigDecimal>,     /* store_prices */
    eth_prices_deltas: Deltas<DeltaBigDecimal>, /* store_eth_prices */
    output: StoreMaxBigDecimal,
) {
    let mut deltas = prices_deltas.deltas;
    let mut eth_deltas = eth_prices_deltas.deltas;
    deltas.append(&mut eth_deltas);
    deltas.sort_by(|x, y| x.ordinal.cmp(&y.ordinal));

    let timestamp_seconds = clock.timestamp.unwrap().seconds;
    let day_id = timestamp_seconds / 86400;
    let hour_id = timestamp_seconds / 3600;
    let minute_id = timestamp_seconds / 60;
    let prev_day_id = day_id - 1;
    let prev_hour_id = hour_id - 1;
    let prev_minute_id = minute_id - 1;

    output.delete_prefix(0, &format!("PoolDayData:{prev_day_id}:"));
    output.delete_prefix(0, &format!("PoolHourData:{prev_hour_id}:"));
    output.delete_prefix(0, &format!("TokenDayData:{prev_day_id}:"));
    output.delete_prefix(0, &format!("TokenHourData:{prev_hour_id}:"));
    output.delete_prefix(0, &format!("TokenMinuteData:{prev_minute_id}:"));

    for delta in deltas {
        if delta.operation == store_delta::Operation::Delete {
            continue;
        }

        let table_name = match key::first_segment(&delta.key) {
            "PoolDayData" => {
                if key::last_segment(&delta.key) != "token0" {
                    continue;
                }
                "PoolDayData"
            }
            "PoolHourData" => {
                if key::last_segment(&delta.key) != "token0" {
                    continue;
                }
                "PoolHourData"
            }
            "TokenDayData" => "TokenDayData",
            "TokenHourData" => "TokenHourData",
            "TokenMinuteData" => "TokenMinuteData",
            _ => continue,
        };

        let day_id = key::segment_at(&delta.key, 1);
        let pool_address = key::segment_at(&delta.key, 2);

        output.max(
            delta.ordinal,
            format!("{table_name}:{day_id}:{pool_address}:high"),
            delta.new_value,
        );
    }
}

#[substreams::handlers::map]
pub fn graph_out(
    clock: Clock,
    block: Block,                                   /* sf.ethereum.type.v2.Block */
    pool_count_deltas: Deltas<DeltaBigInt>,              /* store_pool_count */
    tx_count_deltas: Deltas<DeltaBigInt>,                /* store_total_tx_counts deltas */
    swaps_volume_deltas: Deltas<DeltaBigDecimal>,        /* store_swaps_volume */
    derived_factory_tvl_deltas: Deltas<DeltaBigDecimal>, /* store_derived_factory_tvl */
    derived_eth_prices_deltas: Deltas<DeltaBigDecimal>,  /* store_eth_prices */
    events: Events,                                      /* map_extract_data_types */
    pools_created: Pools,                                /* map_pools_created */
    pools_store: StoreGetProto<Pool>,                    /* store_pools_created */
    pool_sqrt_price_deltas: Deltas<DeltaProto<PoolSqrtPrice>>, /* store_pool_sqrt_price */
    pool_sqrt_price_store: StoreGetProto<PoolSqrtPrice>, /* store_pool_sqrt_price */
    pool_liquidities_store_deltas: Deltas<DeltaBigInt>,  /* store_pool_liquidities */
    token_tvl_deltas: Deltas<DeltaBigDecimal>,           /* store_token_tvl */
    price_deltas: Deltas<DeltaBigDecimal>,               /* store_prices */
    store_prices: StoreGetBigDecimal,                    /* store_prices */
    tokens_store: StoreGetInt64,                         /* store_tokens */
    tokens_whitelist_pools_deltas: Deltas<DeltaArray<String>>, /* store_tokens_whitelist_pools */
    derived_tvl_deltas: Deltas<DeltaBigDecimal>,         /* store_derived_tvl */
    // V2: 删除 ticks_liquidities_deltas - V2 无 Tick 概念
    tx_count_store: StoreGetBigInt,                      /* store_total_tx_counts */
    store_eth_prices: StoreGetBigDecimal,                /* store_eth_prices */
    // V2: 删除 store_positions - V2 使用 LP Token 而非 Position NFT
    min_windows_deltas: Deltas<DeltaBigDecimal>,         /* store_min_windows */
    max_windows_deltas: Deltas<DeltaBigDecimal>,         /* store_max_windows */
) -> Result<EntityChanges, Error> {
    let mut tables = Tables::new();
    let timestamp = clock.timestamp.unwrap().seconds;

    if clock.number == 12369621 {
        // FIXME: Hard-coded start block, how could we pull that from the manifest?
        // FIXME: ideally taken from the params of the module
        db::factory_created_factory_entity_change(&mut tables);
        db::created_bundle_entity_change(&mut tables);
    }

    // Bundle
    db::bundle_store_eth_price_usd_bundle_entity_change(&mut tables, &derived_eth_prices_deltas);

    // Factory:
    db::pool_created_factory_entity_change(&mut tables, &pool_count_deltas);
    db::tx_count_factory_entity_change(&mut tables, &tx_count_deltas);
    db::swap_volume_factory_entity_change(&mut tables, &swaps_volume_deltas);
    db::tvl_factory_entity_change(&mut tables, &derived_factory_tvl_deltas);

    // Pool:
    db::pools_created_pool_entity_changes(&mut tables, &pools_created);
    db::sqrt_price_and_tick_pool_entity_change(&mut tables, &pool_sqrt_price_deltas);
    db::liquidities_pool_entity_change(&mut tables, &pool_liquidities_store_deltas);
    // V2: 删除 fee_growth_global - V2 不追踪费用增长
    // db::fee_growth_global_pool_entity_change(&mut tables, &events.fee_growth_global_updates);
    db::total_value_locked_pool_entity_change(&mut tables, &derived_tvl_deltas);
    db::total_value_locked_by_token_pool_entity_change(&mut tables, &token_tvl_deltas);
    db::price_pool_entity_change(&mut tables, &price_deltas);
    db::tx_count_pool_entity_change(&mut tables, &tx_count_deltas);
    db::swap_volume_pool_entity_change(&mut tables, &swaps_volume_deltas);

    // Tokens:
    db::tokens_created_token_entity_changes(&mut tables, &pools_created, tokens_store);
    db::swap_volume_token_entity_change(&mut tables, &swaps_volume_deltas);
    db::tx_count_token_entity_change(&mut tables, &tx_count_deltas);
    db::total_value_locked_by_token_token_entity_change(&mut tables, &token_tvl_deltas);
    db::total_value_locked_usd_token_entity_change(&mut tables, &derived_tvl_deltas);
    db::derived_eth_prices_token_entity_change(&mut tables, &derived_eth_prices_deltas);
    db::whitelist_token_entity_change(&mut tables, tokens_whitelist_pools_deltas);

    // Users + PairTokenLookup:
    let transfer_users = collect_transfer_users(&block, &pools_store);
    db::users_created_entity_changes(&mut tables, &events.pool_events, &transfer_users);
    db::pair_token_lookup_entity_changes(&mut tables, &pools_created);

    // V2: 删除 Tick 相关处理 - V2 无 Tick 概念
    // db::create_tick_entity_change(&mut tables, &events.ticks_created);
    // db::update_tick_entity_change(&mut tables, &events.ticks_updated);
    // db::liquidities_tick_entity_change(&mut tables, &ticks_liquidities_deltas);

    // Tick Day/Hour data
    // db::create_entity_tick_windows(&mut tables, &events.ticks_created);
    // db::update_tick_windows(&mut tables, &events.ticks_updated);
    // db::liquidities_tick_windows(&mut tables, &ticks_liquidities_deltas);

    // V2: 删除 Position 相关处理 - V2 使用 LP Token 而非 Position NFT
    // db::position_create_entity_change(&mut tables, &events.created_positions);
    // db::increase_liquidity_position_entity_change(&mut tables, &events.increase_liquidity_positions);
    // db::decrease_liquidity_position_entity_change(&mut tables, &events.decrease_liquidity_positions);
    // db::collect_position_entity_change(&mut tables, &events.collect_positions);
    // db::transfer_position_entity_change(&mut tables, &events.transfer_positions);

    // PositionSnapshot:
    // TODO: validate all the snapshot positions here
    // db::snapshot_positions_create_entity_change(&mut tables, &events.created_positions);
    // db::increase_liquidity_snapshot_position_entity_change(
    //     &mut tables,
    //     clock.number,
    //     &events.increase_liquidity_positions,
    //     &store_positions,
    // );
    // db::decrease_liquidity_snapshot_position_entity_change(
    //     &mut tables,
    //     clock.number,
    //     &events.decrease_liquidity_positions,
    //     &store_positions,
    // );
    // db::collect_snapshot_position_entity_change(&mut tables, clock.number, &events.collect_positions, &store_positions);
    // db::transfer_snapshot_position_entity_change(
    //     &mut tables,
    //     clock.number,
    //     &events.transfer_positions,
    //     &store_positions,
    // );

    // Transaction:
    db::transaction_entity_change(&mut tables, &events.transactions);

    // Swap, Mint, Burn:
    let fee_mints = collect_fee_mints(&block, &pools_store, &events);
    db::swaps_mints_burns_created_entity_change(
        &mut tables,
        &events.pool_events,
        tx_count_store,
        store_eth_prices,
        &fee_mints,
    );

    // Flashes:
    // TODO: should we implement flashes entity change - UNISWAP has not done this part
    // db::flashes_update_pool_fee_entity_change(&mut tables, events.flashes);

    // Uniswap day data:
    db::uniswap_day_data_create(&mut tables, &tx_count_deltas);
    db::uniswap_day_data_update(
        &mut tables,
        &swaps_volume_deltas,
        &derived_factory_tvl_deltas,
        &tx_count_deltas,
    );

    // Pool Day/Hour data:
    db::pool_windows_create(&mut tables, &tx_count_deltas);
    db::pool_windows_update(
        &mut tables,
        timestamp,
        &tx_count_deltas,
        &swaps_volume_deltas,
        &events,
        &pool_sqrt_price_store,
        &pool_liquidities_store_deltas,
        &price_deltas,
        &store_prices,
        &derived_tvl_deltas,
        &min_windows_deltas,
        &max_windows_deltas,
    );

    // Token Day/Hour data:
    db::token_windows_create(&mut tables, &tx_count_deltas);
    db::token_windows_update(
        &mut tables,
        timestamp,
        &swaps_volume_deltas,
        &derived_tvl_deltas,
        &min_windows_deltas,
        &max_windows_deltas,
        &derived_eth_prices_deltas,
        &token_tvl_deltas,
    );

    // Bridge:
    db::bridge_events_entity_changes(&mut tables, &block);

    Ok(tables.to_entity_changes())
}
