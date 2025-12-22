# Phase 2 执行状态

最后更新：2025-12-22 20:40

---

## ✅ 已完成

### 1. 配置信息收集 ✅
- ✅ Sepolia Factory: `0x6C9258026A9272368e49bBB7D0A78c17BBe284BF`
- ✅ Sepolia Oracle: `0x694AA1769357215DE4FAC081bf1f309aDC325306`
- ✅ Sepolia 初始区块: `9573280`
- ✅ Scroll Sepolia Factory: `0x6C9258026A9272368e49bBB7D0A78c17BBe284BF`
- ✅ Scroll Sepolia Oracle: `0x59F1ec1f10bD7eD9B938431086bC1D9e233ECf41`
- ✅ Scroll Sepolia 初始区块: `14731854`
- ✅ 白名单代币（7个，两链相同 - 确定性部署）

### 2. 文件创建 ✅
- ✅ `src/constants.rs` - 完整的配置常量（67行）

---

## ⏭️ 待执行（按优先级）

由于 Phase 2 涉及大量 Rust 代码改造（预计 2000+ 行修改），建议分步执行：

### 关键提示

当前 `src/` 目录仍是 V3 代码，直接编译会失败。需要完成以下核心改造：

1. **修改 proto 引用路径**（从 v1 改为 v2）
2. **删除 V3 特有代码**（Tick、Position、Flash）
3. **实现 V2 事件处理**（Sync、Transfer、Pending 状态机）
4. **简化价格计算**（从 sqrtPrice 改为 reserve 比例）

### 建议执行方式

鉴于改造复杂度，建议：

**方案 A：渐进式改造**（推荐）
1. 先完成 proto 编译生成 Rust 绑定
2. 逐个文件进行适配
3. 每完成一个模块就编译测试
4. 确保每步都可编译通过

**方案 B：完整重写**
1. 参考 V3 代码结构
2. 从零开始编写 V2 版本
3. 一次性完成所有改造
4. 最后统一编译测试

---

## 📋 下一步行动建议

由于代码改造量大，我建议先执行以下准备工作：

### Step 1: 编译 Protobuf
```bash
cd apps/substream
# 编译 proto 生成 Rust 绑定
cargo build
```

这将：
- 生成 `src/pb/uniswap.v2.rs`
- 生成 ABI Rust 绑定
- 暴露编译错误

### Step 2: 查看编译错误
根据编译错误确定需要修改的确切位置

### Step 3: 逐步适配
按照 PHASE2-PLAN.md 的步骤进行改造

---

## 🎯 成功标准

Phase 2 完成的标志：
- [ ] 编译通过（无错误）
- [ ] 所有 V3 代码已删除
- [ ] V2 事件处理完整实现
- [ ] 可以打包生成 `.spkg` 文件

---

## 💡 重要提醒

1. **Proto 路径**：新代码使用 `pb::uniswap::v2`，需要更新所有 import
2. **ABI 绑定**：已配置为生成 `pair`、`oracle`、`bridge`，不再有 `pool`
3. **白名单**：已使用您提供的 7 个代币地址
4. **确定性部署**：Factory 地址两链相同，简化了配置

---

**当前状态**：配置完成，等待代码改造

**预估工作量**：16-24 小时（完整改造）

**建议**：由于改造复杂，可以分多次会话完成
