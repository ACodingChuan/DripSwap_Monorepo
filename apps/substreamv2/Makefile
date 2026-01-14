RUSTUP_TOOLCHAIN ?= stable
CARGO := $(HOME)/.cargo/bin/cargo
RUSTC := $(HOME)/.cargo/bin/rustc
RUST_ENV := RUSTUP_TOOLCHAIN=$(RUSTUP_TOOLCHAIN) RUSTC=$(RUSTC) PATH=$(HOME)/.cargo/bin:$$PATH
SUBSTREAMS ?= substreams
SUBSTREAMS_ENV := PATH=/opt/homebrew/bin:$$PATH

.PHONY: build
build:
	$(RUST_ENV) $(CARGO) build --target wasm32-unknown-unknown --release

.PHONY: pack
pack:
	$(SUBSTREAMS_ENV) $(SUBSTREAMS) pack ./substreams.yaml

.PHONY: run-sepolia
run-sepolia:
	substreams run -e https://sepolia.substreams.pinax.network:443 substreams.sepolia.yaml map_uniswap_events -s 9573280

.PHONY: run-scroll
run-scroll:
	substreams run -e https://scrsepolia.substreams.pinax.network:443 substreams.scroll-sepolia.yaml map_uniswap_events -s 14731854
