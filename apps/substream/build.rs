use anyhow::{Ok, Result};
use substreams_ethereum::Abigen;

fn main() -> Result<(), anyhow::Error> {
    // V2 Pair ABI
    Abigen::new("pair", "abis/pair.json")?
        .generate()?
        .write_to_file("src/abi/pair.rs")?;
    
    // Factory ABI
    Abigen::new("factory", "abis/factory.json")?
        .generate()?
        .write_to_file("src/abi/factory.rs")?;
    
    // ERC20 ABI
    Abigen::new("erc20", "abis/ERC20.json")?
        .generate()?
        .write_to_file("src/abi/erc20.rs")?;
    
    // Oracle ABI (用于 ETH/USD 价格)
    Abigen::new("oracle", "abis/oracle.json")?
        .generate()?
        .write_to_file("src/abi/oracle.rs")?;
    
    // Bridge ABI (可选，用于跨链转账)
    Abigen::new("bridge", "abis/bridge.json")?
        .generate()?
        .write_to_file("src/abi/bridge.rs")?;

    Ok(())
}
