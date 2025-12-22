import { useQuery } from '@tanstack/react-query';
import { format } from 'date-fns';
import type { EChartsOption } from 'echarts';
import { BarChart, LineChart } from 'echarts/charts';
import { GridComponent, TooltipComponent } from 'echarts/components';
import * as echarts from 'echarts/core';
import ReactEchartsCore from 'echarts-for-react/lib/core';
import { CanvasRenderer } from 'echarts/renderers';
import { useCallback, useMemo } from 'react';
import { useChainId } from 'wagmi';

import { fetchExploreStats } from '@/app/services/explore-service';
import { useUiStore } from '@/app/store/ui-store';
import { cn } from '@/shared/utils';

echarts.use([CanvasRenderer, LineChart, BarChart, TooltipComponent, GridComponent]);

function formatChainLabel(chainId: number) {
  if (chainId === 11155111) return 'Sepolia';
  if (chainId === 534351) return 'Scroll Sepolia';
  return String(chainId);
}

function formatUSD(value: number) {
  if (!Number.isFinite(value)) return '—';
  return `$${value.toLocaleString(undefined, { maximumFractionDigits: 2 })}`;
}

function asTimeSeries(series: Array<{ date: number; valueUsd: string }>) {
  const points: Array<[number, number]> = series
    .map((p): [number, number] => [p.date * 1000, Number(p.valueUsd)])
    .filter((p) => Number.isFinite(p[0]) && Number.isFinite(p[1]));

  points.sort((a, b) => a[0] - b[0]);
  return points;
}

type HoverParam = { data?: [number, number] };

function updateText(id: string, text: string) {
  const node = document.getElementById(id);
  if (node) node.textContent = text;
}

export function ExploreProtocolStats() {
  const chainId = useChainId();
  const resolvedTheme = useUiStore((s) => s.resolvedTheme);
  const chainLabel = formatChainLabel(chainId);

  const { data: stats, isLoading, isError } = useQuery({
    queryKey: ['explore', 'stats', chainId],
    queryFn: () => fetchExploreStats({ chainId: String(chainId), days: 30 }),
  });

  const tvlData = useMemo(() => asTimeSeries(stats?.tvlSeries ?? []), [stats]);
  const volumeData = useMemo(() => asTimeSeries(stats?.volumeSeries ?? []), [stats]);

  const tvlDefaultValue = tvlData.length ? tvlData[tvlData.length - 1][1] : 0;
  const tvlDefaultDate = tvlData.length ? tvlData[tvlData.length - 1][0] : Date.now();
  const volumeDefaultValue = stats ? Number(stats.volume24hUsd) : 0;

  const tvlIds = useMemo(
    () => ({
      value: `explore-tvl-value-${chainId}`,
      date: `explore-tvl-date-${chainId}`,
      v2: `explore-tvl-v2-${chainId}`,
    }),
    [chainId]
  );

  const volumeIds = useMemo(
    () => ({
      value: `explore-volume-value-${chainId}`,
      date: `explore-volume-date-${chainId}`,
      v2: `explore-volume-v2-${chainId}`,
    }),
    [chainId]
  );

  const onTvlMouseOver = useCallback(
    (params: HoverParam[]) => {
      if (!params[0]?.data) return '';
      const [ts, value] = params[0].data;
      updateText(tvlIds.value, formatUSD(value));
      updateText(tvlIds.date, format(new Date(ts), 'dd MMM yyyy HH:mm aa'));
      updateText(tvlIds.v2, formatUSD(value));
      return '';
    },
    [tvlIds]
  );

  const onTvlMouseLeave = useCallback(() => {
    updateText(tvlIds.value, formatUSD(tvlDefaultValue));
    updateText(tvlIds.date, format(new Date(tvlDefaultDate), 'dd MMM yyyy HH:mm aa'));
    updateText(tvlIds.v2, '');
  }, [tvlDefaultDate, tvlDefaultValue, tvlIds]);

  const onVolumeMouseOver = useCallback(
    (params: HoverParam[]) => {
      if (!params[0]?.data) return '';
      const [ts, value] = params[0].data;
      updateText(volumeIds.value, formatUSD(value));
      updateText(volumeIds.date, format(new Date(ts), 'dd MMM yyyy'));
      updateText(volumeIds.v2, formatUSD(value));
      return '';
    },
    [volumeIds]
  );

  const onVolumeMouseLeave = useCallback(() => {
    updateText(volumeIds.value, formatUSD(volumeDefaultValue));
    updateText(volumeIds.date, 'Past month');
    updateText(volumeIds.v2, '');
  }, [volumeDefaultValue, volumeIds]);

  const tvlOption = useMemo<EChartsOption>(() => {
    const axisLabelColor = resolvedTheme === 'dark' ? 'white' : 'black';
    return {
      tooltip: {
        trigger: 'axis',
        padding: 0,
        borderWidth: 0,
        axisPointer: {
          lineStyle: { type: 'solid' },
        },
        formatter: (params) => onTvlMouseOver(Array.isArray(params) ? (params as HoverParam[]) : [params as HoverParam]),
      },
      color: ['#3B7EF6'],
      grid: { top: 0, left: 0, right: 0, bottom: 40 },
      xAxis: [
        {
          type: 'time',
          splitLine: { show: false },
          axisLine: { show: false },
          axisTick: { show: false },
          splitNumber: 3,
          axisLabel: {
            hideOverlap: true,
            showMinLabel: true,
            showMaxLabel: true,
            color: axisLabelColor,
            formatter: (value: number, index: number) => {
              const date = new Date(value);
              const label = `${date.toLocaleString('en-US', { month: 'short' })} ${date.getDate()}\n${date.getFullYear()}`;
              return index === 0
                ? `{min|${label}}`
                : value > (tvlData?.[tvlData.length - 2]?.[0] ?? 0)
                  ? `{max|${label}}`
                  : label;
            },
            rich: {
              min: { padding: [0, 10, 0, 50] },
              max: { padding: [0, 50, 0, 10] },
            },
          },
        },
      ],
      yAxis: [{ show: false }],
      series: [
        {
          name: 'v2',
          type: 'line',
          smooth: true,
          lineStyle: { width: 0 },
          showSymbol: false,
          areaStyle: { color: '#3B7EF6', opacity: 1 },
          data: tvlData,
        },
      ],
    };
  }, [onTvlMouseOver, resolvedTheme, tvlData]);

  const volumeOption = useMemo<EChartsOption>(() => {
    const axisLabelColor = resolvedTheme === 'dark' ? 'white' : 'black';
    return {
      tooltip: {
        trigger: 'axis',
        padding: 0,
        borderWidth: 0,
        axisPointer: {
          lineStyle: { type: 'solid' },
        },
        formatter: (params) =>
          onVolumeMouseOver(Array.isArray(params) ? (params as HoverParam[]) : [params as HoverParam]),
      },
      grid: { top: 0, left: 0, right: 0, bottom: 40 },
      xAxis: [
        {
          type: 'time',
          splitLine: { show: false },
          axisLine: { show: false },
          axisTick: { show: false },
          splitNumber: 2,
          axisLabel: {
            hideOverlap: true,
            showMinLabel: true,
            showMaxLabel: true,
            color: axisLabelColor,
            formatter: (value: number, index: number) => {
              const label = format(new Date(value), 'MMM d');
              return index === 0
                ? `{min|${label}}`
                : value > (volumeData?.[volumeData.length - 2]?.[0] ?? 0)
                  ? `{max|${label}}`
                  : label;
            },
            rich: {
              min: { padding: [0, 10, 0, 50] },
              max: { padding: [0, 50, 0, 10] },
            },
          },
        },
      ],
      yAxis: [{ show: false }],
      series: [
        {
          name: 'v2',
          type: 'bar',
          data: volumeData,
          itemStyle: { color: '#3B7EF6', barBorderRadius: [2, 2, 2, 2] },
        },
      ],
    };
  }, [onVolumeMouseOver, resolvedTheme, volumeData]);

  return (
    <section className="grid gap-8 lg:grid-cols-2">
      <div className={cn('rounded-[var(--radius-card)] bg-transparent')}>
        <div className="flex flex-col gap-3 px-[var(--space-xl)] pt-[var(--space-xl)]">
          <span className="text-sm text-muted-foreground">{chainLabel} TVL</span>
          <div className="flex justify-between">
            <div className="flex flex-col gap-3">
              <div className="text-3xl font-medium">
                <span id={tvlIds.value}>{formatUSD(tvlDefaultValue)}</span>
              </div>
              <div id={tvlIds.date} className="text-sm text-gray-500 dark:text-slate-500">
                {format(new Date(tvlDefaultDate), 'dd MMM yyyy HH:mm aa')}
              </div>
            </div>
            <div className="flex flex-col">
              <div className="flex items-center justify-between gap-2 text-sm">
                <span id={tvlIds.v2} />
                <span className="flex items-center gap-1">
                  <span className="font-medium">v2</span>
                  <span className="h-3 w-3 rounded-[4px] bg-[#3B7EF6]" />
                </span>
              </div>
            </div>
          </div>
        </div>

        <div className="px-[var(--space-xl)] pb-[var(--space-xl)]">
          {isLoading ? (
            <div className="mt-4">
              <div className="h-[400px] w-full rounded-[var(--radius-card)] border border-border/60 bg-surface-elevated/40">
                <div className="p-3">
                  <div className="h-6 w-24 rounded bg-muted/60" />
                </div>
              </div>
            </div>
          ) : isError || !stats ? (
            <div className="mt-4 flex h-[400px] items-center justify-center rounded-[var(--radius-card)] border border-border/60 bg-surface-elevated/40 text-sm text-muted-foreground">
              Failed to load TVL.
            </div>
          ) : tvlData.length < 2 ? (
            <div className="mt-4 flex h-[400px] items-center justify-center rounded-[var(--radius-card)] border border-border/60 bg-surface-elevated/40 text-sm text-muted-foreground">
              Not enough data yet.
            </div>
          ) : (
            <ReactEchartsCore
              option={tvlOption}
              echarts={echarts}
              style={{ height: 400 }}
              onEvents={{
                globalout: onTvlMouseLeave,
              }}
            />
          )}
        </div>
      </div>

      <div className={cn('rounded-[var(--radius-card)] bg-transparent')}>
        <div className="flex flex-col gap-3 px-[var(--space-xl)] pt-[var(--space-xl)]">
          <span className="text-sm text-muted-foreground">{chainLabel} Volume</span>
          <div className="flex justify-between">
            <div className="flex flex-col gap-3">
              <div className="text-3xl font-medium">
                <span id={volumeIds.value}>{formatUSD(volumeDefaultValue)}</span>
              </div>
              <div id={volumeIds.date} className="text-sm text-gray-500 dark:text-slate-500">
                Past month
              </div>
            </div>
            <div className="flex flex-col">
              <div className="flex items-center justify-between gap-2 text-sm">
                <span id={volumeIds.v2} />
                <span className="flex items-center gap-1">
                  <span className="font-medium">v2</span>
                  <span className="h-3 w-3 rounded-[4px] bg-[#3B7EF6]" />
                </span>
              </div>
            </div>
          </div>
        </div>

        <div className="px-[var(--space-xl)] pb-[var(--space-xl)]">
          {isLoading ? (
            <div className="mt-4">
              <div className="h-[400px] w-full rounded-[var(--radius-card)] border border-border/60 bg-surface-elevated/40">
                <div className="p-3">
                  <div className="h-6 w-24 rounded bg-muted/60" />
                </div>
              </div>
            </div>
          ) : isError || !stats ? (
            <div className="mt-4 flex h-[400px] items-center justify-center rounded-[var(--radius-card)] border border-border/60 bg-surface-elevated/40 text-sm text-muted-foreground">
              Failed to load volume.
            </div>
          ) : volumeData.length < 2 ? (
            <div className="mt-4 flex h-[400px] items-center justify-center rounded-[var(--radius-card)] border border-border/60 bg-surface-elevated/40 text-sm text-muted-foreground">
              Not enough data yet.
            </div>
          ) : (
            <ReactEchartsCore
              option={volumeOption}
              echarts={echarts}
              style={{ height: 400 }}
              onEvents={{
                globalout: onVolumeMouseLeave,
              }}
            />
          )}
        </div>
      </div>
    </section>
  );
}
