// DripSwap V2 常量配置
// 所有地址使用确定性部署（ERC2470 + CREATE2）

use phf::phf_set;
use substreams::scalar::BigInt;

// ========== Factory 地址 ==========
// 两链相同（确定性部署）
pub const UNISWAP_V2_FACTORY: &str = "0x6c9258026a9272368e49bbb7d0a78c17bbe284bf";

// ========== Oracle 地址（Chainlink ETH/USD） ==========
// Sepolia
pub const SEPOLIA_ORACLE_ETH_USD: &str = "0x694aa1769357215de4fac081bf1f309adc325306";

// Scroll Sepolia
pub const SCROLL_SEPOLIA_ORACLE_ETH_USD: &str = "0x59f1ec1f10bd7ed9b938431086bc1d9e233ecf41";

// ========== 初始区块号 ==========
pub const SEPOLIA_INITIAL_BLOCK: u64 = 9573280;
pub const SCROLL_SEPOLIA_INITIAL_BLOCK: u64 = 14731854;

// ========== Bridge 地址 ==========
pub const SEPOLIA_BRIDGE_ADDRESS: &str = "0x9347b320e42877855cc6e66e5e5d6f18216ceee7";
pub const SCROLL_SEPOLIA_BRIDGE_ADDRESS: &str = "0xbe2ccda786bf69b0ae4251e6b34df212cef4f645";

// ========== CCIP 目标链标识 ==========
pub const CCIP_SELECTOR_SEPOLIA: &str = "16015286601757825753";
pub const CCIP_SELECTOR_SCROLL_SEPOLIA: &str = "2279865765895943307";

// ========== 白名单代币（两链相同 - 确定性部署） ==========
pub static WHITELIST_TOKENS: phf::Set<&'static str> = phf_set! {
    "0xe91d02e66a9152fee1bc79c1830121f6507a4f6d", // vETH - Wrapped ETH
    "0x46a906fca4487c87f0d89d2d0824ec57bdaa947d", // vUSDC - USD Coin
    "0xbacdbe38df8421d0aa90262beb1c20d32a634fe7", // vUSDT - Tether USD
    "0x0c156e2f45a812ad743760a88d73fb22879bc299", // vDAI - Dai Stablecoin
    "0xaea8c2f08b10fe1853300df4332e462b449e19d6", // vBTC - Wrapped Bitcoin
    "0x1a95d5d1930b807b62b20f3ca6b2451ffc75b454", // vLINK - Chainlink Token
    "0x4911fb3923f6da0cd4920f914991b0a742d88bfd", // vSCR - Scroll Token (可选)
};

// ========== 最小流动性阈值 ==========
// 用于过滤低流动性的 Pair
pub const MINIMUM_LIQUIDITY_THRESHOLD_ETH: &str = "0.01"; // 0.01 ETH

// ========== 零地址 ==========
pub const ZERO_ADDRESS: &str = "0x0000000000000000000000000000000000000000";

// ========== 固定费率 ==========
// Uniswap V2 固定 0.3% 手续费
pub const SWAP_FEE_RATE: &str = "0.003";

// ========== Helper 函数 ==========
/// 获取当前链的 Oracle 地址
/// 注意：需要通过链 ID 或其他方式判断当前链
pub fn get_oracle_address_for_chain(chain_id: u64) -> &'static str {
    match chain_id {
        11155111 => SEPOLIA_ORACLE_ETH_USD,      // Sepolia
        534351 => SCROLL_SEPOLIA_ORACLE_ETH_USD, // Scroll Sepolia
        _ => SEPOLIA_ORACLE_ETH_USD,              // 默认使用 Sepolia
    }
}

/// 获取当前链的初始区块号
pub fn get_initial_block_for_chain(chain_id: u64) -> u64 {
    match chain_id {
        11155111 => SEPOLIA_INITIAL_BLOCK,
        534351 => SCROLL_SEPOLIA_INITIAL_BLOCK,
        _ => SEPOLIA_INITIAL_BLOCK,
    }
}

pub fn is_bridge_address(address: &str) -> bool {
    let addr = address.trim_start_matches("0x").to_lowercase();
    addr == SEPOLIA_BRIDGE_ADDRESS.trim_start_matches("0x")
        || addr == SCROLL_SEPOLIA_BRIDGE_ADDRESS.trim_start_matches("0x")
}

pub fn get_receiver_chain_name(selector: &BigInt) -> &'static str {
    match selector.to_string().as_str() {
        CCIP_SELECTOR_SEPOLIA => "Ethereum Sepolia",
        CCIP_SELECTOR_SCROLL_SEPOLIA => "Scroll Sepolia",
        _ => "Unknown",
    }
}

/// 检查地址是否在白名单中
pub fn is_whitelisted_token(address: &str) -> bool {
    WHITELIST_TOKENS.contains(&address.to_lowercase().as_str())
}
