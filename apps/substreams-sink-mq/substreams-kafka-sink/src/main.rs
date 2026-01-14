// Kafka-Specific Substreams Sink - Optimized for Redpanda service!

use anyhow::{Context, Result};
use clap::Parser;
use std::fs;
use std::io;
use std::path::{Path, PathBuf};
use std::sync::Arc;
// use std::time::Duration; // Reserved for future timeout features
use tokio::signal;
use tokio_stream::StreamExt;
use tracing::{error, info, warn};

// Kafka/Redpanda imports
use rdkafka::config::ClientConfig;
use rdkafka::producer::{FutureProducer, FutureRecord};
// use rdkafka::util::Timeout; // Reserved for future timeout features
// use std::collections::HashMap; // Reserved for future metadata features

// Shared core imports - ONLY CHANGE from unified version
use std::collections::HashMap;
use substreams_core::protobuf::FieldValue;
use substreams_core::routing::dynamic::{GenericMessage, LazyJsonFields};
use substreams_core::{
    BlockRange, DynamicMessageRouter, ProtobufSchemaLoader, SubstreamsEndpoint, SubstreamsPackage,
};

use prost::Message;
use serde_json;

fn collect_proto_files(dir: &Path) -> std::io::Result<Vec<PathBuf>> {
    let mut files = Vec::new();
    if dir.is_dir() {
        for entry in std::fs::read_dir(dir)? {
            let entry = entry?;
            let path = entry.path();
            if path.is_dir() {
                files.extend(collect_proto_files(&path)?);
            } else if path.extension().map(|ext| ext == "proto").unwrap_or(false) {
                files.push(path);
            }
        }
    }
    Ok(files)
}

fn read_cursor_file(path: &Option<String>) -> Result<Option<String>> {
    let Some(path) = path else {
        return Ok(None);
    };

    match fs::read_to_string(path) {
        Ok(contents) => {
            let trimmed = contents.trim();
            if trimmed.is_empty() {
                Ok(None)
            } else {
                Ok(Some(trimmed.to_string()))
            }
        }
        Err(err) if err.kind() == io::ErrorKind::NotFound => Ok(None),
        Err(err) => Err(err.into()),
    }
}

fn write_cursor_file(path: &Option<String>, cursor: &str) -> Result<()> {
    let Some(path) = path else {
        return Ok(());
    };

    let path = Path::new(path);
    if let Some(parent) = path.parent() {
        fs::create_dir_all(parent)?;
    }
    fs::write(path, cursor)?;
    Ok(())
}

fn extract_cursor(
    block_response: &substreams_core::substreams::BlockResponse,
) -> Option<String> {
    match block_response {
        substreams_core::substreams::BlockResponse::New(block_scoped_data) => {
            if block_scoped_data.cursor.is_empty() {
                None
            } else {
                Some(block_scoped_data.cursor.clone())
            }
        }
        substreams_core::substreams::BlockResponse::Undo(undo_signal) => {
            if undo_signal.last_valid_cursor.is_empty() {
                None
            } else {
                Some(undo_signal.last_valid_cursor.clone())
            }
        }
    }
}

/// Kafka-Specific Substreams Sink - Direct Kafka/Redpanda integration
#[derive(Parser, Debug, Clone)]
#[command(author, version, about, long_about = None)]
struct Args {
    /// Substreams API endpoint
    #[arg(
        long,
        env = "SUBSTREAMS_ENDPOINT",
        default_value = "https://mainnet.sol.streamingfast.io:443"
    )]
    substreams_endpoint: String,

    /// Path to Substreams API token file
    #[arg(long, env = "SUBSTREAMS_API_TOKEN_FILE")]
    substreams_api_token_file: Option<String>,

    /// Substreams API token (can also be provided via file)
    #[arg(long, env = "SUBSTREAMS_API_TOKEN")]
    substreams_api_token: Option<String>,

    /// Path to Substreams package file (.spkg)
    #[arg(
        long,
        env = "SUBSTREAMS_PACKAGE_FILE",
        default_value = "/app/sf/substreams-spl-all-tokens/substreams-spl-all-tokens-v0.1.0.spkg"
    )]
    substreams_package: String,

    /// Substreams start block
    #[arg(long, env = "SUBSTREAMS_START_BLOCK")]
    start_block: Option<u64>,

    /// Substreams stop block (optional)
    #[arg(long, env = "SUBSTREAMS_STOP_BLOCK")]
    stop_block: Option<u64>,

    /// Module to extract from the Substreams package
    #[arg(long, env = "SUBSTREAMS_MODULE_NAME", default_value = "map_spl_tokens")]
    module_name: String,

    /// Module params string (e.g., "chain_id=11155111;factory=0x...")
    #[arg(long, env = "SUBSTREAMS_MODULE_PARAMS")]
    module_params: Option<String>,

    /// Path to cursor file for resuming streams
    #[arg(long, env = "SUBSTREAMS_CURSOR_FILE")]
    cursor_file: Option<String>,

    /// Kafka broker addresses (comma-separated)
    #[arg(long, env = "KAFKA_BROKERS", default_value = "localhost:9092")]
    kafka_brokers: String,

    /// Kafka topic prefix
    #[arg(long, env = "TOPIC_PREFIX", default_value = "blockchain")]
    topic_prefix: String,

    /// Number of Kafka partitions
    #[arg(long, env = "KAFKA_PARTITIONS", default_value = "4")]
    kafka_partitions: u32,

    /// Path to dynamic fanout configuration YAML file
    #[arg(
        long,
        env = "FANOUT_CONFIG_PATH",
        default_value = "/app/configs/spl-fanout.yaml"
    )]
    fanout_config_path: String,

    /// Directory containing .proto files for dynamic schema loading
    #[arg(long, env = "PROTO_SCHEMA_DIR")]
    proto_schema_dir: Option<String>,

    /// Root message type for protobuf parsing (e.g., sf.solana.spl.v1.type.SplInstructions)
    #[arg(long, env = "PROTO_MESSAGE_TYPE")]
    proto_message_type: Option<String>,
}

/// Kafka backend health information (running on Redpanda by default)
#[derive(Debug, Clone)]
#[allow(dead_code)] // Fields reserved for future health monitoring features
struct KafkaHealth {
    connected: bool,
    latency_ms: Option<u64>,
    pending_messages: Option<usize>,
    error_count: u64,
    last_error: Option<String>,
}

/// Main Kafka sink implementation
struct KafkaSink {
    producer: Arc<FutureProducer>,
    topic_prefix: String,
    #[allow(dead_code)] // Reserved for future partitioning strategies
    partitions: u32,
    protobuf_loader: Option<Arc<ProtobufSchemaLoader>>,
    dynamic_router: Option<Arc<DynamicMessageRouter>>,
    health: Arc<tokio::sync::RwLock<KafkaHealth>>,
}

#[derive(thiserror::Error, Debug)]
#[allow(dead_code)] // Error variants reserved for comprehensive error handling
enum SinkError {
    #[error("Substreams connection error: {0}")]
    SubstreamsError(String),
    #[error("Kafka error: {0}")]
    KafkaError(String),
    #[error("Protobuf parsing error: {0}")]
    ProtobufError(String),
    #[error("Configuration error: {0}")]
    ConfigError(String),
}

impl KafkaSink {
    /// Create a new Kafka sink instance
    async fn new(args: Args) -> Result<Self, SinkError> {
        // Create Kafka producer
        let producer: FutureProducer = ClientConfig::new()
            .set("bootstrap.servers", &args.kafka_brokers)
            .set("message.timeout.ms", "5000")
            .set("queue.buffering.max.messages", "10000")
            .set("queue.buffering.max.ms", "100")
            .set("batch.num.messages", "100")
            .create()
            .map_err(|e| {
                SinkError::KafkaError(format!("Failed to create Kafka producer: {}", e))
            })?;

        info!("Connected to Kafka brokers: {}", args.kafka_brokers);

        // Set up protobuf loader if configured
        let protobuf_loader = if let (Some(schema_dir), Some(message_type)) =
            (&args.proto_schema_dir, &args.proto_message_type)
        {
            info!(
                "Loading user-provided protobuf schemas from: {}",
                schema_dir
            );

            let proto_files = collect_proto_files(Path::new(schema_dir)).map_err(|e| {
                SinkError::ConfigError(format!(
                    "Failed to read schema directory {}: {}",
                    schema_dir, e
                ))
            })?;

            if proto_files.is_empty() {
                return Err(SinkError::ConfigError(format!(
                    "No .proto files found in directory: {}",
                    schema_dir
                ))
                .into());
            }

            match ProtobufSchemaLoader::from_proto_files(&proto_files, message_type) {
                Ok(loader) => {
                    info!("Protobuf schema loader initialized");
                    Some(Arc::new(loader))
                }
                Err(e) => {
                    warn!("Failed to load protobuf schema: {}", e);
                    None
                }
            }
        } else {
            None
        };

        // Load dynamic fanout configuration if file exists
        let dynamic_router = if Path::new(&args.fanout_config_path).exists() {
            match substreams_core::routing::dynamic::load_fanout_config(&args.fanout_config_path) {
                Ok(config) => {
                    info!("Dynamic fanout configuration loaded");
                    Some(Arc::new(DynamicMessageRouter::new(config)))
                }
                Err(e) => {
                    warn!("Failed to load fanout config: {}", e);
                    None
                }
            }
        } else {
            None
        };

        let health = Arc::new(tokio::sync::RwLock::new(KafkaHealth {
            connected: true,
            latency_ms: None,
            pending_messages: None,
            error_count: 0,
            last_error: None,
        }));

        Ok(Self {
            producer: Arc::new(producer),
            topic_prefix: args.topic_prefix,
            partitions: args.kafka_partitions,
            protobuf_loader,
            dynamic_router,
            health,
        })
    }

    /// Process a single block response
    async fn process_block_response(
        &self,
        block_response: substreams_core::substreams::BlockResponse,
    ) -> Result<()> {
        match block_response {
            substreams_core::substreams::BlockResponse::New(block_scoped_data) => {
                info!(
                    "Processing block {}",
                    block_scoped_data
                        .clock
                        .as_ref()
                        .map(|c| c.number)
                        .unwrap_or(0)
                );

                // Process each output in the block
                if let Some(output) = &block_scoped_data.output {
                    let key = format!(
                        "block_{}",
                        block_scoped_data
                            .clock
                            .as_ref()
                            .map(|c| c.number)
                            .unwrap_or(0)
                    );

                    if let Some(map_output) = &output.map_output {
                        // Always use structured protobuf processing
                        let block_number = block_scoped_data
                            .clock
                            .as_ref()
                            .map(|c| c.number)
                            .unwrap_or(0);
                        self.process_structured_output(
                            &key,
                            &map_output.type_url,
                            &map_output.value,
                            block_number,
                        )
                        .await?;
                    }
                }
            }
            substreams_core::substreams::BlockResponse::Undo(undo_signal) => {
                warn!(
                    "Block undo signal received for block {}",
                    undo_signal
                        .last_valid_block
                        .as_ref()
                        .map(|b| b.number)
                        .unwrap_or(0)
                );
            }
        }

        Ok(())
    }

    /// Process structured protobuf output using dynamic routing
    async fn process_structured_output(
        &self,
        key: &str,
        type_url: &str,
        raw_data: &[u8],
        block_number: u64,
    ) -> Result<()> {
        if let Some(loader) = &self.protobuf_loader {
            match loader.parse_protobuf_bytes(type_url, raw_data) {
                Ok(parsed_message) => {
                    if let Some(router) = &self.dynamic_router {
                        // Multi-topic fanout using dynamic routing
                        match router.route_message(&parsed_message, block_number) {
                            Ok(routings) => {
                                if routings.is_empty() {
                                    // Fallback topic - create minimal GenericMessage for JSON
                                    let fallback_topic = format!("{}_fallback", self.topic_prefix);
                                    let fallback_message = self.create_fallback_message(
                                        "fallback",
                                        block_number,
                                        raw_data,
                                    );
                                    self.publish_json_to_kafka(
                                        &fallback_topic,
                                        key,
                                        &fallback_message,
                                    )
                                    .await?;
                                } else {
                                    // Send to all routed topics
                                    for routing in routings {
                                        // Use router's already-prefixed topic name (no double prefixing)

                                        // Always serialize as JSON for downstream sink compatibility
                                        self.publish_json_to_kafka(
                                            &routing.topic_name,
                                            key,
                                            &routing.message_data,
                                        )
                                        .await?;
                                    }
                                }
                            }
                            Err(e) => {
                                error!("Routing error: {}", e);
                                let error_topic = format!("{}_error", self.topic_prefix);
                                let error_message = self.create_fallback_message(
                                    "routing_error",
                                    block_number,
                                    raw_data,
                                );
                                self.publish_json_to_kafka(&error_topic, key, &error_message)
                                    .await?;
                            }
                        }
                    } else {
                        // No router - single topic
                        let topic = format!("{}_structured_data", self.topic_prefix);
                        let structured_message =
                            self.create_fallback_message("structured_data", block_number, raw_data);
                        self.publish_json_to_kafka(&topic, key, &structured_message)
                            .await?;
                    }
                }
                Err(e) => {
                    error!("Protobuf parsing failed: {}", e);
                    // Send raw data to error topic
                    let error_topic = format!("{}_parse_error", self.topic_prefix);
                    let parse_error_message =
                        self.create_fallback_message("parse_error", block_number, raw_data);
                    self.publish_json_to_kafka(&error_topic, key, &parse_error_message)
                        .await?;
                }
            }
        } else {
            // No protobuf loader - send raw data
            let topic = format!("{}_raw_data", self.topic_prefix);
            let raw_message = self.create_fallback_message("raw_data", block_number, raw_data);
            self.publish_json_to_kafka(&topic, key, &raw_message)
                .await?;
        }
        Ok(())
    }

    /// Create a minimal GenericMessage for fallback cases
    fn create_fallback_message(
        &self,
        message_type: &str,
        block_number: u64,
        raw_data: &[u8],
    ) -> GenericMessage {
        // Create minimal fields with raw data as base64
        let mut fields = HashMap::new();
        fields.insert("raw_data".to_string(), FieldValue::Bytes(raw_data.to_vec()));
        fields.insert(
            "data_length".to_string(),
            FieldValue::Uint64(raw_data.len() as u64),
        );

        GenericMessage {
            message_type: message_type.to_string(),
            block_number,
            processed_at: chrono::Utc::now().to_rfc3339(),
            fields: LazyJsonFields::Raw(fields),
        }
    }

    /// Publish protobuf message to Kafka/Redpanda with partitioning
    async fn _publish_protobuf_to_kafka(
        &self,
        topic: String,
        key: String,
        payload: Vec<u8>,
    ) -> Result<()> {
        // Non-blocking send for performance (async optimization)
        let producer = self.producer.clone();
        let health = self.health.clone();

        tokio::spawn(async move {
            // Create FutureRecord inside the async block with owned data
            let record = FutureRecord::to(&topic).key(&key).payload(&payload);

            match producer.send_result(record) {
                Ok(delivery_future) => {
                    tokio::spawn(async move {
                        match delivery_future.await {
                            Ok(Ok(_)) => tracing::debug!("Message delivered to topic: {}", topic),
                            Ok(Err((e, _))) => {
                                tracing::warn!("Failed to deliver message to {}: {}", topic, e);

                                if let Ok(mut health) = health.try_write() {
                                    health.error_count += 1;
                                    health.last_error = Some(e.to_string());
                                }
                            }
                            Err(e) => {
                                tracing::warn!("Delivery future canceled for {}: {}", topic, e);

                                if let Ok(mut health) = health.try_write() {
                                    health.error_count += 1;
                                    health.last_error = Some(e.to_string());
                                }
                            }
                        }
                    });
                }
                Err((e, _)) => {
                    tracing::error!("Failed to send message to {}: {}", topic, e);

                    if let Ok(mut health) = health.try_write() {
                        health.error_count += 1;
                        health.last_error = Some(e.to_string());
                    }
                }
            }
        });

        Ok(())
    }

    /// Publish JSON message to Kafka/Redpanda with partitioning
    async fn publish_json_to_kafka(
        &self,
        topic: &str,
        key: &str,
        message: &GenericMessage,
    ) -> Result<()> {
        // Serialize GenericMessage to JSON
        let json_payload = match serde_json::to_string(message) {
            Ok(json) => json,
            Err(e) => {
                error!("Failed to serialize message to JSON: {}", e);
                return Err(anyhow::anyhow!("JSON serialization failed: {}", e));
            }
        };

        let topic = topic.to_string();
        let key = key.to_string();
        let payload = json_payload.into_bytes();

        // Non-blocking send for performance (async optimization)
        let producer = self.producer.clone();
        let health = self.health.clone();

        tokio::spawn(async move {
            // Create FutureRecord inside the async block with owned data
            let record = FutureRecord::to(&topic).key(&key).payload(&payload);

            match producer.send_result(record) {
                Ok(delivery_future) => {
                    tokio::spawn(async move {
                        match delivery_future.await {
                            Ok(Ok(_)) => {
                                tracing::debug!("JSON message delivered to topic: {}", topic)
                            }
                            Ok(Err((e, _))) => {
                                tracing::warn!(
                                    "Failed to deliver JSON message to {}: {}",
                                    topic,
                                    e
                                );

                                if let Ok(mut health) = health.try_write() {
                                    health.error_count += 1;
                                    health.last_error = Some(e.to_string());
                                }
                            }
                            Err(e) => {
                                tracing::warn!(
                                    "JSON delivery future canceled for {}: {}",
                                    topic,
                                    e
                                );

                                if let Ok(mut health) = health.try_write() {
                                    health.error_count += 1;
                                    health.last_error = Some(e.to_string());
                                }
                            }
                        }
                    });
                }
                Err((e, _)) => {
                    tracing::error!("Failed to send JSON message to {}: {}", topic, e);

                    if let Ok(mut health) = health.try_write() {
                        health.error_count += 1;
                        health.last_error = Some(e.to_string());
                    }
                }
            }
        });

        Ok(())
    }
}

#[tokio::main]
async fn main() -> Result<()> {
    tracing_subscriber::fmt::init();

    let args = Args::parse();

    // Display configuration
    info!("Kafka Substreams Sink Configuration (backed by Redpanda)");
    info!("Substreams endpoint: {}", args.substreams_endpoint);
    info!("Package: {}", args.substreams_package);
    info!("Module: {}", args.module_name);
    info!("Kafka brokers: {}", args.kafka_brokers);
    info!("Topic prefix: {}", args.topic_prefix);
    info!("Partitions: {}", args.kafka_partitions);
    info!("Output format: protobuf");

    // Read API token
    let api_token = if let Some(token) = &args.substreams_api_token {
        token.clone()
    } else if let Some(token_file) = &args.substreams_api_token_file {
        fs::read_to_string(token_file)
            .with_context(|| format!("Failed to read API token file: {}", token_file))?
            .trim()
            .to_string()
    } else {
        return Err(anyhow::anyhow!(
            "API token must be provided via --substreams-api-token or --substreams-api-token-file"
        ));
    };

    // Create Kafka sink targeting Redpanda
    let sink = KafkaSink::new(args.clone())
        .await
        .map_err(|e| anyhow::anyhow!("Failed to create Kafka sink: {}", e))?;

    // Load Substreams package
    let package_data = fs::read(&args.substreams_package)
        .with_context(|| format!("Failed to read package: {}", args.substreams_package))?;

    let _package =
        substreams_core::protobuf::pb::sf::substreams::v1::Package::decode(&package_data[..])
            .context("Failed to decode package")?;

    // Create Substreams stream
    let endpoint = SubstreamsEndpoint::new(&args.substreams_endpoint, Some(api_token.clone()))
        .await
        .context("Failed to create Substreams endpoint")?;

    let _block_range = BlockRange {
        start: args.start_block.unwrap_or(0),
        end: args.stop_block,
    };

    // Load and validate the Substreams package
    info!("📦 Loading Substreams package: {}", args.substreams_package);
    let package = SubstreamsPackage::load(&args.substreams_package)
        .context("Failed to load Substreams package")?;

    let cursor = read_cursor_file(&args.cursor_file)?;

    // Create stream with properly loaded modules
    let mut stream = package.create_stream(
        Arc::new(endpoint),
        args.module_name.clone(),
        args.start_block.unwrap_or(0) as i64,
        args.stop_block.unwrap_or(0),
        cursor,
        args.module_params.clone(),
    )?;

    info!("Starting Kafka sink processing (Redpanda backend)");

    // Set up graceful shutdown
    let shutdown = async {
        signal::ctrl_c()
            .await
            .expect("Failed to install CTRL+C signal handler");
        info!("Shutdown signal received");
    };

    tokio::select! {
        _ = async {
            while let Some(response) = stream.next().await {
                match response {
                    Ok(block_response) => {
                        let cursor = extract_cursor(&block_response);
                        if let Err(e) = sink.process_block_response(block_response).await {
                            error!("Failed to process block response: {}", e);
                        } else {
                            if let Some(cursor) = cursor {
                                if let Err(err) = write_cursor_file(&args.cursor_file, &cursor) {
                                    error!("Failed to persist cursor: {}", err);
                                }
                            }
                        }
                    }
                    Err(e) => {
                        error!("Stream error: {}", e);
                        break;
                    }
                }
            }
        } => {
            info!("Stream processing completed");
        }
        _ = shutdown => {
            info!("Graceful shutdown initiated");
        }
    }

    Ok(())
}
