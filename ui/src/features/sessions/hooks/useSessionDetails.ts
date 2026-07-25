// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { useQuery } from '@tanstack/react-query';
import { getSessionDetails } from '../api/getSessionDetails';

export function useSessionDetails(sessionId: string) {
  return useQuery({
    queryKey: ['sessions', sessionId, 'details'],
    queryFn: () => getSessionDetails(sessionId),
    enabled: Boolean(sessionId),
  });
}
