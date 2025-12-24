// V2: 简化版 filtering.rs，删除所有 Tick 和 Position 相关逻辑
use crate::pb::uniswap::events;
use crate::{abi, constants, Pool, BurnEvent, MintEvent, SwapEvent};
// V2: 删除未使用的 BigDecimal 和 BigInt 导入
use substreams::{log, Hex};
use substreams::scalar::BigInt;
use std::collections::HashMap;
use substreams_ethereum::block_view::CallView;
use substreams_ethereum::pb::eth::v2::{Log, TransactionTrace};

#[derive(Debug, Clone)]
pub struct TransferEvent {
    pub log_index: u64,
    pub from: String,
    pub to: String,
    pub value: BigInt,
    pub used: bool,
}

#[derive(Debug, Default)]
pub struct TransferContext {
    per_pool: HashMap<String, Vec<TransferEvent>>,
}

impl TransferContext {
    pub fn record_transfer(&mut self, pool_address: &str, log: &Log) {
        if !abi::pair::events::Transfer::match_log(log) {
            return;
        }
        let transfer = abi::pair::events::Transfer::decode(log).unwrap();
        let from = normalize_address(&Hex(&transfer.from).to_string());
        let to = normalize_address(&Hex(&transfer.to).to_string());

        // ignore initial transfers for first adds
        if to == normalize_address(constants::ZERO_ADDRESS) && transfer.value == BigInt::from(1000) {
            return;
        }

        self.per_pool
            .entry(pool_address.to_string())
            .or_default()
            .push(TransferEvent {
                log_index: log.block_index as u64,
                from,
                to,
                value: transfer.value,
                used: false,
            });
    }

    pub fn consume_mint(&mut self, pool_address: &str, mint_log_index: u64) -> Option<(String, BigInt)> {
        let events = self.per_pool.get_mut(pool_address)?;
        let zero_addr = normalize_address(constants::ZERO_ADDRESS);
        let mut selected: Option<usize> = None;

        for (idx, event) in events.iter().enumerate() {
            if event.used || event.from != zero_addr || event.to == zero_addr {
                continue;
            }
            if event.log_index > mint_log_index {
                continue;
            }
            if selected.is_none() || event.log_index > events[selected.unwrap()].log_index {
                selected = Some(idx);
            }
        }

        if let Some(idx) = selected {
            let event = &mut events[idx];
            event.used = true;
            return Some((event.to.clone(), event.value.clone()));
        }
        None
    }

    pub fn consume_burn(&mut self, pool_address: &str, burn_log_index: u64) -> Option<BigInt> {
        let events = self.per_pool.get_mut(pool_address)?;
        let pool_addr = normalize_address(pool_address);
        let zero_addr = normalize_address(constants::ZERO_ADDRESS);

        // prefer burn completion (pool -> zero) after burn event
        let mut selected: Option<usize> = None;
        for (idx, event) in events.iter().enumerate() {
            if event.used || event.from != pool_addr || event.to != zero_addr {
                continue;
            }
            if event.log_index < burn_log_index {
                continue;
            }
            if selected.is_none() || event.log_index < events[selected.unwrap()].log_index {
                selected = Some(idx);
            }
        }
        if let Some(idx) = selected {
            let event = &mut events[idx];
            event.used = true;
            return Some(event.value.clone());
        }

        // fallback: transfer to pool before burn event
        let mut selected: Option<usize> = None;
        for (idx, event) in events.iter().enumerate() {
            if event.used || event.to != pool_addr {
                continue;
            }
            if event.log_index > burn_log_index {
                continue;
            }
            if selected.is_none() || event.log_index > events[selected.unwrap()].log_index {
                selected = Some(idx);
            }
        }
        if let Some(idx) = selected {
            let event = &mut events[idx];
            event.used = true;
            return Some(event.value.clone());
        }

        None
    }
}

fn normalize_address(address: &str) -> String {
    address.trim_start_matches("0x").to_lowercase()
}

/// V2: 简化的事件提取函数，只处理 Swap/Mint/Burn
/// 删除了 Tick、Position、FeeGrowth 相关逻辑
pub fn extract_pool_events(
    pool_events: &mut Vec<events::PoolEvent>,
    transaction_id: &str,
    from: &str,
    log: &Log,
    _call_view: &CallView,  // V2: 不需要 storage changes
    pool: &Pool,
    timestamp: u64,
    block_number: u64,
    transfer_ctx: &mut TransferContext,
) {
    // V2: 使用 abi::pair 而非 abi::pool（但 proto 中仍使用 pool_event 类型以保持兼容）
    if abi::pair::events::Swap::match_log(log) {
        let swap = abi::pair::events::Swap::decode(log).unwrap();
        log::info!("V2 SWAP: transaction: {}", transaction_id);

        let token0 = pool.token0.as_ref().unwrap();
        let token1 = pool.token1.as_ref().unwrap();

        // V2: Swap 事件有 amount0_in/out 和 amount1_in/out，需要计算净变化
        // amount0 = amount0_out - amount0_in (正值表示 token0 输出，负值表示 token0 输入)
        let amount0 = (swap.amount0_out.clone() - swap.amount0_in.clone()).to_decimal(token0.decimals);
        let amount1 = (swap.amount1_out.clone() - swap.amount1_in.clone()).to_decimal(token1.decimals);

        pool_events.push(events::PoolEvent {
            log_ordinal: log.ordinal,
            log_index: log.block_index as u64,
            pool_address: pool.address.to_string(),
            token0: token0.address.clone(),
            token1: token1.address.clone(),
            fee: pool.fee_tier.clone(),
            transaction_id: transaction_id.to_string(),
            timestamp,
            created_at_block_number: block_number,
            r#type: Some(SwapEvent(events::pool_event::Swap {
                sender: Hex(&swap.sender).to_string(),
                recipient: Hex(&swap.to).to_string(),  // V2: 使用 to 而非 recipient
                origin: from.to_string(),
                amount_0: amount0.into(),
                amount_1: amount1.into(),
                sqrt_price: "0".to_string(),  // V2: 无 sqrtPrice，暂时填 0
                liquidity: "0".to_string(),   // V2: 从 reserves 计算
                tick: "0".to_string(),        // V2: 无 tick
            })),
        });
    } else if abi::pair::events::Mint::match_log(log) {
        let mint = abi::pair::events::Mint::decode(log).unwrap();
        log::info!("V2 MINT: transaction: {}", transaction_id);

        let token0 = pool.token0.as_ref().unwrap();
        let token1 = pool.token1.as_ref().unwrap();
        let amount0 = mint.amount0.to_decimal(token0.decimals);
        let amount1 = mint.amount1.to_decimal(token1.decimals);

        let (mint_to, mint_amount) = match transfer_ctx.consume_mint(&pool.address, log.block_index as u64) {
            Some((to, value)) => (to, value),
            None => (String::new(), BigInt::zero()),
        };
        let owner = if mint_to.is_empty() { Hex(&mint.sender).to_string() } else { mint_to };
        let amount = if mint_amount == BigInt::zero() { "0".to_string() } else { mint_amount.to_string() };

        pool_events.push(events::PoolEvent {
            log_ordinal: log.ordinal,
            log_index: log.block_index as u64,
            pool_address: pool.address.to_string(),
            token0: token0.address.clone(),
            token1: token1.address.clone(),
            fee: pool.fee_tier.clone(),
            transaction_id: transaction_id.to_string(),
            timestamp,
            created_at_block_number: block_number,
            r#type: Some(MintEvent(events::pool_event::Mint {
                owner,  // V2: Transfer(to) 作为 LP 接收地址
                sender: Hex(&mint.sender).to_string(),
                origin: from.to_string(),
                amount,  // V2: LP Token 增量，来自 Transfer
                amount_0: amount0.into(),
                amount_1: amount1.into(),
                tick_lower: "0".to_string(),  // V2: 无 tick
                tick_upper: "0".to_string(),  // V2: 无 tick
            })),
        });
    } else if abi::pair::events::Burn::match_log(log) {
        let burn = abi::pair::events::Burn::decode(log).unwrap();
        log::info!("V2 BURN: transaction: {}", transaction_id);

        let token0 = pool.token0.as_ref().unwrap();
        let token1 = pool.token1.as_ref().unwrap();

        let amount0 = burn.amount0.to_decimal(token0.decimals);
        let amount1 = burn.amount1.to_decimal(token1.decimals);

        let burn_amount = transfer_ctx
            .consume_burn(&pool.address, log.block_index as u64)
            .unwrap_or_default();
        let amount = if burn_amount == BigInt::zero() {
            "0".to_string()
        } else {
            burn_amount.to_string()
        };

        pool_events.push(events::PoolEvent {
            log_ordinal: log.ordinal,
            log_index: log.block_index as u64,
            pool_address: pool.address.to_string(),
            token0: token0.address.clone(),
            token1: token1.address.clone(),
            fee: pool.fee_tier.clone(),
            transaction_id: transaction_id.to_string(),
            timestamp,
            created_at_block_number: block_number,
            r#type: Some(BurnEvent(events::pool_event::Burn {
                owner: Hex(&burn.sender).to_string(),  // V2: Burn 事件中的 sender 是 LP 提供者
                origin: from.to_string(),
                amount,  // V2: LP Token 销毁量，来自 Transfer
                amount_0: amount0.into(),
                amount_1: amount1.into(),
                tick_lower: "0".to_string(),  // V2: 无 tick
                tick_upper: "0".to_string(),  // V2: 无 tick
            })),
        });
    }
}

// V2: 保留 extract_pool_liquidities，但简化逻辑
// V2 中不使用 storage changes，后续需要从 Sync 事件获取 reserves
pub fn extract_pool_liquidities(
    _pool_liquidities: &mut Vec<events::PoolLiquidity>,
    _log: &Log,
    _pool: &Pool,
) {
    // V2: 此函数在 V2 中不再使用
    // reserves 将从 Sync 事件中提取，而非 storage changes
    // 保留空函数以避免编译错误
}

// V2: 删除 extract_fee_growth_update，V2 不需要 fee growth tracking
// pub fn extract_fee_growth_update(...) { ... }

// V2: 保留但简化 extract_pool_sqrt_prices
// V2 中不使用 sqrtPrice，但保留函数以保持接口兼容
pub fn extract_pool_sqrt_prices(
    pool_sqrt_prices: &mut Vec<events::PoolSqrtPrice>,
    log: &Log,
    pool_address: &str,
    _pool: &Pool,
) {
    // V2: 仅处理 Sync 事件，用 reserve0/1 填充
    if abi::pair::events::Sync::match_log(log) {
        let event = abi::pair::events::Sync::decode(log).unwrap();
        pool_sqrt_prices.push(events::PoolSqrtPrice {
            pool_address: pool_address.to_string(),
            ordinal: log.ordinal,
            sqrt_price: event.reserve0.to_string(), // V2: reserve0
            tick: event.reserve1.to_string(),       // V2: reserve1
            initialized: false,
        });
    }
}

// V2: 简化 extract_transactions，删除 Position Manager 相关
pub fn extract_transactions(
    transactions: &mut Vec<events::Transaction>,
    log: &Log,
    transaction_trace: &TransactionTrace,
    timestamp_seconds: u64,
    block_number: u64,
) {
    use crate::utils;  // V2: 保留 load_transaction 函数
    
    let mut add_transaction = false;
    // V2: 只处理 Pair 事件，删除 Position Manager 事件
    if abi::pair::events::Burn::match_log(log)
        || abi::pair::events::Mint::match_log(log)
        || abi::pair::events::Swap::match_log(log)
    {
        add_transaction = true
    }

    if add_transaction {
        transactions.push(utils::load_transaction(
            block_number,
            timestamp_seconds,
            log.ordinal,
            transaction_trace,
        ));
    }
}

// V2: 删除所有 Position 相关函数
// fn extract_positions(...) { ... }
// pub fn extract_flashes(...) { ... }
