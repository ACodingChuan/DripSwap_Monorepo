// Multi-Topic Fanout - Route structured data to specialized topics!

use anyhow::{anyhow, Result};
use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};
use tracing::{debug, info, warn};

use crate::processing_mode::ProcessingMode;
use crate::protobuf::{
    extract_instruction_data, extract_instruction_type, FieldValue, ParsedMessage,
};
use crate::routing::field_extraction::FieldExtractor;

/// Configuration for multi-topic fanout routing
#[derive(Debug, Clone)]
pub struct FanoutConfig {
    pub enable_fanout: bool,
    pub topic_prefix: String,
    pub default_topic: String,
}

impl Default for FanoutConfig {
    fn default() -> Self {
        Self {
            enable_fanout: true,
            topic_prefix: "spl".to_string(),
            default_topic: "blockchain-blocks".to_string(),
        }
    }
}

/// Topic routing decision for a parsed message
#[derive(Debug, Clone)]
pub struct TopicRouting {
    pub topic_name: String,
    pub message: TypedKafkaMessage,
}

/// Specialized Kafka message types for different SPL instructions
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(tag = "type")]
pub enum TypedKafkaMessage {
    Transfer(TransferMessage),
    Mint(MintMessage),
    Burn(BurnMessage),
    InitializeMint(InitializeMintMessage),
    InitializedAccount(InitializedAccountMessage),
    Raw(RawMessage), // Fallback for unparseable data
}

/// Transfer instruction message
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct TransferMessage {
    pub instruction_id: String,
    pub transaction_hash: String,
    pub block_number: u64,
    pub from_address: String,
    pub to_address: String,
    pub amount: u64,
    pub processed_at: DateTime<Utc>,
}

/// Mint instruction message
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct MintMessage {
    pub instruction_id: String,
    pub transaction_hash: String,
    pub block_number: u64,
    pub mint_address: String,
    pub to_address: String,
    pub amount: u64,
    pub processed_at: DateTime<Utc>,
}

/// Burn instruction message
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct BurnMessage {
    pub instruction_id: String,
    pub transaction_hash: String,
    pub block_number: u64,
    pub mint_address: String,
    pub from_address: String,
    pub amount: u64,
    pub processed_at: DateTime<Utc>,
}

/// Initialize mint instruction message
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct InitializeMintMessage {
    pub instruction_id: String,
    pub transaction_hash: String,
    pub block_number: u64,
    pub mint_address: String,
    pub decimals: u64,
    pub mint_authority: String,
    pub freeze_authority: Option<String>,
    pub processed_at: DateTime<Utc>,
}

/// Initialized account instruction message
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct InitializedAccountMessage {
    pub instruction_id: String,
    pub transaction_hash: String,
    pub block_number: u64,
    pub account_address: String,
    pub mint_address: String,
    pub owner: String,
    pub processed_at: DateTime<Utc>,
}

/// Raw message fallback for unparseable data
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct RawMessage {
    pub type_url: String,
    pub value_base64: String,
    pub block_number: u64,
    pub output_module: String,
    pub processed_at: DateTime<Utc>,
}

/// Multi-topic message router
pub struct MessageRouter {
    config: FanoutConfig,
    #[allow(dead_code)] // Future feature for processing mode optimization
    processing_mode: ProcessingMode,
}

impl MessageRouter {
    /// Create a new message router with configuration
    pub fn new(config: FanoutConfig) -> Self {
        let processing_mode = ProcessingMode::from_env();
        info!(
            "Creating message router with fanout: {}, mode: {:?}",
            config.enable_fanout, processing_mode
        );
        Self {
            config,
            processing_mode,
        }
    }

    /// Route a parsed protobuf message to appropriate topics
    pub fn route_message(
        &self,
        parsed_message: &ParsedMessage,
        original_bytes: &[u8],
        block_number: u64,
        output_module: &str,
    ) -> Result<Vec<TopicRouting>> {
        if !self.config.enable_fanout {
            return Ok(vec![self.create_raw_message_routing(
                parsed_message,
                original_bytes,
                block_number,
                output_module,
            )?]);
        }

        debug!("Routing message for block {}", block_number);

        // Extract instruction type from the parsed message
        let instruction_type = match extract_instruction_type(parsed_message) {
            Ok(inst_type) => inst_type,
            Err(e) => {
                warn!(
                    "Could not extract instruction type: {} - falling back to raw",
                    e
                );
                return Ok(vec![self.create_raw_message_routing(
                    parsed_message,
                    original_bytes,
                    block_number,
                    output_module,
                )?]);
            }
        };

        info!(
            "Routing {} instruction to specialized topic",
            instruction_type
        );

        let routing = match instruction_type.as_str() {
            "transfer" => self.create_transfer_routing(parsed_message, block_number)?,
            "mint" => self.create_mint_routing(parsed_message, block_number)?,
            "burn" => self.create_burn_routing(parsed_message, block_number)?,
            "initialize_mint" => {
                self.create_initialize_mint_routing(parsed_message, block_number)?
            }
            "initialized_account" => {
                self.create_initialized_account_routing(parsed_message, block_number)?
            }
            _ => {
                warn!(
                    "Unknown instruction type: {} - using raw fallback",
                    instruction_type
                );
                self.create_raw_message_routing(
                    parsed_message,
                    original_bytes,
                    block_number,
                    output_module,
                )?
            }
        };

        Ok(vec![routing])
    }

    /// Create transfer message routing
    fn create_transfer_routing(
        &self,
        parsed_message: &ParsedMessage,
        block_number: u64,
    ) -> Result<TopicRouting> {
        let transfer_data = extract_instruction_data(parsed_message, "transfer")?;
        let instruction_metadata = self.extract_instruction_metadata(parsed_message)?;

        let transfer_message = TransferMessage {
            instruction_id: instruction_metadata.instruction_id,
            transaction_hash: instruction_metadata.transaction_hash,
            block_number,
            from_address: self.extract_string_field(transfer_data, "from")?,
            to_address: self.extract_string_field(transfer_data, "to")?,
            amount: self.extract_uint64_field(transfer_data, "amount")?,
            processed_at: Utc::now(),
        };

        Ok(TopicRouting {
            topic_name: format!("{}_transfers", self.config.topic_prefix),
            message: TypedKafkaMessage::Transfer(transfer_message),
        })
    }

    /// Create mint message routing
    fn create_mint_routing(
        &self,
        parsed_message: &ParsedMessage,
        block_number: u64,
    ) -> Result<TopicRouting> {
        let mint_data = extract_instruction_data(parsed_message, "mint")?;
        let instruction_metadata = self.extract_instruction_metadata(parsed_message)?;

        let mint_message = MintMessage {
            instruction_id: instruction_metadata.instruction_id,
            transaction_hash: instruction_metadata.transaction_hash,
            block_number,
            mint_address: self.extract_string_field(mint_data, "mint_address")?,
            to_address: self.extract_string_field(mint_data, "to")?,
            amount: self.extract_uint64_field(mint_data, "amount")?,
            processed_at: Utc::now(),
        };

        Ok(TopicRouting {
            topic_name: format!("{}_mints", self.config.topic_prefix),
            message: TypedKafkaMessage::Mint(mint_message),
        })
    }

    /// Create burn message routing
    fn create_burn_routing(
        &self,
        parsed_message: &ParsedMessage,
        block_number: u64,
    ) -> Result<TopicRouting> {
        let burn_data = extract_instruction_data(parsed_message, "burn")?;
        let instruction_metadata = self.extract_instruction_metadata(parsed_message)?;

        let burn_message = BurnMessage {
            instruction_id: instruction_metadata.instruction_id,
            transaction_hash: instruction_metadata.transaction_hash,
            block_number,
            mint_address: self.extract_string_field(burn_data, "mint_address")?,
            from_address: self.extract_string_field(burn_data, "from")?,
            amount: self.extract_uint64_field(burn_data, "amount")?,
            processed_at: Utc::now(),
        };

        Ok(TopicRouting {
            topic_name: format!("{}_burns", self.config.topic_prefix),
            message: TypedKafkaMessage::Burn(burn_message),
        })
    }

    /// Create initialize mint message routing with enhanced field extraction
    fn create_initialize_mint_routing(
        &self,
        parsed_message: &ParsedMessage,
        block_number: u64,
    ) -> Result<TopicRouting> {
        info!(
            "Starting initialize_mint routing for block {}",
            block_number
        );

        let init_mint_data = extract_instruction_data(parsed_message, "initialize_mint")?;
        let instruction_metadata = self.extract_instruction_metadata(parsed_message)?;

        info!(
            "Successfully extracted instruction metadata: id={}, tx={}",
            instruction_metadata.instruction_id, instruction_metadata.transaction_hash
        );

        // Use enhanced field extractor with proper error handling
        let extractor = FieldExtractor::new(init_mint_data, "initialize_mint");

        // Handle the decimals field using Proto3 semantics - all fields are optional with defaults
        let decimals = extractor.extract_uint64_spl_semantic_default("decimals");

        let init_mint_message = InitializeMintMessage {
            instruction_id: instruction_metadata.instruction_id,
            transaction_hash: instruction_metadata.transaction_hash,
            block_number,
            mint_address: self.extract_string_field_enhanced(&extractor, "mint_address")?,
            decimals,
            mint_authority: self.extract_string_field_enhanced(&extractor, "mint_authority")?,
            freeze_authority: {
                let freeze_auth = extractor.extract_string_proto3_default("freeze_authority");
                if freeze_auth.is_empty() {
                    None
                } else {
                    Some(freeze_auth)
                }
            },
            processed_at: Utc::now(),
        };

        Ok(TopicRouting {
            topic_name: format!("{}_initialize_mints", self.config.topic_prefix),
            message: TypedKafkaMessage::InitializeMint(init_mint_message),
        })
    }

    /// Create initialized account message routing
    fn create_initialized_account_routing(
        &self,
        parsed_message: &ParsedMessage,
        block_number: u64,
    ) -> Result<TopicRouting> {
        let init_account_data = extract_instruction_data(parsed_message, "initialized_account")?;
        let instruction_metadata = self.extract_instruction_metadata(parsed_message)?;

        let init_account_message = InitializedAccountMessage {
            instruction_id: instruction_metadata.instruction_id,
            transaction_hash: instruction_metadata.transaction_hash,
            block_number,
            account_address: self.extract_string_field(init_account_data, "account")?,
            mint_address: self.extract_string_field(init_account_data, "mint_address")?,
            owner: self.extract_string_field(init_account_data, "owner")?,
            processed_at: Utc::now(),
        };

        Ok(TopicRouting {
            topic_name: format!("{}_initialized_accounts", self.config.topic_prefix),
            message: TypedKafkaMessage::InitializedAccount(init_account_message),
        })
    }

    /// Create raw message routing as fallback
    fn create_raw_message_routing(
        &self,
        parsed_message: &ParsedMessage,
        original_bytes: &[u8],
        block_number: u64,
        output_module: &str,
    ) -> Result<TopicRouting> {
        use base64::prelude::*;

        let raw_message = RawMessage {
            type_url: parsed_message.type_url.clone(),
            value_base64: BASE64_STANDARD.encode(original_bytes),
            block_number,
            output_module: output_module.to_string(),
            processed_at: Utc::now(),
        };

        Ok(TopicRouting {
            topic_name: self.config.default_topic.clone(),
            message: TypedKafkaMessage::Raw(raw_message),
        })
    }

    /// Extract common instruction metadata
    fn extract_instruction_metadata(
        &self,
        parsed_message: &ParsedMessage,
    ) -> Result<InstructionMetadata> {
        if let Some(FieldValue::Array(instructions)) = parsed_message.fields.get("instructions") {
            if let Some(FieldValue::Message(instruction)) = instructions.first() {
                let instruction_id = self.extract_string_field(instruction, "instruction_id")?;
                let transaction_hash =
                    self.extract_string_field(instruction, "transaction_hash")?;

                return Ok(InstructionMetadata {
                    instruction_id,
                    transaction_hash,
                });
            }
        }

        Err(anyhow!("Could not extract instruction metadata"))
    }

    /// Extract string field from parsed message using Proto3 defaults
    fn extract_string_field(&self, message: &ParsedMessage, field_name: &str) -> Result<String> {
        match message.fields.get(field_name) {
            Some(FieldValue::String(value)) => Ok(value.clone()),
            Some(_) => {
                warn!(
                    "Field '{}' found but not a string, using Proto3 default: ''",
                    field_name
                );
                Ok(String::new()) // Proto3 default for string
            }
            None => {
                debug!("Field '{}' not found, using Proto3 default: ''", field_name);
                Ok(String::new()) // Proto3 default for string
            }
        }
    }

    /// Extract optional string field from parsed message
    #[allow(dead_code)] // Used by future enhanced field extraction
    fn extract_optional_string_field(
        &self,
        message: &ParsedMessage,
        field_name: &str,
    ) -> Option<String> {
        if let Some(FieldValue::String(value)) = message.fields.get(field_name) {
            Some(value.clone())
        } else {
            None
        }
    }

    /// Extract uint64 field from parsed message using Proto3 defaults
    fn extract_uint64_field(&self, message: &ParsedMessage, field_name: &str) -> Result<u64> {
        match message.fields.get(field_name) {
            Some(FieldValue::Uint64(value)) => Ok(*value),
            Some(FieldValue::Uint32(value)) => {
                debug!("Converting uint32 to uint64 for field '{}'", field_name);
                Ok(*value as u64)
            }
            Some(_) => {
                warn!(
                    "Field '{}' found but not a uint64, using Proto3 default: 0",
                    field_name
                );
                Ok(0) // Proto3 default for uint64
            }
            None => {
                debug!("Field '{}' not found, using Proto3 default: 0", field_name);
                Ok(0) // Proto3 default for uint64
            }
        }
    }

    /// Enhanced string field extraction using Proto3 semantics (optional with defaults)
    fn extract_string_field_enhanced(
        &self,
        extractor: &FieldExtractor,
        field_name: &str,
    ) -> Result<String> {
        // Proto3 semantics: all fields are optional with default values
        Ok(extractor.extract_string_proto3_default(field_name))
    }
}

/// Common instruction metadata
#[derive(Debug, Clone)]
struct InstructionMetadata {
    instruction_id: String,
    transaction_hash: String,
}
