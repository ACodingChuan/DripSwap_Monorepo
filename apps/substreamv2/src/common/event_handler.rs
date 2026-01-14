use std::collections::HashMap;

use ethabi::ethereum_types::Address;
use substreams_ethereum::pb::eth::v2 as eth;
use substreams_ethereum::Event;

use crate::common::traits::HasAddresser;

pub struct EventHandler<'a> {
    block: &'a eth::Block,
    handlers: HashMap<&'static str, Box<dyn FnMut(&eth::Log, &eth::TransactionTrace) + 'a>>,
    addresses: Option<Box<dyn HasAddresser + 'a>>,
}

impl<'a> EventHandler<'a> {
    pub fn new(block: &'a eth::Block) -> Self {
        Self {
            block,
            handlers: HashMap::new(),
            addresses: None,
        }
    }

    pub fn filter_by_address(&mut self, addresser: impl HasAddresser + 'a) {
        self.addresses = Some(Box::new(addresser));
    }

    pub fn on<E: Event, F>(&mut self, mut handler: F)
    where
        F: FnMut(E, &eth::TransactionTrace, &eth::Log) + 'a,
    {
        self.handlers.insert(
            E::NAME,
            Box::new(move |log: &eth::Log, tx: &eth::TransactionTrace| {
                if let Some(event) = E::match_and_decode(log) {
                    handler(event, tx, log);
                }
            }),
        );
    }

    pub fn handle_events(&mut self) {
        for log in self.block.logs() {
            if is_log_from_reverted_call(&log.log) {
                continue;
            }

            if self.addresses.is_some()
                && !self
                    .addresses
                    .as_ref()
                    .unwrap()
                    .has_address(Address::from_slice(&log.log.address.as_slice()))
            {
                continue;
            }

            for handler in self.handlers.values_mut() {
                handler(&log.log, log.receipt.transaction);
            }
        }
    }
}

fn is_log_from_reverted_call(log: &eth::Log) -> bool {
    log.block_index == 0
}
