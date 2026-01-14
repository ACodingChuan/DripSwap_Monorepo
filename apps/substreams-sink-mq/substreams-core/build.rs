// Build script - using static protobuf files from working example

fn main() -> Result<(), Box<dyn std::error::Error>> {
    println!("cargo:rerun-if-changed=build.rs");
    println!("Using pre-generated protobuf files from sink-examples");

    Ok(())
}
