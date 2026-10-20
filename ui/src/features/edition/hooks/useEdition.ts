// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { useQuery } from '@tanstack/react-query';
import { getEdition } from '../api/edition';

// The edition cannot change while the server runs, so it is fetched once and a failure is not retried.
export const useEdition = () =>
  useQuery({
    queryKey: ['edition'],
    queryFn: getEdition,
    staleTime: Infinity,
    retry: false,
  });
