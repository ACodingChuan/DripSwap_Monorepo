import { useQuery } from '@tanstack/react-query';
import { Link, createRoute } from '@tanstack/react-router';
import { format } from 'date-fns';
import type { EChartsOption } from 'echarts';
import { BarChart, CandlestickChart, LineChart } from 'echarts/charts';
import { DataZoomComponent, GridComponent, TooltipComponent } from 'echarts/components';
import * as echarts from 'echarts/core';
import ReactEchartsCore from 'echarts-for-react/lib/core';
import { CanvasRenderer } from 'echarts/renderers';
import { useEffect, useMemo, useRef, useState } from 'react';

import { fetchTokenDetails, fetchTokenPools, fetchTokenPriceCandles, fetchTokenTransactions } from '@/app/services/token-service';
import { useUiStore } from '@/app/store/ui-store';
import type { TokenChartInterval } from '@/domain/ports/token-port';
import { ArrowLeft } from '@/shared/icons';
import { Badge, Button, Card, CardContent, CardHeader, CardTitle, Skeleton } from '@/shared/ui';
import { cn } from '@/shared/utils';

import { rootRoute } from './root';

echarts.use([
  CanvasRenderer,
  CandlestickChart,
  BarChart,
  LineChart,
  TooltipComponent,
  GridComponent,
  DataZoomComponent,
]);

const VETH_ADDRESS_SET = new Set([
  '0xe91d02e66a9152fee1bc79c1830121f6507a4f6d', // Scroll Sepolia vETH (example)
]);

function formatChainLabel(chain: string) {
  const chainId = Number(chain);
  if (chainId === 11155111) return 'Sepolia';
  if (chainId === 534351) return 'Scroll Sepolia';
  return chain;
}

function formatUsd(value: number | null | undefined) {
  if (!value) return '—';
  if (!Number.isFinite(value)) return '—';
  return `$${value.toLocaleString(undefined, { maximumFractionDigits: 2 })}`;
}

function formatLargeUsd(value: number | null | undefined) {
  if (!value) return '—';
  if (!Number.isFinite(value)) return '—';
  if (value >= 1_000_000_000) return `$${(value / 1_000_000_000).toFixed(2)}B`;
  if (value >= 1_000_000) return `$${(value / 1_000_000).toFixed(2)}M`;
  if (value >= 1_000) return `$${(value / 1_000).toFixed(2)}K`;
  return `$${value.toFixed(2)}`;
}

function formatPercent(value: number | null | undefined) {
  if (value === null || value === undefined) return '—';
  if (!Number.isFinite(value)) return '—';
  const sign = value > 0 ? '+' : '';
  return `${sign}${value.toLocaleString(undefined, { maximumFractionDigits: 2 })}%`;
}

function percentColor(value: number | null | undefined) {
  if (!value || !Number.isFinite(value) || value === 0) return 'text-muted-foreground';
  return value > 0 ? 'text-emerald-600' : 'text-red-600';
}

type ChartRangeKey = '1D' | '1W' | '1M' | '1Y';

const CHART_RANGES: Array<{
  key: ChartRangeKey;
  label: string;
  seconds: number;
  baseInterval: TokenChartInterval;
  bucketSeconds: number;
}> = [
  // As requested:
  // 1D: 1h/point (token_hour_data)
  // 1W: 2h/point (token_hour_data aggregated)
  // 1M: 8h/point (token_hour_data aggregated)
  // 1Y: 2d/point (token_day_data or day-aggregated, then aggregated again)
  { key: '1D', label: '1D', seconds: 24 * 60 * 60, baseInterval: 'HOUR', bucketSeconds: 1 * 60 * 60 },
  { key: '1W', label: '1W', seconds: 7 * 24 * 60 * 60, baseInterval: 'HOUR', bucketSeconds: 2 * 60 * 60 },
  { key: '1M', label: '1M', seconds: 30 * 24 * 60 * 60, baseInterval: 'HOUR', bucketSeconds: 8 * 60 * 60 },
  { key: '1Y', label: '1Y', seconds: 365 * 24 * 60 * 60, baseInterval: 'DAY', bucketSeconds: 2 * 24 * 60 * 60 },
];

const TokenDetailsPage = () => {
  const { chain, tokenAddress } = tokenDetailsRoute.useParams();
  const resolvedTheme = useUiStore((s) => s.resolvedTheme);
  const [rangeKey, setRangeKey] = useState<ChartRangeKey>('1W');
  const chartContainerRef = useRef<HTMLDivElement | null>(null);

  const chainLabel = formatChainLabel(chain);
  const tokenId = tokenAddress.trim().toLowerCase();
  const mockChart = useMemo(() => {
    if (!import.meta.env.DEV) return false;
    if (typeof window === 'undefined') return false;
    const params = new URLSearchParams(window.location.search);
    return params.get('mockChart') === '1';
  }, []);

  const { data: tokenDetails, isLoading: isTokenLoading } = useQuery({
    queryKey: ['token', 'details', chain, tokenId],
    queryFn: () => fetchTokenDetails({ chainId: chain, tokenAddress: tokenId }),
  });

  const selectedRange = useMemo(
    () => CHART_RANGES.find((r) => r.key === rangeKey) ?? CHART_RANGES[1],
    [rangeKey]
  );

  const nowSec = Math.floor(Date.now() / 1000);
  const candleFrom = nowSec - selectedRange.seconds;

  useEffect(() => {
    const container = chartContainerRef.current;
    if (!container) return;

    const onWheel = (event: WheelEvent) => {
      // On macOS Chrome, trackpad pinch generates ctrl+wheel which triggers browser page zoom.
      // Prevent that while pointer is over the chart so ECharts can use it for zoom.
      if (event.ctrlKey) {
        event.preventDefault();
      }
    };

    container.addEventListener('wheel', onWheel, { passive: false });
    return () => container.removeEventListener('wheel', onWheel);
  }, []);

  const {
    data: candles,
    isLoading: isCandlesLoading,
    isError: isCandlesError,
  } = useQuery({
    queryKey: ['token', 'candles', chain, tokenId, selectedRange.key],
    queryFn: () =>
      fetchTokenPriceCandles({
        chainId: chain,
        tokenAddress: tokenId,
        interval: selectedRange.baseInterval,
        from: candleFrom,
        to: nowSec,
      }),
  });

  const displayedCandles = useMemo(() => {
    const base = bucketOhlcSeries(candles ?? [], selectedRange.bucketSeconds);
    const shouldMockByToken =
      (tokenDetails?.symbol?.toLowerCase() === 'veth' || VETH_ADDRESS_SET.has(tokenId)) &&
      base.length < 12;

    if (!mockChart && !shouldMockByToken) {
      return base;
    }

    if (isCandlesLoading) {
      return base;
    }

    const seedKey = `${tokenId}:${chain}:${selectedRange.baseInterval}:${selectedRange.bucketSeconds}:${candleFrom}:${nowSec}`;
    const generated = generateMockOhlcSeries({
      from: candleFrom,
      to: nowSec,
      interval: selectedRange.baseInterval,
      seedKey,
      basePriceUsd: tokenDetails?.priceUsd ?? 3000,
    });
    return bucketOhlcSeries(generated, selectedRange.bucketSeconds);
  }, [
    candles,
    chain,
    candleFrom,
    isCandlesLoading,
    mockChart,
    nowSec,
    selectedRange.baseInterval,
    selectedRange.bucketSeconds,
    tokenDetails?.priceUsd,
    tokenDetails?.symbol,
    tokenId,
  ]);

  const { data: pools, isLoading: isPoolsLoading } = useQuery({
    queryKey: ['token', 'pools', chain, tokenId],
    queryFn: () => fetchTokenPools({ chainId: chain, tokenAddress: tokenId, limit: 10 }),
  });

  const { data: transactions, isLoading: isTxLoading } = useQuery({
    queryKey: ['token', 'transactions', chain, tokenId],
    queryFn: () => fetchTokenTransactions({ chainId: chain, tokenAddress: tokenId, limit: 25 }),
  });

  const chartOption = useMemo<EChartsOption>(() => {
    const axisLabelColor = resolvedTheme === 'dark' ? 'white' : 'black';
    const rows = (displayedCandles ?? [])
      .map((c) => [c.timestamp * 1000, c.open, c.close, c.low, c.high, c.volumeUsd])
      .filter((row) => Number.isFinite(row[0] as number));

    return {
      axisPointer: {
        link: [{ xAxisIndex: [0, 1] }],
      },
      tooltip: {
        trigger: 'axis',
        axisPointer: { type: 'cross' },
        formatter: (params) => {
          const items = Array.isArray(params) ? params : [params];
          const candle = items.find((p) => p.seriesType === 'candlestick');
          if (!candle || !Array.isArray(candle.data)) return '';
          const [ts, open, close, low, high, volumeUsd] = candle.data as unknown as [
            number,
            number,
            number,
            number,
            number,
            number,
          ];
          const showTime = selectedRange.bucketSeconds < 86400;
          const date = format(new Date(ts), showTime ? 'dd MMM HH:mm' : 'dd MMM yyyy');
          return [
            `<div style="min-width: 180px">`,
            `<div style="margin-bottom: 6px; font-weight: 600">${date}</div>`,
            `<div>O: ${formatUsd(open)}</div>`,
            `<div>H: ${formatUsd(high)}</div>`,
            `<div>L: ${formatUsd(low)}</div>`,
            `<div>C: ${formatUsd(close)}</div>`,
            `<div style="margin-top: 6px; color: #6b7280">Vol: ${formatLargeUsd(volumeUsd)}</div>`,
            `</div>`,
          ].join('');
        },
      },
      dataZoom: [
        {
          type: 'inside',
          xAxisIndex: [0, 1],
          zoomOnMouseWheel: 'ctrl',
          moveOnMouseMove: true,
          moveOnMouseWheel: true,
          preventDefaultMouseMove: true,
          filterMode: 'none',
        },
        {
          type: 'slider',
          xAxisIndex: [0, 1],
          height: 18,
          bottom: 6,
          left: 16,
          right: 16,
          showDetail: false,
          brushSelect: false,
          borderColor: 'transparent',
          fillerColor: 'rgba(59, 126, 246, 0.18)',
          backgroundColor: resolvedTheme === 'dark' ? 'rgba(255,255,255,0.06)' : 'rgba(0,0,0,0.06)',
          handleStyle: {
            color: resolvedTheme === 'dark' ? 'rgba(255,255,255,0.6)' : 'rgba(0,0,0,0.45)',
            borderWidth: 0,
          },
          handleSize: 14,
          realtime: false,
          throttle: 80,
          dataBackground: {
            lineStyle: { opacity: 0 },
            areaStyle: { opacity: 0 },
          },
        },
      ],
      grid: [
        { left: 10, right: 10, top: 10, height: 240 },
        { left: 10, right: 10, top: 260, height: 90 },
      ],
      xAxis: [
        {
          type: 'time',
          gridIndex: 0,
          axisLine: { show: false },
          axisTick: { show: false },
          splitLine: { show: false },
          axisLabel: { show: false },
        },
        {
          type: 'time',
          gridIndex: 1,
          axisLine: { show: false },
          axisTick: { show: false },
          splitLine: { show: false },
          axisLabel: { color: axisLabelColor },
        },
      ],
      yAxis: [
        {
          scale: true,
          gridIndex: 0,
          position: 'right',
          axisLabel: { color: axisLabelColor },
          splitLine: {
            show: true,
            lineStyle: {
              type: 'dashed',
              color: resolvedTheme === 'dark' ? 'rgba(255,255,255,0.14)' : 'rgba(0,0,0,0.12)',
            },
          },
        },
        { scale: true, gridIndex: 1, axisLabel: { show: false }, splitLine: { show: false } },
      ],
      series: [
        {
          name: 'Price',
          type: 'candlestick',
          data: rows.map((r) => [r[0], r[1], r[2], r[3], r[4], r[5]]),
          xAxisIndex: 0,
          yAxisIndex: 0,
          itemStyle: {
            color: '#16a34a',
            color0: '#dc2626',
            borderColor: '#16a34a',
            borderColor0: '#dc2626',
          },
        },
        {
          name: 'Volume',
          type: 'line',
          xAxisIndex: 1,
          yAxisIndex: 1,
          data: rows.map((r) => [r[0], r[5]]),
          showSymbol: false,
          smooth: true,
          lineStyle: { color: '#3B7EF6', width: 2, opacity: 0.9 },
          areaStyle: { color: 'rgba(59, 126, 246, 0.25)' },
        },
      ],
    };
  }, [displayedCandles, resolvedTheme, selectedRange.bucketSeconds]);

  const showChartSkeleton = isCandlesLoading || isTokenLoading;
  const showChartEmpty = !showChartSkeleton && ((displayedCandles ?? []).length === 0 || isCandlesError);
  const isUsingMockChartData = useMemo(() => {
    if (!import.meta.env.DEV) return false;
    if (isCandlesLoading) return false;
    if (!mockChart && tokenDetails?.symbol?.toLowerCase() !== 'veth' && !VETH_ADDRESS_SET.has(tokenId)) {
      return false;
    }
    // If real data is sufficient, don't claim mock.
    const baseBucketed = bucketOhlcSeries(candles ?? [], selectedRange.bucketSeconds);
    return baseBucketed.length < 12;
  }, [candles, isCandlesLoading, mockChart, selectedRange.bucketSeconds, tokenDetails?.symbol, tokenId]);

  return (
    <main className="mx-auto flex min-h-screen w-full max-w-[1200px] flex-col gap-[var(--space-xl)] px-6 py-[var(--space-2xl)]">
      <header className="flex flex-col gap-[var(--space-sm)]">
        <div className="flex items-center gap-[var(--space-sm)] text-sm text-muted-foreground">
          <ArrowLeft className="size-4" aria-hidden="true" />
          <Link to="/explore/tokens" className="hover:text-foreground focus-visible:text-foreground">
            Back to tokens
          </Link>
        </div>

        <div className="flex flex-wrap items-start justify-between gap-[var(--space-md)]">
          <div className="flex flex-col gap-[var(--space-xs)]">
            {isTokenLoading ? (
              <Skeleton className="h-10 w-64" />
            ) : tokenDetails ? (
              <h1 className="text-3xl font-semibold tracking-tight text-foreground sm:text-4xl">
                {tokenDetails.name} <span className="text-muted-foreground">({tokenDetails.symbol})</span>
              </h1>
            ) : (
              <h1 className="text-3xl font-semibold tracking-tight text-foreground sm:text-4xl">Token details</h1>
            )}
            <p className="text-sm text-muted-foreground">
              {chainLabel} · {tokenId}
            </p>
          </div>
          <Badge variant="outline">MVP</Badge>
        </div>
      </header>

      <section className="grid gap-[var(--space-md)] lg:grid-cols-[2fr,1fr]">
        <Card>
          <CardHeader className="flex flex-col gap-[var(--space-sm)] sm:flex-row sm:items-center sm:justify-between">
            <CardTitle className="text-lg">Price</CardTitle>
            <div className="flex flex-wrap items-center gap-2">
              {CHART_RANGES.map((range) => (
                <Button
                  key={range.key}
                  type="button"
                  variant={range.key === rangeKey ? 'primary' : 'outline'}
                  size="sm"
                  onClick={() => setRangeKey(range.key)}
                >
                  {range.label}
                </Button>
              ))}
              {isUsingMockChartData ? (
                <Badge variant="outline" className="ml-2">
                  Mock chart
                </Badge>
              ) : null}
            </div>
          </CardHeader>
          <CardContent>
            {showChartSkeleton ? (
              <Skeleton className="h-[380px] w-full rounded-[var(--radius-card)]" />
            ) : showChartEmpty ? (
              <div className="flex h-[380px] items-center justify-center rounded-[var(--radius-card)] border border-border/60 text-sm text-muted-foreground">
                Not enough data yet.
              </div>
            ) : (
              <div ref={chartContainerRef}>
                <ReactEchartsCore
                  echarts={echarts}
                  option={chartOption}
                  style={{ height: 380 }}
                />
              </div>
            )}
          </CardContent>
        </Card>

        <Card>
          <CardHeader>
            <CardTitle className="text-lg">Snapshot</CardTitle>
          </CardHeader>
          <CardContent className="grid gap-[var(--space-sm)] sm:grid-cols-2 lg:grid-cols-1">
            {[
              {
                label: 'Price',
                value: tokenDetails ? formatUsd(tokenDetails.priceUsd) : '—',
              },
              {
                label: '24h change',
                value: tokenDetails ? formatPercent(tokenDetails.change24hPct) : '—',
                className: tokenDetails ? percentColor(tokenDetails.change24hPct) : 'text-muted-foreground',
              },
              {
                label: 'TVL',
                value: tokenDetails ? formatLargeUsd(tokenDetails.tvlUsd) : '—',
              },
              {
                label: 'Volume (24h)',
                value: tokenDetails ? formatLargeUsd(tokenDetails.volume24hUsd) : '—',
              },
            ].map((item) => (
              <div
                key={item.label}
                className="rounded-[var(--radius-card)] border border-border/60 bg-surface-elevated/60 p-[var(--space-md)]"
              >
                <div className="text-xs font-medium uppercase text-muted-foreground">{item.label}</div>
                <div className={cn('mt-1 text-lg font-semibold text-foreground', item.className)}>
                  {isTokenLoading ? <Skeleton className="h-6 w-24" /> : item.value}
                </div>
              </div>
            ))}
          </CardContent>
        </Card>
      </section>

      <section className="grid gap-[var(--space-md)] lg:grid-cols-2">
        <Card>
          <CardHeader>
            <CardTitle className="text-lg">Pools</CardTitle>
          </CardHeader>
          <CardContent>
            {isPoolsLoading ? (
              <Skeleton className="h-64 w-full rounded-[var(--radius-card)]" />
            ) : (pools ?? []).length === 0 ? (
              <div className="py-10 text-center text-sm text-muted-foreground">No pools found.</div>
            ) : (
              <div className="divide-y divide-border/60">
                {(pools ?? []).map((pool) => (
                  <div key={pool.pairAddress} className="flex items-center justify-between gap-4 py-3 text-sm">
                    <div className="min-w-0">
                      <Link
                        to="/explore/pools/$chain/$poolAddress"
                        params={{ chain, poolAddress: pool.pairAddress }}
                        className="truncate font-medium text-foreground hover:underline"
                      >
                        {pool.token0.symbol}/{pool.token1.symbol}
                      </Link>
                      <div className="truncate text-xs text-muted-foreground">{pool.pairAddress}</div>
                    </div>
                    <div className="flex shrink-0 flex-col items-end">
                      <div className="text-foreground">{formatLargeUsd(pool.tvlUsd)}</div>
                      <div className="text-xs text-muted-foreground">{formatLargeUsd(pool.volumeUsd)} vol</div>
                    </div>
                  </div>
                ))}
              </div>
            )}
          </CardContent>
        </Card>

        <Card>
          <CardHeader>
            <CardTitle className="text-lg">Transactions</CardTitle>
          </CardHeader>
          <CardContent>
            {isTxLoading ? (
              <Skeleton className="h-64 w-full rounded-[var(--radius-card)]" />
            ) : (transactions ?? []).length === 0 ? (
              <div className="py-10 text-center text-sm text-muted-foreground">No transactions found.</div>
            ) : (
              <div className="divide-y divide-border/60">
                {(transactions ?? []).map((tx) => (
                  <div key={tx.id} className="flex items-center justify-between gap-4 py-3 text-sm">
                    <div className="min-w-0">
                      <div className="truncate font-medium text-foreground">
                        {tx.token0.symbol}/{tx.token1.symbol}
                      </div>
                      <div className="truncate text-xs text-muted-foreground">
                        {format(new Date(tx.timestamp * 1000), 'dd MMM yyyy HH:mm')} · {tx.txHash.slice(0, 10)}…
                      </div>
                    </div>
                    <div className="shrink-0 text-right">
                      <div className="text-foreground">{formatLargeUsd(tx.amountUsd)}</div>
                      <div className="text-xs text-muted-foreground">swap</div>
                    </div>
                  </div>
                ))}
              </div>
            )}
          </CardContent>
        </Card>
      </section>
    </main>
  );
};

export const tokenDetailsRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/explore/tokens/$chain/$tokenAddress',
  component: TokenDetailsPage,
});

type MockOhlcInput = {
  from: number;
  to: number;
  interval: TokenChartInterval;
  seedKey: string;
  basePriceUsd: number;
};

function generateMockOhlcSeries(input: MockOhlcInput) {
  const start = Math.min(input.from, input.to);
  const end = Math.max(input.from, input.to);

  const stepSeconds = resolveMockStepSeconds(input.interval);
  const targetPoints = Math.max(30, Math.min(Math.floor((end - start) / stepSeconds), 1200));
  const seed = hashStringToInt(input.seedKey);
  const rnd = mulberry32(seed);

  let lastClose = Math.max(0.01, input.basePriceUsd);
  const result = [];

  for (let i = 0; i <= targetPoints; i++) {
    const timestamp = start + i * stepSeconds;
    if (timestamp > end) break;

    const drift = (rnd() - 0.48) * 0.22;
    const shock = rnd() < 0.03 ? (rnd() - 0.5) * 2.2 : 0;
    const changePct = (drift + shock) * 0.012;

    const open = lastClose;
    const close = Math.max(0.01, open * (1 + changePct));

    const wiggle = open * (0.002 + rnd() * 0.006);
    const high = Math.max(open, close) + wiggle * (0.4 + rnd());
    const low = Math.max(0.01, Math.min(open, close) - wiggle * (0.4 + rnd()));

    const volumeBase = 12000 * (0.4 + rnd() * rnd() * 2.6);
    const volumeUsd = volumeBase * (1 + Math.abs(changePct) * 40);

    result.push({
      timestamp,
      open,
      high,
      low,
      close,
      volumeUsd,
      tvlUsd: 0,
    });

    lastClose = close;
  }

  return result;
}

function resolveMockStepSeconds(interval: TokenChartInterval) {
  if (interval === 'DAY') return 86400;
  if (interval === 'HOUR') return 3600;
  return 60;
}

type OhlcPoint = {
  timestamp: number;
  open: number;
  high: number;
  low: number;
  close: number;
  volumeUsd: number;
  tvlUsd: number;
};

function bucketOhlcSeries<T extends OhlcPoint>(series: T[], bucketSeconds: number): T[] {
  if (series.length === 0) return series;
  if (!Number.isFinite(bucketSeconds) || bucketSeconds <= 0) return series;

  const sorted = [...series].sort((a, b) => a.timestamp - b.timestamp);
  const buckets = new Map<number, T[]>();
  for (const point of sorted) {
    const bucketStart = Math.floor(point.timestamp / bucketSeconds) * bucketSeconds;
    const list = buckets.get(bucketStart);
    if (list) {
      list.push(point);
    } else {
      buckets.set(bucketStart, [point]);
    }
  }

  const result: T[] = [];
  for (const [bucketStart, points] of buckets.entries()) {
    points.sort((a, b) => a.timestamp - b.timestamp);

    const open = points[0].open;
    const close = points[points.length - 1].close;
    let high = Number.NEGATIVE_INFINITY;
    let low = Number.POSITIVE_INFINITY;
    let volumeUsd = 0;
    let tvlUsd = 0;

    for (const point of points) {
      high = Math.max(high, point.high);
      low = Math.min(low, point.low);
      volumeUsd += point.volumeUsd;
      tvlUsd = point.tvlUsd;
    }

    result.push({
      timestamp: bucketStart,
      open,
      high,
      low,
      close,
      volumeUsd,
      tvlUsd,
    } as T);
  }

  return result.sort((a, b) => a.timestamp - b.timestamp);
}

function hashStringToInt(value: string) {
  let hash = 2166136261;
  for (let i = 0; i < value.length; i++) {
    hash ^= value.charCodeAt(i);
    hash = Math.imul(hash, 16777619);
  }
  return hash >>> 0;
}

function mulberry32(seed: number) {
  let state = seed >>> 0;
  return function next() {
    state += 0x6D2B79F5;
    let t = state;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}
