// Processing Mode Management - Fail-fast vs Best-effort!

use anyhow::Result;
use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};
use tracing::{error, info, warn};

/// Processing mode determines error handling strategy
#[derive(Debug, Clone, Copy, PartialEq)]
pub enum ProcessingMode {
    /// Structured output mode - fail fast on any parsing error
    StructuredOutput,
    /// Legacy output mode - best-effort with fallbacks
    LegacyOutput,
}

impl ProcessingMode {
    pub fn from_env() -> Self {
        match std::env::var("ENABLE_STRUCTURED_OUTPUT") {
            Ok(val) if val.to_lowercase() == "true" => ProcessingMode::StructuredOutput,
            _ => ProcessingMode::LegacyOutput,
        }
    }

    #[allow(dead_code)] // Available for processing mode checks
    pub fn is_structured(&self) -> bool {
        matches!(self, ProcessingMode::StructuredOutput)
    }
}

/// Result of structured processing - either success or critical failure
#[derive(Debug)]
#[allow(dead_code)] // Future feature for structured processing results
pub enum StructuredProcessingResult<T> {
    Success(T),
    CriticalFailure(CriticalProcessingError),
}

/// Critical processing error that requires stopping ingestion
#[derive(Debug, Clone, Serialize)]
pub struct CriticalProcessingError {
    pub block_number: u64,
    pub error_type: String,
    pub error_message: String,
    pub raw_data: Vec<u8>,
    pub debug_info: DebugInfo,
    pub occurred_at: DateTime<Utc>,
}

/// Debug information for troubleshooting parsing failures
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct DebugInfo {
    pub type_url: String,
    pub protobuf_size: usize,
    pub available_fields: Vec<String>,
    pub extraction_context: String,
    pub expected_fields: Vec<String>,
}

/// Instructions for resuming ingestion after fixing issues
#[derive(Debug, Serialize)]
pub struct ResumptionInfo {
    pub failed_block: u64,
    pub cursor_for_resume: Option<String>,
    pub raw_data_location: String,
    pub suggested_fixes: Vec<String>,
}

/// Ingestion control action based on processing results
#[derive(Debug)]
#[allow(dead_code)] // Future feature for ingestion control
pub enum IngestionControlAction {
    Continue,
    StopImmediately {
        reason: String,
        debug_report: DebugReport,
        resumption_info: ResumptionInfo,
    },
}

/// Complete debug report generated on critical failures
#[derive(Debug, Serialize)]
pub struct DebugReport {
    pub failure_summary: String,
    pub block_number: u64,
    pub timestamp: DateTime<Utc>,
    pub error_details: CriticalProcessingError,
    pub processing_mode: String,
    pub system_context: SystemContext,
}

/// System context at time of failure
#[derive(Debug, Serialize)]
pub struct SystemContext {
    pub sink_version: String,
    pub processing_mode: String,
    pub kafka_topics: Vec<String>,
    pub protobuf_schemas_loaded: Vec<String>,
}

impl CriticalProcessingError {
    #[allow(dead_code)] // Available for error handling
    pub fn new(
        block_number: u64,
        error_type: &str,
        error_message: &str,
        raw_data: Vec<u8>,
        debug_info: DebugInfo,
    ) -> Self {
        Self {
            block_number,
            error_type: error_type.to_string(),
            error_message: error_message.to_string(),
            raw_data,
            debug_info,
            occurred_at: Utc::now(),
        }
    }

    /// Save raw protobuf data for later analysis
    #[allow(dead_code)] // Available for error handling
    pub fn save_raw_data(&self) -> Result<String> {
        let filename = format!("failed_block_{}_raw.bin", self.block_number);
        let filepath = format!("/tmp/protobuf_failures/{}", filename);

        std::fs::create_dir_all("/tmp/protobuf_failures")?;
        std::fs::write(&filepath, &self.raw_data)?;

        info!("Saved raw protobuf data to {} for analysis", filepath);
        Ok(filepath)
    }

    /// Generate comprehensive debug report
    #[allow(dead_code)] // Available for error handling
    pub fn generate_debug_report(&self, system_context: SystemContext) -> DebugReport {
        DebugReport {
            failure_summary: format!(
                "Critical parsing failure in {} mode at block {}",
                system_context.processing_mode, self.block_number
            ),
            block_number: self.block_number,
            timestamp: self.occurred_at,
            error_details: (*self).clone(),
            processing_mode: system_context.processing_mode.clone(),
            system_context,
        }
    }

    /// Generate resumption instructions
    #[allow(dead_code)] // Available for error handling
    pub fn generate_resumption_info(&self, cursor: Option<String>) -> ResumptionInfo {
        let raw_data_path = self
            .save_raw_data()
            .unwrap_or_else(|_| "Failed to save".to_string());

        ResumptionInfo {
            failed_block: self.block_number,
            cursor_for_resume: cursor,
            raw_data_location: raw_data_path.clone(),
            suggested_fixes: vec![
                "Check protobuf schema compatibility".to_string(),
                "Verify field types match expectations".to_string(),
                "Consider updating fanout field extraction logic".to_string(),
                format!("Inspect raw data at: {}", raw_data_path),
            ],
        }
    }
}

/// Processing mode manager handles error strategy decisions
pub struct ProcessingModeManager {
    #[allow(dead_code)] // Future feature for processing mode management
    mode: ProcessingMode,
}

impl ProcessingModeManager {
    #[allow(dead_code)] // Available for error handling
    pub fn new() -> Self {
        let mode = ProcessingMode::from_env();
        info!("Processing mode: {:?}", mode);

        Self { mode }
    }

    #[allow(dead_code)] // Available for mode access
    pub fn mode(&self) -> ProcessingMode {
        self.mode
    }

    /// Determine ingestion control action based on processing result
    #[allow(dead_code)] // Available for processing evaluation
    pub fn evaluate_processing_result<T>(
        &self,
        result: Result<T, anyhow::Error>,
        block_number: u64,
        raw_data: Vec<u8>,
        debug_info: DebugInfo,
        cursor: Option<String>,
    ) -> IngestionControlAction {
        match (self.mode, result) {
            // Structured mode - any error stops ingestion
            (ProcessingMode::StructuredOutput, Err(error)) => {
                let critical_error = CriticalProcessingError::new(
                    block_number,
                    "FieldExtractionFailure",
                    &error.to_string(),
                    raw_data,
                    debug_info,
                );

                let system_context = SystemContext {
                    sink_version: "1.0.0".to_string(),
                    processing_mode: "StructuredOutput".to_string(),
                    kafka_topics: vec!["spl_transfers".to_string(), "spl_burns".to_string()],
                    protobuf_schemas_loaded: vec![
                        "sf.solana.spl.v1.type.SplInstructions".to_string()
                    ],
                };

                let debug_report = critical_error.generate_debug_report(system_context);
                let resumption_info = critical_error.generate_resumption_info(cursor);

                error!(
                    "CRITICAL FAILURE in structured mode at block {} - STOPPING INGESTION! ",
                    block_number
                );

                IngestionControlAction::StopImmediately {
                    reason: format!("Structured mode parsing failure at block {}", block_number),
                    debug_report,
                    resumption_info,
                }
            }

            // Legacy mode - log error but continue
            (ProcessingMode::LegacyOutput, Err(error)) => {
                warn!(
                    "Legacy mode parsing error at block {} - continuing with fallback: {}",
                    block_number, error
                );
                IngestionControlAction::Continue
            }

            // Success in any mode - continue
            (_, Ok(_)) => IngestionControlAction::Continue,
        }
    }
}

impl Default for ProcessingModeManager {
    fn default() -> Self {
        Self::new()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_processing_mode_from_env() {
        std::env::set_var("ENABLE_STRUCTURED_OUTPUT", "true");
        assert_eq!(ProcessingMode::from_env(), ProcessingMode::StructuredOutput);

        std::env::set_var("ENABLE_STRUCTURED_OUTPUT", "false");
        assert_eq!(ProcessingMode::from_env(), ProcessingMode::LegacyOutput);

        std::env::remove_var("ENABLE_STRUCTURED_OUTPUT");
        assert_eq!(ProcessingMode::from_env(), ProcessingMode::LegacyOutput);
    }

    #[test]
    fn test_critical_error_generation() {
        let debug_info = DebugInfo {
            type_url: "sf.solana.spl.v1.type.SplInstructions".to_string(),
            protobuf_size: 1024,
            available_fields: vec!["instructions".to_string()],
            extraction_context: "initialize_mint".to_string(),
            expected_fields: vec!["decimals".to_string(), "mint_address".to_string()],
        };

        let error = CriticalProcessingError::new(
            12345,
            "FieldExtractionFailure",
            "Field 'decimals' not found or not a uint64",
            vec![1, 2, 3, 4],
            debug_info,
        );

        assert_eq!(error.block_number, 12345);
        assert_eq!(error.error_type, "FieldExtractionFailure");
        assert!(error.error_message.contains("decimals"));
    }
}
