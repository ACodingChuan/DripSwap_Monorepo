import { Address, BigDecimal, BigInt, dataSource } from '@graphprotocol/graph-ts/index'

// DripSwap vToken-centric defaults (applies to sepolia / scroll-sepolia; same factory/pairs)
// 若后续地址或稳定币集合有调整，请在此更新。

export const FACTORY_ADDRESS = '0x6c9258026a9272368e49bbb7d0a78c17bbe284bf'

// 参考币：vETH
export const REFERENCE_TOKEN = '0xe91d02e66a9152fee1bc79c1830121f6507a4f6d'

// ETH/USD 价格来源：链上 oracle（Chainlink Aggregator 风格）
// sepolia:        0x694AA1769357215DE4FAC081bf1f309aDC325306
// scroll-sepolia: 0x59F1ec1f10bD7eD9B938431086bC1D9e233ECf41
export const ORACLE_ETH_USD_SEPOLIA = '0x694aa1769357215de4fac081bf1f309adc325306'
export const ORACLE_ETH_USD_SCROLL = '0x59f1ec1f10bd7ed9b938431086bc1d9e233ecf41'
export const ADDRESS_ZERO = '0x0000000000000000000000000000000000000000'
export const CHAIN_NAME_SEPOLIA = 'Ethereum Sepolia'
export const CHAIN_NAME_SCROLL = 'Scroll Sepolia'

export function getOracleEthUsd(): string {
  const net = dataSource.network()
  if (net == 'sepolia') {
    return ORACLE_ETH_USD_SEPOLIA
  }
  if (net == 'scroll-sepolia' || net == 'scroll' || net == 'scroll-testnet') {
    return ORACLE_ETH_USD_SCROLL
  }
  return ADDRESS_ZERO
}

export function getReceiverChainName(selector: BigInt): string {
  if (selector.equals(BigInt.fromString('16015286601757825753'))) {
    return CHAIN_NAME_SEPOLIA
  }
  if (selector.equals(BigInt.fromString('2279865765895943307'))) {
    return CHAIN_NAME_SCROLL
  }
  return 'Unknown'
}

// 稳定币相关配置（目前按 vUSDC / vUSDT / vDAI 处理）
export const STABLE_TOKEN_PAIRS = [
  '0x25dccbf72a348de92bdf646bfaaaf66adc7225c7', // vUSDC / vETH
  '0x9e1e7211fddff362fb3289eccd6e93b21284f980', // vUSDT / vETH
  '0x6bf8659fe87a250bcf40938021092726cbbe0ad9', // vDAI  / vETH
]

// token where amounts should contribute to tracked volume and liquidity
export const WHITELIST: string[] = [
  '0xe91d02e66a9152fee1bc79c1830121f6507a4f6d', // vETH
  '0x46a906fca4487c87f0d89d2d0824ec57bdaa947d', // vUSDC
  '0xbacdbe38df8421d0aa90262beb1c20d32a634fe7', // vUSDT
  '0x0c156e2f45a812ad743760a88d73fb22879bc299', // vDAI
  '0xaea8c2f08b10fe1853300df4332e462b449e19d6', // vBTC
  '0x1a95d5d1930b807b62b20f3ca6b2451ffc75b454', // vLINK
  '0x4911fb3923f6da0cd4920f914991b0a742d88bfd', // vSCR (可选稳定资产，如不需可移除)
]

export const STABLECOINS = [
  '0x46a906fca4487c87f0d89d2d0824ec57bdaa947d', // vUSDC
  '0xbacdbe38df8421d0aa90262beb1c20d32a634fe7', // vUSDT
  '0x0c156e2f45a812ad743760a88d73fb22879bc299', // vDAI
]

// minimum liquidity required to count towards tracked volume for pairs with small # of Lps
export const MINIMUM_USD_THRESHOLD_NEW_PAIRS = BigDecimal.fromString('1000')

// minimum liquidity for price to get tracked
export const MINIMUM_LIQUIDITY_THRESHOLD_ETH = BigDecimal.fromString('0.001')

export class TokenDefinition {
  address: Address
  symbol: string
  name: string
  decimals: BigInt
}

export const STATIC_TOKEN_DEFINITIONS: TokenDefinition[] = [
  {
    address: Address.fromString('0xe91d02e66a9152fee1bc79c1830121f6507a4f6d'),
    symbol: 'vETH',
    name: 'DripSwap vETH',
    decimals: BigInt.fromI32(18),
  },
  {
    address: Address.fromString('0x46a906fca4487c87f0d89d2d0824ec57bdaa947d'),
    symbol: 'vUSDC',
    name: 'DripSwap vUSDC',
    decimals: BigInt.fromI32(6),
  },
  {
    address: Address.fromString('0xbacdbe38df8421d0aa90262beb1c20d32a634fe7'),
    symbol: 'vUSDT',
    name: 'DripSwap vUSDT',
    decimals: BigInt.fromI32(6),
  },
  {
    address: Address.fromString('0x0c156e2f45a812ad743760a88d73fb22879bc299'),
    symbol: 'vDAI',
    name: 'DripSwap vDAI',
    decimals: BigInt.fromI32(18),
  },
  {
    address: Address.fromString('0xaea8c2f08b10fe1853300df4332e462b449e19d6'),
    symbol: 'vBTC',
    name: 'DripSwap vBTC',
    decimals: BigInt.fromI32(8),
  },
  {
    address: Address.fromString('0x1a95d5d1930b807b62b20f3ca6b2451ffc75b454'),
    symbol: 'vLINK',
    name: 'DripSwap vLINK',
    decimals: BigInt.fromI32(18),
  },
  {
    address: Address.fromString('0x4911fb3923f6da0cd4920f914991b0a742d88bfd'),
    symbol: 'vSCR',
    name: 'DripSwap vSCR',
    decimals: BigInt.fromI32(18),
  },
]

export const SKIP_TOTAL_SUPPLY: string[] = []
