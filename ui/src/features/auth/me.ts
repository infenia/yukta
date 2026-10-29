// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { fetchApi } from '@/lib/apiClient';
import { setMe, type Me } from '@/lib/identity';

/** Asks the server who the caller is; an older server without /api/me leaves everything open. */
export async function loadMe(): Promise<void> {
  try {
    setMe(await fetchApi<Me>('/api/me'));
  } catch {
    setMe(null);
  }
}
