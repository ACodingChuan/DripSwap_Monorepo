import { createConfig, factory, loadBalance, rateLimit } from "ponder";
import { http } from "viem";
import { parseAbiItem } from "viem";
import { FactoryAbi } from "./abis/FactoryAbi";
import { PairAbi } from "./abis/PairAbi";

const FACTORY_ADDRESS = "0x6c9258026a9272368e49bbb7d0a78c17bbe284bf";
const SEPOLIA_START_BLOCK = 9573280;
const SEPOLIA_END_BLOCK = 9573314;

const SCROLL_START_BLOCK = 14731854;
const SCROLL_END_BLOCK = 14731890;

// 平衡 Alchemy CU 限制与索引速度
// 推荐值：4 RPS/端点（避免 429 同时保证索引进度）
// RPS=2 太保守会导致索引卡住，RPS=8 容易触发 429
const RPS = Number(process.env.PONDER_RPC_RPS ?? 4);

const rl = (url: string) =>
  rateLimit(http(url), { 
    requestsPerSecond: RPS, 
    browser: false 
  });

const ensureUrls = (urls: Array<string | undefined>, label: string): string[] => {
  const filtered = urls.filter((url): url is string => Boolean(url));
  if (filtered.length === 0) {
    throw new Error(`Missing ${label} RPC/WS URLs`);
  }
  return filtered;
};

const testWebSocket = (url: string, timeoutMs = 1500): Promise<boolean> =>
  new Promise((resolve) => {
    const WebSocketImpl = (globalThis as { WebSocket?: any }).WebSocket;
    if (!WebSocketImpl) {
      resolve(true);
      return;
    }

    let settled = false;
    const socket = new WebSocketImpl(url);
    const finish = (ok: boolean) => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      try {
        socket.close();
      } catch {
        // ignore
      }
      resolve(ok);
    };

    const timer = setTimeout(() => finish(false), timeoutMs);
    if (typeof socket.addEventListener === "function") {
      socket.addEventListener("open", () => finish(true));
      socket.addEventListener("error", () => finish(false));
    } else {
      socket.onopen = () => finish(true);
      socket.onerror = () => finish(false);
    }
  });

const pickWsUrl = async (urls: Array<string | undefined>, label: string) => {
  const candidates = ensureUrls(urls, label);
  for (const url of candidates) {
    const ok = await testWebSocket(url);
    if (ok) {
      return url;
    }
  }
  return candidates[0];
};

const sepoliaWsUrl = await pickWsUrl(
  [
    process.env.PONDER_SEPOLIA_WS_URL_1,
    process.env.PONDER_SEPOLIA_WS_URL_2,
    process.env.PONDER_SEPOLIA_WS_URL_3,
    process.env.PONDER_SEPOLIA_WS_URL,
  ],
  "PONDER_SEPOLIA_WS"
);
const scrollSepoliaWsUrl = await pickWsUrl(
  [
    process.env.PONDER_SCROLL_SEPOLIA_WS_URL_1,
    process.env.PONDER_SCROLL_SEPOLIA_WS_URL_2,
    process.env.PONDER_SCROLL_SEPOLIA_WS_URL_3,
    process.env.PONDER_SCROLL_SEPOLIA_WS_URL,
  ],
  "PONDER_SCROLL_SEPOLIA_WS"
);

export default createConfig({
  chains: {
    sepolia: {
      id: 11155111,
      rpc: loadBalance(
        ensureUrls(
          [
            process.env.PONDER_SEPOLIA_RPC_URL_1,
            process.env.PONDER_SEPOLIA_RPC_URL_2,
            process.env.PONDER_SEPOLIA_RPC_URL_3,
            process.env.PONDER_SEPOLIA_RPC_URL,
          ],
          "PONDER_SEPOLIA"
        ).map(rl)
      ),
      // WS 只负责实时订阅新块，减少轮询带来的 RPC 使用
      ws: sepoliaWsUrl,
    },
    scroll: {
      id: 534351,
      rpc: loadBalance(
        ensureUrls(
          [
            process.env.PONDER_SCROLL_SEPOLIA_RPC_URL_1,
            process.env.PONDER_SCROLL_SEPOLIA_RPC_URL_2,
            process.env.PONDER_SCROLL_SEPOLIA_RPC_URL_3,
            process.env.PONDER_SCROLL_SEPOLIA_RPC_URL,
          ],
          "PONDER_SCROLL_SEPOLIA"
        ).map(rl)
      ),
      ws: scrollSepoliaWsUrl,
    },
  },

  contracts: {
    Factory: {
      chain: {
        sepolia: { address: FACTORY_ADDRESS, startBlock: SEPOLIA_START_BLOCK },
        scroll: { address: FACTORY_ADDRESS, startBlock: SCROLL_START_BLOCK},
      },
      abi: FactoryAbi,
    },
    Pair: {
      chain: {
        sepolia: { startBlock: SEPOLIA_START_BLOCK},
        scroll: { startBlock: SCROLL_START_BLOCK },
      },
      address: factory({
        address: FACTORY_ADDRESS,
        event: parseAbiItem(
          "event PairCreated(address indexed token0,address indexed token1,address pair,uint256)"
        ),
        parameter: "pair",
      }),
      abi: PairAbi,
    },
  },
});
