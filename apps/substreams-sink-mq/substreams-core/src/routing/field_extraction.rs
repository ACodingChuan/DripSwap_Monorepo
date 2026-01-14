// Enhanced Field Extraction - Robust protobuf field handling!

use anyhow::{anyhow, Result};
use tracing::{debug, warn};

use crate::processing_mode::DebugInfo;
use crate::protobuf::{FieldValue, ParsedMessage};

/// Enhanced field extractor with robust error handling
pub struct FieldExtractor<'a> {
    message: &'a ParsedMessage,
    context: String,
}

impl<'a> FieldExtractor<'a> {
    pub fn new(message: &'a ParsedMessage, context: &str) -> Self {
        Self {
            message,
            context: context.to_string(),
        }
    }

    /// Extract required string field - fails if missing or wrong type
    #[allow(dead_code)] // Available for comprehensive field extraction
    pub fn extract_required_string(&self, field_name: &str) -> Result<String> {
        match self.message.fields.get(field_name) {
            Some(FieldValue::String(value)) => {
                debug!("Extracted string field '{}': {}", field_name, value);
                Ok(value.clone())
            }
            Some(other_type) => Err(anyhow!(
                "Field '{}' expected string but found {:?} in context '{}'",
                field_name,
                other_type,
                self.context
            )),
            None => Err(anyhow!(
                "Required field '{}' not found in context '{}'",
                field_name,
                self.context
            )),
        }
    }

    /// Extract optional string field - returns None if missing, fails if wrong type
    pub fn extract_optional_string(&self, field_name: &str) -> Result<Option<String>> {
        match self.message.fields.get(field_name) {
            Some(FieldValue::String(value)) => {
                debug!(
                    "Extracted optional string field '{}': {}",
                    field_name, value
                );
                Ok(Some(value.clone()))
            }
            Some(other_type) => Err(anyhow!(
                "Field '{}' expected string but found {:?} in context '{}'",
                field_name,
                other_type,
                self.context
            )),
            None => {
                debug!("Optional string field '{}' not present", field_name);
                Ok(None)
            }
        }
    }

    /// Extract required uint64 field - fails if missing or wrong type
    #[allow(dead_code)] // Available for comprehensive field extraction
    pub fn extract_required_uint64(&self, field_name: &str) -> Result<u64> {
        match self.message.fields.get(field_name) {
            Some(FieldValue::Uint64(value)) => {
                debug!("Extracted uint64 field '{}': {}", field_name, value);
                Ok(*value)
            }
            Some(FieldValue::Uint32(value)) => {
                // Allow uint32 -> uint64 conversion
                debug!(
                    "Converted uint32 to uint64 for field '{}': {}",
                    field_name, value
                );
                Ok(*value as u64)
            }
            Some(other_type) => Err(anyhow!(
                "Field '{}' expected uint64 but found {:?} in context '{}'",
                field_name,
                other_type,
                self.context
            )),
            None => Err(anyhow!(
                "Required field '{}' not found in context '{}'",
                field_name,
                self.context
            )),
        }
    }

    /// Extract optional uint64 field - returns None if missing, fails if wrong type
    pub fn extract_optional_uint64(&self, field_name: &str) -> Result<Option<u64>> {
        match self.message.fields.get(field_name) {
            Some(FieldValue::Uint64(value)) => {
                debug!(
                    "Extracted optional uint64 field '{}': {}",
                    field_name, value
                );
                Ok(Some(*value))
            }
            Some(FieldValue::Uint32(value)) => {
                // Allow uint32 -> uint64 conversion
                debug!(
                    "Converted uint32 to uint64 for optional field '{}': {}",
                    field_name, value
                );
                Ok(Some(*value as u64))
            }
            Some(other_type) => Err(anyhow!(
                "Field '{}' expected uint64 but found {:?} in context '{}'",
                field_name,
                other_type,
                self.context
            )),
            None => {
                debug!("Optional uint64 field '{}' not present", field_name);
                Ok(None)
            }
        }
    }

    /// Extract required bytes field - fails if missing or wrong type
    #[allow(dead_code)] // Available for comprehensive field extraction
    pub fn extract_required_bytes(&self, field_name: &str) -> Result<Vec<u8>> {
        match self.message.fields.get(field_name) {
            Some(FieldValue::Bytes(value)) => {
                debug!(
                    "Extracted bytes field '{}': {} bytes",
                    field_name,
                    value.len()
                );
                Ok(value.clone())
            }
            Some(other_type) => Err(anyhow!(
                "Field '{}' expected bytes but found {:?} in context '{}'",
                field_name,
                other_type,
                self.context
            )),
            None => Err(anyhow!(
                "Required field '{}' not found in context '{}'",
                field_name,
                self.context
            )),
        }
    }

    /// Extract nested message field - fails if missing or wrong type
    #[allow(dead_code)] // Available for comprehensive field extraction
    pub fn extract_required_message(&self, field_name: &str) -> Result<&ParsedMessage> {
        match self.message.fields.get(field_name) {
            Some(FieldValue::Message(message)) => {
                debug!(
                    "Extracted message field '{}' with {} fields",
                    field_name,
                    message.fields.len()
                );
                Ok(message)
            }
            Some(other_type) => Err(anyhow!(
                "Field '{}' expected message but found {:?} in context '{}'",
                field_name,
                other_type,
                self.context
            )),
            None => Err(anyhow!(
                "Required field '{}' not found in context '{}'",
                field_name,
                self.context
            )),
        }
    }

    /// Get all available field names for debugging
    #[allow(dead_code)] // Available for debugging and introspection
    pub fn get_available_fields(&self) -> Vec<String> {
        self.message.fields.keys().cloned().collect()
    }

    /// Generate debug info for error reporting
    #[allow(dead_code)] // Available for debugging field extraction issues
    pub fn generate_debug_info(&self, expected_fields: Vec<String>) -> DebugInfo {
        DebugInfo {
            type_url: self.message.type_url.clone(),
            protobuf_size: 0, // Will be set by caller
            available_fields: self.get_available_fields(),
            extraction_context: self.context.clone(),
            expected_fields,
        }
    }

    /// Validate all required fields exist before extraction
    #[allow(dead_code)] // Available for comprehensive validation
    pub fn validate_required_fields(&self, required_fields: &[&str]) -> Result<()> {
        let mut missing_fields = Vec::new();

        for field_name in required_fields {
            if !self.message.fields.contains_key(*field_name) {
                missing_fields.push(field_name.to_string());
            }
        }

        if !missing_fields.is_empty() {
            return Err(anyhow!(
                "Missing required fields in context '{}': {}. Available fields: {:?}",
                self.context,
                missing_fields.join(", "),
                self.get_available_fields()
            ));
        }

        Ok(())
    }

    /// Extract string with fallback values
    #[allow(dead_code)] // Available for robust field extraction with defaults
    pub fn extract_string_with_fallback(&self, field_name: &str, fallback: &str) -> String {
        match self.extract_optional_string(field_name) {
            Ok(Some(value)) => value,
            Ok(None) => {
                warn!(
                    "Field '{}' missing, using fallback: '{}'",
                    field_name, fallback
                );
                fallback.to_string()
            }
            Err(e) => {
                warn!(
                    "Field '{}' extraction error, using fallback: '{}' - Error: {}",
                    field_name, fallback, e
                );
                fallback.to_string()
            }
        }
    }

    /// Extract uint64 with fallback value
    #[allow(dead_code)] // Available for robust field extraction with defaults
    pub fn extract_uint64_with_fallback(&self, field_name: &str, fallback: u64) -> u64 {
        match self.extract_optional_uint64(field_name) {
            Ok(Some(value)) => value,
            Ok(None) => {
                warn!(
                    "Field '{}' missing, using fallback: {}",
                    field_name, fallback
                );
                fallback
            }
            Err(e) => {
                warn!(
                    "Field '{}' extraction error, using fallback: {} - Error: {}",
                    field_name, fallback, e
                );
                fallback
            }
        }
    }

    /// Extract string with Proto3 default (empty string)
    pub fn extract_string_proto3_default(&self, field_name: &str) -> String {
        match self.extract_optional_string(field_name) {
            Ok(Some(value)) => value,
            Ok(None) => {
                debug!("Field '{}' missing, using Proto3 default: ''", field_name);
                String::new() // Proto3 default for string
            }
            Err(e) => {
                warn!(
                    "Field '{}' extraction error, using Proto3 default: '' - Error: {}",
                    field_name, e
                );
                String::new() // Proto3 default for string
            }
        }
    }

    /// Extract uint64 with Proto3 default (0)
    #[allow(dead_code)] // Available for proto3-compliant field extraction
    pub fn extract_uint64_proto3_default(&self, field_name: &str) -> u64 {
        match self.extract_optional_uint64(field_name) {
            Ok(Some(value)) => value,
            Ok(None) => {
                debug!("Field '{}' missing, using Proto3 default: 0", field_name);
                0 // Proto3 default for uint64
            }
            Err(e) => {
                warn!(
                    "Field '{}' extraction error, using Proto3 default: 0 - Error: {}",
                    field_name, e
                );
                0 // Proto3 default for uint64
            }
        }
    }

    /// Extract uint64 with semantic-aware Proto3 defaults for SPL tokens
    pub fn extract_uint64_spl_semantic_default(&self, field_name: &str) -> u64 {
        match self.extract_optional_uint64(field_name) {
            Ok(Some(value)) => value,
            Ok(None) => {
                let default_value = match field_name {
                    "decimals" => 9, // Common SPL token decimal precision
                    _ => 0,          // Standard Proto3 default for other uint64 fields
                };
                debug!(
                    "Field '{}' missing, using SPL semantic default: {}",
                    field_name, default_value
                );
                default_value
            }
            Err(e) => {
                let default_value = match field_name {
                    "decimals" => 9, // Common SPL token decimal precision
                    _ => 0,          // Standard Proto3 default for other uint64 fields
                };
                warn!(
                    "Field '{}' extraction error, using SPL semantic default: {} - Error: {}",
                    field_name, default_value, e
                );
                default_value
            }
        }
    }
}

/// Helper function to extract instruction metadata safely
#[allow(dead_code)] // Available for instruction-specific extraction
pub fn extract_instruction_metadata(parsed_message: &ParsedMessage) -> Result<(String, String)> {
    if let Some(FieldValue::Array(instructions)) = parsed_message.fields.get("instructions") {
        if let Some(FieldValue::Message(instruction)) = instructions.first() {
            let extractor = FieldExtractor::new(instruction, "instruction_metadata");

            let instruction_id = extractor.extract_required_string("instruction_id")?;
            let transaction_hash = extractor.extract_required_string("transaction_hash")?;

            return Ok((instruction_id, transaction_hash));
        }
    }

    Err(anyhow!(
        "Could not extract instruction metadata - no instructions array found"
    ))
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::collections::HashMap;

    fn create_test_message() -> ParsedMessage {
        let mut fields = HashMap::new();
        fields.insert(
            "test_string".to_string(),
            FieldValue::String("hello".to_string()),
        );
        fields.insert("test_uint64".to_string(), FieldValue::Uint64(12345));
        fields.insert("test_uint32".to_string(), FieldValue::Uint32(67890));
        fields.insert(
            "test_bytes".to_string(),
            FieldValue::Bytes(vec![1, 2, 3, 4]),
        );

        ParsedMessage {
            type_url: "test.message".to_string(),
            fields,
        }
    }

    #[test]
    fn test_extract_required_string() {
        let message = create_test_message();
        let extractor = FieldExtractor::new(&message, "test");

        assert_eq!(
            extractor.extract_required_string("test_string").unwrap(),
            "hello"
        );
        assert!(extractor.extract_required_string("missing_field").is_err());
    }

    #[test]
    fn test_extract_optional_string() {
        let message = create_test_message();
        let extractor = FieldExtractor::new(&message, "test");

        assert_eq!(
            extractor.extract_optional_string("test_string").unwrap(),
            Some("hello".to_string())
        );
        assert_eq!(
            extractor.extract_optional_string("missing_field").unwrap(),
            None
        );
    }

    #[test]
    fn test_extract_uint64_with_conversion() {
        let message = create_test_message();
        let extractor = FieldExtractor::new(&message, "test");

        assert_eq!(
            extractor.extract_required_uint64("test_uint64").unwrap(),
            12345
        );
        assert_eq!(
            extractor.extract_required_uint64("test_uint32").unwrap(),
            67890
        ); // uint32 -> uint64
        assert!(extractor.extract_required_uint64("missing_field").is_err());
    }

    #[test]
    fn test_fallback_methods() {
        let message = create_test_message();
        let extractor = FieldExtractor::new(&message, "test");

        assert_eq!(
            extractor.extract_string_with_fallback("missing_field", "default"),
            "default"
        );
        assert_eq!(
            extractor.extract_uint64_with_fallback("missing_field", 999),
            999
        );
    }

    #[test]
    fn test_validate_required_fields() {
        let message = create_test_message();
        let extractor = FieldExtractor::new(&message, "test");

        assert!(extractor
            .validate_required_fields(&["test_string", "test_uint64"])
            .is_ok());
        assert!(extractor
            .validate_required_fields(&["test_string", "missing_field"])
            .is_err());
    }
}
