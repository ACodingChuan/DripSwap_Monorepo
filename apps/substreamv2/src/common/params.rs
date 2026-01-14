use std::collections::HashMap;

use super::constants::{DEFAULT_CHAIN_ID, DEFAULT_FACTORY_ADDRESS};

#[derive(Debug, Clone)]
pub struct StreamParams {
    pub chain_id: u64,
    pub factory_address: String,
}

pub fn parse_params(params: &str) -> StreamParams {
    let mut map: HashMap<String, String> = HashMap::new();
    for part in params.split(';') {
        let part = part.trim();
        if part.is_empty() {
            continue;
        }
        let mut iter = part.splitn(2, '=');
        let key = iter.next().unwrap_or("").trim().to_lowercase();
        let value = iter.next().unwrap_or("").trim();
        if key.is_empty() || value.is_empty() {
            continue;
        }
        map.insert(key, value.to_string());
    }

    let chain_id = map
        .get("chain_id")
        .and_then(|value| value.parse::<u64>().ok())
        .unwrap_or(DEFAULT_CHAIN_ID);

    let factory_address = map
        .get("factory")
        .or_else(|| map.get("factory_address"))
        .cloned()
        .unwrap_or_else(|| DEFAULT_FACTORY_ADDRESS.to_string());

    StreamParams {
        chain_id,
        factory_address,
    }
}
