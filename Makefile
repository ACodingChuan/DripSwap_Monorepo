.PHONY: install frontend-dev frontend-build frontend-test frontend-lint frontend-typecheck contracts-test contracts-deploy contracts-fmt bff-dev bff-test subgraph-build subgraph-codegen subgraph-deploy

install:
	@pnpm install

frontend-dev:
	@pnpm --dir apps/frontend dev

frontend-build:
	@pnpm --dir apps/frontend build

frontend-test:
	@pnpm --dir apps/frontend test

frontend-lint:
	@pnpm --dir apps/frontend lint

frontend-typecheck:
	@pnpm --dir apps/frontend typecheck

contracts-test:
	@cd apps/contracts && forge test

contracts-deploy:
	@cd apps/contracts && forge script

contracts-fmt:
	@cd apps/contracts && forge fmt

bff-dev:
	@cd apps/bff && mvn spring-boot:run

bff-test:
	@cd apps/bff && mvn test

subgraph-build:
	@pnpm --dir apps/subgraph/sepolia run build

subgraph-codegen:
	@pnpm --dir apps/subgraph/sepolia run codegen

subgraph-deploy:
	@pnpm --dir apps/subgraph/sepolia run deploy

package-all:
	@pnpm --dir apps/frontend build
	@pnpm --dir apps/contracts run build
	@pnpm --dir apps/subgraph/sepolia run build
	@cd apps/bff && mvn -DskipTests package

test-all:
	@pnpm --dir apps/frontend test
	@pnpm --dir apps/contracts test
	@pnpm --dir apps/subgraph/sepolia run test
	@cd apps/bff && mvn test

lint-all:
	@pnpm --dir apps/frontend lint

format-all:
	@pnpm --dir apps/frontend format
	@pnpm --dir apps/contracts fmt

help:
	@echo "DripSwap Monorepo Makefile"
	@echo "frontend-dev, frontend-build, frontend-test, frontend-lint, frontend-typecheck"
	@echo "contracts-test, contracts-deploy, contracts-fmt"
	@echo "bff-dev, bff-test"
	@echo "subgraph-build, subgraph-codegen, subgraph-deploy"
	@echo "package-all, test-all, lint-all, format-all"