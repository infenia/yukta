// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { useQuery } from '@tanstack/react-query';
import { getPlugin, getPlugins } from '../api/plugins';

export const usePlugins = () => useQuery({ queryKey: ['plugins'], queryFn: getPlugins });

export const usePlugin = (type: string | null) =>
  useQuery({
    queryKey: ['plugins', type],
    queryFn: () => getPlugin(type as string),
    enabled: Boolean(type),
  });
