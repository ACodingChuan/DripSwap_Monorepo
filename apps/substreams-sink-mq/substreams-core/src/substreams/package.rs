// Substreams Package Loading & Validation

use anyhow::{anyhow, Context, Error};
use prost::Message;
use std::sync::Arc;

use crate::pb::sf::substreams::v1::{module, Modules, Package};
use crate::substreams::{SubstreamsEndpoint, SubstreamsStream};

/// Loaded Substreams package with validated modules
#[derive(Debug, Clone)]
pub struct SubstreamsPackage {
    /// The loaded modules from the .spkg file
    pub modules: Modules,
    /// Path to the package file for reference
    pub package_path: String,
}

impl SubstreamsPackage {
    /// Load a Substreams package from a .spkg file
    pub fn load(package_path: &str) -> Result<Self, Error> {
        // Load the .spkg package file
        let package_bytes = std::fs::read(package_path)
            .context(format!("Failed to read package file: {}", package_path))?;

        // Decode the protobuf package
        let package = Package::decode(package_bytes.as_ref())
            .context("Failed to decode Substreams package")?;

        // Extract modules
        let modules = package
            .modules
            .ok_or_else(|| anyhow!("No modules found in package"))?;

        tracing::info!(
            "📦 Loaded Substreams package: {} modules available",
            modules.modules.len()
        );

        Ok(SubstreamsPackage {
            modules,
            package_path: package_path.to_string(),
        })
    }

    /// Validate that a specific module exists in the package
    pub fn validate_module(&self, module_name: &str) -> Result<(), Error> {
        if !self.modules.modules.iter().any(|m| m.name == module_name) {
            return Err(anyhow!(
                "Module '{}' not found in package '{}'. Available modules: {}",
                module_name,
                self.package_path,
                self.modules
                    .modules
                    .iter()
                    .map(|m| m.name.as_str())
                    .collect::<Vec<_>>()
                    .join(", ")
            ));
        }

        tracing::info!("✅ Module '{}' validated in package", module_name);
        Ok(())
    }

    /// Get all available module names in the package
    pub fn available_modules(&self) -> Vec<&str> {
        self.modules
            .modules
            .iter()
            .map(|m| m.name.as_str())
            .collect()
    }

    /// Create a SubstreamsStream from this package
    pub fn create_stream(
        &self,
        endpoint: Arc<SubstreamsEndpoint>,
        module_name: String,
        start_block: i64,
        stop_block: u64,
        cursor: Option<String>,
        module_params: Option<String>,
    ) -> Result<SubstreamsStream, Error> {
        // Validate the module exists before creating stream
        self.validate_module(&module_name)?;

        let modules = apply_module_params(self.modules.clone(), module_params);

        tracing::info!(
            "🌊 Creating Substreams stream for module '{}' (blocks {}-{})",
            module_name,
            start_block,
            stop_block
        );

        Ok(SubstreamsStream::new(
            endpoint,
            cursor,
            Some(modules),              // ✅ Pass the loaded modules!
            module_name,
            start_block,
            stop_block,
        ))
    }
}

fn apply_module_params(mut modules: Modules, module_params: Option<String>) -> Modules {
    let Some(params_value) = module_params.filter(|value| !value.trim().is_empty()) else {
        return modules;
    };

    for module in modules.modules.iter_mut() {
        for input in module.inputs.iter_mut() {
            if let Some(module::input::Input::Params(params)) = &mut input.input {
                params.value = params_value.clone();
            }
        }
    }

    modules
}

/// Convenience function to load package and create stream in one call
pub fn load_substreams_from_package(
    endpoint: Arc<SubstreamsEndpoint>,
    package_path: &str,
    module_name: &str,
    start_block: i64,
    stop_block: u64,
    cursor: Option<String>,
    module_params: Option<String>,
) -> Result<SubstreamsStream, Error> {
    let package = SubstreamsPackage::load(package_path)?;
    package.create_stream(
        endpoint,
        module_name.to_string(),
        start_block,
        stop_block,
        cursor,
        module_params,
    )
}
