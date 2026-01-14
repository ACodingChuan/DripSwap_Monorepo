// Dynamic Protobuf Fanout - Generic routing for ANY protobuf schema!

use anyhow::{anyhow, Result};
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use tracing::{debug, info};

// ONLY CHANGE: Updated import path for shared core structure
use crate::protobuf::{FieldValue, ParsedMessage};

/// Dynamic fanout configuration provided by user
#[derive(Debug, Clone, Deserialize)]
pub struct DynamicFanoutConfig {
    pub fanout_strategy: FanoutStrategy,
    #[allow(dead_code)] // Future feature for advanced discriminator logic
    pub discriminator: Option<DiscriminatorConfig>,
    pub topics: HashMap<String, String>,
    pub default_topic: String,
    pub topic_prefix: Option<String>,
}

/// Fanout strategy types
#[derive(Debug, Clone, Deserialize)]
#[serde(tag = "type")]
pub enum FanoutStrategy {
    /// No fanout - everything goes to default topic
    SingleTopic,

    /// Route based on oneof field variant (like SPL instructions)
    OneofField {
        field_path: String,  // e.g., "instructions[0]"
        oneof_field: String, // e.g., "Item"
    },

    /// Route based on repeated fields (like Events with multiple repeated fields)
    RepeatedFields {
        message_type: String,         // e.g., "Events"
        repeated_fields: Vec<String>, // e.g., ["trove_operations", "batch_operations"]
    },

    /// Route based on string field value (like protocol field)
    StringField {
        field_path: String,                     // e.g., "swaps[0].protocol"
        value_mapping: HashMap<String, String>, // "uniswap" -> "uniswap_swaps"
    },

    /// Route based on message type (different message types)
    MessageType {
        type_mapping: HashMap<String, String>, // "TransferEvent" -> "transfers_topic"
    },
}

/// Discriminator configuration for field-based routing
#[derive(Debug, Clone, Deserialize)]
pub struct DiscriminatorConfig {
    #[allow(dead_code)] // Future feature for field-based discrimination
    pub field_path: String,
    #[allow(dead_code)] // Future feature for oneof discrimination
    pub oneof_field: Option<String>,
    #[allow(dead_code)] // Future feature for value mapping
    pub value_mapping: Option<HashMap<String, String>>,
}

/// Dynamic message routing result
#[derive(Debug, Clone)]
pub struct DynamicTopicRouting {
    pub topic_name: String,
    pub message_data: GenericMessage,
}

/// Optimized message structure that delays JSON conversion until needed
#[derive(Debug, Clone)]
pub struct GenericMessage {
    pub message_type: String,
    pub block_number: u64,
    pub processed_at: String,
    pub fields: LazyJsonFields,
}

/// Lazy JSON fields that convert only when serialized
#[derive(Debug, Clone)]
#[allow(dead_code)] // Enum variants used conditionally based on configuration
pub enum LazyJsonFields {
    /// Already converted to JSON (cached)
    Json(serde_json::Value),
    /// Raw protobuf fields (convert on demand)
    Raw(HashMap<String, FieldValue>),
}

impl Serialize for GenericMessage {
    fn serialize<S>(&self, serializer: S) -> Result<S::Ok, S::Error>
    where
        S: serde::Serializer,
    {
        // Create flattened JSON object directly
        let mut json_map = serde_json::Map::new();

        // Add metadata fields
        json_map.insert(
            "message_type".to_string(),
            serde_json::Value::String(self.message_type.clone()),
        );
        json_map.insert(
            "block_number".to_string(),
            serde_json::Value::Number(self.block_number.into()),
        );
        json_map.insert(
            "processed_at".to_string(),
            serde_json::Value::String(self.processed_at.clone()),
        );

        // Add flattened fields
        let flattened_fields = match &self.fields {
            LazyJsonFields::Json(json) => {
                // If already JSON, flatten it
                flatten_json_object(json).map_err(serde::ser::Error::custom)?
            }
            LazyJsonFields::Raw(fields) => {
                // Convert and flatten raw fields
                flatten_protobuf_fields(fields).map_err(serde::ser::Error::custom)?
            }
        };

        // Insert all flattened fields
        for (field_name, field_value) in flattened_fields {
            json_map.insert(field_name, field_value);
        }

        // Serialize the complete JSON object
        serde_json::Value::Object(json_map).serialize(serializer)
    }
}

/// Optimized JSON conversion - avoids recursion where possible
fn convert_fields_to_json_optimized(
    fields: &HashMap<String, FieldValue>,
) -> Result<serde_json::Value> {
    let mut json_map = serde_json::Map::with_capacity(fields.len());

    for (field_name, field_value) in fields {
        let json_value = match field_value {
            FieldValue::String(s) => serde_json::Value::String(s.clone()),
            FieldValue::Uint64(n) => serde_json::Value::Number((*n).into()),
            FieldValue::Uint32(n) => serde_json::Value::Number((*n).into()),
            FieldValue::Bool(b) => serde_json::Value::Bool(*b),
            FieldValue::Array(arr) => {
                // Optimize simple arrays
                let json_array: Result<Vec<_>, _> = arr
                    .iter()
                    .map(|item| convert_field_value_to_json_optimized(item))
                    .collect();
                serde_json::Value::Array(json_array?)
            }
            FieldValue::Message(msg) => convert_fields_to_json_optimized(&msg.fields)?,
            _ => serde_json::Value::Null,
        };
        json_map.insert(field_name.clone(), json_value);
    }

    Ok(serde_json::Value::Object(json_map))
}

/// Optimized field value conversion
fn convert_field_value_to_json_optimized(field_value: &FieldValue) -> Result<serde_json::Value> {
    match field_value {
        FieldValue::String(s) => Ok(serde_json::Value::String(s.clone())),
        FieldValue::Uint64(n) => Ok(serde_json::Value::Number((*n).into())),
        FieldValue::Uint32(n) => Ok(serde_json::Value::Number((*n).into())),
        FieldValue::Bool(b) => Ok(serde_json::Value::Bool(*b)),
        FieldValue::Array(arr) => {
            let json_array: Result<Vec<_>, _> = arr
                .iter()
                .map(|item| convert_field_value_to_json_optimized(item))
                .collect();
            Ok(serde_json::Value::Array(json_array?))
        }
        FieldValue::Message(msg) => convert_fields_to_json_optimized(&msg.fields),
        _ => Ok(serde_json::Value::Null),
    }
}

/// Flatten protobuf fields to root level with dot-notation conversion
/// Example: balance_update.bold_balance -> balance_update_bold_balance
fn flatten_protobuf_fields(
    fields: &HashMap<String, FieldValue>,
) -> Result<Vec<(String, serde_json::Value)>> {
    let mut flattened = Vec::new();

    for (field_name, field_value) in fields {
        flatten_field_recursive(field_name, field_value, &mut flattened)?;
    }

    Ok(flattened)
}

/// Recursively flatten a single field
fn flatten_field_recursive(
    prefix: &str,
    field_value: &FieldValue,
    result: &mut Vec<(String, serde_json::Value)>,
) -> Result<()> {
    match field_value {
        FieldValue::String(s) => {
            result.push((prefix.to_string(), serde_json::Value::String(s.clone())));
        }
        FieldValue::Uint64(n) => {
            result.push((prefix.to_string(), serde_json::Value::Number((*n).into())));
        }
        FieldValue::Uint32(n) => {
            result.push((prefix.to_string(), serde_json::Value::Number((*n).into())));
        }
        FieldValue::Bool(b) => {
            result.push((prefix.to_string(), serde_json::Value::Bool(*b)));
        }
        FieldValue::Message(msg) => {
            // Flatten nested message fields with underscore separator
            for (nested_field_name, nested_field_value) in &msg.fields {
                let flattened_name = format!("{}_{}", prefix, nested_field_name);
                flatten_field_recursive(&flattened_name, nested_field_value, result)?;
            }
        }
        FieldValue::Array(arr) => {
            // For arrays, we'll serialize the entire array as a single JSON value
            let json_array: Result<Vec<_>, _> = arr
                .iter()
                .map(|item| convert_field_value_to_json_optimized(item))
                .collect();
            result.push((prefix.to_string(), serde_json::Value::Array(json_array?)));
        }
        _ => {
            // For unknown types, use null
            result.push((prefix.to_string(), serde_json::Value::Null));
        }
    }
    Ok(())
}

/// Flatten JSON object to root level (for already-converted JSON)
fn flatten_json_object(json: &serde_json::Value) -> Result<Vec<(String, serde_json::Value)>> {
    let mut flattened = Vec::new();

    if let serde_json::Value::Object(obj) = json {
        for (key, value) in obj {
            flatten_json_recursive(key, value, &mut flattened);
        }
    }

    Ok(flattened)
}

/// Recursively flatten JSON value
fn flatten_json_recursive(
    prefix: &str,
    value: &serde_json::Value,
    result: &mut Vec<(String, serde_json::Value)>,
) {
    match value {
        serde_json::Value::Object(obj) => {
            // Flatten nested objects with underscore separator
            for (nested_key, nested_value) in obj {
                let flattened_name = format!("{}_{}", prefix, nested_key);
                flatten_json_recursive(&flattened_name, nested_value, result);
            }
        }
        _ => {
            // For non-objects, add as-is
            result.push((prefix.to_string(), value.clone()));
        }
    }
}

/// Dynamic message router that works with any protobuf schema
pub struct DynamicMessageRouter {
    config: DynamicFanoutConfig,
    /// Cached topic names to avoid repeated string formatting
    topic_cache: std::sync::RwLock<HashMap<String, String>>,
}

impl DynamicMessageRouter {
    /// Create a new dynamic router from user configuration
    pub fn new(config: DynamicFanoutConfig) -> Self {
        info!(
            "Creating dynamic message router with strategy: {:?}",
            config.fanout_strategy
        );
        Self {
            config,
            topic_cache: std::sync::RwLock::new(HashMap::new()),
        }
    }

    /// Route a parsed protobuf message based on user configuration
    pub fn route_message(
        &self,
        parsed_message: &ParsedMessage,
        block_number: u64,
    ) -> Result<Vec<DynamicTopicRouting>> {
        match &self.config.fanout_strategy {
            FanoutStrategy::SingleTopic => {
                self.create_single_topic_routing(parsed_message, block_number)
            }
            FanoutStrategy::OneofField {
                field_path,
                oneof_field,
            } => self.create_oneof_routing(parsed_message, block_number, field_path, oneof_field),
            FanoutStrategy::RepeatedFields {
                message_type,
                repeated_fields,
            } => self.create_repeated_fields_routing(
                parsed_message,
                block_number,
                message_type,
                repeated_fields,
            ),
            FanoutStrategy::StringField {
                field_path,
                value_mapping,
            } => self.create_string_field_routing(
                parsed_message,
                block_number,
                field_path,
                value_mapping,
            ),
            FanoutStrategy::MessageType { type_mapping } => {
                self.create_message_type_routing(parsed_message, block_number, type_mapping)
            }
        }
    }

    /// Route everything to a single topic
    fn create_single_topic_routing(
        &self,
        parsed_message: &ParsedMessage,
        block_number: u64,
    ) -> Result<Vec<DynamicTopicRouting>> {
        let topic_name = self.resolve_topic_name(&self.config.default_topic);
        let message = self.create_generic_message(parsed_message, "generic", block_number)?;

        Ok(vec![DynamicTopicRouting {
            topic_name,
            message_data: message,
        }])
    }

    /// Route based on oneof field (like SPL instructions)
    fn create_oneof_routing(
        &self,
        parsed_message: &ParsedMessage,
        block_number: u64,
        field_path: &str,
        oneof_field: &str,
    ) -> Result<Vec<DynamicTopicRouting>> {
        // Try to extract the message containing the oneof field
        match self.extract_message_by_path(parsed_message, field_path) {
            Ok(target_message) => {
                // Field path exists - try oneof variant detection
                match self.detect_oneof_variant(target_message, oneof_field) {
                    Ok(oneof_variant) => {
                        // Found variant - route to specific topic
                        let topic_name = self
                            .config
                            .topics
                            .get(&oneof_variant)
                            .map(|topic| self.resolve_topic_name(topic))
                            .unwrap_or_else(|| self.resolve_topic_name(&self.config.default_topic));

                        let message = self.create_generic_message(
                            target_message,
                            &oneof_variant,
                            block_number,
                        )?;

                        debug!(
                            "Routed oneof variant '{}' to topic '{}'",
                            oneof_variant, topic_name
                        );

                        Ok(vec![DynamicTopicRouting {
                            topic_name,
                            message_data: message,
                        }])
                    }
                    Err(_) => {
                        // No oneof variant found - route to default topic
                        debug!(
                            "No oneof variant detected at '{}' - routing to default topic",
                            field_path
                        );
                        self.create_default_routing(
                            parsed_message,
                            block_number,
                            "no_variant_detected",
                        )
                    }
                }
            }
            Err(_) => {
                // Field path doesn't exist - route to default topic
                debug!(
                    "Field path '{}' not found - routing to default topic",
                    field_path
                );
                self.create_default_routing(parsed_message, block_number, "field_path_not_found")
            }
        }
    }

    /// Route based on repeated fields (like Events with multiple repeated fields)
    fn create_repeated_fields_routing(
        &self,
        parsed_message: &ParsedMessage,
        block_number: u64,
        _message_type: &str,
        repeated_fields: &[String],
    ) -> Result<Vec<DynamicTopicRouting>> {
        let mut routings = Vec::new();

        // Check each configured repeated field
        for field_name in repeated_fields {
            // Look for this repeated field in the message
            if let Some(field_value) = parsed_message.fields.get(field_name) {
                match field_value {
                    FieldValue::Array(array) if !array.is_empty() => {
                        // Get topic name for this repeated field
                        let topic_name = self
                            .config
                            .topics
                            .get(field_name)
                            .map(|topic| self.resolve_topic_name(topic))
                            .unwrap_or_else(|| self.resolve_topic_name(&self.config.default_topic));

                        // Create separate routing for each element in the array
                        for array_element in array {
                            if let FieldValue::Message(individual_msg) = array_element {
                                // Create GenericMessage with individual message fields
                                let message = self.create_individual_message(
                                    field_name,
                                    individual_msg,
                                    block_number,
                                )?;

                                routings.push(DynamicTopicRouting {
                                    topic_name: topic_name.clone(),
                                    message_data: message,
                                });
                            }
                        }

                        debug!(
                            "Routed repeated field '{}' to topic '{}' ({} individual messages)",
                            field_name,
                            topic_name,
                            array.len()
                        );
                    }
                    FieldValue::Array(_) => {
                        // Empty array - skip this field
                        debug!("Repeated field '{}' is empty - skipping", field_name);
                    }
                    _ => {
                        // Not an array - unexpected but log it
                        debug!(
                            "Field '{}' is not an array (found {:?}) - skipping",
                            field_name, field_value
                        );
                    }
                }
            } else {
                // Field not found in message - skip
                debug!(
                    "Repeated field '{}' not found in message - skipping",
                    field_name
                );
            }
        }

        // If no repeated fields had data, route to default topic
        if routings.is_empty() {
            debug!("No repeated fields contained data - routing to default topic");
            return self.create_default_routing(
                parsed_message,
                block_number,
                "no_repeated_fields_with_data",
            );
        }

        Ok(routings)
    }

    /// Create GenericMessage from individual message (extracted from repeated field array)
    fn create_individual_message(
        &self,
        message_type: &str,
        individual_msg: &ParsedMessage,
        block_number: u64,
    ) -> Result<GenericMessage> {
        // Use the individual message's fields directly (they will be flattened during serialization)
        Ok(GenericMessage {
            message_type: message_type.to_string(),
            block_number,
            processed_at: chrono::Utc::now().to_rfc3339(),
            fields: LazyJsonFields::Raw(individual_msg.fields.clone()),
        })
    }

    /// Create default routing for messages that don't match specific patterns
    fn create_default_routing(
        &self,
        parsed_message: &ParsedMessage,
        block_number: u64,
        reason: &str,
    ) -> Result<Vec<DynamicTopicRouting>> {
        let topic_name = self.resolve_topic_name(&self.config.default_topic);
        let message = self.create_generic_message(parsed_message, reason, block_number)?;

        debug!(
            "Routed to default topic '{}' (reason: {})",
            topic_name, reason
        );

        Ok(vec![DynamicTopicRouting {
            topic_name,
            message_data: message,
        }])
    }

    /// Route based on string field value
    fn create_string_field_routing(
        &self,
        parsed_message: &ParsedMessage,
        block_number: u64,
        field_path: &str,
        value_mapping: &HashMap<String, String>,
    ) -> Result<Vec<DynamicTopicRouting>> {
        let field_value = self.extract_string_by_path(parsed_message, field_path)?;

        let topic_name = value_mapping
            .get(&field_value)
            .map(|topic| self.resolve_topic_name(topic))
            .unwrap_or_else(|| self.resolve_topic_name(&self.config.default_topic));

        let message = self.create_generic_message(parsed_message, &field_value, block_number)?;

        debug!(
            "Routed string field '{}' to topic '{}'",
            field_value, topic_name
        );

        Ok(vec![DynamicTopicRouting {
            topic_name,
            message_data: message,
        }])
    }

    /// Route based on message type
    fn create_message_type_routing(
        &self,
        parsed_message: &ParsedMessage,
        block_number: u64,
        type_mapping: &HashMap<String, String>,
    ) -> Result<Vec<DynamicTopicRouting>> {
        let message_type = self.extract_message_type_name(&parsed_message.type_url);

        let topic_name = type_mapping
            .get(&message_type)
            .map(|topic| self.resolve_topic_name(topic))
            .unwrap_or_else(|| self.resolve_topic_name(&self.config.default_topic));

        let message = self.create_generic_message(parsed_message, &message_type, block_number)?;

        debug!(
            "Routed message type '{}' to topic '{}'",
            message_type, topic_name
        );

        Ok(vec![DynamicTopicRouting {
            topic_name,
            message_data: message,
        }])
    }

    /// Extract message by field path (e.g., "instructions[0]")
    fn extract_message_by_path<'a>(
        &self,
        parsed_message: &'a ParsedMessage,
        field_path: &str,
    ) -> Result<&'a ParsedMessage> {
        // Parse field path: "instructions[0]" -> ("instructions", Some(0))
        let (field_name, array_index) = self.parse_field_path(field_path);

        match parsed_message.fields.get(field_name) {
            Some(FieldValue::Array(array)) => {
                let index = array_index.unwrap_or(0);
                match array.get(index) {
                    Some(FieldValue::Message(message)) => Ok(message),
                    Some(_) => Err(anyhow!(
                        "Field path '{}' does not point to a message",
                        field_path
                    )),
                    None => Err(anyhow!(
                        "Array index {} out of bounds for field '{}'",
                        index,
                        field_name
                    )),
                }
            }
            Some(FieldValue::Message(message)) => Ok(message),
            Some(_) => Err(anyhow!("Field '{}' is not a message or array", field_name)),
            None => Err(anyhow!("Field '{}' not found", field_name)),
        }
    }

    /// Extract string value by field path - OPTIMIZED for performance
    fn extract_string_by_path(
        &self,
        parsed_message: &ParsedMessage,
        field_path: &str,
    ) -> Result<String> {
        let target_message = self.extract_message_by_path(parsed_message, field_path)?;

        // OPTIMIZATION: Fast path for common cases
        // Check if there's only one string field (common case)
        let string_fields: Vec<_> = target_message
            .fields
            .values()
            .filter_map(|field| match field {
                FieldValue::String(s) => Some(s),
                _ => None,
            })
            .collect();

        match string_fields.len() {
            0 => Err(anyhow!("No string field found at path '{}'", field_path)),
            1 => Ok(string_fields[0].clone()), // Fast path - single string
            _ => {
                // Multiple strings - use original logic
                target_message
                    .fields
                    .values()
                    .find_map(|field| match field {
                        FieldValue::String(s) => Some(s.clone()),
                        _ => None,
                    })
                    .ok_or_else(|| anyhow!("No string field found at path '{}'", field_path))
            }
        }
    }

    /// Detect which oneof variant is present in a message
    fn detect_oneof_variant(&self, message: &ParsedMessage, _oneof_field: &str) -> Result<String> {
        // Look for non-metadata fields (exclude instruction_id, transaction_hash)
        for (field_name, _field_value) in &message.fields {
            if !self.is_metadata_field(field_name) {
                return Ok(field_name.clone());
            }
        }

        Err(anyhow!("No oneof variant detected in message"))
    }

    /// Check if a field is metadata (instruction_id, transaction_hash, etc.)
    fn is_metadata_field(&self, field_name: &str) -> bool {
        matches!(
            field_name,
            "instruction_id" | "transaction_hash" | "block_number"
        )
    }

    /// Parse field path like "instructions[0]" into ("instructions", Some(0))
    fn parse_field_path<'a>(&self, field_path: &'a str) -> (&'a str, Option<usize>) {
        if let Some(bracket_start) = field_path.find('[') {
            if let Some(bracket_end) = field_path.find(']') {
                let field_name = &field_path[..bracket_start];
                let index_str = &field_path[bracket_start + 1..bracket_end];
                let index = index_str.parse().unwrap_or(0);
                return (field_name, Some(index));
            }
        }
        (field_path, None)
    }

    /// Extract message type name from type URL
    fn extract_message_type_name(&self, type_url: &str) -> String {
        type_url.split('.').last().unwrap_or("unknown").to_string()
    }

    /// Create generic message from protobuf data - OPTIMIZED (lazy JSON conversion)
    fn create_generic_message(
        &self,
        parsed_message: &ParsedMessage,
        message_type: &str,
        block_number: u64,
    ) -> Result<GenericMessage> {
        // Store raw fields - JSON conversion happens only when serializing
        Ok(GenericMessage {
            message_type: message_type.to_string(),
            block_number,
            processed_at: chrono::Utc::now().to_rfc3339(),
            fields: LazyJsonFields::Raw(parsed_message.fields.clone()),
        })
    }

    /// Convert protobuf fields to JSON value
    #[allow(dead_code)] // Used by future fanout strategies
    fn protobuf_fields_to_json(
        &self,
        fields: &HashMap<String, FieldValue>,
    ) -> Result<serde_json::Value> {
        let mut json_map = serde_json::Map::new();

        for (field_name, field_value) in fields {
            let json_value = self.field_value_to_json(field_value)?;
            json_map.insert(field_name.clone(), json_value);
        }

        Ok(serde_json::Value::Object(json_map))
    }

    /// Convert individual field value to JSON
    #[allow(dead_code)] // Used by protobuf_fields_to_json when needed
    fn field_value_to_json(&self, field_value: &FieldValue) -> Result<serde_json::Value> {
        match field_value {
            FieldValue::String(s) => Ok(serde_json::Value::String(s.clone())),
            FieldValue::Uint64(n) => Ok(serde_json::Value::Number((*n).into())),
            FieldValue::Uint32(n) => Ok(serde_json::Value::Number((*n).into())),
            FieldValue::Bool(b) => Ok(serde_json::Value::Bool(*b)),
            FieldValue::Array(arr) => {
                let json_array: Result<Vec<_>, _> = arr
                    .iter()
                    .map(|item| self.field_value_to_json(item))
                    .collect();
                Ok(serde_json::Value::Array(json_array?))
            }
            FieldValue::Message(msg) => self.protobuf_fields_to_json(&msg.fields),
            _ => Ok(serde_json::Value::Null), // Fallback for unsupported types
        }
    }

    /// Resolve topic name with optional prefix - OPTIMIZED with caching
    fn resolve_topic_name(&self, base_topic: &str) -> String {
        // Fast path: check cache first (read lock)
        if let Ok(cache) = self.topic_cache.read() {
            if let Some(cached) = cache.get(base_topic) {
                return cached.clone();
            }
        }

        // Slow path: compute and cache result (write lock)
        let resolved = if let Some(prefix) = &self.config.topic_prefix {
            format!("{}_{}", prefix, base_topic)
        } else {
            base_topic.to_string()
        };

        // Cache the result
        if let Ok(mut cache) = self.topic_cache.write() {
            cache.insert(base_topic.to_string(), resolved.clone());
        }

        resolved
    }
}

/// Load dynamic fanout configuration from file
pub fn load_fanout_config(config_path: &str) -> Result<DynamicFanoutConfig> {
    let config_content = std::fs::read_to_string(config_path)
        .map_err(|e| anyhow!("Failed to read config file '{}': {}", config_path, e))?;

    let config: DynamicFanoutConfig = serde_yaml::from_str(&config_content)
        .map_err(|e| anyhow!("Failed to parse config file '{}': {}", config_path, e))?;

    info!("Loaded dynamic fanout config from '{}'", config_path);
    Ok(config)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_parse_field_path() {
        let router = DynamicMessageRouter::new(DynamicFanoutConfig {
            fanout_strategy: FanoutStrategy::SingleTopic,
            discriminator: None,
            topics: HashMap::new(),
            default_topic: "test".to_string(),
            topic_prefix: None,
        });

        assert_eq!(
            router.parse_field_path("instructions[0]"),
            ("instructions", Some(0))
        );
        assert_eq!(router.parse_field_path("field"), ("field", None));
        assert_eq!(router.parse_field_path("data[5]"), ("data", Some(5)));
    }
}
