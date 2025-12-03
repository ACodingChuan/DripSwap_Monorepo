// 合约配置 - 从 DripSwap_Contract/deployments/{network}/address_book.md 迁移

import { SupportedChainId, SUPPORTED_CHAINS } from './chains';

export interface TokenConfig {
  address: `0x${string}`;
  symbol: string;
  decimals: number;
  logo: string;
}

export interface ChainConfig {
  guardedRouter: `0x${string}`;
  chainlinkOracle: `0x${string}`;
  factory: `0x${string}`;
  router: `0x${string}`;
  bridge: `0x${string}`;
  ccipSelector: string;
  tokens: Record<string, TokenConfig>;
  pairs: Record<string, Record<string, `0x${string}`>>;
}

export const CCIP_CHAINS = {
  SEPOLIA: 11155111,
  SCROLL_SEPOLIA: 534351,
} as const;

// Sepolia 配置
const SEPOLIA_CONFIG: ChainConfig = {
  guardedRouter: '0x87b4C3B91e995888F7242A08668e4Cf6763d52Ca',
  chainlinkOracle: '0x7e8F17B349fD0f6b8A89d7c0640F232E15C68Ff3',
  factory: '0x6C9258026A9272368e49bBB7D0A78c17BBe284BF',
  router: '0x2358DC77bB41a275195E49427A8ae78e61aE9040',
  bridge: '0x9347B320e42877855Cc6E66e5E5d6f18216CEEe7',
  ccipSelector: '16015286601757825753',
  tokens: {
    vETH: {
      address: '0xE91d02E66a9152Fee1BC79c1830121F6507a4F6D',
      symbol: 'vETH',
      decimals: 18,
      logo: '/tokens/vETH.svg',
    },
    vUSDT: {
      address: '0xBAcDBe38Df8421d0AA90262BEB1C20d32a634fe7',
      symbol: 'vUSDT',
      decimals: 6,
      logo: '/tokens/vUSDT.svg',
    },
    vUSDC: {
      address: '0x46A906fcA4487C87f0d89D2d0824EC57bdAa947D',
      symbol: 'vUSDC',
      decimals: 6,
      logo: '/tokens/vUSDC.svg',
    },
    vDAI: {
      address: '0x0C156E2F45a812ad743760A88d73fB22879BC299',
      symbol: 'vDAI',
      decimals: 18,
      logo: '/tokens/vDAI.svg',
    },
    vBTC: {
      address: '0xAeA8C2F08b10Fe1853300dF4332E462b449e19D6',
      symbol: 'vBTC',
      decimals: 8,
      logo: '/tokens/vBTC.svg',
    },
    vLINK: {
      address: '0x1A95d5d1930b807B62B20f3cA6b2451Ffc75B454',
      symbol: 'vLINK',
      decimals: 18,
      logo: '/tokens/vLINK.svg',
    },
    vSCR: {
      address: '0x4911Fb3923F6DA0cd4920F914991B0A742d88Bfd',
      symbol: 'vSCR',
      decimals: 18,
      logo: '/tokens/vSCR.svg',
    },
  },
  pairs: {
    // vETH pairs
    '0xE91d02E66a9152Fee1BC79c1830121F6507a4F6D': {
      '0xBAcDBe38Df8421d0AA90262BEB1C20d32a634fe7': '0x9e1E7211fddff362fb3289eCCD6e93B21284f980', // vUSDT
      '0xAeA8C2F08b10Fe1853300dF4332E462b449e19D6': '0xad00AB83a3Aaa3fE48E21dd738e82b6AAcD4eBbb', // vBTC
      '0x0C156E2F45a812ad743760A88d73fB22879BC299': '0x6bF8659Fe87a250Bcf40938021092726CBBE0ad9', // vDAI
      '0x46A906fcA4487C87f0d89D2d0824EC57bdAa947D': '0x25dCcBF72A348dE92bDf646bFAAAf66ADC7225C7', // vUSDC
      '0x1A95d5d1930b807B62B20f3cA6b2451Ffc75B454': '0x4047EDdAa71f98700dC0f9Eb4e21c2427Ca4A427', // vLINK
    },
    // vUSDT pairs
    '0xBAcDBe38Df8421d0AA90262BEB1C20d32a634fe7': {
      '0xE91d02E66a9152Fee1BC79c1830121F6507a4F6D': '0x9e1E7211fddff362fb3289eCCD6e93B21284f980', // vETH
      '0xAeA8C2F08b10Fe1853300dF4332E462b449e19D6': '0x3C5F6694456F9CE35cfE0b459C16EFcA380C70ea', // vBTC
      '0x0C156E2F45a812ad743760A88d73fB22879BC299': '0x63fF7b7974e5B1B9b944Ac0fa87f98Dbe5a2fa1d', // vDAI
      '0x46A906fcA4487C87f0d89D2d0824EC57bdAa947D': '0x5623e52a5f4cfd272028f129291b43BB42A29C6D', // vUSDC
      '0x1A95d5d1930b807B62B20f3cA6b2451Ffc75B454': '0x2855b9FeBE9C16617cE5A4a66F50838FdB806Ce7', // vLINK
    },
    // vBTC pairs
    '0xAeA8C2F08b10Fe1853300dF4332E462b449e19D6': {
      '0xE91d02E66a9152Fee1BC79c1830121F6507a4F6D': '0xad00AB83a3Aaa3fE48E21dd738e82b6AAcD4eBbb', // vETH
      '0xBAcDBe38Df8421d0AA90262BEB1C20d32a634fe7': '0x3C5F6694456F9CE35cfE0b459C16EFcA380C70ea', // vUSDT
      '0x0C156E2F45a812ad743760A88d73fB22879BC299': '0x62b17eC4d1F4b274bF998D6BCD4570A9f8E45Fe9', // vDAI
      '0x46A906fcA4487C87f0d89D2d0824EC57bdAa947D': '0xDae14E909Bb4B77a2c187721B877279967195893', // vUSDC
      '0x1A95d5d1930b807B62B20f3cA6b2451Ffc75B454': '0x7A247E3F42f0fab514FD32c076E95Add5900a711', // vLINK
    },
    // vDAI pairs
    '0x0C156E2F45a812ad743760A88d73fB22879BC299': {
      '0xE91d02E66a9152Fee1BC79c1830121F6507a4F6D': '0x6bF8659Fe87a250Bcf40938021092726CBBE0ad9', // vETH
      '0xBAcDBe38Df8421d0AA90262BEB1C20d32a634fe7': '0x63fF7b7974e5B1B9b944Ac0fa87f98Dbe5a2fa1d', // vUSDT
      '0xAeA8C2F08b10Fe1853300dF4332E462b449e19D6': '0x62b17eC4d1F4b274bF998D6BCD4570A9f8E45Fe9', // vBTC
      '0x46A906fcA4487C87f0d89D2d0824EC57bdAa947D': '0x5208D3802D520CcD5dc4A00922c68c758D342807', // vUSDC
      '0x1A95d5d1930b807B62B20f3cA6b2451Ffc75B454': '0x1A23C7A16A1A2153460585982837957B5fE637CC', // vLINK
    },
    // vUSDC pairs
    '0x46A906fcA4487C87f0d89D2d0824EC57bdAa947D': {
      '0xE91d02E66a9152Fee1BC79c1830121F6507a4F6D': '0x25dCcBF72A348dE92bDf646bFAAAf66ADC7225C7', // vETH
      '0xBAcDBe38Df8421d0AA90262BEB1C20d32a634fe7': '0x5623e52a5f4cfd272028f129291b43BB42A29C6D', // vUSDT
      '0xAeA8C2F08b10Fe1853300dF4332E462b449e19D6': '0xDae14E909Bb4B77a2c187721B877279967195893', // vBTC
      '0x0C156E2F45a812ad743760A88d73fB22879BC299': '0x5208D3802D520CcD5dc4A00922c68c758D342807', // vDAI
      '0x1A95d5d1930b807B62B20f3cA6b2451Ffc75B454': '0x5FD66fb96090E0c780539A1E3A0eFfDe765b6b42', // vLINK
    },
    // vLINK pairs
    '0x1A95d5d1930b807B62B20f3cA6b2451Ffc75B454': {
      '0xE91d02E66a9152Fee1BC79c1830121F6507a4F6D': '0x4047EDdAa71f98700dC0f9Eb4e21c2427Ca4A427', // vETH
      '0xBAcDBe38Df8421d0AA90262BEB1C20d32a634fe7': '0x2855b9FeBE9C16617cE5A4a66F50838FdB806Ce7', // vUSDT
      '0xAeA8C2F08b10Fe1853300dF4332E462b449e19D6': '0x7A247E3F42f0fab514FD32c076E95Add5900a711', // vBTC
      '0x0C156E2F45a812ad743760A88d73fB22879BC299': '0x1A23C7A16A1A2153460585982837957B5fE637CC', // vDAI
      '0x46A906fcA4487C87f0d89D2d0824EC57bdAa947D': '0x5FD66fb96090E0c780539A1E3A0eFfDe765b6b42', // vUSDC
    },
  },
};

// Scroll 配置
const SCROLL_CONFIG: ChainConfig = {
  guardedRouter: '0x87b4C3B91e995888F7242A08668e4Cf6763d52Ca',
  chainlinkOracle: '0x7e8F17B349fD0f6b8A89d7c0640F232E15C68Ff3',
  factory: '0x6C9258026A9272368e49bBB7D0A78c17BBe284BF',
  router: '0x2358DC77bB41a275195E49427A8ae78e61aE9040',
  bridge: '0xBE2CcDA786BF69B0AE4251E6b34dF212CEF4F645',
  ccipSelector: '2279865765895943307',
  tokens: {
    vETH: {
      address: '0xE91d02E66a9152Fee1BC79c1830121F6507a4F6D',
      symbol: 'vETH',
      decimals: 18,
      logo: '/tokens/vETH.svg',
    },
    vUSDT: {
      address: '0xBAcDBe38Df8421d0AA90262BEB1C20d32a634fe7',
      symbol: 'vUSDT',
      decimals: 6,
      logo: '/tokens/vUSDT.svg',
    },
    vUSDC: {
      address: '0x46A906fcA4487C87f0d89D2d0824EC57bdAa947D',
      symbol: 'vUSDC',
      decimals: 6,
      logo: '/tokens/vUSDC.svg',
    },
    vDAI: {
      address: '0x0C156E2F45a812ad743760A88d73fB22879BC299',
      symbol: 'vDAI',
      decimals: 18,
      logo: '/tokens/vDAI.svg',
    },
    vBTC: {
      address: '0xAeA8C2F08b10Fe1853300dF4332E462b449e19D6',
      symbol: 'vBTC',
      decimals: 8,
      logo: '/tokens/vBTC.svg',
    },
    vLINK: {
      address: '0x1A95d5d1930b807B62B20f3cA6b2451Ffc75B454',
      symbol: 'vLINK',
      decimals: 18,
      logo: '/tokens/vLINK.svg',
    },
    vSCR: {
      address: '0x4911Fb3923F6DA0cd4920F914991B0A742d88Bfd',
      symbol: 'vSCR',
      decimals: 18,
      logo: '/tokens/vSCR.svg',
    },
  },
  pairs: {
    '0xE91d02E66a9152Fee1BC79c1830121F6507a4F6D': {
      '0xBAcDBe38Df8421d0AA90262BEB1C20d32a634fe7': '0x9e1E7211fddff362fb3289eCCD6e93B21284f980',
      '0xAeA8C2F08b10Fe1853300dF4332E462b449e19D6': '0xad00AB83a3Aaa3fE48E21dd738e82b6AAcD4eBbb',
      '0x0C156E2F45a812ad743760A88d73fB22879BC299': '0x6bF8659Fe87a250Bcf40938021092726CBBE0ad9',
      '0x46A906fcA4487C87f0d89D2d0824EC57bdAa947D': '0x25dCcBF72A348dE92bDf646bFAAAf66ADC7225C7',
      '0x1A95d5d1930b807B62B20f3cA6b2451Ffc75B454': '0x4047EDdAa71f98700dC0f9Eb4e21c2427Ca4A427',
      '0x4911Fb3923F6DA0cd4920F914991B0A742d88Bfd': '0x330d612323C67f3Ce2FD72c7DE29DC92C4EA94eE', // vSCR
    },
    '0xBAcDBe38Df8421d0AA90262BEB1C20d32a634fe7': {
      '0xE91d02E66a9152Fee1BC79c1830121F6507a4F6D': '0x9e1E7211fddff362fb3289eCCD6e93B21284f980',
      '0xAeA8C2F08b10Fe1853300dF4332E462b449e19D6': '0x3C5F6694456F9CE35cfE0b459C16EFcA380C70ea',
      '0x0C156E2F45a812ad743760A88d73fB22879BC299': '0x63fF7b7974e5B1B9b944Ac0fa87f98Dbe5a2fa1d',
      '0x46A906fcA4487C87f0d89D2d0824EC57bdAa947D': '0x5623e52a5f4cfd272028f129291b43BB42A29C6D',
      '0x1A95d5d1930b807B62B20f3cA6b2451Ffc75B454': '0x2855b9FeBE9C16617cE5A4a66F50838FdB806Ce7',
      '0x4911Fb3923F6DA0cd4920F914991B0A742d88Bfd': '0x1C91AE1283a9f9E2c84950c2553a99CEB65d2703', // vSCR
    },
    '0xAeA8C2F08b10Fe1853300dF4332E462b449e19D6': {
      '0xE91d02E66a9152Fee1BC79c1830121F6507a4F6D': '0xad00AB83a3Aaa3fE48E21dd738e82b6AAcD4eBbb',
      '0xBAcDBe38Df8421d0AA90262BEB1C20d32a634fe7': '0x3C5F6694456F9CE35cfE0b459C16EFcA380C70ea',
      '0x0C156E2F45a812ad743760A88d73fB22879BC299': '0x62b17eC4d1F4b274bF998D6BCD4570A9f8E45Fe9',
      '0x46A906fcA4487C87f0d89D2d0824EC57bdAa947D': '0xDae14E909Bb4B77a2c187721B877279967195893',
      '0x1A95d5d1930b807B62B20f3cA6b2451Ffc75B454': '0x7A247E3F42f0fab514FD32c076E95Add5900a711',
    },
    '0x0C156E2F45a812ad743760A88d73fB22879BC299': {
      '0xE91d02E66a9152Fee1BC79c1830121F6507a4F6D': '0x6bF8659Fe87a250Bcf40938021092726CBBE0ad9',
      '0xBAcDBe38Df8421d0AA90262BEB1C20d32a634fe7': '0x63fF7b7974e5B1B9b944Ac0fa87f98Dbe5a2fa1d',
      '0xAeA8C2F08b10Fe1853300dF4332E462b449e19D6': '0x62b17eC4d1F4b274bF998D6BCD4570A9f8E45Fe9',
      '0x46A906fcA4487C87f0d89D2d0824EC57bdAa947D': '0x5208D3802D520CcD5dc4A00922c68c758D342807',
      '0x1A95d5d1930b807B62B20f3cA6b2451Ffc75B454': '0x1A23C7A16A1A2153460585982837957B5fE637CC',
    },
    '0x46A906fcA4487C87f0d89D2d0824EC57bdAa947D': {
      '0xE91d02E66a9152Fee1BC79c1830121F6507a4F6D': '0x25dCcBF72A348dE92bDf646bFAAAf66ADC7225C7',
      '0xBAcDBe38Df8421d0AA90262BEB1C20d32a634fe7': '0x5623e52a5f4cfd272028f129291b43BB42A29C6D',
      '0xAeA8C2F08b10Fe1853300dF4332E462b449e19D6': '0xDae14E909Bb4B77a2c187721B877279967195893',
      '0x0C156E2F45a812ad743760A88d73fB22879BC299': '0x5208D3802D520CcD5dc4A00922c68c758D342807',
      '0x1A95d5d1930b807B62B20f3cA6b2451Ffc75B454': '0x5FD66fb96090E0c780539A1E3A0eFfDe765b6b42',
      '0x4911Fb3923F6DA0cd4920F914991B0A742d88Bfd': '0x9AFD47452b1e67B57a47432D6713e480AdF17436', // vSCR
    },
    '0x1A95d5d1930b807B62B20f3cA6b2451Ffc75B454': {
      '0xE91d02E66a9152Fee1BC79c1830121F6507a4F6D': '0x4047EDdAa71f98700dC0f9Eb4e21c2427Ca4A427',
      '0xBAcDBe38Df8421d0AA90262BEB1C20d32a634fe7': '0x2855b9FeBE9C16617cE5A4a66F50838FdB806Ce7',
      '0xAeA8C2F08b10Fe1853300dF4332E462b449e19D6': '0x7A247E3F42f0fab514FD32c076E95Add5900a711',
      '0x0C156E2F45a812ad743760A88d73fB22879BC299': '0x1A23C7A16A1A2153460585982837957B5fE637CC',
      '0x46A906fcA4487C87f0d89D2d0824EC57bdAa947D': '0x5FD66fb96090E0c780539A1E3A0eFfDe765b6b42',
    },
    '0x4911Fb3923F6DA0cd4920F914991B0A742d88Bfd': {
      '0xE91d02E66a9152Fee1BC79c1830121F6507a4F6D': '0x330d612323C67f3Ce2FD72c7DE29DC92C4EA94eE',
      '0xBAcDBe38Df8421d0AA90262BEB1C20d32a634fe7': '0x1C91AE1283a9f9E2c84950c2553a99CEB65d2703',
      '0x46A906fcA4487C87f0d89D2d0824EC57bdAa947D': '0x9AFD47452b1e67B57a47432D6713e480AdF17436',
    },
  },
};

// 多链配置映射
export const CHAIN_CONFIGS: Record<SupportedChainId, ChainConfig> = {
  sepolia: SEPOLIA_CONFIG,
  scroll: SCROLL_CONFIG,
};

// 中间代币定义（用于多跳路由）
const BASES: Record<number, string[]> = {
  11155111: ['vETH', 'vUSDT', 'vUSDC', 'vDAI', 'vBTC'],
  534351: ['vETH', 'vUSDT', 'vUSDC', 'vDAI', 'vBTC'],
};

// 辅助函数
export function getIntermediaryTokens(chainId: number): string[] {
  return BASES[chainId] || [];
}

export function getChainConfig(chainId: number): ChainConfig | undefined {
  const chainEntry = Object.entries(SUPPORTED_CHAINS).find(
    ([_, chainConfig]) => chainConfig.id === chainId
  );
  if (!chainEntry) return undefined;

  const chainKey = chainEntry[0] as SupportedChainId;
  return CHAIN_CONFIGS[chainKey];
}

export function getPairAddress(
  chainId: number,
  tokenA: string,
  tokenB: string
): string | undefined {
  const config = getChainConfig(chainId);
  if (!config) return undefined;

  const tokenALower = tokenA.toLowerCase();
  const tokenBLower = tokenB.toLowerCase();

  // Try both directions
  for (const [key, pairs] of Object.entries(config.pairs)) {
    if (key.toLowerCase() === tokenALower) {
      for (const [pairKey, pairAddress] of Object.entries(pairs)) {
        if (pairKey.toLowerCase() === tokenBLower) {
          return pairAddress;
        }
      }
    }
  }

  return undefined;
}

export function getTokenConfig(chainId: number, address: string): TokenConfig | undefined {
  const config = getChainConfig(chainId);
  if (!config) return undefined;

  const addressLower = address.toLowerCase();
  return Object.values(config.tokens).find((t) => t.address.toLowerCase() === addressLower);
}

export function getAllTokens(chainId: number): TokenConfig[] {
  const config = getChainConfig(chainId);
  if (!config) return [];
  return Object.values(config.tokens);
}
