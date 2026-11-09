// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { api, fetchApi } from '@/lib/apiClient';
import type { Ownership } from '../types';

/** Who owns the run level and each node of a workflow. */
export const getOwnership = (session: string, workflow: string) =>
  fetchApi<Ownership>(
    api`/api/supervision/ownership?session=${session}&workflow=${workflow}`
  );
