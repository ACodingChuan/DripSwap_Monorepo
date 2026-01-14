export const FACTORY_ADDRESS = "0x6c9258026a9272368e49bbb7d0a78c17bbe284bf";
export const REFERENCE_TOKEN = "0xe91d02e66a9152fee1bc79c1830121f6507a4f6d";

export const ORACLE_ETH_USD_SEPOLIA = "0x694aa1769357215de4fac081bf1f309adc325306";
export const ORACLE_ETH_USD_SCROLL = "0x59f1ec1f10bd7ed9b938431086bc1d9e233ecf41";

export const ADDRESS_ZERO = "0x0000000000000000000000000000000000000000";

export const STABLE_TOKEN_PAIRS = [
  "0x25dccbf72a348de92bdf646bfaaaf66adc7225c7",
  "0x9e1e7211fddff362fb3289eccd6e93b21284f980",
  "0x6bf8659fe87a250bcf40938021092726cbbe0ad9",
];

export const WHITELIST = [
  "0xe91d02e66a9152fee1bc79c1830121f6507a4f6d",
  "0x46a906fca4487c87f0d89d2d0824ec57bdaa947d",
  "0xbacdbe38df8421d0aa90262beb1c20d32a634fe7",
  "0x0c156e2f45a812ad743760a88d73fb22879bc299",
  "0xaea8c2f08b10fe1853300df4332e462b449e19d6",
  "0x1a95d5d1930b807b62b20f3ca6b2451ffc75b454",
  "0x4911fb3923f6da0cd4920f914991b0a742d88bfd",
];

export const STABLECOINS = [
  "0x46a906fca4487c87f0d89d2d0824ec57bdaa947d",
  "0xbacdbe38df8421d0aa90262beb1c20d32a634fe7",
  "0x0c156e2f45a812ad743760a88d73fb22879bc299",
];

export const MINIMUM_USD_THRESHOLD_NEW_PAIRS = "1000";
export const MINIMUM_LIQUIDITY_THRESHOLD_ETH = "0.001";

export const SKIP_TOTAL_SUPPLY: string[] = [];

export const STATIC_TOKEN_DEFINITIONS = [
  {
    address: "0xe91d02e66a9152fee1bc79c1830121f6507a4f6d",
    symbol: "vETH",
    name: "DripSwap vETH",
    decimals: 18n,
  },
  {
    address: "0x46a906fca4487c87f0d89d2d0824ec57bdaa947d",
    symbol: "vUSDC",
    name: "DripSwap vUSDC",
    decimals: 6n,
  },
  {
    address: "0xbacdbe38df8421d0aa90262beb1c20d32a634fe7",
    symbol: "vUSDT",
    name: "DripSwap vUSDT",
    decimals: 6n,
  },
  {
    address: "0x0c156e2f45a812ad743760a88d73fb22879bc299",
    symbol: "vDAI",
    name: "DripSwap vDAI",
    decimals: 18n,
  },
  {
    address: "0xaea8c2f08b10fe1853300df4332e462b449e19d6",
    symbol: "vBTC",
    name: "DripSwap vBTC",
    decimals: 8n,
  },
  {
    address: "0x1a95d5d1930b807b62b20f3ca6b2451ffc75b454",
    symbol: "vLINK",
    name: "DripSwap vLINK",
    decimals: 18n,
  },
  {
    address: "0x4911fb3923f6da0cd4920f914991b0a742d88bfd",
    symbol: "vSCR",
    name: "DripSwap vSCR",
    decimals: 18n,
  },
];
