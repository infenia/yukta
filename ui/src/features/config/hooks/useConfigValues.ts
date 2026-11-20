// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { useQuery } from '@tanstack/react-query';
import { useMe } from '@/lib/identity';
import { getConfigValues, type ConfigValue } from '../api/config';
import { readableKeys } from '../lib/catalog';

export type LiveValues =
  | { status: 'denied' | 'loading' | 'error' }
  | { status: 'ready'; values: Map<string, ConfigValue> };

/**
 * The values the server uses for the catalog's options. Only an ADMIN key may read them, so any
 * other known role is `denied` without asking; while the identity is unknown the server decides.
 */
export function useConfigValues(): LiveValues {
  const me = useMe();
  const allowed = me === null || me.role === 'ADMIN';
  const query = useQuery({
    queryKey: ['config', 'values'],
    queryFn: () => getConfigValues(readableKeys()),
    enabled: allowed,
    staleTime: 30_000,
    retry: false,
  });
  if (!allowed) return { status: 'denied' };
  if (query.isPending) return { status: 'loading' };
  if (query.isError) return { status: 'error' };
  return {
    status: 'ready',
    values: new Map(query.data.map((value) => [value.key, value])),
  };
}
