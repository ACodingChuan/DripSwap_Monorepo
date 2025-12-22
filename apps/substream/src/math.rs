// V2: 删除 ticks_idx 导入（V3 特有）
// V2: compute_price_from_tick_idx 函数在 V2 中不使用，因为 V2 直接从 reserves 计算价格
use std::ops::{Div, Mul};
use substreams::scalar::BigDecimal;

// V2: 注释掉 compute_price_from_tick_idx 函数（V3 特有）
// pub fn compute_price_from_tick_idx(desired_tick_idx: i32) -> BigDecimal { ... }

pub fn safe_div(amount0: &BigDecimal, amount1: &BigDecimal) -> BigDecimal {
    let big_decimal_zero: &BigDecimal = &BigDecimal::zero();
    return if amount1.eq(big_decimal_zero) {
        BigDecimal::zero()
    } else {
        amount0.clone().div(amount1.clone())
    };
}

pub fn exponent_to_big_decimal(decimals: u64) -> BigDecimal {
    let mut result = BigDecimal::one();
    let big_decimal_ten: &BigDecimal = &BigDecimal::from(10 as i32);

    let mut i = 1 as u64;
    while i < decimals {
        result = result.mul(big_decimal_ten.clone());
        i += 1;
    }

    return result;
}

#[cfg(test)]
mod test {
    use crate::math::compute_price_from_tick_idx;
    use std::str::FromStr;
    use substreams::prelude::BigDecimal;

    #[test]
    fn test_positive_tick_idx() {
        let tick_idx = 257820;
        let actual_value = compute_price_from_tick_idx(tick_idx);
        let expected_value = BigDecimal::from_str(
            "157188409912.8279800665572784382799429044388135818770675416117949775512036146406973508030483126972441",
        )
        .unwrap();
        assert_eq!(expected_value, actual_value);
    }

    #[test]
    fn test_negative_tick_idx() {
        let tick_idx = -16200;
        let actual_value = compute_price_from_tick_idx(tick_idx);
        let expected_value = BigDecimal::from_str(
            "0.1979147284588052764428880652056914101428568621377186361720748341060154174725108347202390730358454528",
        )
        .unwrap();
        assert_eq!(expected_value, actual_value);
    }
}
