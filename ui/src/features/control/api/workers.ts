// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { useQuery } from '@tanstack/react-query';
import { fetchApi } from '@/lib/apiClient';

/** A remote worker that is connected and keeps its lease. */
export interface Worker {
  workerId: string;
  pools: string[];
  pluginTypes: string[];
  capacity: number;
  usedSlots: number;
  draining: boolean;
  registeredAt: string;
  leaseUntil: string;
}

export const getWorkers = () => fetchApi<Worker[]>('/api/workers');

/** The connected workers, refreshed every 5 s unless `live` is false (a paused dashboard). */
export const useWorkers = (live = true) =>
  useQuery({
    queryKey: ['control', 'workers'],
    queryFn: getWorkers,
    refetchInterval: live ? 5000 : false,
  });
