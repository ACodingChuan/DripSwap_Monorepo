use crate::pb::uniswap::v2::Pair;

const SEPOLIA_CHAIN_ID: u64 = 11155111;
const SCROLL_SEPOLIA_CHAIN_ID: u64 = 534351;

const TOKEN_VETH: &str = "0xE91d02E66a9152Fee1BC79c1830121F6507a4F6D";
const TOKEN_VUSDT: &str = "0xBAcDBe38Df8421d0AA90262BEB1C20d32a634fe7";
const TOKEN_VUSDC: &str = "0x46A906fcA4487C87f0d89D2d0824EC57bdAa947D";
const TOKEN_VDAI: &str = "0x0C156E2F45a812ad743760A88d73fB22879BC299";
const TOKEN_VBTC: &str = "0xAeA8C2F08b10Fe1853300dF4332E462b449e19D6";
const TOKEN_VLINK: &str = "0x1A95d5d1930b807B62B20f3cA6b2451Ffc75B454";
const TOKEN_VSCR: &str = "0x4911Fb3923F6DA0cd4920F914991B0A742d88Bfd";

struct SeedPair {
    pair_address: &'static str,
    token_a: &'static str,
    token_b: &'static str,
}

const COMMON_SEED_PAIRS: &[SeedPair] = &[
    SeedPair {
        pair_address: "0x3C5F6694456F9CE35cfE0b459C16EFcA380C70ea",
        token_a: TOKEN_VBTC,
        token_b: TOKEN_VUSDT,
    },
    SeedPair {
        pair_address: "0x62b17eC4d1F4b274bF998D6BCD4570A9f8E45Fe9",
        token_a: TOKEN_VBTC,
        token_b: TOKEN_VDAI,
    },
    SeedPair {
        pair_address: "0xDae14E909Bb4B77a2c187721B877279967195893",
        token_a: TOKEN_VBTC,
        token_b: TOKEN_VUSDC,
    },
    SeedPair {
        pair_address: "0xad00AB83a3Aaa3fE48E21dd738e82b6AAcD4eBbb",
        token_a: TOKEN_VBTC,
        token_b: TOKEN_VETH,
    },
    SeedPair {
        pair_address: "0x7A247E3F42f0fab514FD32c076E95Add5900a711",
        token_a: TOKEN_VBTC,
        token_b: TOKEN_VLINK,
    },
    SeedPair {
        pair_address: "0x63fF7b7974e5B1B9b944Ac0fa87f98Dbe5a2fa1d",
        token_a: TOKEN_VUSDT,
        token_b: TOKEN_VDAI,
    },
    SeedPair {
        pair_address: "0x5623e52a5f4cfd272028f129291b43BB42A29C6D",
        token_a: TOKEN_VUSDT,
        token_b: TOKEN_VUSDC,
    },
    SeedPair {
        pair_address: "0x9e1E7211fddff362fb3289eCCD6e93B21284f980",
        token_a: TOKEN_VUSDT,
        token_b: TOKEN_VETH,
    },
    SeedPair {
        pair_address: "0x2855b9FeBE9C16617cE5A4a66F50838FdB806Ce7",
        token_a: TOKEN_VUSDT,
        token_b: TOKEN_VLINK,
    },
    SeedPair {
        pair_address: "0x5208D3802D520CcD5dc4A00922c68c758D342807",
        token_a: TOKEN_VDAI,
        token_b: TOKEN_VUSDC,
    },
    SeedPair {
        pair_address: "0x6bF8659Fe87a250Bcf40938021092726CBBE0ad9",
        token_a: TOKEN_VDAI,
        token_b: TOKEN_VETH,
    },
    SeedPair {
        pair_address: "0x1A23C7A16A1A2153460585982837957B5fE637CC",
        token_a: TOKEN_VDAI,
        token_b: TOKEN_VLINK,
    },
    SeedPair {
        pair_address: "0x25dCcBF72A348dE92bDf646bFAAAf66ADC7225C7",
        token_a: TOKEN_VUSDC,
        token_b: TOKEN_VETH,
    },
    SeedPair {
        pair_address: "0x5FD66fb96090E0c780539A1E3A0eFfDe765b6b42",
        token_a: TOKEN_VUSDC,
        token_b: TOKEN_VLINK,
    },
    SeedPair {
        pair_address: "0x4047EDdAa71f98700dC0f9Eb4e21c2427Ca4A427",
        token_a: TOKEN_VETH,
        token_b: TOKEN_VLINK,
    },
];

const SCROLL_ONLY_SEED_PAIRS: &[SeedPair] = &[
    SeedPair {
        pair_address: "0x9AFD47452b1e67B57a47432D6713e480AdF17436",
        token_a: TOKEN_VSCR,
        token_b: TOKEN_VUSDC,
    },
    SeedPair {
        pair_address: "0x1C91AE1283a9f9E2c84950c2553a99CEB65d2703",
        token_a: TOKEN_VSCR,
        token_b: TOKEN_VUSDT,
    },
    SeedPair {
        pair_address: "0x330d612323C67f3Ce2FD72c7DE29DC92C4EA94eE",
        token_a: TOKEN_VSCR,
        token_b: TOKEN_VETH,
    },
];

fn normalize_address(address: &str) -> String {
    address.trim().to_lowercase()
}

fn sort_tokens(token_a: &str, token_b: &str) -> (String, String) {
    let token_a = normalize_address(token_a);
    let token_b = normalize_address(token_b);

    if token_a <= token_b {
        (token_a, token_b)
    } else {
        (token_b, token_a)
    }
}

pub fn seed_pairs(chain_id: u64) -> Vec<Pair> {
    let mut seeds: Vec<&SeedPair> = vec![];

    match chain_id {
        SEPOLIA_CHAIN_ID => seeds.extend(COMMON_SEED_PAIRS.iter()),
        SCROLL_SEPOLIA_CHAIN_ID => {
            seeds.extend(COMMON_SEED_PAIRS.iter());
            seeds.extend(SCROLL_ONLY_SEED_PAIRS.iter());
        }
        _ => {}
    }

    seeds
        .into_iter()
        .map(|seed| {
            let (token0, token1) = sort_tokens(seed.token_a, seed.token_b);
            Pair {
                pair_address: normalize_address(seed.pair_address),
                token0,
                token1,
                log_ordinal: 0,
            }
        })
        .collect()
}
