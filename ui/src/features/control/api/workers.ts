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

/** The connected workers, refreshed every 5 s. */
export const useWorkers = () =>
  useQuery({
    queryKey: ['control', 'workers'],
    queryFn: getWorkers,
    refetchInterval: 5000,
  });
