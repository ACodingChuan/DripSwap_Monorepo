import { useQuery } from '@tanstack/react-query';
import { Link, createRoute } from '@tanstack/react-router';
import { format } from 'date-fns';
import type { EChartsOption } from 'echarts';
import { BarChart, CandlestickChart as EchartsCandlestickChart, LineChart as EchartsLineChart } from 'echarts/charts';
import { DataZoomComponent, GridComponent, TooltipComponent } from 'echarts/components';
import * as echarts from 'echarts/core';
import ReactEchartsCore from 'echarts-for-react/lib/core';
import { CanvasRenderer } from 'echarts/renderers';
import { useEffect, useMemo, useRef, useState } from 'react';

import { fetchTokenDetails, fetchTokenPools, fetchTokenPriceCandles, fetchTokenTransactions } from '@/app/services/token-service';
import { useUiStore } from '@/app/store/ui-store';
import type { TokenChartInterval } from '@/domain/ports/token-port';
import { ArrowLeft, CandlestickChart, LineChart } from '@/shared/icons';
import { Badge, Button, Card, CardContent, CardHeader, CardTitle, Skeleton } from '@/shared/ui';
import { cn, shortenHex } from '@/shared/utils';

import { rootRoute } from './root';

echarts.use([
  CanvasRenderer,
  BarChart,
  EchartsCandlestickChart,
  EchartsLineChart,
  TooltipComponent,
  GridComponent,
  DataZoomComponent,
]);

function formatChainLabel(chain: string) {
  const chainId = Number(chain);
  if (chainId === 11155111) return 'Sepolia';
  if (chainId === 534351) return 'Scroll Sepolia';
  return chain;
}

function formatUsd(value: number | null | undefined) {
  if (value === null || value === undefined) return '—';
  if (!Number.isFinite(value)) return '—';
  return `$${value.toLocaleString(undefined, { maximumFractionDigits: 2 })}`;
}

function formatLargeUsd(value: number | null | undefined) {
  if (value === null || value === undefined) return '—';
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

type ChartRangeKey = '1H' | '1D' | '1W' | '1M' | '1Y' | 'ALL';
type ChartMetricKey = 'PRICE' | 'VOLUME' | 'TVL';
type PriceViewKey = 'LINE' | 'CANDLE';

const CHART_RANGES: Array<{
  key: ChartRangeKey;
  label: string;
  seconds: number;
  baseInterval: TokenChartInterval;
  bucketSeconds: number;
}> = [
  { key: '1H', label: '1H', seconds: 1 * 60 * 60, baseInterval: 'MINUTE', bucketSeconds: 1 * 60 },
  { key: '1D', label: '1D', seconds: 24 * 60 * 60, baseInterval: 'HOUR', bucketSeconds: 1 * 60 * 60 },
  { key: '1W', label: '1W', seconds: 7 * 24 * 60 * 60, baseInterval: 'HOUR', bucketSeconds: 2 * 60 * 60 },
  { key: '1M', label: '1M', seconds: 30 * 24 * 60 * 60, baseInterval: 'HOUR', bucketSeconds: 8 * 60 * 60 },
  { key: '1Y', label: '1Y', seconds: 365 * 24 * 60 * 60, baseInterval: 'DAY', bucketSeconds: 2 * 24 * 60 * 60 },
  // Backend caches a ~400d window for DAY; "ALL" maps to that window.
  { key: 'ALL', label: 'ALL', seconds: 400 * 24 * 60 * 60, baseInterval: 'DAY', bucketSeconds: 7 * 24 * 60 * 60 },
];

const TokenDetailsPage = () => {
  const { chain, tokenAddress } = tokenDetailsRoute.useParams();
  const resolvedTheme = useUiStore((s) => s.resolvedTheme);
  const [rangeKey, setRangeKey] = useState<ChartRangeKey>('1W');
  const [metricKey, setMetricKey] = useState<ChartMetricKey>('PRICE');
  const [priceViewKey, setPriceViewKey] = useState<PriceViewKey>('LINE');
  const chartContainerRef = useRef<HTMLDivElement | null>(null);

  const chainLabel = formatChainLabel(chain);
  const tokenId = tokenAddress.trim().toLowerCase();

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
    return bucketOhlcSeries(candles ?? [], selectedRange.bucketSeconds);
  }, [
    candles,
    selectedRange.bucketSeconds,
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
    const splitLineColor = resolvedTheme === 'dark' ? 'rgba(255,255,255,0.14)' : 'rgba(0,0,0,0.12)';

    const points = (displayedCandles ?? [])
      .map((c) => ({
        ts: c.timestamp * 1000,
        open: c.open,
        high: c.high,
        low: c.low,
        close: c.close,
        price: c.close,
        volume: c.volumeUsd,
        tvl: c.tvlUsd,
      }))
      .filter((p) => Number.isFinite(p.ts));

    const metricLabel = metricKey === 'PRICE' ? 'Price' : metricKey === 'VOLUME' ? 'Volume' : 'TVL';

    const tooltipFormatter = (params: unknown) => {
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

      const showTime = selectedRange.bucketSeconds < 86400;
      const date = format(new Date(ts), showTime ? 'dd MMM HH:mm' : 'dd MMM yyyy');

      if (metricKey === 'PRICE' && priceViewKey === 'CANDLE') {
        const open = data[1] as number;
        const close = data[2] as number;
        const low = data[3] as number;
        const high = data[4] as number;
        return [
          `<div style="min-width: 180px">`,
          `<div style="margin-bottom: 6px; font-weight: 600">${date}</div>`,
          `<div>O: ${formatUsd(open)}</div>`,
          `<div>H: ${formatUsd(high)}</div>`,
          `<div>L: ${formatUsd(low)}</div>`,
          `<div>C: ${formatUsd(close)}</div>`,
          `</div>`,
        ].join('');
      }

      const metricValue = data[1] as number;
      const formatted = metricKey === 'PRICE' ? formatUsd(metricValue) : formatLargeUsd(metricValue);
      return [
        `<div style="min-width: 180px">`,
        `<div style="margin-bottom: 6px; font-weight: 600">${date}</div>`,
          `<div>${metricLabel}: ${formatted}</div>`,
        `</div>`,
      ].join('');
    };

    const series: EChartsOption['series'] = (() => {
      if (metricKey === 'VOLUME') {
        return [
          {
            name: metricLabel,
            type: 'bar' as const,
            data: points.map((p) => [p.ts, p.volume]),
            encode: { x: 0, y: 1 },
            itemStyle: { color: 'rgba(59, 126, 246, 0.75)' },
            barMaxWidth: 10,
          },
        ];
      }

      if (metricKey === 'TVL') {
        return [
          {
            name: metricLabel,
            type: 'line' as const,
            data: points.map((p) => [p.ts, p.tvl]),
            encode: { x: 0, y: 1 },
            showSymbol: false,
            smooth: true,
            lineStyle: { color: '#3B7EF6', width: 2, opacity: 0.95 },
            areaStyle: { color: 'rgba(59, 126, 246, 0.18)' },
          },
        ];
      }

      // PRICE
      if (priceViewKey === 'CANDLE') {
        return [
          {
            name: metricLabel,
            type: 'candlestick' as const,
            data: points.map((p) => [p.ts, p.open, p.close, p.low, p.high]),
            encode: { x: 0, y: [1, 2, 3, 4] },
            itemStyle: {
              color: '#16a34a',
              color0: '#dc2626',
              borderColor: '#16a34a',
              borderColor0: '#dc2626',
            },
          },
        ];
      }

      return [
        {
          name: metricLabel,
          type: 'line' as const,
          data: points.map((p) => [p.ts, p.price]),
          encode: { x: 0, y: 1 },
          showSymbol: false,
          smooth: true,
          lineStyle: { color: '#3B7EF6', width: 2, opacity: 0.95 },
          areaStyle: { color: 'rgba(59, 126, 246, 0.18)' },
        },
      ];
    })();

    return {
      tooltip: {
        trigger: 'axis',
        axisPointer: { type: 'cross' },
        formatter: tooltipFormatter,
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
        {
          type: 'slider',
          xAxisIndex: 0,
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
      grid: { left: 10, right: 10, top: 10, bottom: 40, containLabel: true },
      xAxis: {
        type: 'time',
        axisLine: { show: false },
        axisTick: { show: false },
        splitLine: { show: false },
        axisLabel: {
          color: axisLabelColor,
          formatter: (value: number) => {
            const date = new Date(value);
            switch (selectedRange.key) {
              case '1H':
              case '1D':
                return format(date, 'HH:mm');
              case '1W':
              case '1M':
                return format(date, 'd MMM');
              case '1Y':
              case 'ALL':
              default:
                return format(date, 'MMM yy');
            }
          },
        },
      },
      yAxis: {
        scale: true,
        position: 'right',
        axisLabel: { color: axisLabelColor },
        splitLine: {
          show: true,
          lineStyle: {
            type: 'dashed',
            color: splitLineColor,
          },
        },
      },
      series,
    };
  }, [displayedCandles, metricKey, priceViewKey, resolvedTheme, selectedRange.bucketSeconds, selectedRange.key]);

  const showChartSkeleton = isCandlesLoading || isTokenLoading;
  const showChartEmpty = !showChartSkeleton && ((displayedCandles ?? []).length === 0 || isCandlesError);
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
              {chainLabel} · {shortenHex(tokenId)}
            </p>
          </div>
          <Badge variant="outline">MVP</Badge>
        </div>
      </header>

      <section className="grid gap-[var(--space-md)] lg:grid-cols-[2fr,1fr]">
        <Card>
          <CardHeader className="flex flex-col gap-[var(--space-sm)]">
            <div className="flex flex-wrap items-center justify-between gap-3">
              <CardTitle className="text-lg">
                {metricKey === 'PRICE' ? 'Price' : metricKey === 'VOLUME' ? 'Volume' : 'TVL'}
              </CardTitle>

              <div className="flex flex-wrap items-center gap-3">
                <div className="flex items-center gap-2">
                  <div className="inline-flex items-center rounded-full border border-border bg-background p-1">
                    {[
                      { key: 'PRICE' as const, label: 'Price' },
                      { key: 'VOLUME' as const, label: 'Volume' },
                      { key: 'TVL' as const, label: 'TVL' },
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

                  {metricKey === 'PRICE' ? (
                    <div className="inline-flex items-center rounded-full border border-border bg-background p-1">
                      {[
                        { key: 'LINE' as const, label: 'Line', Icon: LineChart },
                        { key: 'CANDLE' as const, label: 'K-line', Icon: CandlestickChart },
                      ].map((view) => (
                        <Button
                          key={view.key}
                          type="button"
                          variant={view.key === priceViewKey ? 'secondary' : 'ghost'}
                          size="sm"
                          className="h-8 rounded-full px-4"
                          onClick={() => setPriceViewKey(view.key)}
                        >
                          <view.Icon className="mr-2 size-4" aria-hidden="true" />
                          {view.label}
                        </Button>
                      ))}
                    </div>
                  ) : null}
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
                value: tokenDetails ? formatPercent(tokenDetails.change24hPct ?? 0) : '—',
                className: tokenDetails ? percentColor(tokenDetails.change24hPct ?? 0) : 'text-muted-foreground',
              },
              {
                label: 'TVL',
                value: tokenDetails ? formatLargeUsd(tokenDetails.tvlUsd) : '—',
              },
              {
                label: 'Volume (24h)',
                value:
                  tokenDetails && tokenDetails.volume24hUsd === 0 ? '—' : tokenDetails ? formatLargeUsd(tokenDetails.volume24hUsd) : '—',
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
                      <div className="truncate text-xs text-muted-foreground">{shortenHex(pool.pairAddress)}</div>
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
