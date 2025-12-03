import { Link, createRoute } from '@tanstack/react-router';

import { rootRoute } from './root';
import { usePageFocus } from '@/shared/hooks';
import {
  Badge,
  Button,
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
  Input,
  Skeleton,
} from '@/shared/ui';
import { BarChart3, CalendarClock, LineChart, Search, TrendingUp } from '@/shared/icons';

import { useEffect, useState } from 'react';
import { fetchPoolsSummary } from '@/app/services/pools-service';
import type { PoolsSummary } from '@/domain/ports/pools-port';
import { toast } from '@/shared/ui';

const POOL_ROWS = [
  {
    name: 'WETH / USDC',
    tvl: '$58.07m',
    tvlChange: '-2.61%',
    volume: '$290.95k',
    volumeChange: '-21.58%',
    fees: '$2.77m',
    tx: '99',
    apr: '0.61%',
  },
  {
    name: 'DAI / ETH',
    tvl: '$19.98m',
    tvlChange: '+0.90%',
    volume: '$171.76k',
    volumeChange: '+1.53%',
    fees: '$1.75m',
    tx: '158',
    apr: '2.83%',
  },
  {
    name: 'WBTC / USDT',
    tvl: '$12.45m',
    tvlChange: '+0.12%',
    volume: '$215.40k',
    volumeChange: '-0.45%',
    fees: '$1.02m',
    tx: '78',
    apr: '1.24%',
  },
] as const;

// PoolsPage 是一个 React 组件（函数），代表 /pools 页面
const PoolsPage = () => {
  // usePageFocus 是你项目里的一个自定义 Hook：
  // 作用：页面打开后把焦点聚到 <h1> 上，方便键盘操作和无障碍阅读器。
  // 这里通过泛型 <HTMLHeadingElement> 告诉它，这个 ref 会挂在一个 <h1> 元素上。
  const headingRef = usePageFocus<HTMLHeadingElement>();

  // ------------------ 三个“状态”（state）开始 ------------------
  // 1) loading：是否正在加载数据。初始 true = “页面一进来先显示加载中”
  const [loading, setLoading] = useState(true);

  // 2) error：是否有错误消息。初始 null = “暂时没错误”
  const [error, setError] = useState<string | null>(null);

  // 3) summary：接口返回的业务数据。
  //    类型 PoolsSummary 是我们定义的“领域模型”，内容包含 tvlUsd、volume24hUsd、updatedAt。
  //    初始 null = “还没拿到数据”
  const [summary, setSummary] = useState<PoolsSummary | null>(null);
  // ------------------ 三个“状态”（state）结束 ------------------

  // useEffect：副作用钩子。这里的意思是：
  // “在组件渲染到页面（挂载）之后，做一件异步的事情（发请求取数据）”
  useEffect(() => {
    // 这个 cancelled 标志用于“防止内存泄漏”的小技巧：
    // 如果请求还没回来，用户就离开了这个页面（组件卸载），
    // 我们就不应该再去 setState（否则 React 会报警告）。
    let cancelled = false;

    // 立刻执行一个异步函数（IIFE：立即调用的异步函数表达式）
    // 这样我们可以在 effect 里使用 await 写法，更直观。
    (async () => {
      // 每次加载前，先设置 UI 状态：
      // - 显示骨架屏/Loading
      // - 清空旧错误
      setLoading(true);
      setError(null);

      try {
        // 真正的取数动作：通过“Ports + Adapters”层去拿数据
        // - fetchPoolsSummary() 是“应用服务函数”，内部会调用端口（PoolsPort）
        // - 端口的具体实现是 HTTP 适配器（底层用 fetch 发 GET /api/pools/summary）
        const s = await fetchPoolsSummary();

        // 如果组件还在（没有被卸载），才安全地更新数据状态
        if (!cancelled) setSummary(s);
      } catch (e: any) {
        // 捕获异常（网络错误、后端返回非 2xx 被 http() 封装抛出等）
        // 如果组件还在，设置错误消息（用于在 UI 上显示“加载失败”的提示）
        if (!cancelled) setError(e?.message ?? '加载失败，请稍后重试');
      } finally {
        // 无论成功/失败，最终都要把 loading 关掉
        if (!cancelled) setLoading(false);
      }
    })();

    // 这个 return 的函数叫“清理函数”（cleanup）：
    // 当组件卸载时，React 会调用它。
    // 我们在里面把 cancelled 置为 true，表示“别再 setState 了”。
    return () => {
      cancelled = true;
    };

    // 依赖数组 []：空数组表示这个 effect 只在“首次挂载”时执行一次，
    // 不会因为后续的 state 变化而重新跑（避免无限循环请求）。
  }, []);
  // ↑ 到这里为止，就是“进入页面 → 触发请求 → 把结果放进 state → 根据 state 渲染 UI”的完整流程

  return (
    <main className="mx-auto flex min-h-screen w-full max-w-[1200px] flex-col gap-[var(--space-xl)] px-6 py-[var(--space-2xl)]">
      <header className="flex flex-col gap-[var(--space-sm)]">
        {/* ...保留你的 Badge/标题/说明不变 ... */}
      </header>

      <section className="grid gap-[var(--space-md)] lg:grid-cols-[2fr,1fr]">
        <Card className="h-full">
          <CardHeader className="flex flex-row items-start justify-between">
            <div className="flex flex-col gap-[var(--space-xs)]">
              <CardTitle className="text-lg">Ethereum TVL</CardTitle>
              <CardDescription className="flex items-center gap-[var(--space-xs)] text-sm text-muted-foreground">
                <TrendingUp className="size-4 text-primary" aria-hidden="true" />
                {loading
                  ? 'Loading…' // 加载中：文案 “Loading…”
                  : error
                    ? 'Unavailable' // 失败：文案 “Unavailable”
                    : `$${summary!.tvlUsd.toLocaleString(undefined, { maximumFractionDigits: 2 })} total`}
              </CardDescription>
            </div>
            <Badge variant="outline" className="gap-[var(--space-xs)]">
              <CalendarClock className="size-3" aria-hidden="true" />{' '}
              {loading
                ? '…' // 加载中：省略号
                : error
                  ? '—' // 失败：横杠
                  : new Date(summary!.updatedAt).toLocaleDateString()}
            </Badge>
          </CardHeader>
          <CardContent className="flex flex-col gap-[var(--space-lg)]">
            <Skeleton className="h-52 w-full rounded-[var(--radius-card)]" />
            <div className="flex items-center gap-[var(--space-md)]">
              <Button variant="secondary" size="sm" disabled>
                Tokens
              </Button>
              <Button variant="primary" size="sm" className="shadow-sm" disabled>
                Pools
              </Button>
            </div>
          </CardContent>
        </Card>

        <div className="flex flex-col gap-[var(--space-md)]">
          <Card>
            <CardHeader className="flex flex-col gap-[var(--space-sm)]">
              <CardTitle className="flex items-center gap-[var(--space-sm)] text-lg">
                <BarChart3 className="size-5 text-primary" aria-hidden="true" />
                Ethereum volume
              </CardTitle>
              <CardDescription>Past month activity</CardDescription>
            </CardHeader>
            <CardContent>
              <Skeleton className="h-40 w-full" />
            </CardContent>
          </Card>

          <Card className="bg-surface-elevated/60">
            <CardHeader>
              <CardTitle className="text-lg">Network status</CardTitle>
              <CardDescription>All chains are operating nominally.</CardDescription>
            </CardHeader>
            <CardContent className="flex flex-col gap-[var(--space-sm)] text-sm text-muted-foreground">
              <div className="flex items-center gap-[var(--space-sm)]">
                <LineChart className="size-4 text-success" aria-hidden="true" />
                <span>
                  TVL change (24h): <strong className="text-success">+1.5%</strong>
                </span>
              </div>
              <div className="flex items-center gap-[var(--space-sm)]">
                <TrendingUp className="size-4 text-warning" aria-hidden="true" />
                <span>
                  Volume change (24h): <strong className="text-warning">-0.8%</strong>
                </span>
              </div>
            </CardContent>
          </Card>
        </div>
      </section>

      <section className="flex flex-col gap-[var(--space-sm)]">
        <div className="flex flex-col gap-[var(--space-sm)] sm:flex-row sm:items-center sm:justify-between">
          <h2 className="text-xl font-semibold text-foreground">Pools</h2>
          <div className="flex flex-wrap items-center gap-[var(--space-sm)]">
            <div className="relative w-full sm:w-auto">
              <Input
                placeholder="Search pools"
                className="w-full pl-10"
                aria-label="Search pools"
              />
              <Search
                className="absolute left-3 top-1/2 size-4 -translate-y-1/2 text-muted-foreground"
                aria-hidden="true"
              />
            </div>
            <Badge variant="outline">Pools: 1468</Badge>
          </div>
        </div>

        <Card className="overflow-hidden">
          <div className="grid grid-cols-[2fr_repeat(5,minmax(0,1fr))_auto] border-b border-border/70 bg-muted/60 px-[var(--space-md)] py-[var(--space-sm)] text-xs font-medium uppercase tracking-wide text-muted-foreground">
            <span>Name</span>
            <span className="text-right">TVL</span>
            <span className="text-right">Volume (24h)</span>
            <span className="text-right">Fees (24h)</span>
            <span className="text-right">Transactions</span>
            <span className="text-right">APR</span>
            <span className="sr-only">Actions</span>
          </div>
          <div className="divide-y divide-border/60">
            {POOL_ROWS.map((row) => (
              <div
                key={row.name}
                className="grid grid-cols-[2fr_repeat(5,minmax(0,1fr))_auto] items-center gap-[var(--space-sm)] px-[var(--space-md)] py-[var(--space-sm)] text-sm text-foreground"
              >
                <div className="flex flex-col">
                  <span className="font-medium">{row.name}</span>
                  <span className="text-xs text-muted-foreground">TVL change {row.tvlChange}</span>
                </div>
                <span className="text-right">{row.tvl}</span>
                <span className="text-right">{row.volume}</span>
                <span className="text-right">{row.fees}</span>
                <span className="text-right">{row.tx}</span>
                <span className="text-right">{row.apr}</span>
                <div className="flex items-center justify-end gap-[var(--space-xs)]">
                  <Button variant="ghost" size="sm" asChild>
                    <Link to="/pools/add">Add</Link>
                  </Button>
                  <Button variant="outline" size="sm" asChild>
                    <Link to="/pools/remove">Claim Reward</Link>
                  </Button>
                </div>
              </div>
            ))}
          </div>
        </Card>
      </section>
    </main>
  );
};

export const poolsRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/pools',
  component: PoolsPage,
});
