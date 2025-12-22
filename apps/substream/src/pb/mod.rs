// V2: 删除 Position 相关导入（V3 特有）
// use crate::pb::uniswap::events::position_event::Type::{...};
use crate::pb::uniswap::events::pool_event::Type;
use crate::pb::uniswap::events::PoolEvent;
// V2: 删除 ERROR_POOL 引用（V3 特有）
use crate::{Erc20Token, Pool};
use substreams::scalar::BigDecimal;
use substreams::log;

#[allow(unused_imports)]
#[allow(dead_code)]
#[path = "./uniswap.types.v1.rs"]
pub mod uniswap;

// V2: 删除 PositionEvent impl（V3 特有）
// impl PositionEvent { ... }

impl Erc20Token {
    pub fn log(&self) {
        log::info!(
            "token addr: {}, name: {}, symbol: {}, decimals: {}",
            self.address,
            self.name,
            self.symbol,
            self.decimals
        );
    }
}

impl Pool {
    // V2: 简化 should_handle 函数，删除 ERROR_POOL 检查
    pub fn should_handle_swap(&self) -> bool {
        !self.ignore_pool
    }

    pub fn should_handle_mint_and_burn(&self) -> bool {
        !self.ignore_pool
    }

    pub fn token0_ref(&self) -> &Erc20Token {
        self.token0.as_ref().unwrap()
    }
    pub fn token1_ref(&self) -> &Erc20Token {
        self.token1.as_ref().unwrap()
    }
    pub fn token0(&self) -> Erc20Token {
        self.clone().token0.unwrap()
    }
    pub fn token1(&self) -> Erc20Token {
        self.clone().token1.unwrap()
    }
}

impl Erc20Token {
    pub fn address(&self) -> &String {
        &self.address
    }
}

pub struct TokenAmounts {
    pub amount0: BigDecimal,
    pub amount1: BigDecimal,
    pub token0_addr: String,
    pub token1_addr: String,
}

pub struct AdjustedAmounts {
    // pub token0: BigDecimal,
    // pub token0_abs: BigDecimal,
    // pub token0_eth: BigDecimal,
    // pub token0_usd: BigDecimal,
    // pub token1: BigDecimal,
    // pub token1_abs: BigDecimal,
    // pub token1_eth: BigDecimal,
    // pub token1_usd: BigDecimal,
    pub delta_tvl_eth: BigDecimal,
    pub delta_tvl_usd: BigDecimal,
    pub stable_eth_untracked: BigDecimal,
    pub stable_usd_untracked: BigDecimal,
}

impl PoolEvent {
    pub fn get_amounts(&self) -> Option<TokenAmounts> {
        return match self.r#type.as_ref().unwrap().clone() {
            Type::Mint(evt) => Some(TokenAmounts {
                amount0: BigDecimal::try_from(evt.amount_0).unwrap(),
                amount1: BigDecimal::try_from(evt.amount_1).unwrap(),
                token0_addr: self.token0.clone(),
                token1_addr: self.token1.clone(),
            }),
            Type::Burn(evt) => Some(TokenAmounts {
                amount0: BigDecimal::try_from(evt.amount_0).unwrap().neg(),
                amount1: BigDecimal::try_from(evt.amount_1).unwrap().neg(),
                token0_addr: self.token0.clone(),
                token1_addr: self.token1.clone(),
            }),
            Type::Swap(evt) => Some(TokenAmounts {
                amount0: BigDecimal::try_from(evt.amount_0).unwrap(),
                amount1: BigDecimal::try_from(evt.amount_1).unwrap(),
                token0_addr: self.token0.clone(),
                token1_addr: self.token1.clone(),
            }),
        };
    }
}
