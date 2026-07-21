// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { useQuery } from '@tanstack/react-query';
import { getSessionSummaries } from '../api/getSessionSummaries';

export function useSessionSummaries() {
  return useQuery({
    queryKey: ['sessions', 'summaries'],
    queryFn: async () => {
      const data = await getSessionSummaries();
      return data.sessions;
    },
  });
}
