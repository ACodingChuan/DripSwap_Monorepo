use substreams::store::{StoreNew, StoreSetIfNotExists, StoreSetIfNotExistsProto};

use crate::common::params::parse_params;
use crate::common::seed_pairs::seed_pairs;
use crate::pb::uniswap::v2::{Pair, Pairs};

#[substreams::handlers::store]
pub fn store_pairs(params: String, pairs: Pairs, store: StoreSetIfNotExistsProto<Pair>) {
    let config = parse_params(&params);

    for pair in seed_pairs(config.chain_id) {
        store.set_if_not_exists(0, pair.pair_address.clone(), &pair);
    }

    for pair in pairs.pairs {
        store.set_if_not_exists(pair.log_ordinal, pair.pair_address.clone(), &pair);
    }
}
