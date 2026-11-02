// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import type { MetricPoint } from '@/features/metrics/api';

/** A duration in milliseconds as a person reads it: 850 ms, 1.8 s, 2 m 5 s. */
export function formatMs(ms: number): string {
  if (!Number.isFinite(ms)) return '–';
  if (ms < 1000) return `${Math.round(ms)} ms`;
  if (ms < 60_000) return `${(ms / 1000).toFixed(1)} s`;
  const minutes = Math.floor(ms / 60_000);
  return `${minutes} m ${Math.round((ms % 60_000) / 1000)} s`;
}

/** The sum of every value whose key starts with `prefix` and ends with `suffix`. */
export function sumOf(
  values: Record<string, number> | undefined,
  prefix: string,
  suffix = ''
): number {
  return Object.entries(values ?? {})
    .filter(([key]) => key.startsWith(prefix) && key.endsWith(suffix))
    .reduce((total, [, v]) => total + v, 0);
}

/** A value of `{tag=value}` in a series key, such as the `reason` of a rejected-start rate. */
export function tagOf(key: string, tag: string): string | undefined {
  return new RegExp(`[{,]${tag}=([^,}|]*)`).exec(key)?.[1];
}

/** Points sorted by time, from several series added up minute by minute. */
export function addUp(series: MetricPoint[][]): MetricPoint[] {
  const byTime = new Map<number, number>();
  for (const points of series) {
    for (const p of points) byTime.set(p.t, (byTime.get(p.t) ?? 0) + p.v);
  }
  return [...byTime.entries()]
    .sort(([a], [b]) => a - b)
    .map(([t, v]) => ({ t, v }));
}

/** Success rate as a percentage, or null without runs. */
export function successRate(runs: number, failed: number): number | null {
  return runs > 0 ? Math.round(((runs - failed) / runs) * 100) : null;
}

/**
 * The change from the first to the last point of a series as a whole percentage, or undefined
 * when it cannot be told (fewer than two points, or a start of zero).
 */
export function percentChange(points: MetricPoint[]): number | undefined {
  if (points.length < 2) return undefined;
  const first = points[0].v;
  const last = points[points.length - 1].v;
  if (first === 0) return undefined;
  return Math.round(((last - first) / Math.abs(first)) * 100);
}

/** A change as a person reads it: +12%, -3%, 0%. */
export function formatChange(change: number): string {
  return `${change > 0 ? '+' : ''}${change}%`;
}

/**
 * What a per-second rate added up to over its points: each point's rate times the time since the
 * point before it. The first point only marks where the window starts.
 */
export function integrate(points: MetricPoint[]): number {
  let total = 0;
  for (let i = 1; i < points.length; i++) {
    total += (points[i].v * (points[i].t - points[i - 1].t)) / 1000;
  }
  return total;
}

/** One provider's model traffic: tokens per minute each way and spend per hour. */
export interface ProviderUsage {
  provider: string;
  tokensInPerMinute: number;
  tokensOutPerMinute: number;
  spendPerHour: number;
}

/** Model and tool-call traffic from a snapshot, per provider and in total. */
export interface AiUsage {
  providers: ProviderUsage[];
  mcpCallsPerMinute: number;
  mcpFailedPerMinute: number;
  heavyRefusedPerMinute: number;
}

/**
 * Reads the `llm.tokens{direction,provider}`, `llm.cost{provider}`, `yukta.mcp.call{outcome,tool}`
 * (outcome SUCCESS or FAILURE) and `yukta.mcp.heavy.refused` rates of a snapshot. Providers are
 * sorted by name.
 */
export function aiUsage(values: Record<string, number> | undefined): AiUsage {
  const providers = new Map<string, ProviderUsage>();
  const of = (key: string) => {
    const provider = tagOf(key, 'provider') ?? 'unknown';
    let row = providers.get(provider);
    if (!row) {
      row = {
        provider,
        tokensInPerMinute: 0,
        tokensOutPerMinute: 0,
        spendPerHour: 0,
      };
      providers.set(provider, row);
    }
    return row;
  };
  let mcpCallsPerMinute = 0;
  let mcpFailedPerMinute = 0;
  let heavyRefusedPerMinute = 0;
  for (const [key, rate] of Object.entries(values ?? {})) {
    if (!key.endsWith('|rate')) continue;
    if (key.startsWith('llm.tokens')) {
      const row = of(key);
      if (tagOf(key, 'direction') === 'output') {
        row.tokensOutPerMinute += rate * 60;
      } else {
        row.tokensInPerMinute += rate * 60;
      }
    } else if (key.startsWith('llm.cost')) {
      of(key).spendPerHour += rate * 3600;
    } else if (key.startsWith('yukta.mcp.call')) {
      mcpCallsPerMinute += rate * 60;
      const outcome = tagOf(key, 'outcome');
      if (outcome === 'FAILURE') {
        mcpFailedPerMinute += rate * 60;
      }
    } else if (key.startsWith('yukta.mcp.heavy.refused')) {
      heavyRefusedPerMinute += rate * 60;
    }
  }
  return {
    providers: [...providers.values()].sort((a, b) =>
      a.provider.localeCompare(b.provider)
    ),
    mcpCallsPerMinute,
    mcpFailedPerMinute,
    heavyRefusedPerMinute,
  };
}
