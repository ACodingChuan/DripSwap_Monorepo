pub mod dynamic_fanout;
pub mod pb;
pub mod processing_mode;
pub mod protobuf;
pub mod routing;
pub mod substreams;
pub mod utils;

// Re-export main types for easy consumption
pub use dynamic_fanout::{DynamicFanoutConfig, DynamicTopicRouting};
pub use protobuf::{FieldValue, ParsedMessage, ProtobufSchemaLoader};
pub use routing::{DynamicMessageRouter, MessageRouter};
pub use substreams::{
    load_substreams_from_package, SubstreamsEndpoint, SubstreamsPackage, SubstreamsStream,
};

// Configuration types
#[derive(Debug, Clone)]
pub struct SubstreamsConfig {
    pub endpoint: String,
    pub api_token: String,
    pub package_path: String,
    pub module_name: String,
    pub start_block: u64,
    pub stop_block: Option<u64>,
}

#[derive(Debug, Clone)]
pub struct BlockRange {
    pub start: u64,
    pub end: Option<u64>,
}
