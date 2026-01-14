use std::str::FromStr;

use ethabi::ethereum_types::Address;
use substreams::store::{StoreGet, StoreGetProto};
use substreams_ethereum::pb::eth::v2 as eth;
use crate::common::event_handler::EventHandler;
use crate::common::hex::Hexable;

use crate::abi::Factory::events::PairCreated as PairCreatedEvent;
use crate::abi::Pool::events::{Burn as BurnEvent, Mint as MintEvent, Swap as SwapEvent, Sync as SyncEvent, Transfer as TransferEvent};
use crate::common::params::parse_params;
use crate::common::traits::PairAddresser;
use crate::pb::uniswap::v2::event::Payload as EventPayload;
use crate::pb::uniswap::v2::{
    Burn,
    Event,
    EventType,
    Events,
    Mint,
    Pair,
    PairCreated,
    Swap,
    Sync,
    Transfer,
};

#[substreams::handlers::map]
pub fn map_uniswap_events(
    params: String,
    block: eth::Block,
    pairs_store: StoreGetProto<Pair>,
) -> Result<Events, substreams::errors::Error> {
    let config = parse_params(&params);
    let mut events: Vec<Event> = vec![];

    handle_pair_created(&config, &block, &mut events);
    handle_transfer(&config, &block, &pairs_store, &mut events);
    handle_mint(&config, &block, &pairs_store, &mut events);
    handle_burn(&config, &block, &pairs_store, &mut events);
    handle_swap(&config, &block, &pairs_store, &mut events);
    handle_sync(&config, &block, &pairs_store, &mut events);

    events.sort_by_key(|event| event.log_ordinal);

    Ok(Events { events })
}

fn build_event(
    chain_id: u64,
    event_type: EventType,
    block: &eth::Block,
    tx: &eth::TransactionTrace,
    log: &eth::Log,
    address: String,
    pair_address: String,
    payload: Option<EventPayload>,
) -> Event {
    let block_hash = block.hash.to_hex();
    let tx_hash = tx.hash.to_hex();
    let event_id = format!(
        "{}:{}:{}:{}:{}",
        chain_id,
        block_hash,
        tx_hash,
        log.index,
        event_type.as_str_name()
    );

    Event {
        event_id,
        chain_id,
        event_type: event_type as i32,
        block_hash,
        block_number: block.number,
        block_timestamp: block.timestamp_seconds(),
        tx_hash,
        tx_index: tx.index,
        log_index: log.index,
        log_ordinal: log.ordinal,
        address,
        pair_address,
        payload,
    }
}

fn handle_pair_created(config: &crate::common::params::StreamParams, block: &eth::Block, events: &mut Vec<Event>) {
    let mut on_pair_created = |event: PairCreatedEvent, tx: &eth::TransactionTrace, log: &eth::Log| {
        let pair_address = event.pair.to_hex();
        let payload = PairCreated {
            pair: pair_address.clone(),
            token0: event.token0.to_hex(),
            token1: event.token1.to_hex(),
        };

        events.push(build_event(
            config.chain_id,
            EventType::PairCreated,
            block,
            tx,
            log,
            log.address.to_hex(),
            pair_address,
            Some(EventPayload::PairCreated(payload)),
        ));
    };

    let mut eh = EventHandler::new(block);
    eh.filter_by_address(vec![Address::from_str(&config.factory_address).unwrap()]);
    eh.on::<PairCreatedEvent, _>(&mut on_pair_created);
    eh.handle_events();
}

fn handle_transfer(
    config: &crate::common::params::StreamParams,
    block: &eth::Block,
    pairs_store: &StoreGetProto<Pair>,
    events: &mut Vec<Event>,
) {
    let mut on_transfer = |event: TransferEvent, tx: &eth::TransactionTrace, log: &eth::Log| {
        let pair_address = log.address.to_hex();
        let payload = Transfer {
            from: event.from.to_hex(),
            to: event.to.to_hex(),
            value: event.value.to_string(),
        };

        events.push(build_event(
            config.chain_id,
            EventType::Transfer,
            block,
            tx,
            log,
            pair_address.clone(),
            pair_address,
            Some(EventPayload::Transfer(payload)),
        ));
    };

    let mut eh = EventHandler::new(block);
    eh.filter_by_address(PairAddresser { store: pairs_store });
    eh.on::<TransferEvent, _>(&mut on_transfer);
    eh.handle_events();
}

fn handle_mint(
    config: &crate::common::params::StreamParams,
    block: &eth::Block,
    pairs_store: &StoreGetProto<Pair>,
    events: &mut Vec<Event>,
) {
    let mut on_mint = |event: MintEvent, tx: &eth::TransactionTrace, log: &eth::Log| {
        let pair_address = log.address.to_hex();
        let payload = Mint {
            sender: event.sender.to_hex(),
            amount0: event.amount0.to_string(),
            amount1: event.amount1.to_string(),
        };

        events.push(build_event(
            config.chain_id,
            EventType::Mint,
            block,
            tx,
            log,
            pair_address.clone(),
            pair_address,
            Some(EventPayload::Mint(payload)),
        ));
    };

    let mut eh = EventHandler::new(block);
    eh.filter_by_address(PairAddresser { store: pairs_store });
    eh.on::<MintEvent, _>(&mut on_mint);
    eh.handle_events();
}

fn handle_burn(
    config: &crate::common::params::StreamParams,
    block: &eth::Block,
    pairs_store: &StoreGetProto<Pair>,
    events: &mut Vec<Event>,
) {
    let mut on_burn = |event: BurnEvent, tx: &eth::TransactionTrace, log: &eth::Log| {
        let pair_address = log.address.to_hex();
        let payload = Burn {
            sender: event.sender.to_hex(),
            to: event.to.to_hex(),
            amount0: event.amount0.to_string(),
            amount1: event.amount1.to_string(),
        };

        events.push(build_event(
            config.chain_id,
            EventType::Burn,
            block,
            tx,
            log,
            pair_address.clone(),
            pair_address,
            Some(EventPayload::Burn(payload)),
        ));
    };

    let mut eh = EventHandler::new(block);
    eh.filter_by_address(PairAddresser { store: pairs_store });
    eh.on::<BurnEvent, _>(&mut on_burn);
    eh.handle_events();
}

fn handle_swap(
    config: &crate::common::params::StreamParams,
    block: &eth::Block,
    pairs_store: &StoreGetProto<Pair>,
    events: &mut Vec<Event>,
) {
    let mut on_swap = |event: SwapEvent, tx: &eth::TransactionTrace, log: &eth::Log| {
        let pair_address = log.address.to_hex();
        let payload = Swap {
            sender: event.sender.to_hex(),
            to: event.to.to_hex(),
            amount0_in: event.amount0_in.to_string(),
            amount1_in: event.amount1_in.to_string(),
            amount0_out: event.amount0_out.to_string(),
            amount1_out: event.amount1_out.to_string(),
        };

        events.push(build_event(
            config.chain_id,
            EventType::Swap,
            block,
            tx,
            log,
            pair_address.clone(),
            pair_address,
            Some(EventPayload::Swap(payload)),
        ));
    };

    let mut eh = EventHandler::new(block);
    eh.filter_by_address(PairAddresser { store: pairs_store });
    eh.on::<SwapEvent, _>(&mut on_swap);
    eh.handle_events();
}

fn handle_sync(
    config: &crate::common::params::StreamParams,
    block: &eth::Block,
    pairs_store: &StoreGetProto<Pair>,
    events: &mut Vec<Event>,
) {
    let mut on_sync = |event: SyncEvent, tx: &eth::TransactionTrace, log: &eth::Log| {
        let pair_address = log.address.to_hex();
        let payload = Sync {
            reserve0: event.reserve0.to_string(),
            reserve1: event.reserve1.to_string(),
        };

        events.push(build_event(
            config.chain_id,
            EventType::Sync,
            block,
            tx,
            log,
            pair_address.clone(),
            pair_address,
            Some(EventPayload::Sync(payload)),
        ));
    };

    let mut eh = EventHandler::new(block);
    eh.filter_by_address(PairAddresser { store: pairs_store });
    eh.on::<SyncEvent, _>(&mut on_sync);
    eh.handle_events();
}
