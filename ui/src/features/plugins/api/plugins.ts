// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { api, fetchApi } from '@/lib/apiClient';
import type { PluginDetails, PluginSummary } from '../types';

export const getPlugins = () => fetchApi<PluginSummary[]>('/api/plugins');

export const getPlugin = (type: string) =>
  fetchApi<PluginDetails>(api`/api/plugins/${type}`);

/** A workflow that uses a plugin, and how many of its nodes do. */
export interface PluginUsage {
  sessionId: string;
  workflowId: string;
  nodes: number;
}

export const getPluginUsage = (type: string) =>
  fetchApi<PluginUsage[]>(api`/api/plugins/${type}/usage`);
