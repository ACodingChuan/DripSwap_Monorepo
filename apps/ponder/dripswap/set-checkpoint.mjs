import { execSync } from "node:child_process";
import { createPublicClient, http } from "viem";

const args = process.argv.slice(2);
const getArg = (name) => {
  const idx = args.findIndex((arg) => arg === `--${name}`);
  if (idx === -1) return undefined;
  return args[idx + 1];
};

const chain = getArg("chain");
const blockArg = getArg("block");
const schema = getArg("schema") ?? "public";
const shouldApply = args.includes("--apply");

if (!chain || !blockArg) {
  console.error("Usage: node set-checkpoint.mjs --chain <sepolia|scroll> --block <number|latest> [--schema public] [--apply]");
  process.exit(1);
}

const chainConfig = {
  sepolia: {
    chainId: 11155111n,
    rpc:
      process.env.PONDER_SEPOLIA_RPC_URL_1 ??
      process.env.PONDER_SEPOLIA_RPC_URL_2 ??
      process.env.PONDER_SEPOLIA_RPC_URL_3 ??
      process.env.PONDER_SEPOLIA_RPC_URL,
  },
  scroll: {
    chainId: 534351n,
    rpc:
      process.env.PONDER_SCROLL_SEPOLIA_RPC_URL_1 ??
      process.env.PONDER_SCROLL_SEPOLIA_RPC_URL_2 ??
      process.env.PONDER_SCROLL_SEPOLIA_RPC_URL_3 ??
      process.env.PONDER_SCROLL_SEPOLIA_RPC_URL,
  },
};

const cfg = chainConfig[chain];
if (!cfg?.rpc) {
  console.error(`Missing RPC url for chain=${chain}. Check PONDER_*_RPC_URL envs.`);
  process.exit(1);
}

const client = createPublicClient({ transport: http(cfg.rpc) });

// Manual RPC (if you want to fetch block timestamp yourself):
// curl -s -X POST "$PONDER_SEPOLIA_RPC_URL_1" \
//   -H "Content-Type: application/json" \
//   --data '{"jsonrpc":"2.0","id":1,"method":"eth_getBlockByNumber","params":["0xAABBCC",false]}'
// curl -s -X POST "$PONDER_SCROLL_SEPOLIA_RPC_URL_1" \
//   -H "Content-Type: application/json" \
//   --data '{"jsonrpc":"2.0","id":1,"method":"eth_getBlockByNumber","params":["latest",false]}'

const block =
  blockArg === "latest"
    ? await client.getBlock()
    : await client.getBlock({ blockNumber: BigInt(blockArg) });

const blockNumber = block.number;
const blockTimestamp = block.timestamp;

const pad = (value, digits) => value.toString().padStart(digits, "0");
const checkpoint =
  pad(blockTimestamp, 10) +
  pad(cfg.chainId, 16) +
  pad(blockNumber, 16) +
  pad(0n, 16) +
  "5" +
  pad(0n, 16);

const sql = `UPDATE "${schema}"."_ponder_checkpoint"
SET latest_checkpoint='${checkpoint}',
    safe_checkpoint='${checkpoint}',
    finalized_checkpoint='${checkpoint}'
WHERE chain_id=${cfg.chainId.toString()};`;

console.info(`chain=${chain} chainId=${cfg.chainId.toString()}`);
console.info(`blockNumber=${blockNumber.toString()} blockTimestamp=${blockTimestamp.toString()}`);
console.info(`checkpoint=${checkpoint}`);
console.info(sql);

if (shouldApply) {
  const databaseUrl = process.env.DATABASE_URL;
  if (!databaseUrl) {
    console.error("Missing DATABASE_URL env; cannot apply SQL.");
    process.exit(1);
  }
  execSync(`psql "${databaseUrl}" -c "${sql.replace(/\n/g, " ")}"`, {
    stdio: "inherit",
  });
}
