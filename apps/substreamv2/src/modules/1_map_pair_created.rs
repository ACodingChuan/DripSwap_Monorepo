use std::str::FromStr;

use ethabi::ethereum_types::Address;
use substreams_ethereum::pb::eth::v2 as eth;
use crate::common::event_handler::EventHandler;
use crate::common::hex::Hexable;

use crate::abi::Factory::events::PairCreated;
use crate::common::params::parse_params;
use crate::pb::uniswap::v2::{Pair, Pairs};

#[substreams::handlers::map]
pub fn map_pair_created(params: String, block: eth::Block) -> Result<Pairs, substreams::errors::Error> {
    let config = parse_params(&params);
    let mut pairs: Vec<Pair> = vec![];

    let mut on_pair_created = |event: PairCreated, _tx: &eth::TransactionTrace, log: &eth::Log| {
        pairs.push(Pair {
            pair_address: event.pair.to_hex(),
            token0: event.token0.to_hex(),
            token1: event.token1.to_hex(),
            log_ordinal: log.ordinal,
        });
    };

    {
        let mut eh = EventHandler::new(&block);
        eh.filter_by_address(vec![Address::from_str(&config.factory_address).unwrap()]);
        eh.on::<PairCreated, _>(&mut on_pair_created);
        eh.handle_events();
    }

    Ok(Pairs { pairs })
}
