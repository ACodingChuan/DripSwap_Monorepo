// Build script - using shared core, no protobuf compilation needed

fn main() -> Result<(), Box<dyn std::error::Error>> {
    println!("cargo:rerun-if-changed=build.rs");
    println!("Kafka sink (Redpanda service) - using shared core protobuf files");

    Ok(())
}
