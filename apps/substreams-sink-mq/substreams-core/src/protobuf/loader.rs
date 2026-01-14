// Dynamic Protobuf Schema Loader - Transform raw bytes to structured data!

use anyhow::{anyhow, Result};
use prost::Message;
use prost_reflect::{
    DescriptorPool, DynamicMessage, MessageDescriptor, ReflectMessage, Value as ReflectValue,
};
use prost_types::FileDescriptorSet;
use std::collections::HashMap;
use std::fs;
use std::path::Path;
use tracing::{debug, info};

/// Dynamic protobuf schema loader that parses user-provided .proto files
pub struct ProtobufSchemaLoader {
    /// Compiled protobuf descriptor pool
    #[allow(dead_code)] // Core protobuf processing infrastructure
    descriptor_pool: DescriptorPool,
    /// Maps type URLs to message descriptors
    type_registry: HashMap<String, MessageDescriptor>,
    /// Root message type for parsing
    #[allow(dead_code)] // Core protobuf processing infrastructure
    root_message_type: String,
}

/// Information about a protobuf message type
#[derive(Debug, Clone)]
#[allow(dead_code)] // Available for advanced protobuf introspection
pub struct MessageInfo {
    pub type_url: String,
    pub message_name: String,
    pub fields: Vec<FieldInfo>,
}

/// Information about a protobuf field
#[derive(Debug, Clone)]
#[allow(dead_code)] // Available for advanced protobuf introspection
pub struct FieldInfo {
    pub name: String,
    pub field_type: FieldType,
    pub field_number: u32,
    pub is_repeated: bool,
    pub is_optional: bool,
}

/// Supported protobuf field types for our SPL instruction parsing
#[derive(Debug, Clone)]
#[allow(dead_code)] // Available for advanced protobuf introspection
pub enum FieldType {
    String,
    Uint64,
    Uint32,
    Int64,
    Int32,
    Bool,
    Bytes,
    Message(String), // Message type name
    Enum(String),    // Enum type name
    Oneof(Vec<OneofVariant>),
}

#[derive(Debug, Clone)]
#[allow(dead_code)] // Available for advanced protobuf introspection
pub struct OneofVariant {
    pub name: String,
    pub field_number: u32,
    pub message_type: String,
}

/// Parsed protobuf message with structured data
#[derive(Debug, Clone)]
pub struct ParsedMessage {
    pub type_url: String,
    pub fields: HashMap<String, FieldValue>,
}

/// Structured field values from parsed protobuf
#[derive(Debug, Clone)]
pub enum FieldValue {
    String(String),
    Uint64(u64),
    Uint32(u32),
    #[allow(dead_code)] // Available for signed integer processing
    Int64(i64),
    #[allow(dead_code)] // Available for 32-bit integer processing
    Int32(i32),
    Bool(bool),
    #[allow(dead_code)] // Available for binary data processing
    Bytes(Vec<u8>),
    Message(Box<ParsedMessage>),
    Array(Vec<FieldValue>),
    #[allow(dead_code)] // Available for oneof field handling
    Oneof {
        variant_name: String,
        value: Box<FieldValue>,
    },
}

impl ProtobufSchemaLoader {
    /// Create a new dynamic protobuf schema loader from .proto files
    pub fn from_proto_files<P: AsRef<Path>>(
        proto_files: &[P],
        root_message_type: &str,
    ) -> Result<Self> {
        info!(
            "Loading protobuf schemas from {} .proto files",
            proto_files.len()
        );

        // Compile the .proto files into a descriptor pool
        let descriptor_pool = Self::compile_proto_files(proto_files)?;

        // Build type registry from the descriptor pool
        let type_registry = Self::build_type_registry(&descriptor_pool)?;

        info!(
            "Successfully loaded {} protobuf message types",
            type_registry.len()
        );

        Ok(Self {
            descriptor_pool,
            type_registry,
            root_message_type: root_message_type.to_string(),
        })
    }

    /// Create a loader for SPL instructions using the existing .proto file
    pub fn new_spl_loader() -> Result<Self> {
        // Use the SPL .proto file that we already have
        let proto_file = "/proto/sf/solana/v1/spl/type/spl.proto";

        if !Path::new(proto_file).exists() {
            return Err(anyhow!("SPL proto file not found at: {}", proto_file));
        }

        Self::from_proto_files(&[proto_file], "sf.solana.spl.v1.type.SplInstructions")
    }

    /// Compile .proto files into a descriptor pool using protoc
    fn compile_proto_files<P: AsRef<Path>>(proto_files: &[P]) -> Result<DescriptorPool> {
        use std::env;
        use std::process::Command;

        // Create temporary directory for compiled descriptors
        let temp_dir = env::temp_dir().join("substreams_proto_compile");
        fs::create_dir_all(&temp_dir)?;

        let descriptor_file = temp_dir.join("compiled.desc");

        // Build protoc command
        let mut protoc_cmd = Command::new("protoc");
        protoc_cmd.arg("--descriptor_set_out").arg(&descriptor_file);
        protoc_cmd.arg("--include_imports");

        // Add include paths (look in the proto file directories)
        for proto_file in proto_files {
            if let Some(parent_dir) = proto_file.as_ref().parent() {
                protoc_cmd.arg("--proto_path").arg(parent_dir);
            }
        }

        // Add additional proto paths for dependencies
        protoc_cmd.arg("--proto_path").arg("/proto");
        //protoc_cmd.arg("--proto_path").arg("/proto-deps");

        // Add the proto files
        for proto_file in proto_files {
            protoc_cmd.arg(proto_file.as_ref());
        }

        debug!("Running protoc: {:?}", protoc_cmd);

        // Execute protoc
        let output = protoc_cmd.output()?;

        if !output.status.success() {
            let stderr = String::from_utf8_lossy(&output.stderr);
            return Err(anyhow!("protoc compilation failed: {}", stderr));
        }

        // Load the compiled descriptor set
        let descriptor_bytes = fs::read(&descriptor_file)?;
        let file_descriptor_set = FileDescriptorSet::decode(&descriptor_bytes[..])?;
        let descriptor_pool = DescriptorPool::from_file_descriptor_set(file_descriptor_set)?;

        // Clean up
        let _ = fs::remove_file(&descriptor_file);
        let _ = fs::remove_dir(&temp_dir);

        info!("Successfully compiled protobuf descriptors");
        Ok(descriptor_pool)
    }

    /// Build type registry from descriptor pool
    fn build_type_registry(pool: &DescriptorPool) -> Result<HashMap<String, MessageDescriptor>> {
        let mut registry = HashMap::new();

        // Register all message types from the descriptor pool
        for message_desc in pool.all_messages() {
            let full_name = message_desc.full_name().to_string();
            let type_url = format!("type.googleapis.com/{}", full_name);

            debug!("Registering message type: {} -> {}", type_url, full_name);
            registry.insert(type_url, message_desc);
        }

        Ok(registry)
    }

    /// Parse protobuf bytes into structured data using dynamic reflection
    pub fn parse_protobuf_bytes(&self, type_url: &str, data: &[u8]) -> Result<ParsedMessage> {
        debug!(
            "Dynamically parsing {} bytes for type: {}",
            data.len(),
            type_url
        );

        // Find the message descriptor for this type URL
        let message_desc = self
            .type_registry
            .get(type_url)
            .ok_or_else(|| anyhow!("No message descriptor found for type: {}", type_url))?;

        // Parse the binary data into a dynamic message
        let dynamic_message = DynamicMessage::decode(message_desc.clone(), data)
            .map_err(|e| anyhow!("Failed to decode protobuf message: {}", e))?;

        // Convert to our structured format
        let parsed_message = self.convert_dynamic_message_to_parsed(&dynamic_message, type_url)?;

        info!("Successfully parsed real protobuf data for {}", type_url);
        Ok(parsed_message)
    }

    /// Convert a DynamicMessage to our ParsedMessage format
    fn convert_dynamic_message_to_parsed(
        &self,
        dynamic_msg: &DynamicMessage,
        type_url: &str,
    ) -> Result<ParsedMessage> {
        let mut fields = HashMap::new();

        // Iterate through all fields in the dynamic message
        for (field_desc, value) in dynamic_msg.fields() {
            let field_name = field_desc.name().to_string();
            let field_value = self.convert_reflect_value_to_field_value(value)?;
            fields.insert(field_name, field_value);
        }

        Ok(ParsedMessage {
            type_url: type_url.to_string(),
            fields,
        })
    }

    /// Convert a prost_reflect::Value to our FieldValue
    fn convert_reflect_value_to_field_value(&self, value: &ReflectValue) -> Result<FieldValue> {
        match value {
            ReflectValue::Bool(b) => Ok(FieldValue::Bool(*b)),
            ReflectValue::I32(i) => Ok(FieldValue::Int32(*i)),
            ReflectValue::I64(i) => Ok(FieldValue::Int64(*i)),
            ReflectValue::U32(u) => Ok(FieldValue::Uint32(*u)),
            ReflectValue::U64(u) => Ok(FieldValue::Uint64(*u)),
            ReflectValue::String(s) => Ok(FieldValue::String(s.clone())),
            ReflectValue::Bytes(b) => Ok(FieldValue::Bytes(b.to_vec())),
            ReflectValue::List(list) => {
                let mut array_values = Vec::new();
                for item in list.iter() {
                    array_values.push(self.convert_reflect_value_to_field_value(item)?);
                }
                Ok(FieldValue::Array(array_values))
            }
            ReflectValue::Message(msg) => {
                let type_url = format!("type.googleapis.com/{}", msg.descriptor().full_name());
                let parsed_msg = self.convert_dynamic_message_to_parsed(msg, &type_url)?;
                Ok(FieldValue::Message(Box::new(parsed_msg)))
            }
            _ => {
                debug!("Unsupported reflect value type, using string representation");
                Ok(FieldValue::String(format!("{:?}", value)))
            }
        }
    }

    /// Get message descriptor for a type URL
    #[allow(dead_code)] // Available for protobuf introspection
    pub fn get_message_descriptor(&self, type_url: &str) -> Option<&MessageDescriptor> {
        self.type_registry.get(type_url)
    }

    /// List all registered types
    #[allow(dead_code)] // Available for protobuf introspection
    pub fn list_registered_types(&self) -> Vec<&str> {
        self.type_registry.keys().map(|s| s.as_str()).collect()
    }
}

/// Helper to extract instruction type from parsed message
pub fn extract_instruction_type(parsed_message: &ParsedMessage) -> Result<String> {
    use tracing::info;

    // Info: Log the actual structure we received
    info!("Parsed message type_url: {}", parsed_message.type_url);
    info!(
        "Available fields: {:?}",
        parsed_message.fields.keys().collect::<Vec<_>>()
    );

    // Navigate to the first instruction to determine its type
    if let Some(FieldValue::Array(instructions)) = parsed_message.fields.get("instructions") {
        info!("Found instructions array with {} items", instructions.len());
        if let Some(FieldValue::Message(instruction)) = instructions.first() {
            info!(
                "First instruction fields: {:?}",
                instruction.fields.keys().collect::<Vec<_>>()
            );

            // The real protobuf has the instruction type as direct field names, not in a oneof called "item"
            // Look for known instruction types in the field names
            for field_name in instruction.fields.keys() {
                match field_name.as_str() {
                    "transfer" | "mint" | "burn" | "initialize_mint" | "initialized_account" => {
                        info!("Found instruction type: {}", field_name);
                        return Ok(field_name.clone());
                    }
                    _ => continue,
                }
            }

            info!("No recognized instruction type found in fields");
        } else {
            info!("First instruction is not a message");
        }
    } else {
        info!("No 'instructions' field found or not an array");
    }

    Err(anyhow!(
        "Could not extract instruction type from parsed message"
    ))
}

/// Helper to extract instruction data for specific type
pub fn extract_instruction_data<'a>(
    parsed_message: &'a ParsedMessage,
    instruction_type: &str,
) -> Result<&'a ParsedMessage> {
    use tracing::{info, warn};

    if let Some(FieldValue::Array(instructions)) = parsed_message.fields.get("instructions") {
        if let Some(FieldValue::Message(instruction)) = instructions.first() {
            info!(
                "Extracting {} instruction data from fields: {:?}",
                instruction_type,
                instruction.fields.keys().collect::<Vec<_>>()
            );

            // The real protobuf has the instruction data directly as field names
            if let Some(FieldValue::Message(data)) = instruction.fields.get(instruction_type) {
                // DEBUG: Show what's actually in the nested instruction data
                info!(
                    "Found {} instruction data with fields: {:?}",
                    instruction_type,
                    data.fields.keys().collect::<Vec<_>>()
                );

                // DEBUG: Show the actual field values for critical fields
                for (field_name, field_value) in &data.fields {
                    match field_value {
                        FieldValue::String(s) => info!("  {}: '{}'", field_name, s),
                        FieldValue::Uint64(u) => info!("  {}: {}", field_name, u),
                        FieldValue::Uint32(u) => info!("  {}: {} (u32)", field_name, u),
                        FieldValue::Bool(b) => info!("  {}: {}", field_name, b),
                        _ => info!("  {}: {:?}", field_name, field_value),
                    }
                }

                return Ok(data.as_ref());
            } else {
                warn!(
                    "Instruction type '{}' field not found in instruction! Available: {:?}",
                    instruction_type,
                    instruction.fields.keys().collect::<Vec<_>>()
                );
            }
        } else {
            warn!("First instruction is not a message");
        }
    } else {
        warn!("No 'instructions' array found in message");
    }

    Err(anyhow!(
        "Could not extract {} instruction data",
        instruction_type
    ))
}
