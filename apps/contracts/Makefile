# =======================================================
# DripSwap Contract Makefile
# =======================================================

.PHONY: all build test clean fmt deploy-all deploy-verify help
.PHONY: smoke-faucetv2

SHELL := /bin/bash
NETWORK ?= local

# -------------------- 环境加载 --------------------
ifneq (,$(wildcard .env))
  include .env
  export
endif

ifneq (,$(wildcard .env.$(NETWORK)))
  include .env.$(NETWORK)
  export
endif

# -------------------- Forge 参数 --------------------
FORGE_COMMON_FLAGS := --rpc-url $(RPC_URL) --broadcast --force -vvvvv
ifneq ($(strip $(DEPLOYER_PK)),)
  FORGE_COMMON_FLAGS += --private-key $(DEPLOYER_PK)
endif

define run_script
	@FOUNDRY_DISABLE_TERMINAL_PROMPT=1 forge script $(1) $(FORGE_COMMON_FLAGS)
endef

define run_script_verify
	@FOUNDRY_DISABLE_TERMINAL_PROMPT=1 forge script $(1) $(FORGE_COMMON_FLAGS) --verify --etherscan-api-key $(ETHERSCAN_API_KEY)
endef

# -------------------- 构建 --------------------
all: build

build:
	@echo "🔨 编译合约..."
	forge build
	@echo "📄 提取 ABI..."
	npm run extract-abi

build-v2:
	@echo "🔨 编译 V2 核心..."
	./script/build-v2.sh

build-all: build-v2 build

test:
	@echo "🧪 运行测试..."
	forge test -vvv

smoke-faucetv2:
	@echo "🧯 FaucetV2 smoke：部署/配置/入金/签名/代付跑一次 claimWithSig ($(NETWORK))"
	@echo "   - 配置写在 .env.$(NETWORK)（或 .env）里：FAUCET_TOKEN_SYMBOL(推荐) 或 FAUCET_TOKEN、FAUCET_FUND_AMOUNT_RAW、FAUCET_CLAIM_NONCE 等"
	@echo "   - 固定口径见 configs/faucetv2-calibration-v1.json（EIP-712 name/version、singleAmount、relayer min balance）"
	$(call run_script,script/SmokeFaucetV2.s.sol:SmokeFaucetV2)

configure-faucetv2:
	@echo "🛠️  FaucetV2 configure：白名单/cap/passPriceWei ($(NETWORK))"
	@echo "   - 读取 deployments/$(NETWORK)/address_book.md 的 faucetv2.address"
	@echo "   - token 列表来自 configs/faucetv2-calibration-v1.json；token 地址来自 address_book.md"
	@echo "   - 如需写 cap：FAUCET_SET_CAPS=true 且设置 FAUCET_TOKEN_DAILY_CAP_RAW__vUSDC 等"
	$(call run_script,script/ConfigureFaucetV2.s.sol:ConfigureFaucetV2)

fund-faucetv2:
	@echo "💰 FaucetV2 fund：按 token 分别入金 ($(NETWORK))"
	@echo "   - 设置 FAUCET_FUND_RAW__vUSDC 等；未提供的 token 会跳过"
	$(call run_script,script/FundFaucetV2Inventory.s.sol:FundFaucetV2Inventory)

fund-faucetv2-100d:
	@echo "💰 FaucetV2 fund：按 tokenDailyCap * 100 天入金 ($(NETWORK))"
	@echo "   - 默认 100 天；可用 FAUCET_FUND_DAYS 覆盖"
	$(call run_script,script/FundFaucetV2Inventory100Days.s.sol:FundFaucetV2Inventory100Days)

mint-faucetv2-100d:
	@echo "🪙 Mint VTokens -> FaucetV2：按 100 天库存直接 mint 到 FaucetV2 合约 ($(NETWORK))"
	@echo "   - 需要 admin 拥有 token 的 DEFAULT_ADMIN_ROLE（脚本会临时 grant BRIDGE_ROLE 给 admin）"
	@echo "   - 可选：FAUCET_REVOKE_MINTER=true 执行完后 revoke BRIDGE_ROLE"
	$(call run_script,script/MintVTokensToFaucetV2_100Days.s.sol:MintVTokensToFaucetV2_100Days)

clean:
	@echo "🧹 清理构建文件..."
	forge clean
	rm -rf abi/*.json out-v2core/ out-v2router/

fmt:
	@echo "✨ 格式化..."
	forge fmt

# -------------------- 部署 (不验证) --------------------

setup-erc2470:
	@echo "🏭 确保 ERC-2470 工厂就绪..."
	$(call run_script,script/EnsureERC2470.s.sol)

deploy-logic:
	@echo "🧱 部署逻辑合约 ($(NETWORK))"
	$(call run_script,script/DeployLogic.s.sol)

deploy-v2:
	@echo "🏭 部署 UniswapV2 ($(NETWORK))"
	$(call run_script,script/DeployV2Deterministic.s.sol)

deploy-tokens:
	@echo "🪙 部署 VToken ($(NETWORK))"
	$(call run_script,script/DeployTokens.s.sol)

deploy-oracle:
	@echo "🔮 部署 Oracle ($(NETWORK))"
	$(call run_script,script/DeployOracleRouter.s.sol)

deploy-guard:
	@echo "🛡️  部署 Guard ($(NETWORK))"
	$(call run_script,script/DeployGuard.s.sol)

deploy-pairs:
	@echo "💧 创建交易对 ($(NETWORK))"
	$(call run_script,script/CreatePairsAndSeed.s.sol)

deploy-bridge:
	@echo "🌉 部署 Bridge ($(NETWORK))"
	$(call run_script,script/DeployBridge.s.sol)

deploy-burnmint:
	@echo "🔥 部署 BurnMint Pools ($(NETWORK))"
	$(call run_script,script/DeployBurnMintPools.s.sol)

deploy-all:
	@$(MAKE) NETWORK=$(NETWORK) setup-erc2470
	@$(MAKE) NETWORK=$(NETWORK) deploy-logic
	@$(MAKE) NETWORK=$(NETWORK) deploy-v2
	@$(MAKE) NETWORK=$(NETWORK) deploy-tokens
	@$(MAKE) NETWORK=$(NETWORK) deploy-oracle
	@$(MAKE) NETWORK=$(NETWORK) deploy-guard
	@$(MAKE) NETWORK=$(NETWORK) deploy-pairs
	@$(MAKE) NETWORK=$(NETWORK) deploy-bridge
	@$(MAKE) NETWORK=$(NETWORK) deploy-burnmint
	@echo "✅ $(NETWORK) 部署完成！"

# -------------------- 部署并验证 --------------------

deploy-logic-verify:
	@echo "🧱 部署并验证逻辑合约 ($(NETWORK))"
	$(call run_script_verify,script/DeployLogic.s.sol)

deploy-v2-verify:
	@echo "🏭 部署并验证 UniswapV2 ($(NETWORK))"
	$(call run_script_verify,script/DeployV2Deterministic.s.sol)

deploy-oracle-verify:
	@echo "🔮 部署并验证 Oracle ($(NETWORK))"
	$(call run_script_verify,script/DeployOracleRouter.s.sol)

deploy-guard-verify:
	@echo "🛡️  部署并验证 Guard ($(NETWORK))"
	$(call run_script_verify,script/DeployGuard.s.sol)

deploy-bridge-verify:
	@echo "🌉 部署并验证 Bridge ($(NETWORK))"
	$(call run_script_verify,script/DeployBridge.s.sol)

deploy-burnmint-verify:
	@echo "🔥 部署并验证 BurnMint Pools ($(NETWORK))"
	$(call run_script_verify,script/DeployBurnMintPools.s.sol)

deploy-all-verify:
	@$(MAKE) NETWORK=$(NETWORK) setup-erc2470
	@$(MAKE) NETWORK=$(NETWORK) deploy-logic-verify
	@$(MAKE) NETWORK=$(NETWORK) deploy-v2-verify
	@$(MAKE) NETWORK=$(NETWORK) deploy-tokens
	@$(MAKE) NETWORK=$(NETWORK) deploy-oracle-verify
	@$(MAKE) NETWORK=$(NETWORK) deploy-guard-verify
	@$(MAKE) NETWORK=$(NETWORK) deploy-pairs
	@$(MAKE) NETWORK=$(NETWORK) deploy-bridge-verify
	@$(MAKE) NETWORK=$(NETWORK) deploy-burnmint-verify
	@echo "✅ $(NETWORK) 部署并验证完成！"

# -------------------- 快捷命令 --------------------

deploy-sepolia:
	@$(MAKE) NETWORK=sepolia deploy-all

deploy-scroll:
	@$(MAKE) NETWORK=scroll deploy-all

deploy-verify-sepolia:
	@$(MAKE) NETWORK=sepolia deploy-all-verify

deploy-verify-scroll:
	@$(MAKE) NETWORK=scroll deploy-all-verify

# -------------------- 慢速部署 (避免 429 错误) --------------------

deploy-all-slow:
	@echo "🐌 慢速部署模式 (避免 RPC 速率限制)"
	@$(MAKE) NETWORK=$(NETWORK) setup-erc2470
	@sleep 8
	@$(MAKE) NETWORK=$(NETWORK) deploy-logic
	@sleep 8
	@$(MAKE) NETWORK=$(NETWORK) deploy-v2
	@sleep 8
	@$(MAKE) NETWORK=$(NETWORK) deploy-tokens
	@sleep 8
	@$(MAKE) NETWORK=$(NETWORK) deploy-oracle
	@sleep 8
	@$(MAKE) NETWORK=$(NETWORK) deploy-guard
	@sleep 8
	@$(MAKE) NETWORK=$(NETWORK) deploy-pairs
	@sleep 8
	@$(MAKE) NETWORK=$(NETWORK) deploy-bridge
	@sleep 8
	@$(MAKE) NETWORK=$(NETWORK) deploy-burnmint
	@echo "✅ $(NETWORK) 慢速部署完成！"

deploy-all-verify-slow:
	@echo "🐌 慢速部署并验证模式 (避免 RPC 速率限制)"
	@$(MAKE) NETWORK=$(NETWORK) setup-erc2470
	@sleep 8
	@$(MAKE) NETWORK=$(NETWORK) deploy-logic-verify
	@sleep 8
	@$(MAKE) NETWORK=$(NETWORK) deploy-v2-verify
	@sleep 8
	@$(MAKE) NETWORK=$(NETWORK) deploy-tokens
	@sleep 8
	@$(MAKE) NETWORK=$(NETWORK) deploy-oracle-verify
	@sleep 8
	@$(MAKE) NETWORK=$(NETWORK) deploy-guard-verify
	@sleep 8
	@$(MAKE) NETWORK=$(NETWORK) deploy-pairs
	@sleep 8
	@$(MAKE) NETWORK=$(NETWORK) deploy-bridge-verify
	@sleep 8
	@$(MAKE) NETWORK=$(NETWORK) deploy-burnmint-verify
	@echo "✅ $(NETWORK) 慢速部署并验证完成！"

deploy-slow-sepolia:
	@$(MAKE) NETWORK=sepolia deploy-all-slow

deploy-slow-scroll:
	@$(MAKE) NETWORK=scroll deploy-all-slow

deploy-verify-slow-sepolia:
	@$(MAKE) NETWORK=sepolia deploy-all-verify-slow

deploy-verify-slow-scroll:
	@$(MAKE) NETWORK=scroll deploy-all-verify-slow

# -------------------- 帮助 --------------------

help:
	@echo "📘 DripSwap 合约 Makefile"
	@echo ""
	@echo "常用命令："
	@echo "  make build               - 编译合约"
	@echo "  make test                - 运行测试"
	@echo "  make smoke-faucetv2 NETWORK=<net> - FaucetV2 smoke（跑通一次真实链 claim）"
	@echo "  make fmt                 - 格式化代码"
	@echo "  make clean               - 清理构建文件"
	@echo ""
	@echo "部署命令 (不验证)："
	@echo "  make deploy-all NETWORK=<net>  - 部署所有合约"
	@echo "  make deploy-sepolia            - 部署到 Sepolia"
	@echo "  make deploy-scroll             - 部署到 Scroll"
	@echo ""
	@echo "部署并验证 (推荐)："
	@echo "  make deploy-all-verify NETWORK=<net>  - 部署并验证所有合约"
	@echo "  make deploy-verify-sepolia            - 部署并验证到 Sepolia"
	@echo "  make deploy-verify-scroll             - 部署并验证到 Scroll"
	@echo ""
	@echo "慢速部署 (避免 429 错误)："
	@echo "  make deploy-all-slow NETWORK=<net>           - 慢速部署"
	@echo "  make deploy-all-verify-slow NETWORK=<net>    - 慢速部署并验证"
	@echo "  make deploy-verify-slow-sepolia              - 慢速部署并验证到 Sepolia"
	@echo "  make deploy-verify-slow-scroll               - 慢速部署并验证到 Scroll"
	@echo ""
	@echo "单独部署命令："
	@echo "  make deploy-logic    NETWORK=<net>  - 部署逻辑合约"
	@echo "  make deploy-v2       NETWORK=<net>  - 部署 UniswapV2"
	@echo "  make deploy-oracle   NETWORK=<net>  - 部署 Oracle"
	@echo "  make deploy-guard    NETWORK=<net>  - 部署 Guard"
	@echo "  make deploy-bridge   NETWORK=<net>  - 部署 Bridge"
	@echo "  make deploy-burnmint NETWORK=<net>  - 部署 BurnMint Pools"
	@echo ""
	@echo "说明："
	@echo "  - VToken 代理和 Pairs 使用标准模式，Etherscan 自动识别"
	@echo "  - 使用 deploy-all-verify 可在部署时自动验证合约"
	@echo "  - 遇到 RPC 429 错误时使用慢速部署模式"
	@echo "  - 需要设置 ETHERSCAN_API_KEY 环境变量"
	@echo ""
	@echo "当前网络: $(NETWORK)"
