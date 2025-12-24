#!/bin/bash

# DripSwap Uniswap V2 Substreams 构建脚本

set -e

echo "================================================"
echo "  DripSwap Uniswap V2 Substreams - Build Script"
echo "================================================"
echo ""

# 检查 Rust 环境
if ! command -v cargo &> /dev/null; then
    echo "❌ Error: Rust/Cargo not found"
    echo "   Please install Rust: https://rustup.rs/"
    exit 1
fi

# 检查 wasm32 target
if ! rustup target list --installed | grep -q "wasm32-unknown-unknown"; then
    echo "📦 Installing wasm32-unknown-unknown target..."
    rustup target add wasm32-unknown-unknown
fi

# 检查 substreams CLI
if ! command -v substreams &> /dev/null; then
    echo "⚠️  Warning: substreams CLI not found"
    echo "   Install: https://substreams.streamingfast.io/getting-started/installing-the-cli"
    echo ""
fi

echo "🔨 Building Substreams WASM module..."
echo ""

# 编译
cargo build --release --target wasm32-unknown-unknown

if [ $? -eq 0 ]; then
    echo ""
    echo "✅ Build successful!"
    echo ""
    echo "Output: target/wasm32-unknown-unknown/release/substreams_uniswap_v2.wasm"
    echo ""
    echo "Next steps:"
    echo "  1. Test locally:"
    echo "     substreams gui substreams-v2.yaml graph_out -e https://sepolia.substreams.pinax.network:443 -t +100"
    echo ""
    echo "  2. Package for deployment:"
    echo "     substreams pack substreams-v2.yaml"
    echo ""
else
    echo ""
    echo "❌ Build failed!"
    exit 1
fi
