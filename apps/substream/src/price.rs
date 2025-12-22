use crate::{constants, math, Erc20Token, Pool};
use std::ops::{Div, Mul};
use std::str;
use std::str::FromStr;
use substreams::log;
use substreams::scalar::{BigDecimal, BigInt};
use substreams::store::{StoreGet, StoreGetBigDecimal, StoreGetBigInt, StoreGetProto, StoreGetRaw};

// V2: 使用 constants 模块中的白名单配置
pub use constants::WHITELIST_TOKENS;

// V2: WETH 地址（从白名单中提取，两链相同 - 确定性部署）
const WETH_ADDRESS: &str = "0xe91d02e66a9152fee1bc79c1830121f6507a4f6d"; // vETH

// V2: USDC 地址（用于 ETH/USD 价格查询）
const USDC_ADDRESS: &str = "0x46a906fca4487c87f0d89d2d0824ec57bdaa947d"; // vUSDC

// V2: 稳定币列表（从白名单中提取）
pub const STABLE_COINS: [&str; 3] = [
    "0x46a906fca4487c87f0d89d2d0824ec57bdaa947d", // vUSDC
    "0xbacdbe38df8421d0aa90262beb1c20d32a634fe7", // vUSDT
    "0x0c156e2f45a812ad743760a88d73fb22879bc299", // vDAI
];

pub fn sqrt_price_x96_to_token_prices(
    sqrt_price: BigDecimal,
    token_0: &Erc20Token,
    token_1: &Erc20Token,
) -> (BigDecimal, BigDecimal) {
    log::debug!(
        "Computing prices for {} {} and {} {}",
        token_0.symbol,
        token_0.decimals,
        token_1.symbol,
        token_1.decimals
    );

    let price: BigDecimal = sqrt_price.clone().mul(sqrt_price);
    let denominator: BigDecimal =
        BigDecimal::from_str("6277101735386680763835789423207666416102355444464034512896").unwrap();

    let price1 = price
        .div(denominator)
        .mul(math::exponent_to_big_decimal(token_0.decimals))
        .div(math::exponent_to_big_decimal(token_1.decimals));

    let price0 = math::safe_div(&BigDecimal::one(), &price1);

    return (price0, price1);
}

pub fn find_eth_per_token(
    ord: u64,
    pool_address: &String,
    token_address: &String,
    pools_store: &StoreGetProto<Pool>,
    pool_liquidities_store: &StoreGetBigInt,
    tokens_whitelist_pools_store: &StoreGetRaw,
    total_native_amounts_store: &StoreGetBigDecimal,
    prices_store: &StoreGetBigDecimal,
) -> BigDecimal {
    log::debug!("finding ETH per token for {} in pool {}", token_address, pool_address);
    if token_address.eq(WETH_ADDRESS) {
        log::debug!("is ETH return 1");
        return BigDecimal::one();
    }

    let mut price_so_far = BigDecimal::zero();

    if STABLE_COINS.contains(&token_address.as_str()) {
        log::debug!("token addr: {} is a stable coin", token_address);
        let eth_price_usd = get_eth_price_in_usd(prices_store, ord);
        log::info!("eth_price_usd {}", eth_price_usd);
        price_so_far = math::safe_div(&BigDecimal::one(), &eth_price_usd);
    } else {
        // TODO: @eduard change this once the changes for store of list has been merged
        let wl = match tokens_whitelist_pools_store.get_last(&format!("token:{token_address}")) {
            None => {
                log::debug!("failed to get whitelisted pools for token {}", token_address);
                return BigDecimal::zero();
            }
            Some(bytes) => String::from_utf8(bytes.to_vec()).unwrap(),
        };

        let mut whitelisted_pools: Vec<&str> = vec![];
        for p in wl.split(";") {
            if !p.is_empty() {
                whitelisted_pools.push(p);
            }
        }
        log::debug!("found whitelisted pools {}", whitelisted_pools.len());

        let mut largest_eth_locked = BigDecimal::zero();
        let minimum_eth_locked = BigDecimal::from_str("52").unwrap();
        let mut eth_locked: BigDecimal;

        for pool_address in whitelisted_pools.iter() {
            log::debug!("checking pool: {}", pool_address);
            let pool = match pools_store.get_last(format!("pool:{pool_address}")) {
                None => continue,
                Some(p) => p,
            };
            let token0 = pool.token0.as_ref().unwrap();
            let token1 = pool.token1.as_ref().unwrap();
            let token0_addr = &token0.address;
            let token1_addr = &token1.address;

            log::debug!("found pool: {pool_address} with token0 {token0_addr} and with token1 {token1_addr}",);

            let liquidity: BigInt = match pool_liquidities_store.get_at(ord, format!("pool:{pool_address}")) {
                None => {
                    log::debug!("No liquidity for pool {pool_address}",);
                    BigInt::zero()
                }
                Some(l) => l,
            };

            if liquidity.gt(&BigInt::zero()) {
                if &token0.address == token_address {
                    log::info!(
                        "current pool token 0 matches desired token, complementary token is {} {}",
                        token1_addr,
                        token1.symbol
                    );
                    let native_amount = match total_native_amounts_store
                        .get_at(ord, format!("pool:{pool_address}:{token1_addr}:native"))
                    {
                        None => BigDecimal::zero(),
                        Some(amount) => amount,
                    };
                    log::debug!("native amount value of token1 in pool {}", native_amount);

                    let token1_eth_price;
                    // If the counter token is WETH we know the derived price is 1
                    if token1.address.eq(WETH_ADDRESS) {
                        log::debug!("token 1 is WETH");
                        eth_locked = native_amount;
                        token1_eth_price = BigDecimal::one();
                    } else {
                        log::debug!("token 1 is NOT WETH");

                        match pool_liquidities_store.get_at(ord, format!("pair:{WETH_ADDRESS}:{token1_addr}")) {
                            None => {
                                log::debug!("unable to find liquidity for {:?}", token1_addr);
                                continue;
                            }
                            Some(l) => {
                                // There is no liquidity in the pool. We can't compute the eth_price
                                // of the token.
                                if l.eq(&BigInt::zero()) {
                                    continue;
                                }

                                // Else we have enough liquidity to compute the price
                            }
                        }

                        token1_eth_price = match prices_store.get_at(ord, format!("pair:{WETH_ADDRESS}:{token1_addr}"))
                        {
                            None => {
                                log::debug!("unable to find token 1 price in eth {token1_addr}");
                                continue;
                            }
                            Some(price) => price,
                        };
                        log::debug!("token 1 is price in eth {}", token1_eth_price);
                        eth_locked = native_amount.mul(token1_eth_price.clone());
                        log::debug!("computed eth locked {}", eth_locked);
                    }
                    log::debug!(
                        "eth locked in pool {pool_address} {} (largest {})",
                        eth_locked,
                        largest_eth_locked
                    );
                    // should the check below make more sens if we EITHER have eth.gt > largest && (eth_locked > min BUT !Whitelist || whitelist)???
                    if eth_locked.gt(&largest_eth_locked)
                        && (eth_locked.gt(&minimum_eth_locked) || WHITELIST_TOKENS.contains(&token0_addr.as_str()))
                    {
                        log::debug!("eth locked passed test");
                        let token1_price =
                            match prices_store.get_at(ord, format!("pool:{pool_address}:{token1_addr}:token1")) {
                                None => {
                                    log::debug!("unable to find pool {pool_address} for token {token1_addr} price",);
                                    continue;
                                }
                                Some(price) => price,
                            };
                        log::debug!("found token 1 price {}", token1_price);
                        largest_eth_locked = eth_locked.clone();
                        price_so_far = token1_price.mul(token1_eth_price.clone());
                        log::debug!("price_so_far {}", price_so_far);
                    }
                }
                if &token1.address == token_address {
                    log::debug!(
                        "current pool token 1 matches desired token, complementary token is {} {}",
                        token0.address,
                        token1.symbol
                    );
                    let native_amount = match total_native_amounts_store
                        .get_at(ord, format!("pool:{pool_address}:{token0_addr}:native"))
                    {
                        None => BigDecimal::zero(),
                        Some(price) => price,
                    };
                    log::debug!("native amount value of token0 in pool {}", native_amount);

                    let mut token0_eth_price = BigDecimal::zero();

                    // If the counter token is WETH we know the derived price is 1
                    if token0.address.eq(WETH_ADDRESS) {
                        log::debug!("token 0 is WETH");
                        eth_locked = native_amount
                    } else {
                        log::debug!("token 0 is NOT WETH");

                        match pool_liquidities_store.get_at(ord, format!("pair:{WETH_ADDRESS}:{token0_addr}")) {
                            None => {
                                log::debug!("unable to find liquidity for {:?}", token0_addr);
                                continue;
                            }
                            Some(l) => {
                                // There is no liquidity in the pool. We can't compute the eth_price
                                // of the token.
                                if l.eq(&BigInt::zero()) {
                                    continue;
                                }

                                // Else we have enough liquidity to compute the price
                            }
                        }

                        token0_eth_price = match prices_store.get_at(ord, format!("pair:{WETH_ADDRESS}:{token0_addr}"))
                        {
                            None => {
                                log::debug!("unable to find token 0 price in eth {:?}", token0.address);
                                continue;
                            }
                            Some(price) => price,
                        };
                        log::debug!("token 0 is price in eth {}", token0_eth_price);
                        eth_locked = native_amount.mul(token0_eth_price.clone());
                        log::debug!("computed eth locked {}", eth_locked);
                    }
                    log::debug!("eth locked in pool {pool_address} {eth_locked} (largest {largest_eth_locked})",);
                    if eth_locked.gt(&largest_eth_locked)
                        && (eth_locked.gt(&minimum_eth_locked) || WHITELIST_TOKENS.contains(&token1_addr.as_str()))
                    {
                        log::debug!("eth locked passed test");
                        let token0_price =
                            match prices_store.get_at(ord, format!("pool:{pool_address}:{token0_addr}:token0")) {
                                None => {
                                    log::debug!("unable to find pool {pool_address} for token {token0_addr} price",);
                                    continue;
                                }
                                Some(price) => price,
                            };
                        log::debug!("found token 0 price {}", token0_price);
                        largest_eth_locked = eth_locked.clone();
                        price_so_far = token0_price.mul(token0_eth_price.clone());
                        log::debug!("price_so_far {}", price_so_far);
                    }
                }
            }
        }
    }
    return price_so_far;
}

// V2: 动态查找 WETH/USDC pair 以获取 ETH/USD 价格
pub fn get_eth_price_in_usd(prices_store: &StoreGetBigDecimal, ordinal: u64) -> BigDecimal {
    // V2: 使用 pair key 而非硬编码的 pool 地址
    // 尝试从 WETH/USDC pair 查询价格
    let key = format!("pair:{}:{}", WETH_ADDRESS, USDC_ADDRESS);
    log::debug!("Looking for ETH/USD price with key: {}", key);
    
    return match prices_store.get_at(ordinal, &key) {
        None => {
            log::debug!("ETH/USD price not found, trying reverse pair");
            // V2: 尝试反向 pair
            let reverse_key = format!("pair:{}:{}", USDC_ADDRESS, WETH_ADDRESS);
            match prices_store.get_at(ordinal, &reverse_key) {
                None => {
                    log::debug!("ETH/USD price not found in reverse pair either");
                    BigDecimal::zero()
                }
                Some(price) => {
                    // V2: 反向价格需要取倒数
                    math::safe_div(&BigDecimal::one(), &price)
                }
            }
        }
        Some(price) => price,
    };
}
