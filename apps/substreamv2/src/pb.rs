#[rustfmt::skip]
#[path = "../target/pb/messari.uniswap.v2.rs"]
pub(in crate::pb) mod uniswap_v2;

pub mod uniswap {
    pub mod v2 {
        pub use super::super::uniswap_v2::*;
    }
}
