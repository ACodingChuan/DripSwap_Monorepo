use ethabi::ethereum_types::Address;
use substreams::store::{StoreGet, StoreGetProto};

use crate::common::hex::Hexable;

use crate::pb::uniswap::v2::Pair;

pub trait HasAddresser {
    fn has_address(&self, key: Address) -> bool;
}

impl HasAddresser for Vec<Address> {
    fn has_address(&self, key: Address) -> bool {
        self.iter().any(|addr| *addr == key)
    }
}

pub struct PairAddresser<'a> {
    pub store: &'a StoreGetProto<Pair>,
}

impl<'a> HasAddresser for PairAddresser<'a> {
    fn has_address(&self, key: Address) -> bool {
        self.store.get_last(key.to_hex()).is_some()
    }
}
