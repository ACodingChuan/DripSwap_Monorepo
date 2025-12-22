#!/bin/bash
# DripSwap V2 Substreams 构建脚本

set -e

echo "=========================================="
echo "DripSwap V2 Substreams 构建脚本"
echo "=========================================="

# 1. 编译 Rust WASM
echo ""
echo "📦 步骤 1/3: 编译 Rust 代码为 WASM..."
cargo build --target wasm32-unknown-unknown --release

# 检查 WASM 文件是否生成
if [ ! -f "target/wasm32-unknown-unknown/release/substreams_uniswap_v2.wasm" ]; then
    echo "❌ 错误: WASM 文件未生成"
    exit 1
fi

WASM_SIZE=$(ls -lh target/wasm32-unknown-unknown/release/substreams_uniswap_v2.wasm | awk '{print $5}')
echo "✅ WASM 编译成功 (大小: $WASM_SIZE)"

# 2. 打包 Sepolia Substreams
echo ""
echo "📦 步骤 2/3: 打包 Sepolia Substreams..."
substreams pack substreams.yaml

if [ -f "dripswap-v2-v0.1.0.spkg" ]; then
    mv dripswap-v2-v0.1.0.spkg dripswap-v2-sepolia-v0.1.0.spkg
    SPKG_SIZE=$(ls -lh dripswap-v2-sepolia-v0.1.0.spkg | awk '{print $5}')
    echo "✅ Sepolia SPKG 打包成功 (大小: $SPKG_SIZE)"
else
    echo "❌ 错误: Sepolia SPKG 打包失败"
    exit 1
fi

# 3. 打包 Scroll Sepolia Substreams
echo ""
echo "📦 步骤 3/3: 打包 Scroll Sepolia Substreams..."
substreams pack substreams.scroll-sepolia.yaml

if [ -f "dripswap-v2-v0.1.0.spkg" ]; then
    mv dripswap-v2-v0.1.0.spkg dripswap-v2-scroll-sepolia-v0.1.0.spkg
    SPKG_SIZE=$(ls -lh dripswap-v2-scroll-sepolia-v0.1.0.spkg | awk '{print $5}')
    echo "✅ Scroll Sepolia SPKG 打包成功 (大小: $SPKG_SIZE)"
else
    echo "❌ 错误: Scroll Sepolia SPKG 打包失败"
    exit 1
fi

# 完成
echo ""
echo "=========================================="
echo "✅ 构建完成!"
echo "=========================================="
echo ""
echo "生成的文件:"
echo "  - dripswap-v2-sepolia-v0.1.0.spkg"
echo "  - dripswap-v2-scroll-sepolia-v0.1.0.spkg"
echo ""
echo "下一步操作:"
echo "  1. 启动 Sepolia Sink:"
echo "     substreams-sink-postgres run \\"
echo "       \"postgresql://user:pass@localhost:5432/dripswap?sslmode=disable\" \\"
echo "       \"https://sepolia.substreams.pinax.network:443\" \\"
echo "       \"dripswap-v2-sepolia-v0.1.0.spkg\" \\"
echo "       graph_out"
echo ""
echo "  2. 启动 Scroll Sepolia Sink:"
echo "     substreams-sink-postgres run \\"
echo "       \"postgresql://user:pass@localhost:5432/dripswap?sslmode=disable\" \\"
echo "       \"https://scrsepolia.substreams.pinax.network:443\" \\"
echo "       \"dripswap-v2-scroll-sepolia-v0.1.0.spkg\" \\"
echo "       graph_out"
echo ""
