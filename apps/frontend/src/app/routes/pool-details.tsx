import { useQuery } from '@tanstack/react-query';
import { Link, createRoute } from '@tanstack/react-router';
import { formatDistanceToNowStrict } from 'date-fns';
import type { EChartsOption } from 'echarts';
import { BarChart, LineChart } from 'echarts/charts';
import { DataZoomComponent, GridComponent, TooltipComponent } from 'echarts/components';
import * as echarts from 'echarts/core';
import ReactEchartsCore from 'echarts-for-react/lib/core';
import { CanvasRenderer } from 'echarts/renderers';
import { useEffect, useMemo, useRef, useState } from 'react';

import { rootRoute } from './root';
import { fetchPoolDetails, fetchPoolPriceCandles, fetchPoolTransactions } from '@/app/services/pool-details-service';
import { useUiStore } from '@/app/store/ui-store';
import type { PoolChartInterval, PoolTransactionType } from '@/domain/ports/pool-port';
import { Badge, Button, Card, CardContent, CardHeader, CardTitle, Skeleton } from '@/shared/ui';
import { ArrowLeft } from '@/shared/icons';
import { cn, getExplorerAddressUrl, getExplorerTxUrl, shortenHex } from '@/shared/utils';

echarts.use([CanvasRenderer, BarChart, LineChart, TooltipComponent, GridComponent, DataZoomComponent]);

function formatLargeUsd(value: number | null | undefined) {
  if (value === null || value === undefined) return '—';
  if (!Number.isFinite(value)) return '—';
  if (value >= 1_000_000_000) return `$${(value / 1_000_000_000).toFixed(2)}B`;
  if (value >= 1_000_000) return `$${(value / 1_000_000).toFixed(2)}M`;
  if (value >= 1_000) return `$${(value / 1_000).toFixed(2)}K`;
  return `$${value.toFixed(2)}`;
}

function formatNumber(value: number | null | undefined, maxFractionDigits = 4) {
  if (value === null || value === undefined) return '—';
  if (!Number.isFinite(value)) return '—';
  return value.toLocaleString(undefined, { maximumFractionDigits: maxFractionDigits });
}

function formatChainLabel(chain: string) {
  const chainId = Number(chain);
  if (chainId === 11155111) return 'Sepolia';
  if (chainId === 534351) return 'Scroll Sepolia';
  return chain;
}

type ChartMetricKey = 'VOLUME' | 'TVL' | 'FEES';
type ChartRangeKey = '1D' | '1W' | '1M' | '1Y' | 'ALL';

const CHART_RANGES: Array<{
  key: ChartRangeKey;
  label: string;
  seconds: number;
  baseInterval: PoolChartInterval;
  bucketSeconds: number;
}> = [
  { key: '1D', label: '1D', seconds: 24 * 60 * 60, baseInterval: 'HOUR', bucketSeconds: 1 * 60 * 60 },
  { key: '1W', label: '1W', seconds: 7 * 24 * 60 * 60, baseInterval: 'HOUR', bucketSeconds: 2 * 60 * 60 },
  { key: '1M', label: '1M', seconds: 30 * 24 * 60 * 60, baseInterval: 'HOUR', bucketSeconds: 8 * 60 * 60 },
  { key: '1Y', label: '1Y', seconds: 365 * 24 * 60 * 60, baseInterval: 'DAY', bucketSeconds: 2 * 24 * 60 * 60 },
  // Backend caches a ~400d window for DAY; "ALL" maps to that window.
  { key: 'ALL', label: 'All', seconds: 400 * 24 * 60 * 60, baseInterval: 'DAY', bucketSeconds: 7 * 24 * 60 * 60 },
];

const PoolDetailsPage = () => {
  const { chain, poolAddress } = poolDetailsRoute.useParams();
  const pair = poolAddress.trim().toLowerCase();
  const chainId = Number(chain);
  const resolvedTheme = useUiStore((s) => s.resolvedTheme);
  const chainLabel = formatChainLabel(chain);

  const [metricKey, setMetricKey] = useState<ChartMetricKey>('VOLUME');
  const [rangeKey, setRangeKey] = useState<ChartRangeKey>('1M');
  const [txType, setTxType] = useState<PoolTransactionType>('SWAP');
  const chartContainerRef = useRef<HTMLDivElement | null>(null);

  const { data: poolDetails, isLoading: isPoolLoading } = useQuery({
    queryKey: ['pool', 'details', chain, pair],
    queryFn: () => fetchPoolDetails({ chainId: chain, pairAddress: pair }),
  });

  const selectedRange = useMemo(
    () => CHART_RANGES.find((r) => r.key === rangeKey) ?? CHART_RANGES[2],
    [rangeKey]
  );

  const nowSec = Math.floor(Date.now() / 1000);
  const fromSec = nowSec - selectedRange.seconds;

  useEffect(() => {
    const container = chartContainerRef.current;
    if (!container) return;
    const onWheel = (event: WheelEvent) => {
      if (event.ctrlKey) event.preventDefault();
    };
    container.addEventListener('wheel', onWheel, { passive: false });
    return () => container.removeEventListener('wheel', onWheel);
  }, []);

  const { data: candles, isLoading: isCandlesLoading, isError: isCandlesError } = useQuery({
    queryKey: ['pool', 'candles', chain, pair, selectedRange.key],
    queryFn: () =>
      fetchPoolPriceCandles({
        chainId: chain,
        pairAddress: pair,
        interval: selectedRange.baseInterval,
        from: fromSec,
        to: nowSec,
      }),
  });

  const displayedCandles = useMemo(() => {
    return bucketPoolSeries(candles ?? [], selectedRange.bucketSeconds);
  }, [candles, selectedRange.bucketSeconds]);

  const latestPoint = useMemo(() => {
    if (!displayedCandles.length) return null;
    return displayedCandles[displayedCandles.length - 1];
  }, [displayedCandles]);

  const chartOption = useMemo<EChartsOption>(() => {
    const axisLabelColor = resolvedTheme === 'dark' ? 'white' : 'black';
    const splitLineColor = resolvedTheme === 'dark' ? 'rgba(255,255,255,0.14)' : 'rgba(0,0,0,0.12)';

    const points = displayedCandles
      .map((c) => ({
        ts: c.timestamp * 1000,
        tvl: c.tvlUsd,
        volume: c.volumeUsd,
        fees: c.feesUsd,
      }))
      .filter((p) => Number.isFinite(p.ts));

    const metricLabel = metricKey === 'VOLUME' ? 'Volume' : metricKey === 'TVL' ? 'TVL' : 'Fees';
    const getValue = (p: (typeof points)[number]) => (metricKey === 'VOLUME' ? p.volume : metricKey === 'TVL' ? p.tvl : p.fees);

    const series: EChartsOption['series'] =
      metricKey === 'VOLUME'
        ? [
            {
              name: metricLabel,
              type: 'bar' as const,
              data: points.map((p) => [p.ts, getValue(p)]),
              encode: { x: 0, y: 1 },
              itemStyle: { color: 'rgba(59, 126, 246, 0.75)' },
              barMaxWidth: 12,
            },
          ]
        : [
            {
              name: metricLabel,
              type: 'line' as const,
              data: points.map((p) => [p.ts, getValue(p)]),
              encode: { x: 0, y: 1 },
              showSymbol: false,
              smooth: true,
              lineStyle: { color: '#3B7EF6', width: 2, opacity: 0.95 },
              areaStyle: { color: 'rgba(59, 126, 246, 0.18)' },
            },
          ];

    return {
      tooltip: {
        trigger: 'axis',
        axisPointer: { type: 'cross' },
        formatter: (params: unknown) => {
          const items = Array.isArray(params) ? (params as any[]) : [params as any];
          const item = items[0];
          const value = (item?.value ?? item?.data) as unknown;
          const axisValue = item?.axisValue as unknown;
          const data = Array.isArray(value) ? value : [];
          const ts =
            typeof axisValue === 'number'
              ? axisValue
              : typeof data[0] === 'number'
                ? (data[0] as number)
                : Number.NaN;
          if (!Number.isFinite(ts)) return '';
          const v = data[1] as number;
          return [
            `<div style="min-width: 180px">`,
            `<div style="margin-bottom: 6px; font-weight: 600">${formatDistanceToNowStrict(new Date(ts), { addSuffix: true })}</div>`,
            `<div>${metricLabel}: ${formatLargeUsd(v)}</div>`,
            `</div>`,
          ].join('');
        },
      },
      dataZoom: [
        {
          type: 'inside',
          xAxisIndex: 0,
          zoomOnMouseWheel: 'ctrl',
          moveOnMouseMove: true,
          moveOnMouseWheel: true,
          preventDefaultMouseMove: true,
          filterMode: 'none',
        },
      ],
      grid: { left: 10, right: 10, top: 10, bottom: 30, containLabel: true },
      xAxis: {
        type: 'time',
        axisLine: { show: false },
        axisTick: { show: false },
        splitLine: { show: false },
        axisLabel: { color: axisLabelColor },
      },
      yAxis: {
        scale: true,
        position: 'right',
        axisLabel: { color: axisLabelColor },
        splitLine: { show: true, lineStyle: { type: 'dashed', color: splitLineColor } },
      },
      series,
    };
  }, [displayedCandles, metricKey, resolvedTheme]);

  const { data: transactions, isLoading: isTxLoading } = useQuery({
    queryKey: ['pool', 'transactions', chain, pair, txType],
    queryFn: () =>
      fetchPoolTransactions({
        chainId: chain,
        pairAddress: pair,
        limit: 50,
        types: [txType],
      }),
    refetchInterval: 60_000,
  });

  const token0UsdValue = useMemo(() => {
    if (!poolDetails) return null;
    const { tvlUsd, reserve0, reserve1, token0Price, token1Price } = poolDetails;
    if (!reserve0 || !reserve1 || !tvlUsd) return null;

    if (token0Price && token0Price > 0) {
      const denom = reserve0 * token0Price + reserve1;
      if (denom > 0) {
        const p1Usd = tvlUsd / denom;
        const p0Usd = token0Price * p1Usd;
        return reserve0 * p0Usd;
      }
    }

    if (token1Price && token1Price > 0) {
      const denom = reserve0 + reserve1 * token1Price;
      if (denom > 0) {
        const p0Usd = tvlUsd / denom;
        return reserve0 * p0Usd;
      }
    }
    return null;
  }, [poolDetails]);

  const token1UsdValue = useMemo(() => {
    if (!poolDetails) return null;
    const { tvlUsd, reserve0, reserve1, token0Price, token1Price } = poolDetails;
    if (!reserve0 || !reserve1 || !tvlUsd) return null;

    if (token0Price && token0Price > 0) {
      const denom = reserve0 * token0Price + reserve1;
      if (denom > 0) {
        const p1Usd = tvlUsd / denom;
        return reserve1 * p1Usd;
      }
    }

    if (token1Price && token1Price > 0) {
      const denom = reserve0 + reserve1 * token1Price;
      if (denom > 0) {
        const p0Usd = tvlUsd / denom;
        const p1Usd = token1Price * p0Usd;
        return reserve1 * p1Usd;
      }
    }

    return null;
  }, [poolDetails]);

  const showChartSkeleton = isCandlesLoading || isPoolLoading;
  const showChartEmpty = !showChartSkeleton && ((displayedCandles ?? []).length === 0 || isCandlesError);

  return (
    <main className="mx-auto flex min-h-screen w-full max-w-[1200px] flex-col gap-[var(--space-xl)] px-6 py-[var(--space-2xl)]">
      <header className="flex flex-col gap-[var(--space-sm)]">
        <div className="flex items-center gap-[var(--space-sm)] text-sm text-muted-foreground">
          <ArrowLeft className="size-4" aria-hidden="true" />
          <Link to="/explore/pools" className="hover:text-foreground focus-visible:text-foreground">
            Back to pools
          </Link>
        </div>

        <div className="flex flex-wrap items-start justify-between gap-[var(--space-md)]">
          <div className="flex flex-col gap-[var(--space-xs)]">
            {isPoolLoading ? (
              <Skeleton className="h-10 w-64" />
            ) : poolDetails ? (
              <h1 className="text-3xl font-semibold tracking-tight text-foreground sm:text-4xl">
                {poolDetails.token0.symbol}/{poolDetails.token1.symbol} <span className="text-muted-foreground">v2</span>
              </h1>
            ) : (
              <h1 className="text-3xl font-semibold tracking-tight text-foreground sm:text-4xl">
                Pool details
              </h1>
            )}
            <p className="text-sm text-muted-foreground">
              {chainLabel} · {shortenHex(poolAddress)}
            </p>
          </div>
          <div className="flex items-center gap-3">
            <Button variant="outline" size="sm" asChild>
              <Link to="/pools/add" search={{ chainId: chain, pair: pair }}>
                Add Liquidity
              </Link>
            </Button>
            <Button variant="outline" size="sm" asChild>
              <Link to="/pools/remove" search={{ chainId: chain, pair: pair }}>
                Remove Liquidity
              </Link>
            </Button>
            <Badge variant="outline">MVP</Badge>
          </div>
        </div>
      </header>

      <section className="grid gap-[var(--space-md)] lg:grid-cols-[2fr,1fr]">
        <Card>
          <CardHeader className="flex flex-col gap-[var(--space-sm)]">
            <div className="flex flex-wrap items-center justify-between gap-3">
              <div className="flex flex-col">
                <CardTitle className="text-lg">
                  {metricKey === 'VOLUME' ? 'Volume' : metricKey === 'TVL' ? 'TVL' : 'Fees'}
                </CardTitle>
                <div className="mt-1 text-sm text-muted-foreground">
                  {latestPoint ? (
                    metricKey === 'VOLUME' ? (
                      <>
                        {formatLargeUsd(latestPoint.volumeUsd)} <span className="text-muted-foreground">•</span>{' '}
                        {formatLargeUsd(latestPoint.feesUsd)} earned
                      </>
                    ) : metricKey === 'TVL' ? (
                      <>{formatLargeUsd(latestPoint.tvlUsd)}</>
                    ) : (
                      <>{formatLargeUsd(latestPoint.feesUsd)}</>
                    )
                  ) : (
                    '—'
                  )}
                </div>
              </div>

              <div className="flex flex-wrap items-center gap-3">
                <div className="inline-flex items-center rounded-full border border-border bg-background p-1">
                  {[
                    { key: 'VOLUME' as const, label: 'Volume' },
                    { key: 'TVL' as const, label: 'TVL' },
                    { key: 'FEES' as const, label: 'Fees' },
                  ].map((metric) => (
                    <Button
                      key={metric.key}
                      type="button"
                      variant={metric.key === metricKey ? 'secondary' : 'ghost'}
                      size="sm"
                      className="h-8 rounded-full px-4"
                      onClick={() => setMetricKey(metric.key)}
                    >
                      {metric.label}
                    </Button>
                  ))}
                </div>

                <div className="inline-flex items-center rounded-full border border-border bg-background p-1">
                  {CHART_RANGES.map((range) => (
                    <Button
                      key={range.key}
                      type="button"
                      variant={range.key === rangeKey ? 'secondary' : 'ghost'}
                      size="sm"
                      className="h-8 rounded-full px-4"
                      onClick={() => setRangeKey(range.key)}
                    >
                      {range.label}
                    </Button>
                  ))}
                </div>
              </div>
            </div>
          </CardHeader>
          <CardContent>
            {showChartSkeleton ? (
              <Skeleton className="h-[360px] w-full rounded-[var(--radius-card)]" />
            ) : showChartEmpty ? (
              <div className="flex h-[360px] items-center justify-center rounded-[var(--radius-card)] border border-border/60 text-sm text-muted-foreground">
                Not enough data yet.
              </div>
            ) : (
              <div ref={chartContainerRef}>
                <ReactEchartsCore echarts={echarts} option={chartOption} style={{ height: 360 }} />
              </div>
            )}
          </CardContent>
        </Card>
        <div className="flex flex-col gap-[var(--space-md)]">
          <Card>
            <CardHeader>
              <CardTitle className="text-lg">Pool Liquidity</CardTitle>
              <div className="text-sm text-muted-foreground">
                {isPoolLoading ? <Skeleton className="h-4 w-24" /> : poolDetails ? formatLargeUsd(poolDetails.tvlUsd) : '—'}
              </div>
            </CardHeader>
            <CardContent className="grid gap-3 text-sm">
              <div className="text-xs font-medium uppercase text-muted-foreground">Tokens</div>
              <div className="flex items-center justify-between gap-3">
                <div className="min-w-0 truncate text-muted-foreground">{poolDetails?.token0.symbol ?? '—'}</div>
                <div className="text-right">
                  <div className="font-medium text-foreground">{formatNumber(poolDetails?.reserve0 ?? null, 6)}</div>
                  <div className="text-xs text-muted-foreground">{formatLargeUsd(token0UsdValue)}</div>
                </div>
              </div>
              <div className="flex items-center justify-between gap-3">
                <div className="min-w-0 truncate text-muted-foreground">{poolDetails?.token1.symbol ?? '—'}</div>
                <div className="text-right">
                  <div className="font-medium text-foreground">{formatNumber(poolDetails?.reserve1 ?? null, 6)}</div>
                  <div className="text-xs text-muted-foreground">{formatLargeUsd(token1UsdValue)}</div>
                </div>
              </div>
            </CardContent>
          </Card>

          <Card>
            <CardHeader>
              <CardTitle className="text-lg">Statistics</CardTitle>
            </CardHeader>
            <CardContent className="grid gap-3">
              {[
                { label: 'Liquidity', value: poolDetails ? formatLargeUsd(poolDetails.tvlUsd) : '—' },
                { label: 'Volume (24h)', value: poolDetails ? formatLargeUsd(poolDetails.volume24hUsd) : '—' },
                { label: 'Fees (24h)', value: poolDetails ? formatLargeUsd(poolDetails.fees24hUsd) : '—' },
                { label: 'Transactions (24h)', value: poolDetails ? String(poolDetails.tx24hCount ?? 0) : '—' },
              ].map((item) => (
                <div key={item.label}>
                  <div className="text-xs font-medium uppercase text-muted-foreground">{item.label}</div>
                  <div className={cn('mt-1 text-xl font-semibold text-foreground')}>
                    {isPoolLoading ? <Skeleton className="h-6 w-24" /> : item.value}
                  </div>
                </div>
              ))}
            </CardContent>
          </Card>
        </div>
      </section>

      <Card>
        <CardHeader>
          <CardTitle className="text-lg">
            <div className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
              <span>Transactions</span>
              <div className="inline-flex items-center rounded-full border border-border bg-background p-1">
                {[
                  { key: 'SWAP' as const, label: 'Swaps' },
                  { key: 'MINT' as const, label: 'Add' },
                  { key: 'BURN' as const, label: 'Remove' },
                ].map((item) => (
                  <Button
                    key={item.key}
                    type="button"
                    variant={item.key === txType ? 'secondary' : 'ghost'}
                    size="sm"
                    className="h-8 rounded-full px-4"
                    onClick={() => setTxType(item.key)}
                  >
                    {item.label}
                  </Button>
                ))}
              </div>
            </div>
          </CardTitle>
        </CardHeader>
        <CardContent className="overflow-x-auto">
          {isTxLoading ? (
            <Skeleton className="h-64 w-full rounded-[var(--radius-card)]" />
          ) : (transactions ?? []).length === 0 ? (
            <div className="py-10 text-center text-sm text-muted-foreground">No transactions found.</div>
          ) : (
            <table className="w-full min-w-[740px] text-sm">
              <thead className="text-xs uppercase text-muted-foreground">
                <tr className="border-b border-border/60">
                  <th className="px-2 py-3 text-left font-medium">Maker</th>
                  <th className="px-2 py-3 text-left font-medium">Amount in</th>
                  <th className="px-2 py-3 text-left font-medium">Amount out</th>
                  <th className="px-2 py-3 text-right font-medium">Amount (USD)</th>
                  <th className="px-2 py-3 text-right font-medium">Time</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-border/60">
                {(transactions ?? []).map((tx) => {
                  const sym0 = poolDetails?.token0.symbol ?? 'Token0';
                  const sym1 = poolDetails?.token1.symbol ?? 'Token1';
                  const a0 = tx.token0Amount ?? 0;
                  const a1 = tx.token1Amount ?? 0;
                  const maker = shortenHex(tx.account ?? null);
                  const makerUrl = tx.account ? getExplorerAddressUrl(chainId, tx.account) : null;
                  const txUrl = getExplorerTxUrl(chainId, tx.txHash);

                  const fmt = (v: number, sym: string) => `${formatNumber(Math.abs(v), 6)} ${sym}`;

                  let amountIn = '—';
                  let amountOut = '—';
                  if (tx.type === 'SWAP') {
                    if (a0 > 0 && a1 < 0) {
                      amountIn = fmt(a0, sym0);
                      amountOut = fmt(a1, sym1);
                    } else if (a1 > 0 && a0 < 0) {
                      amountIn = fmt(a1, sym1);
                      amountOut = fmt(a0, sym0);
                    } else {
                      // Fallback: show both signed legs.
                      amountIn = `${formatNumber(a0, 6)} ${sym0}`;
                      amountOut = `${formatNumber(a1, 6)} ${sym1}`;
                    }
                  } else if (tx.type === 'MINT') {
                    amountIn = `${fmt(a0, sym0)} + ${fmt(a1, sym1)}`;
                    amountOut = '—';
                  } else if (tx.type === 'BURN') {
                    amountIn = '—';
                    amountOut = `${fmt(a0, sym0)} + ${fmt(a1, sym1)}`;
                  }

                  return (
                    <tr key={`${tx.txHash}-${tx.timestamp}`}>
                      <td className="px-2 py-3 text-left text-foreground">
                        {makerUrl ? (
                          <a
                            href={makerUrl}
                            target="_blank"
                            rel="noreferrer"
                            className="font-mono hover:underline"
                          >
                            {maker}
                          </a>
                        ) : (
                          <span className="font-mono">{maker}</span>
                        )}
                      </td>
                      <td className="px-2 py-3 text-left text-foreground">{amountIn}</td>
                      <td className="px-2 py-3 text-left text-foreground">{amountOut}</td>
                      <td className="px-2 py-3 text-right text-foreground">{formatLargeUsd(tx.amountUsd ?? null)}</td>
                      <td className="px-2 py-3 text-right text-muted-foreground">
                        {tx.timestamp ? (
                          txUrl ? (
                            <a
                              href={txUrl}
                              target="_blank"
                              rel="noreferrer"
                              className="hover:underline"
                            >
                              {formatDistanceToNowStrict(new Date(tx.timestamp * 1000), { addSuffix: true })}
                            </a>
                          ) : (
                            formatDistanceToNowStrict(new Date(tx.timestamp * 1000), { addSuffix: true })
                          )
                        ) : (
                          '—'
                        )}
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          )}
        </CardContent>
      </Card>
    </main>
  );
};

export const poolDetailsRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/explore/pools/$chain/$poolAddress',
  component: PoolDetailsPage,
});

type PoolPoint = {
  timestamp: number;
  tvlUsd: number;
  volumeUsd: number;
  feesUsd: number;
};

function bucketPoolSeries(series: PoolPoint[], bucketSeconds: number): PoolPoint[] {
  if (series.length === 0) return series;
  if (!Number.isFinite(bucketSeconds) || bucketSeconds <= 0) return series;

  const sorted = [...series].sort((a, b) => a.timestamp - b.timestamp);
  const buckets = new Map<number, PoolPoint[]>();
  for (const point of sorted) {
    const bucketStart = Math.floor(point.timestamp / bucketSeconds) * bucketSeconds;
    const list = buckets.get(bucketStart);
    if (list) list.push(point);
    else buckets.set(bucketStart, [point]);
  }

  const result: PoolPoint[] = [];
  for (const [bucketStart, points] of buckets.entries()) {
    points.sort((a, b) => a.timestamp - b.timestamp);
    let volumeUsd = 0;
    let feesUsd = 0;
    let tvlUsd = 0;
    for (const p of points) {
      volumeUsd += p.volumeUsd;
      feesUsd += p.feesUsd;
      tvlUsd = p.tvlUsd;
    }
    result.push({ timestamp: bucketStart, tvlUsd, volumeUsd, feesUsd });
  }

  return result.sort((a, b) => a.timestamp - b.timestamp);
}
